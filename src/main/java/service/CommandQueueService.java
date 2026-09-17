package service;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared, file-backed command queue that lets a thin GUI ask the daemon
 * (the sole scheduler/worker-pool owner) to actually do something, instead
 * of the GUI executing it in-process.
 *
 * <p>Before this existed, {@code ui.NotificationPanel}, {@code
 * ui.TaskManagerPanel}, {@code ui.SettingsPanel}, and {@code
 * ui.WatcherInfoPopup} called {@code TaskSchedulerService#runNow},
 * {@code #cancelTask}, {@code #restartTask}, and {@code #reconnectWatch}
 * directly on the GUI's <em>own</em> {@code TaskSchedulerService} instance.
 * That instance's {@code executor} (worker pool) exists and can run tasks
 * regardless of whether {@code start()} was ever called — so even a GUI
 * that never runs its own poll loop could still directly execute a task in
 * its own process via one of these calls, which defeats the point of the
 * daemon being the sole place tasks execute (isolated resource usage, one
 * consistent log/history writer, no chance of the same "run now" click
 * executing twice if both processes happened to see it).
 *
 * <p>Storage is a small table in {@code app.db} — the same shared,
 * per-machine SQLite file {@link util.AppSettings} already uses — so it
 * works the same way whether the daemon runs as the interactive user or,
 * once wrapped as a Windows Service, as a separate service account: no
 * per-user registry/session state is involved.
 *
 * <p>Connections are opened per call rather than held open. Commands are a
 * rare, user-triggered event (a button click), not a hot loop, so the extra
 * few milliseconds of connect overhead is immaterial and this avoids two
 * long-running processes needing to coordinate a shared connection's
 * lifecycle.
 */
public final class CommandQueueService {

    private static final Logger log = Logger.getLogger(CommandQueueService.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    public enum Action { RUN_NOW, CANCEL, RESTART, RECONNECT_WATCH, REFRESH }
    public enum Status { PENDING, DONE, FAILED }

    /** One row, as read back by the daemon's poller. */
    public record Command(long id, String taskId, Action action, String requestedBy, LocalDateTime requestedAt) {}

    private CommandQueueService() {}

    // ── GUI side: enqueue ──────────────────────────────────────────────────

    /**
     * Enqueues a command for the daemon to pick up on its next poll
     * (typically within a couple of seconds — see {@code Daemon}'s command
     * poller). Failures are logged, not thrown: a queueing hiccup shouldn't
     * crash a button click, and the caller has nothing useful to do
     * differently if this returns false beyond what showing "action failed,
     * try again" already covers.
     */
    public static boolean enqueue(File dataDir, String taskId, Action action, String requestedBy) {
        try (Connection c = connection(dataDir)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gui_commands (task_id, action, requested_by, requested_at, status) " +
                    "VALUES (?, ?, ?, ?, 'PENDING')")) {
                ps.setString(1, taskId);
                ps.setString(2, action.name());
                ps.setString(3, requestedBy);
                ps.setString(4, LocalDateTime.now().format(TS));
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to enqueue command " + action + " for task " + taskId, e);
            return false;
        }
    }

    // ── Daemon side: drain and resolve ───────────────────────────────────

    /** Fetches every command still waiting to be handled, oldest first. */
    public static List<Command> pollPending(File dataDir) {
        List<Command> out = new ArrayList<>();
        try (Connection c = connection(dataDir);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, task_id, action, requested_by, requested_at FROM gui_commands " +
                     "WHERE status = 'PENDING' ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(new Command(
                            rs.getLong("id"),
                            rs.getString("task_id"),
                            Action.valueOf(rs.getString("action")),
                            rs.getString("requested_by"),
                            LocalDateTime.parse(rs.getString("requested_at"), TS)));
                } catch (IllegalArgumentException badAction) {
                    // Unknown action name (e.g. queued by a newer GUI against an
                    // older daemon) — mark it failed rather than looping on it forever.
                    markFailed(dataDir, rs.getLong("id"), "Unrecognized action: " + rs.getString("action"));
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to read pending commands", e);
        }
        return out;
    }

    public static void markDone(File dataDir, long commandId) {
        updateStatus(dataDir, commandId, Status.DONE, null);
    }

    public static void markFailed(File dataDir, long commandId, String detail) {
        updateStatus(dataDir, commandId, Status.FAILED, detail);
    }

    private static void updateStatus(File dataDir, long commandId, Status status, String detail) {
        try (Connection c = connection(dataDir);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE gui_commands SET status = ?, detail = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, detail);
            ps.setLong(3, commandId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to update command " + commandId + " to " + status, e);
        }
    }

    /**
     * Deletes resolved (DONE/FAILED) commands older than a day, so this
     * table doesn't grow without bound. Cheap enough to call on every daemon
     * poll tick — it's a single indexed DELETE against what's normally a
     * handful of rows.
     */
    public static void pruneOldResolved(File dataDir) {
        try (Connection c = connection(dataDir);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM gui_commands WHERE status != 'PENDING' AND requested_at < ?")) {
            ps.setString(1, LocalDateTime.now().minusDays(1).format(TS));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to prune old commands", e);
        }
    }

    // ── Connection helper ────────────────────────────────────────────────

    private static Connection connection(File dataDir) throws SQLException {
        dataDir.mkdirs();
        File dbFile = new File(dataDir, "app.db");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
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
        return c;
    }
}
