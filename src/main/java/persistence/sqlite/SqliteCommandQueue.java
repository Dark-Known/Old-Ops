package persistence.sqlite;

import persistence.CommandQueue;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The command queue as a small table ({@code gui_commands}) in {@code app.db}: the same shared,
 * per-machine SQLite file the tasks live in, so it works whether the daemon runs as the interactive user
 * or as a separate service account. The enqueue side opens a connection per call (a button click, not a
 * hot loop); the daemon's {@link CommandQueue.Poller} holds one connection and creates the schema once.
 */
public final class SqliteCommandQueue implements CommandQueue {

    private static final Logger log = Logger.getLogger(SqliteCommandQueue.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final File dataDir;

    public SqliteCommandQueue(File dataDir) { this.dataDir = dataDir; }

    @Override
    public void enqueue(String taskId, Action action, String requestedBy) throws IOException {
        try (Connection c = connection(dataDir);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO gui_commands (task_id, action, requested_by, requested_at, status) " +
                     "VALUES (?, ?, ?, ?, 'PENDING')")) {
            ps.setString(1, taskId);
            ps.setString(2, action.name());
            ps.setString(3, requestedBy);
            ps.setString(4, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to enqueue command " + action + " for task " + taskId + ": " + e.getMessage(), e);
        }
    }

    @Override public Poller poller() { return new SqlitePoller(dataDir); }

    // ── connection helpers ───────────────────────────────────────────────

    private static Connection connection(File dataDir) throws SQLException {
        Connection c = open(dataDir);
        ensureSchema(c);
        return c;
    }

    private static Connection open(File dataDir) throws SQLException {
        dataDir.mkdirs();
        File dbFile = new File(dataDir, "app.db");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath", e);
        }
        Connection c = Db.connect(dbFile);
        try (Statement st = c.createStatement()) { st.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }

    private static void ensureSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS gui_commands (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "task_id TEXT NOT NULL, " +
                    "action TEXT NOT NULL, " +
                    "requested_by TEXT, " +
                    "requested_at TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "detail TEXT)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_gui_commands_status ON gui_commands (status)");
        }
    }

    // ── the daemon's poller ──────────────────────────────────────────────

    /**
     * Holds <b>one</b> connection and creates the schema once. The empty-queue check is a single indexed
     * SELECT; expiry runs about once a minute and pruning every ten minutes, not on every poll. Pair it with
     * {@link persistence.ChangeSignals#awaitChange} so it only runs when {@code app.db} was committed to
     * (or on a slow safety interval).
     */
    private static final class SqlitePoller implements Poller {
        private static final long EXPIRE_EVERY_MS = 60_000;
        private static final long PRUNE_EVERY_MS = 600_000;

        private final File dataDir;
        private Connection conn;
        private long lastExpire, lastPrune;

        SqlitePoller(File dataDir) { this.dataDir = dataDir; }

        private Connection conn() throws SQLException {
            if (conn == null || conn.isClosed()) {
                conn = open(dataDir);
                ensureSchema(conn);
            }
            return conn;
        }

        private void reset() {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
            conn = null;
        }

        @Override public List<Command> pollPending() {
            List<Command> out = new ArrayList<>();
            try {
                Connection c = conn();
                long now = System.currentTimeMillis();
                // Prune first, then expire: a command that sat PENDING for more than a day is marked FAILED
                // ("Expired") by this poll and must stay visible as FAILED until the next prune, not be deleted
                // in the same breath. (Before the port, expiry ran on every poll and pruning was a separate call.)
                if (now - lastPrune >= PRUNE_EVERY_MS) { lastPrune = now; prune(c); }
                if (now - lastExpire >= EXPIRE_EVERY_MS) { lastExpire = now; expire(c); }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, task_id, action, requested_by, requested_at FROM gui_commands " +
                        "WHERE status = 'PENDING' ORDER BY id ASC");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        try {
                            out.add(new Command(id, rs.getString("task_id"),
                                    Action.valueOf(rs.getString("action")),
                                    rs.getString("requested_by"),
                                    LocalDateTime.parse(rs.getString("requested_at"), TS)));
                        } catch (IllegalArgumentException | java.time.format.DateTimeParseException bad) {
                            // Unknown action (newer GUI, older daemon) or unreadable row: fail it rather than loop on it.
                            markOn(id, "FAILED", "Unrecognized command row: " + rs.getString("action"));
                        }
                    }
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to read pending commands", e);
                reset(); // reopen next time in case the connection itself went bad
            }
            return out;
        }

        @Override public void markDone(long id) { markOn(id, "DONE", null); }
        @Override public void markFailed(long id, String detail) { markOn(id, "FAILED", detail); }

        private void markOn(long id, String status, String detail) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE gui_commands SET status = ?, detail = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, detail);
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to update command " + id + " to " + status, e);
                reset();
            }
        }

        private static void expire(Connection c) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE gui_commands SET status = 'FAILED', detail = ? WHERE status = 'PENDING' AND requested_at < ?")) {
                ps.setString(1, "Expired: not picked up within " + PENDING_TTL_MINUTES + " minutes (daemon not running?)");
                ps.setString(2, LocalDateTime.now().minusMinutes(PENDING_TTL_MINUTES).format(TS));
                ps.executeUpdate();
            }
        }

        private static void prune(Connection c) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM gui_commands WHERE status != 'PENDING' AND requested_at < ?")) {
                ps.setString(1, LocalDateTime.now().minusDays(1).format(TS));
                ps.executeUpdate();
            }
        }

        @Override public void close() { reset(); }
    }
}
