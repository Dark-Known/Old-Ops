package persistence.sqlite;

import persistence.TransferLedger;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The memory of which files a task has already sent — what "Skip files already sent" consults, and
 * what the "Forget sent files" button clears.
 *
 * <p>It lives in {@code app.db} (tables {@code sent_files} and {@code task_state}), one set of rows per
 * task. Until then it had its own file ({@value #FILE_NAME}); that file is imported once and renamed to {@code .migrated}. An entry is the
 * file's name, size and last-changed time <i>as seen on the source side</i> at the moment it was sent,
 * plus when that happened; entries older than {@value TransferLedger#RETENTION_DAYS} days are dropped.
 *
 * <p>Every call opens a short-lived connection: the GUI and the Daemon both use this file, and SQLite's
 * file locking (with a busy timeout) takes care of them, so no connection is held between runs.
 * Failures are reported as {@link IOException} — callers decide whether that stops a run (reading) or
 * only earns a warning (writing after a transfer that already happened).
 */
public final class SqliteTransferLedger implements TransferLedger {

    /** The separate file older versions kept this memory in; imported into app.db once. */
    public static final String FILE_NAME = "transfer_ledger.db";

    private final File dataDir;
    private final File dbFile;
    private final LongSupplier clock;

    public SqliteTransferLedger(File dataDir) { this(dataDir, System::currentTimeMillis); }

    /** For tests: a clock that can be moved to check the retention. */
    public SqliteTransferLedger(File dataDir, LongSupplier clockMillis) {
        this.dataDir = dataDir;
        this.dbFile = new File(dataDir, "app.db");
        this.clock = clockMillis;
    }

    /** What is remembered for {@code taskId} right now. */
    @Override public Snapshot snapshot(String taskId) throws IOException {
        Snapshot s = new Snapshot();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT name, size, mtime FROM sent_files WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.add(rs.getString(1), rs.getLong(2), rs.getLong(3));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
        return s;
    }

    /** Remembers these files as sent now. Safe to call again for the same file (it just refreshes the time). */
    @Override public void record(String taskId, Collection<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) return;
        long now = clock.getAsLong();
        try (Connection c = open()) {
            Db.inTransaction(c, tx -> {
                try (PreparedStatement ps = tx.prepareStatement(
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
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IOException("Cannot write the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /** How many files are remembered for the task. */
    @Override public int count(String taskId) throws IOException {
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

    /** Forgets everything remembered for the task; returns how many entries were removed. */
    @Override public int clear(String taskId) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sent_files WHERE task_id = ?")) {
            ps.setString(1, taskId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Cannot clear the sent-files memory (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    // ── "No new files" tracking ──────────────────────────────────────

    @Override public State state(String taskId) throws IOException {
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

    /** Starts the "watching since" clock for a task, if it has not started yet. */
    @Override public void startWatching(String taskId) throws IOException {
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

    /** Files arrived now. Returns true when a "no new files" alert was outstanding (so a "files are back" note is due). */
    @Override public boolean markFilesTransferred(String taskId) throws IOException {
        long now = clock.getAsLong();
        try (Connection c = open()) {
            return Db.inTransaction(c, tx -> {
                boolean wasSilent;
                try (PreparedStatement ps = tx.prepareStatement("SELECT last_alert_at FROM task_state WHERE task_id = ?")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) { wasSilent = rs.next() && rs.getLong(1) > 0; }
                }
                try (PreparedStatement ps = tx.prepareStatement(
                        "INSERT INTO task_state (task_id, last_files_at, watching_since, last_alert_at) VALUES (?, ?, ?, 0) "
                                + "ON CONFLICT(task_id) DO UPDATE SET last_files_at = excluded.last_files_at, last_alert_at = 0")) {
                    ps.setString(1, taskId);
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                return wasSilent;
            });
        } catch (SQLException e) {
            throw new IOException("Cannot write the task state (" + dbFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * Claims the right to raise the "no new files" alert: true for exactly one caller (even across the GUI and the
     * Daemon) when no alert was raised yet or the last one is at least {@code everyMillis} old ({@code <= 0}: always).
     */
    @Override public boolean claimSilenceAlert(String taskId, long everyMillis) throws IOException {
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

    // ── internals ────────────────────────────────────────────────────

    /** Opens app.db (creating the tables on first use), imports the old separate file once, and drops entries past the retention. */
    private Connection open() throws SQLException {
        Connection c = Db.open(dbFile);
        try (Statement st = c.createStatement()) {
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
        importLegacy(c);
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
     * Copies the rows of the old {@value #FILE_NAME} into app.db and renames that file to {@code .migrated}. Safe to repeat or to
     * run in the GUI and the Daemon at the same time (existing rows win). If anything fails the old file is left where it is and
     * the import is tried again next time; a failure here never stops a run.
     */
    private void importLegacy(Connection c) {
        File legacy = new File(dataDir, FILE_NAME);
        if (!legacy.isFile()) return;
        try (Statement st = c.createStatement()) {
            try (PreparedStatement ps = c.prepareStatement("ATTACH DATABASE ? AS old")) {
                ps.setString(1, legacy.getAbsolutePath());
                ps.execute();
            }
            try {
                st.execute("BEGIN IMMEDIATE");
                try {
                    st.execute("INSERT OR IGNORE INTO sent_files (task_id, name, size, mtime, sent_at) "
                            + "SELECT task_id, name, size, mtime, sent_at FROM old.sent_files");
                    st.execute("INSERT OR IGNORE INTO task_state (task_id, last_files_at, watching_since, last_alert_at) "
                            + "SELECT task_id, last_files_at, watching_since, last_alert_at FROM old.task_state");
                    st.execute("COMMIT");
                } catch (SQLException e) {
                    try { st.execute("ROLLBACK"); } catch (SQLException ignored) { }
                    throw e;
                }
            } finally {
                try { st.execute("DETACH DATABASE old"); } catch (SQLException ignored) { }
            }
            java.nio.file.Files.move(legacy.toPath(), new File(dataDir, FILE_NAME + ".migrated").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (SQLException | IOException e) {
            // keep going: the old file stays and is imported next time (or another process already moved it)
        }
    }
}
