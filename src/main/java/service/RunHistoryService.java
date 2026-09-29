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
 * <p>Database file lives at {@code <dataDir>/run_history.db} and holds
 * <strong>two</strong> tables with an identical column layout, split by what
 * a row actually represents:
 * <ul>
 *   <li>{@code run_history} — a genuine outcome via {@link #recordRun},
 *       one row per completed/failed/skipped task run. This is what
 *       Statistics and the Logs tab's totals reflect.</li>
 *   <li>{@code event_monitor_history} — activity-feed entries via
 *       {@link #recordActivityEvent}: a worker picking up a task, a
 *       watcher's file-detection scan, or an application-activity note
 *       (a credential deleted, a task edited, a setting changed). These are
 *       about something *happening*, not an outcome, so they're kept out
 *       of run_history entirely rather than filtered out after the fact —
 *       counting them alongside real outcomes would double- or triple-
 *       count every run in aggregate stats.</li>
 * </ul>
 * The Event Monitor's live feed merges both tables chronologically; Statistics
 * and the Logs tab read only {@code run_history}.
 *
 * <p>Pre-existing installs had a single {@code task_runs} table mixing all
 * three row shapes together; the constructor migrates it to {@code
 * run_history} in place (rename) the first time this runs against an old
 * database, and anything in it whose reason matched the old synthetic-
 * activity markers is moved into the new {@code event_monitor_history}
 * table so existing data isn't lost or miscounted.
 *
 * <p>A single shared {@link Connection} is kept open and all access is
 * synchronized — this app's write volume doesn't need a connection pool,
 * and SQLite only supports one writer at a time regardless.
 */
public class RunHistoryService {

    private static final Logger log = Logger.getLogger(RunHistoryService.class.getName());
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String RUN_TABLE = "run_history";
    private static final String ACTIVITY_TABLE = "event_monitor_history";

    private final Connection conn;

    // Notified (on whatever thread recordRun() was called from — usually a
    // scheduler worker thread, never the EDT) after every real outcome is
    // recorded, so the UI can show a toast and refresh live views without
    // polling. Activity events (recordActivityEvent) do not fire this —
    // nothing currently needs a push notification for those, only a poll
    // (EventMonitorPanel's refresh timer already covers that).
    private final List<java.util.function.Consumer<TaskRunRecord>> runListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // Same idea as runListeners, but for recordActivityEvent — added because
    // it previously fired nothing at all, so a credential deleted, task
    // edited, or setting saved in one tab only ever reached the Event
    // Monitor's feed via its 1s poll timer, never immediately. Kept as a
    // separate list rather than reusing runListeners since most existing
    // runListeners consumers (the failure-toast logic) only care about real
    // outcomes and would otherwise need to filter every activity note out.
    private final List<java.util.function.Consumer<TaskRunRecord>> activityListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public RunHistoryService(String dataDir) {
        File dbFile = new File(dataDir, "run_history.db");
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                migrateLegacyTaskRunsTable(c, st);
                createTable(st, RUN_TABLE);
                createTable(st, ACTIVITY_TABLE);
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize run history database", e);
        }
        this.conn = c;
    }

    private static void createTable(Statement st, String table) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS " + table + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT NOT NULL," +
                "task_name TEXT NOT NULL," +
                "task_type TEXT," +
                "status TEXT NOT NULL," +
                "reason TEXT," +
                "details TEXT," +
                "started_at TEXT NOT NULL," +
                "ended_at TEXT NOT NULL," +
                "duration_ms INTEGER," +
                "failure_category TEXT," +
                "retryable INTEGER," +
                "suggested_action TEXT" +
                ")");
        st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_task_id ON " + table + "(task_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_started_at ON " + table + "(started_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_status ON " + table + "(status)");
        addColumnIfMissing(st, table, "failure_category", "TEXT");
        addColumnIfMissing(st, table, "retryable", "INTEGER");
        addColumnIfMissing(st, table, "suggested_action", "TEXT");
    }

    /**
     * Adds {@code column} to an existing {@code table} if it's not already
     * there — covers upgrading a database created before failure
     * classification existed, where {@code CREATE TABLE IF NOT EXISTS}
     * above is a no-op against the already-existing table and so never adds
     * new columns on its own.
     */
    private static void addColumnIfMissing(Statement st, String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
            }
        }
        if (!exists) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /**
     * One-time migration for installs that still have the old single
     * {@code task_runs} table. Renames it to {@code run_history}, then
     * moves every row whose reason matches the old synthetic-activity
     * markers ("Started —" / "Detected ") over to the new
     * {@code event_monitor_history} table. No-ops entirely on a fresh
     * install or one that's already been migrated.
     */
    private static void migrateLegacyTaskRunsTable(Connection c, Statement st) throws SQLException {
        boolean hasLegacy;
        try (ResultSet rs = c.getMetaData().getTables(null, null, "task_runs", null)) {
            hasLegacy = rs.next();
        }
        if (!hasLegacy) return;

        boolean alreadyMigrated;
        try (ResultSet rs = c.getMetaData().getTables(null, null, RUN_TABLE, null)) {
            alreadyMigrated = rs.next();
        }
        if (alreadyMigrated) return;

        log.info("Migrating legacy task_runs table to run_history / event_monitor_history");
        st.execute("ALTER TABLE task_runs RENAME TO " + RUN_TABLE);
        createTable(st, ACTIVITY_TABLE);
        st.execute("INSERT INTO " + ACTIVITY_TABLE +
                " SELECT * FROM " + RUN_TABLE +
                " WHERE reason LIKE 'Started \u2014%' OR reason LIKE 'Detected %'");
        st.execute("DELETE FROM " + RUN_TABLE +
                " WHERE reason LIKE 'Started \u2014%' OR reason LIKE 'Detected %'");
    }

    /** Registers a listener invoked after every recorded real outcome (success, failure, or skip). Not called on the EDT — marshal accordingly. */
    public void addRunListener(java.util.function.Consumer<TaskRunRecord> listener) {
        runListeners.add(listener);
    }

    /** Unregisters a listener previously passed to {@link #addRunListener}. No-op if it isn't registered. */
    public void removeRunListener(java.util.function.Consumer<TaskRunRecord> listener) {
        runListeners.remove(listener);
    }

    /**
     * Registers a listener invoked immediately after every recorded
     * activity-feed entry (worker start-up, detection scan, or an
     * application-activity note like a credential/task/settings change) —
     * this is what makes those show up in an open Event Monitor the instant
     * they happen rather than waiting for its next poll tick. Only reaches
     * listeners in the *same process*; a run recorded by the headless
     * Daemon (a separate JVM) can never fire this, which is why
     * {@link ui.EventMonitorPanel} still polls periodically too — this is a
     * fast path for same-process activity, not a replacement for polling.
     * Not called on the EDT — marshal accordingly.
     */
    public void addActivityListener(java.util.function.Consumer<TaskRunRecord> listener) {
        activityListeners.add(listener);
    }

    /** Unregisters a listener previously passed to {@link #addActivityListener}. No-op if it isn't registered. */
    public void removeActivityListener(java.util.function.Consumer<TaskRunRecord> listener) {
        activityListeners.remove(listener);
    }

    /** Records one completed real task outcome (SUCCESS/FAILED/SKIPPED) into {@code run_history}. Safe no-op if the database failed to initialize. */
    public synchronized void recordRun(String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String reason, String details,
            LocalDateTime startedAt, LocalDateTime endedAt) {
        recordRun(taskId, taskName, taskType, status, reason, details, startedAt, endedAt, null);
    }

    /**
     * Same as the five-arg {@link #recordRun} above, but takes an already-
     * classified failure (see {@link FailureClassifier#classifyThrowable})
     * instead of deriving one from {@code reason}'s text — pass the
     * {@code TaskRunRecord} {@link TransferService#getLastFailureClassification()}
     * returned right after a FAILED run, when one is available. This is the
     * reliable path: classifying from the actual exception type where it
     * was caught, rather than pattern-matching whatever text it eventually
     * became. {@code preclassified} may be null (falls back to
     * {@link FailureClassifier#classify(String, String)} on {@code reason}
     * text, same as before) — most failure sites in the app don't have a
     * classified exception in hand yet, only {@link TransferService}'s
     * outer catch blocks do so far.
     */
    public synchronized void recordRun(String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String reason, String details,
            LocalDateTime startedAt, LocalDateTime endedAt, TaskRunRecord preclassified) {
        TaskRunRecord.FailureCategory category = null;
        boolean retryable = false;
        String suggestedAction = null;
        if (status == TaskRunRecord.Status.FAILED) {
            TaskRunRecord classified = preclassified != null ? preclassified : FailureClassifier.classify(reason, details);
            category = classified.getFailureCategory();
            retryable = classified.isRetryable();
            suggestedAction = classified.getSuggestedAction();
        }

        long durationMs = insertInto(RUN_TABLE, taskId, taskName, taskType, status, reason, details,
                startedAt, endedAt, category, retryable, suggestedAction);
        if (durationMs < 0) return; // insert failed; already logged

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
            rec.setFailureCategory(category);
            rec.setRetryable(retryable);
            rec.setSuggestedAction(suggestedAction);
            for (java.util.function.Consumer<TaskRunRecord> listener : runListeners) {
                try { listener.accept(rec); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Records one activity-feed entry (worker start-up, watcher detection
     * scan, or an application-activity note) into
     * {@code event_monitor_history}, kept entirely separate from real
     * SUCCESS/FAILED/SKIPPED outcomes in {@code run_history}. Convenience
     * overload for the common case — always SUCCESS, since most activity
     * notes ("task edited", "credential deleted") don't have a failure
     * concept. See the 8-arg overload below for notes that DO fail (a test
     * connection, a credential save) and need to render as a failure in the
     * feed rather than a neutral note.
     *
     * @param taskId   the related task's id, or a fixed pseudo-id like
     *                 "SETTINGS"/"CREDENTIALS" for application-activity
     *                 notes not tied to one task
     * @param taskName display name shown in the feed row
     * @param taskType the related task's type, or null for non-task activity
     * @param message  shown as both the feed row's one-line summary and (if
     *                 {@code details} is null) the click-through detail
     */
    public synchronized void recordActivityEvent(String taskId, String taskName, String taskType,
            String message, String details, LocalDateTime startedAt, LocalDateTime endedAt) {
        recordActivityEvent(taskId, taskName, taskType, TaskRunRecord.Status.SUCCESS,
                message, details, startedAt, endedAt);
    }

    /**
     * Full form of {@link #recordActivityEvent} that lets an application
     * activity note report as a genuine failure (status FAILED) rather than
     * always SUCCESS — the feed (see {@code QueueMonitorView.toActivityRow},
     * which reads {@code r.getStatus() == FAILED} to decide the row's color
     * and "Failed: ..." prefix) renders it exactly like a failed task run.
     * Use this for anything that can meaningfully fail on its own — a test
     * connection, a credential or task save that didn't persist — so the
     * reason is visible right in the feed, not just in a modal the operator
     * may have already dismissed.
     */
    public synchronized void recordActivityEvent(String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String message, String details,
            LocalDateTime startedAt, LocalDateTime endedAt) {
        long durationMs = insertInto(ACTIVITY_TABLE, taskId, taskName, taskType, status,
                message, details != null ? details : message, startedAt, endedAt, null, false, null);
        if (durationMs < 0 || activityListeners.isEmpty()) return;

        TaskRunRecord rec = new TaskRunRecord();
        rec.setTaskId(taskId);
        rec.setTaskName(taskName);
        rec.setTaskType(taskType);
        rec.setStatus(status);
        rec.setReason(message);
        rec.setDetails(details != null ? details : message);
        rec.setStartedAt(startedAt);
        rec.setEndedAt(endedAt);
        rec.setDurationMs(durationMs);
        for (java.util.function.Consumer<TaskRunRecord> listener : activityListeners) {
            try { listener.accept(rec); } catch (Exception ignored) {}
        }
    }

    /** @return duration in ms on success, or -1 if the insert failed (already logged). */
    private long insertInto(String table, String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String reason, String details,
            LocalDateTime startedAt, LocalDateTime endedAt,
            TaskRunRecord.FailureCategory category, boolean retryable, String suggestedAction) {
        if (conn == null) return -1;
        String sql = "INSERT INTO " + table +
                " (task_id, task_name, task_type, status, reason, details, started_at, ended_at, duration_ms," +
                "  failure_category, retryable, suggested_action) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
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
            ps.setString(10, category != null ? category.name() : null);
            ps.setInt(11, retryable ? 1 : 0);
            ps.setString(12, suggestedAction);
            ps.executeUpdate();
            return durationMs;
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to record row into " + table, e);
            return -1;
        }
    }

    /** Most recent real outcomes across all tasks, newest first. */
    public List<TaskRunRecord> getRecentRuns(int limit) {
        return queryRuns(null, null, limit);
    }

    /** Most recent real outcomes for one task, newest first. */
    public List<TaskRunRecord> getRunsForTask(String taskId, int limit) {
        return queryRuns(taskId, null, limit);
    }

    /**
     * Looks up one real-outcome row by its exact database row id. Returns
     * {@code null} if not found; see {@link #getActivityEventById} for the
     * {@code event_monitor_history} counterpart (row ids are independent
     * per-table autoincrement sequences, so the caller must know which
     * table a given {@code QueueMonitorView.ActivityRow} came from — see
     * its {@code source()} field).
     */
    public synchronized TaskRunRecord getRunById(long id) {
        return getByIdFrom(RUN_TABLE, id);
    }

    /** {@code event_monitor_history} counterpart of {@link #getRunById}. */
    public synchronized TaskRunRecord getActivityEventById(long id) {
        return getByIdFrom(ACTIVITY_TABLE, id);
    }

    private TaskRunRecord getByIdFrom(String table, long id) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to look up row by id in " + table, e);
            return null;
        }
    }

    /** Queries real outcomes, newest first, optionally filtered by task id and/or status. Either filter may be null to mean "any". */
    public List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status, int limit) {
        return queryRuns(taskId, status, null, null, limit);
    }

    /**
     * Queries real outcomes, newest first, optionally filtered by task id,
     * status, and/or a start-time date range. Any filter may be null to
     * mean "any". {@code from}/{@code to} bound {@code started_at}
     * (inclusive on both ends).
     */
    public synchronized List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status,
            LocalDateTime from, LocalDateTime to, int limit) {
        return query(RUN_TABLE, taskId, status, from, to, limit);
    }

    /**
     * Queries activity-feed entries (worker start-ups, detection scans,
     * application-activity notes), newest first — same filter semantics as
     * {@link #queryRuns}. Used to build the Event Monitor's merged live
     * feed; Statistics and the Logs tab never call this.
     */
    public synchronized List<TaskRunRecord> queryActivityEvents(String taskId, LocalDateTime from, LocalDateTime to, int limit) {
        return query(ACTIVITY_TABLE, taskId, null, from, to, limit);
    }

    private List<TaskRunRecord> query(String table, String taskId, TaskRunRecord.Status status,
            LocalDateTime from, LocalDateTime to, int limit) {
        List<TaskRunRecord> results = new ArrayList<>();
        if (conn == null) return results;

        StringBuilder sql = new StringBuilder("SELECT * FROM " + table + " WHERE 1=1");
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
            log.log(Level.WARNING, "Failed to query " + table, e);
        }
        return results;
    }

    /**
     * Deletes rows older than {@code keepDays} days from both tables. Call
     * periodically (e.g. once at app startup) to keep the database from
     * growing unbounded — event_monitor_history in particular accumulates
     * much faster than run_history now that every task start and every
     * watcher detection scan is its own row there.
     */
    public synchronized void pruneOlderThan(int keepDays) {
        if (conn == null) return;
        String cutoff = LocalDateTime.now().minusDays(keepDays).format(TS_FMT);
        for (String table : new String[]{RUN_TABLE, ACTIVITY_TABLE}) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE started_at < ?")) {
                ps.setString(1, cutoff);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    log.info("Pruned " + deleted + " rows older than " + keepDays + " days from " + table);
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to prune old rows from " + table, e);
            }
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
        String category = rs.getString("failure_category");
        if (category != null) {
            try { r.setFailureCategory(TaskRunRecord.FailureCategory.valueOf(category)); }
            catch (IllegalArgumentException ignored) { /* unknown category value from a future version — leave null */ }
        }
        r.setRetryable(rs.getInt("retryable") != 0);
        r.setSuggestedAction(rs.getString("suggested_action"));
        return r;
    }

    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
