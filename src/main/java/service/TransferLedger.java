package service;

import model.FileSelection.SentPolicy;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The memory of which files a task has already sent — what "Skip files already sent" consults, and
 * what the "Forget sent files" button clears.
 *
 * <p>It lives in its own database file ({@value #FILE_NAME}) in the data folder, one set of rows per
 * task, so it can be deleted or inspected without touching tasks or run history. An entry is the
 * file's name, size and last-changed time <i>as seen on the source side</i> at the moment it was sent,
 * plus when that happened; entries older than {@value #RETENTION_DAYS} days are dropped.
 *
 * <p>Every call opens a short-lived connection: the GUI and the Daemon both use this file, and SQLite's
 * file locking (with a busy timeout) takes care of them, so no connection is held between runs.
 * Failures are reported as {@link IOException} — callers decide whether that stops a run (reading) or
 * only earns a warning (writing after a transfer that already happened).
 */
public final class TransferLedger {

    public static final String FILE_NAME = "transfer_ledger.db";
    public static final int RETENTION_DAYS = 180;
    private final File dbFile;
    private final LongSupplier clock;

    public TransferLedger(File dataDir) {
        this(dataDir, System::currentTimeMillis);
    }
    /**
     * For tests: a clock that can be moved to check the retention.
     */
    public TransferLedger(File dataDir, LongSupplier clockMillis) {
        this.dbFile = new File(dataDir, FILE_NAME);
        this.clock = clockMillis;
    }

    /**
     * What is remembered for {@code taskId} right now.
     */
    public Snapshot snapshot(String taskId) throws IOException {
        Snapshot s = new Snapshot();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT name, size, mtime FROM sent_files WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    s.names.add(name);
                    s.exact.add(Snapshot.key(name, rs.getLong(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
        return s;
    }

    /**
     * Remembers these files as sent now. Safe to call again for the same file (it just refreshes the time).
     */
    public void record(String taskId, Collection<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) return;
        long now = clock.getAsLong();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sent_files (task_id, name, size, mtime, sent_at) VALUES (?,?,?,?,?) "
                            + "ON CONFLICT(task_id, name, size, mtime) DO UPDATE SET sent_at = excluded.sent_at")) {
                for (Entry e : entries) {
                    ps.setString(1, taskId);
                    ps.setString(2, e.name());
                    ps.setLong(3, e.size());
                    ps.setLong(4, e.mtimeMillis());
                    ps.setLong(5, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw ex;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot write the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * How many files are remembered for the task.
     */
    public int count(String taskId) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sent_files WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * Forgets everything remembered for the task; returns how many entries were removed.
     */
    public int clear(String taskId) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sent_files WHERE task_id = ?")) {
            ps.setString(1, taskId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Cannot clear the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    public State state(String taskId) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_files_at, watching_since, last_alert_at FROM task_state WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new State(rs.getLong(1), rs.getLong(2), rs.getLong(3)) : new State(0, 0, 0);
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read the task state (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * Starts the "watching since" clock for a task, if it has not started yet.
     */
    public void startWatching(String taskId) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO task_state (task_id, watching_since) VALUES (?, ?) ON CONFLICT(task_id) DO UPDATE SET "
                             + "watching_since = CASE WHEN watching_since = 0 THEN excluded.watching_since ELSE watching_since END")) {
            ps.setString(1, taskId);
            ps.setLong(2, clock.getAsLong());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Cannot write the task state (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    // ── "No new files" tracking ──────────────────────────────────────

    /**
     * Files arrived now. Returns true when a "no new files" alert was outstanding (so a "files are back" note is due).
     */
    public boolean markFilesTransferred(String taskId) throws IOException {
        long now = clock.getAsLong();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                boolean wasSilent = false;
                try (PreparedStatement ps = c.prepareStatement("SELECT last_alert_at FROM task_state WHERE task_id = ?")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        wasSilent = rs.next() && rs.getLong(1) > 0;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO task_state (task_id, last_files_at, watching_since, last_alert_at) VALUES (?, ?, ?, 0) "
                                + "ON CONFLICT(task_id) DO UPDATE SET last_files_at = excluded.last_files_at, last_alert_at = 0")) {
                    ps.setString(1, taskId);
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                c.commit();
                return wasSilent;
            } catch (SQLException ex) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw ex;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot write the task state (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * Claims the right to raise the "no new files" alert: true for exactly one caller (even across the GUI and the
     * Daemon) when no alert was raised yet or the last one is at least {@code everyMillis} old ({@code <= 0}: always).
     */
    public boolean claimSilenceAlert(String taskId, long everyMillis) throws IOException {
        long now = clock.getAsLong();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE task_state SET last_alert_at = ? WHERE task_id = ? AND (last_alert_at = 0 OR ? <= 0 OR ? - last_alert_at >= ?)")) {
            ps.setLong(1, now);
            ps.setString(2, taskId);
            ps.setLong(3, everyMillis);
            ps.setLong(4, now);
            ps.setLong(5, everyMillis);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IOException("Cannot write the task state (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * Opens the file (creating it and the table on first use) and drops entries past the retention.
     */
    private Connection open() throws SQLException {
        File dir = dbFile.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new SQLException("cannot create folder " + dir);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not available", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("CREATE TABLE IF NOT EXISTS sent_files ("
                    + "task_id TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, "
                    + "mtime INTEGER NOT NULL, sent_at INTEGER NOT NULL, "
                    + "PRIMARY KEY (task_id, name, size, mtime))");
            st.execute("CREATE TABLE IF NOT EXISTS task_state ("
                    + "task_id TEXT PRIMARY KEY, last_files_at INTEGER NOT NULL DEFAULT 0, "
                    + "watching_since INTEGER NOT NULL DEFAULT 0, last_alert_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sent_files_task_name ON sent_files (task_id, name)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sent_files_sent_at ON sent_files (sent_at)");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM sent_files WHERE sent_at < ?")) {
            ps.setLong(1, clock.getAsLong() - TimeUnit.DAYS.toMillis(RETENTION_DAYS));
            ps.executeUpdate();
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * One sent file as seen on the source side.
     */
    public record Entry(String name, long size, long mtimeMillis) {
    }

    /**
     * Everything remembered for one task, loaded once per run so checking a file costs nothing.
     */
    public static final class Snapshot {
        private final Set<String> names = new HashSet<>();
        private final Set<String> exact = new HashSet<>();

        private static String key(String name, long size, long mtime) {
            return name + '\u0000' + size + '\u0000' + mtime;
        }

        /**
         * Whether this file counts as already sent under {@code policy}.
         */
        public boolean wasSent(String name, long size, long mtimeMillis, SentPolicy policy) {
            if (policy == SentPolicy.ANY_NAME) return names.contains(name);
            if (policy == SentPolicy.UNCHANGED) return exact.contains(key(name, size, mtimeMillis));
            return false;
        }

        /**
         * Number of remembered files (distinct name/size/time combinations).
         */
        public int size() {
            return exact.size();
        }
    }

    // ── internals ────────────────────────────────────────────────────

    /**
     * When files last arrived for a task, when we started watching it, and when we last alerted (0 = never).
     */
    public record State(long lastFilesAt, long watchingSince, long lastAlertAt) {
    }
}
