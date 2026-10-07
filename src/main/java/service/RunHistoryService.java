package service;

import model.EventKind;
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
    private final File dbFile;

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
        this.dbFile = dbFile;
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                // The GUI and the daemon each hold a connection to this file; wait
                // briefly for a lock (e.g. during a purge/VACUUM) instead of failing.
                st.execute("PRAGMA busy_timeout=5000");
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
        // Added with the Event Monitor redesign: what kind of event a row is,
        // which execution it belongs to, and what caused it. All nullable so
        // rows written by older builds stay valid (the monitor classifies them
        // from their text instead).
        addColumnIfMissing(st, table, "event_kind", "TEXT");
        addColumnIfMissing(st, table, "run_key", "TEXT");
        addColumnIfMissing(st, table, "trigger_src", "TEXT");
        st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_run_key ON " + table + "(run_key)");
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
        // Bring the renamed legacy table up to the current column set first,
        // then copy with an explicit column list — never SELECT * across two
        // tables whose layouts could differ.
        createTable(st, RUN_TABLE);
        createTable(st, ACTIVITY_TABLE);
        final String cols = "task_id, task_name, task_type, status, reason, details, started_at, ended_at, "
                + "duration_ms, failure_category, retryable, suggested_action";
        st.execute("INSERT INTO " + ACTIVITY_TABLE + " (" + cols + ") SELECT " + cols + " FROM " + RUN_TABLE +
                " WHERE reason LIKE 'Started \u2014%' OR reason LIKE 'Detected %'");
        st.execute("DELETE FROM " + RUN_TABLE +
                " WHERE reason LIKE 'Started \u2014%' OR reason LIKE 'Detected %'");
    }

    // ── Run correlation context ──────────────────────────────────────────
    // A task execution happens start-to-finish on one worker thread, and
    // everything it records (Started, Detected, retry notes, the final
    // outcome) should carry the same run key and trigger so the Event Monitor
    // can fold them into one expandable run. Threading these through every
    // recordRun/recordActivityEvent call site would mean changing signatures
    // across the scheduler; a thread-local set once at the start of a run and
    // cleared at the end gets the same result with no call-site changes.
    private static final ThreadLocal<String[]> RUN_CONTEXT = new ThreadLocal<>();

    /** Short, unique-enough id for one task execution. */
    public static String newRunKey() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** Marks the current thread as executing the run {@code runKey}, caused by {@code trigger} (SCHEDULE/WATCHER/MANUAL/RETRY). */
    public static void beginRunContext(String runKey, String trigger) {
        RUN_CONTEXT.set(new String[]{runKey, trigger});
    }

    /** Clears the current thread's run context. Always call from a finally block. */
    public static void endRunContext() {
        RUN_CONTEXT.remove();
    }

    /** Run key of the run executing on this thread, or null if none. */
    public static String currentRunKey() {
        String[] c = RUN_CONTEXT.get();
        return c != null ? c[0] : null;
    }

    /** Trigger of the run executing on this thread, or null if none. */
    public static String currentTrigger() {
        String[] c = RUN_CONTEXT.get();
        return c != null ? c[1] : null;
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

        EventKind kind = status == TaskRunRecord.Status.SUCCESS ? EventKind.RUN_SUCCEEDED
                : status == TaskRunRecord.Status.FAILED ? EventKind.RUN_FAILED : EventKind.RUN_SKIPPED;
        String runKey = currentRunKey();
        String trigger = currentTrigger();
        long durationMs = insertInto(RUN_TABLE, taskId, taskName, taskType, status, reason, details,
                startedAt, endedAt, category, retryable, suggestedAction, kind, runKey, trigger);
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
            rec.setEventKind(kind.name());
            rec.setRunKey(runKey);
            rec.setTrigger(trigger);
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
     * always SUCCESS — the feed (see {@code ui.monitor.EventClassifier},
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
        recordEvent(null, taskId, taskName, taskType, status, message, details, startedAt, endedAt,
                currentRunKey(), currentTrigger());
    }

    /**
     * Records one event of an explicit {@link EventKind} into the activity
     * table — the structured way to log anything the Event Monitor should
     * show: a watcher arming or degrading, a retry being scheduled, a
     * cancellation, a deferred fire. Prefer this over the free-text
     * {@link #recordActivityEvent} overloads for anything new.
     *
     * @param kind   what happened; null lets the monitor infer it from the row
     * @param runKey the run this belongs to, or null for events not tied to one
     *               (use {@link #currentRunKey()} when recording from a worker thread)
     * @param trigger what caused the run (SCHEDULE/WATCHER/MANUAL/RETRY), or null
     */
    public synchronized void recordEvent(EventKind kind, String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String message, String details,
            LocalDateTime startedAt, LocalDateTime endedAt, String runKey, String trigger) {
        long durationMs = insertInto(ACTIVITY_TABLE, taskId, taskName, taskType, status,
                message, details != null ? details : message, startedAt, endedAt, null, false, null,
                kind, runKey, trigger);
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
        rec.setEventKind(kind != null ? kind.name() : null);
        rec.setRunKey(runKey);
        rec.setTrigger(trigger);
        for (java.util.function.Consumer<TaskRunRecord> listener : activityListeners) {
            try { listener.accept(rec); } catch (Exception ignored) {}
        }
    }

    /** @return duration in ms on success, or -1 if the insert failed (already logged). */
    private long insertInto(String table, String taskId, String taskName, String taskType,
            TaskRunRecord.Status status, String reason, String details,
            LocalDateTime startedAt, LocalDateTime endedAt,
            TaskRunRecord.FailureCategory category, boolean retryable, String suggestedAction,
            EventKind kind, String runKey, String trigger) {
        if (conn == null) return -1;
        String sql = "INSERT INTO " + table +
                " (task_id, task_name, task_type, status, reason, details, started_at, ended_at, duration_ms,"
                + "  failure_category, retryable, suggested_action, event_kind, run_key, trigger_src) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
            ps.setString(13, kind != null ? kind.name() : null);
            ps.setString(14, runKey);
            ps.setString(15, trigger);
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
     * table a given {@code ui.monitor.MonitorEvent} came from — see
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
     * Cheap "did anything get written?" probe for pollers: the highest row id
     * in each table. Both tables are append-only and ids only grow, so an
     * unchanged token means no new rows — from <em>any</em> process sharing
     * this database, which is what lets the Event Monitor skip its query
     * entirely on the (common) idle tick instead of re-reading the feed every
     * second.
     */
    public synchronized String getChangeToken() {
        if (conn == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String table : new String[]{RUN_TABLE, ACTIVITY_TABLE}) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + table)) {
                sb.append(rs.next() ? rs.getLong(1) : 0L).append(':');
            } catch (SQLException e) {
                sb.append("?:");
            }
        }
        return sb.toString();
    }

    /**
     * Like {@link #queryRuns} but <b>without the {@code details} column</b>
     * (the full captured run log — easily tens of KB per row). The live feed
     * only needs the one-line {@code reason}; the full log is fetched on
     * demand for the single selected row via {@link #getRunById}.
     */
    public synchronized List<TaskRunRecord> queryRunSummaries(LocalDateTime from, int limit) {
        return querySummaries(RUN_TABLE, from, limit);
    }

    /** Activity-table counterpart of {@link #queryRunSummaries}. */
    public synchronized List<TaskRunRecord> queryActivitySummaries(LocalDateTime from, int limit) {
        return querySummaries(ACTIVITY_TABLE, from, limit);
    }

    private List<TaskRunRecord> querySummaries(String table, LocalDateTime from, int limit) {
        List<TaskRunRecord> results = new ArrayList<>();
        if (conn == null) return results;
        StringBuilder sql = new StringBuilder("SELECT id, task_id, task_name, task_type, status, reason, started_at, "
                + "ended_at, duration_ms, failure_category, retryable, suggested_action, event_kind, run_key, trigger_src "
                + "FROM " + table + " WHERE 1=1");
        if (from != null) sql.append(" AND started_at >= ?");
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (from != null) ps.setString(i++, from.format(TS_FMT));
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs, false));
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to query summaries from " + table, e);
        }
        return results;
    }

    /**
     * Deletes rows older than {@code keepDays} days from both tables. Kept
     * for existing callers (no VACUUM, so it is cheap enough for startup);
     * {@link #purge} is the full retention routine (separate windows per
     * table, log clearing, size cap, VACUUM).
     */
    public void pruneOlderThan(int keepDays) {
        pruneOlderThan(keepDays, keepDays);
    }

    /** Same, with a separate window per table; 0 leaves that table alone. */
    public void pruneOlderThan(int runDays, int eventDays) {
        if (conn == null) return;
        try {
            long runs = runDays > 0 ? deleteOlder(RUN_TABLE, cutoff(runDays), false) : 0;
            long events = eventDays > 0 ? deleteOlder(ACTIVITY_TABLE, cutoff(eventDays), false) : 0;
            if (runs + events > 0) {
                log.info("Pruned " + runs + " run row(s) and " + events + " event row(s)");
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to prune old rows", e);
        }
    }

    // ── Retention / purge ────────────────────────────────────────────────

    /** What one {@link #purge} call did (or, for a dry run, would do). */
    public static final class PurgeReport {
        public long runRowsDeleted;
        public long eventRowsDeleted;
        public long detailsCleared;
        public long sizeCapRowsDeleted;
        public long fileBytesBefore;
        public long fileBytesAfter;
        public boolean vacuumed;
        public String note = "";   // e.g. why VACUUM was skipped
        public boolean dryRun;

        public long totalRowsDeleted() { return runRowsDeleted + eventRowsDeleted + sizeCapRowsDeleted; }
    }

    private static final int PURGE_BATCH_ROWS = 5000;

    /**
     * Applies the retention rules to {@code run_history.db}. A rule with a
     * value of 0 is skipped.
     *
     * <ol>
     *   <li>delete {@code run_history} rows older than {@code runDays},</li>
     *   <li>delete {@code event_monitor_history} rows older than {@code eventDays},</li>
     *   <li>clear the full captured run log ({@code details}, the bulk of the
     *       file) on {@code run_history} rows older than {@code detailsDays},
     *       keeping the summary row,</li>
     *   <li>if the live data is still above {@code maxDbMb}, delete the oldest
     *       rows (events first) until it fits,</li>
     *   <li>VACUUM, because SQLite never shrinks the file on DELETE alone.</li>
     * </ol>
     *
     * Work is done in small batches, each taking this service's lock
     * separately, so a running task is never blocked from recording its
     * outcome for more than one batch (VACUUM is the one exception: it holds
     * the lock for as long as it takes to rewrite the file).
     *
     * @param dryRun count what would be removed; change nothing
     */
    public PurgeReport purge(int runDays, int eventDays, int detailsDays, int maxDbMb, boolean dryRun) {
        PurgeReport r = new PurgeReport();
        r.dryRun = dryRun;
        if (conn == null) { r.note = "database not available"; return r; }
        r.fileBytesBefore = dbFile.length();
        try {
            if (runDays > 0) r.runRowsDeleted = deleteOlder(RUN_TABLE, cutoff(runDays), dryRun);
            if (eventDays > 0) r.eventRowsDeleted = deleteOlder(ACTIVITY_TABLE, cutoff(eventDays), dryRun);
            if (detailsDays > 0) r.detailsCleared = clearDetailsOlder(cutoff(detailsDays), dryRun);

            if (maxDbMb > 0 && !dryRun) {
                r.sizeCapRowsDeleted = enforceSizeCap(maxDbMb * 1024L * 1024L);
            } else if (maxDbMb > 0) {
                r.note = "size cap not simulated in dry run";
            }

            if (!dryRun) {
                r.vacuumed = vacuumIfWorthIt(r);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Purge of run history failed part-way", e);
            r.note = "failed: " + e.getMessage();
        }
        r.fileBytesAfter = dbFile.length();
        if (r.totalRowsDeleted() > 0 || r.detailsCleared > 0) {
            log.info((dryRun ? "[dry run] would purge " : "Purged ") + r.runRowsDeleted + " run row(s), "
                    + r.eventRowsDeleted + " event row(s), cleared " + r.detailsCleared + " run log(s), "
                    + r.sizeCapRowsDeleted + " row(s) for size cap");
        }
        return r;
    }

    private static String cutoff(int days) {
        return LocalDateTime.now().minusDays(days).format(TS_FMT);
    }

    private long deleteOlder(String table, String cutoff, boolean dryRun) throws SQLException {
        if (dryRun) return countWhere(table, "started_at < ?", cutoff);
        long total = 0;
        int n;
        do {
            n = deleteBatchOlder(table, cutoff);
            total += n;
        } while (n >= PURGE_BATCH_ROWS);
        return total;
    }

    private synchronized int deleteBatchOlder(String table, String cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table
                + " WHERE id IN (SELECT id FROM " + table + " WHERE started_at < ? LIMIT ?)")) {
            ps.setString(1, cutoff);
            ps.setInt(2, PURGE_BATCH_ROWS);
            return ps.executeUpdate();
        }
    }

    private long clearDetailsOlder(String cutoff, boolean dryRun) throws SQLException {
        final String cond = "started_at < ? AND details IS NOT NULL";
        if (dryRun) return countWhere(RUN_TABLE, cond, cutoff);
        long total = 0;
        int n;
        do {
            n = clearDetailsBatch(cutoff);
            total += n;
        } while (n >= PURGE_BATCH_ROWS);
        return total;
    }

    private synchronized int clearDetailsBatch(String cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + RUN_TABLE + " SET details = NULL"
                + " WHERE id IN (SELECT id FROM " + RUN_TABLE
                + " WHERE started_at < ? AND details IS NOT NULL LIMIT ?)")) {
            ps.setString(1, cutoff);
            ps.setInt(2, PURGE_BATCH_ROWS);
            return ps.executeUpdate();
        }
    }

    private synchronized long countWhere(String table, String where, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    /** Bytes of actual data (file size minus pages already freed by DELETE but not yet VACUUMed). */
    private synchronized long liveBytes() throws SQLException {
        long pages = pragmaLong("page_count"), free = pragmaLong("freelist_count"), size = pragmaLong("page_size");
        return Math.max(0, pages - free) * size;
    }

    private long pragmaLong(String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * Deletes the oldest rows (event table first, then runs) until live data
     * fits {@code capBytes}. Each step deletes just enough of the oldest rows
     * to cover the remaining excess, judged by their stored text size, and
     * re-measures, so it neither overshoots by much nor wastes passes on rows
     * that are already tiny (e.g. ones whose run log was cleared).
     */
    private long enforceSizeCap(long capBytes) throws SQLException {
        long deleted = 0;
        for (int i = 0; i < 100; i++) {
            long excess = liveBytes() - capBytes;
            if (excess <= 0) return deleted;
            long n = deleteOldestBytes(ACTIVITY_TABLE, excess);
            if (n == 0) n = deleteOldestBytes(RUN_TABLE, excess);
            if (n == 0) break;   // nothing left to delete
            deleted += n;
        }
        if (liveBytes() > capBytes) {
            log.warning("run_history.db still above the " + (capBytes / 1024 / 1024)
                    + " MB cap after deleting everything deletable");
        }
        return deleted;
    }

    /** Deletes oldest rows of {@code table} until their estimated size reaches {@code needBytes} (at most 20,000 rows per call). */
    private synchronized long deleteOldestBytes(String table, long needBytes) throws SQLException {
        List<Long> ids = new ArrayList<>();
        long acc = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, 150 + COALESCE(length(CAST(details AS BLOB)),0)"
                     + " + COALESCE(length(CAST(reason AS BLOB)),0) + COALESCE(length(CAST(task_name AS BLOB)),0)"
                     + " FROM " + table + " ORDER BY started_at ASC, id ASC LIMIT 20000")) {
            while (acc < needBytes && rs.next()) {
                ids.add(rs.getLong(1));
                acc += rs.getLong(2);
            }
        }
        if (ids.isEmpty()) return 0;
        boolean prior = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            for (long id : ids) { ps.setLong(1, id); ps.addBatch(); }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prior);
        }
        return ids.size();
    }

    /** VACUUM when at least 1 MB (and 10%) of the file is free pages. Never throws: a busy database just skips it until next run. */
    private synchronized boolean vacuumIfWorthIt(PurgeReport r) {
        try {
            long freeBytes = pragmaLong("freelist_count") * pragmaLong("page_size");
            long fileBytes = dbFile.length();
            if (freeBytes < 1024 * 1024 || freeBytes * 10 < fileBytes) return false;
            try (Statement st = conn.createStatement()) { st.execute("VACUUM"); }
            return true;
        } catch (SQLException e) {
            r.note = (r.note.isEmpty() ? "" : r.note + "; ") + "VACUUM skipped: " + e.getMessage();
            log.log(Level.WARNING, "VACUUM of run_history.db skipped", e);
            return false;
        }
    }

    /** Current size of run_history.db on disk, in bytes (0 if missing). */
    public long fileSizeBytes() {
        return dbFile.length();
    }

    private TaskRunRecord mapRow(ResultSet rs) throws SQLException {
        return mapRow(rs, true);
    }

    private TaskRunRecord mapRow(ResultSet rs, boolean withDetails) throws SQLException {
        TaskRunRecord r = new TaskRunRecord();
        r.setId(rs.getLong("id"));
        r.setTaskId(rs.getString("task_id"));
        r.setTaskName(rs.getString("task_name"));
        r.setTaskType(rs.getString("task_type"));
        r.setStatus(TaskRunRecord.Status.valueOf(rs.getString("status")));
        r.setReason(rs.getString("reason"));
        if (withDetails) r.setDetails(rs.getString("details"));
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
        r.setEventKind(rs.getString("event_kind"));
        r.setRunKey(rs.getString("run_key"));
        r.setTrigger(rs.getString("trigger_src"));
        return r;
    }

    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
