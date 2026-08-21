package service;

import model.TaskRunRecord;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores per-run summaries in a small SQLite database, so the Logs panel can
 * answer "what happened on this run" without scanning raw text log files.
 *
 * <p>This is intentionally NOT a copy of the full text logs (see
 * {@link TaskLogService} for that) — one row per task run, with:
 * <ul>
 *   <li>{@code status}  — SUCCESS / FAILED / SKIPPED</li>
 *   <li>{@code reason}  — short: why skipped, why failed, or a one-line
 *       success summary</li>
 *   <li>{@code details} — the full captured log text for that one run, for
 *       drill-down when the short reason isn't enough</li>
 * </ul>
 *
 * <p>Database file lives at {@code <dataDir>/run_history.db}. A single
 * shared {@link Connection} is kept open and all access is synchronized —
 * this app's write volume (one row per task run) doesn't need a connection
 * pool, and SQLite only supports one writer at a time regardless.
 */
public class RunHistoryService {

    private static final Logger log = Logger.getLogger(RunHistoryService.class.getName());
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Connection conn;

    // Notified (on whatever thread recordRun() was called from — usually a
    // scheduler worker thread, never the EDT) after every run is recorded,
    // so the UI can show a toast and refresh live views without polling.
    private final List<java.util.function.Consumer<TaskRunRecord>> runListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public RunHistoryService(String dataDir) {
        File dbFile = new File(dataDir, "run_history.db");
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS task_runs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "task_id TEXT NOT NULL," +
                        "task_name TEXT NOT NULL," +
                        "task_type TEXT," +
                        "status TEXT NOT NULL," +
                        "reason TEXT," +
                        "details TEXT," +
                        "started_at TEXT NOT NULL," +
                        "ended_at TEXT NOT NULL," +
                        "duration_ms INTEGER" +
                        ")");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_runs_task_id ON task_runs(task_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_runs_started_at ON task_runs(started_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_runs_status ON task_runs(status)");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize run history database", e);
        }
        this.conn = c;
    }

    /** Registers a listener invoked after every recorded run (success, failure, or skip). Not called on the EDT — marshal accordingly. */
    public void addRunListener(java.util.function.Consumer<TaskRunRecord> listener) {
        runListeners.add(listener);
    }

    /** Records one completed run. Safe no-op if the database failed to initialize. */
    public synchronized void recordRun(String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String reason, String details,
            LocalDateTime startedAt, LocalDateTime endedAt) {
        if (conn == null) return;
        String sql = "INSERT INTO task_runs " +
                "(task_id, task_name, task_type, status, reason, details, started_at, ended_at, duration_ms) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        long durationMs = java.time.Duration.between(startedAt, endedAt).toMillis();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, taskName);
            ps.setString(3, taskType);
            ps.setString(4, status.name());
            ps.setString(5, reason);
            ps.setString(6, details);
            ps.setString(7, startedAt.format(TS_FMT));
            ps.setString(8, endedAt.format(TS_FMT));
            ps.setLong(9, durationMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to record task run history", e);
            return;
        }

        if (!runListeners.isEmpty()) {
            TaskRunRecord rec = new TaskRunRecord();
            rec.setTaskId(taskId);
            rec.setTaskName(taskName);
            rec.setTaskType(taskType);
            rec.setStatus(status);
            rec.setReason(reason);
            rec.setDetails(details);
            rec.setStartedAt(startedAt);
            rec.setEndedAt(endedAt);
            rec.setDurationMs(durationMs);
            for (java.util.function.Consumer<TaskRunRecord> listener : runListeners) {
                try { listener.accept(rec); } catch (Exception ignored) {}
            }
        }
    }

    /** Most recent runs across all tasks, newest first. */
    public List<TaskRunRecord> getRecentRuns(int limit) {
        return queryRuns(null, null, limit);
    }

    /** Most recent runs for one task, newest first. */
    public List<TaskRunRecord> getRunsForTask(String taskId, int limit) {
        return queryRuns(taskId, null, limit);
    }

    /**
     * Queries runs, newest first, optionally filtered by task id and/or
     * status. Either filter may be null to mean "any".
     */
    public List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status, int limit) {
        return queryRuns(taskId, status, null, null, limit);
    }

    /**
     * Queries runs, newest first, optionally filtered by task id, status,
     * and/or a start-time date range. Any filter may be null to mean "any".
     * {@code from}/{@code to} bound {@code started_at} (inclusive on both ends).
     */
    public synchronized List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status,
            LocalDateTime from, LocalDateTime to, int limit) {
        List<TaskRunRecord> results = new ArrayList<>();
        if (conn == null) return results;

        StringBuilder sql = new StringBuilder("SELECT * FROM task_runs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (taskId != null) {
            sql.append(" AND task_id = ?");
            params.add(taskId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (from != null) {
            sql.append(" AND started_at >= ?");
            params.add(from.format(TS_FMT));
        }
        if (to != null) {
            sql.append(" AND started_at <= ?");
            params.add(to.format(TS_FMT));
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ?");
        params.add(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to query run history", e);
        }
        return results;
    }

    /**
     * Deletes run rows older than {@code keepDays} days. Call periodically
     * (e.g. once at app startup) to keep the database from growing
     * unbounded, since every task run — success, failure, or skip — adds a
     * row.
     */
    public synchronized void pruneOlderThan(int keepDays) {
        if (conn == null) return;
        String cutoff = LocalDateTime.now().minusDays(keepDays).format(TS_FMT);
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM task_runs WHERE started_at < ?")) {
            ps.setString(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Pruned " + deleted + " run-history rows older than " + keepDays + " days");
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to prune old run history", e);
        }
    }

    private TaskRunRecord mapRow(ResultSet rs) throws SQLException {
        TaskRunRecord r = new TaskRunRecord();
        r.setId(rs.getLong("id"));
        r.setTaskId(rs.getString("task_id"));
        r.setTaskName(rs.getString("task_name"));
        r.setTaskType(rs.getString("task_type"));
        r.setStatus(TaskRunRecord.Status.valueOf(rs.getString("status")));
        r.setReason(rs.getString("reason"));
        r.setDetails(rs.getString("details"));
        r.setStartedAt(LocalDateTime.parse(rs.getString("started_at"), TS_FMT));
        r.setEndedAt(LocalDateTime.parse(rs.getString("ended_at"), TS_FMT));
        r.setDurationMs(rs.getLong("duration_ms"));
        return r;
    }

    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
