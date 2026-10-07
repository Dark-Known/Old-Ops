package service;

import model.ScheduledTask;
import model.TaskRunRecord;
import util.AppSettings;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Data retention: keeps {@code run_history.db} and the per-task log folders
 * from growing without bound.
 *
 * <p>What it does is controlled by the {@code purge*} settings in
 * {@link AppSettings} (editable in the Settings panel, live, no restart):
 * old run/event rows are deleted, the bulky full run log can be cleared from
 * older rows while keeping their summary, an optional size cap deletes the
 * oldest rows until the database fits, the file is VACUUMed so it really
 * shrinks, and task log archives / log folders of deleted tasks are removed.
 * See {@link RunHistoryService#purge} and {@link TaskLogService#purgeLogs}
 * for the exact rules.
 *
 * <p><b>Who runs it:</b> the daemon calls {@link #start()}, which checks every
 * 10 minutes and runs the purge once per calendar day after the configured
 * hour (so a machine that was off at 03:00 simply runs it on the next check).
 * The Settings panel's "Purge now" button queues a
 * {@link CommandQueueService.Action#PURGE_NOW} command which the daemon
 * handles with {@link #runNowAsync}. It can also be run by hand or from
 * Windows Task Scheduler:
 * <pre>java -cp Monitoring-Tool.jar service.MaintenanceService [dataDir] [--dry-run]</pre>
 */
public final class MaintenanceService {

    /** Pseudo task id used for the activity-feed entry a purge leaves behind. */
    public static final String PSEUDO_TASK_ID = "MAINTENANCE";

    private static final long CHECK_INTERVAL_MINUTES = 10;

    private final XmlStorageService storage;
    private final RunHistoryService history;
    private final TaskLogService logs;
    private final Consumer<String> logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    /** What one purge did; {@link #summary} is a one-line human description. */
    public record Result(boolean dryRun, String summary, RunHistoryService.PurgeReport db, TaskLogService.PurgeStats logs) {}

    public MaintenanceService(XmlStorageService storage, RunHistoryService history,
                              TaskLogService logs, Consumer<String> logger) {
        this.storage = storage;
        this.history = history;
        this.logs = logs;
        this.logger = logger != null ? logger : s -> { };
    }

    // ── Scheduling ───────────────────────────────────────────────────────

    /** Starts the background check (daemon only). Safe to call once. */
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "maintenance-purge");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkAndRun, 1, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
        logger.accept("Data retention scheduler started — daily purge after "
                + String.format("%02d:00", AppSettings.getPurgeHour()) + " (enabled="
                + AppSettings.isPurgeEnabled() + ")");
    }

    public synchronized void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
    }

    /** Runs a purge now on the scheduler thread, ignoring the enabled flag and the once-a-day rule (a manual request). */
    public void runNowAsync(String trigger) {
        synchronized (this) {
            if (scheduler == null) {
                Thread t = new Thread(() -> runPurge(false, trigger), "maintenance-purge-manual");
                t.setDaemon(true);
                t.start();
                return;
            }
            scheduler.execute(() -> runPurge(false, trigger));
        }
    }

    private void checkAndRun() {
        try {
            if (!AppSettings.isPurgeEnabled()) return;
            if (isDue(AppSettings.getPurgeLastRunEpochMs(), AppSettings.getPurgeHour(), LocalDateTime.now())) {
                runPurge(false, "SCHEDULE");
            }
        } catch (Throwable t) {   // never let an exception cancel the periodic check
            logger.accept("[WARN] Data retention check failed: " + t);
        }
    }

    /**
     * Due once per calendar day: last run was before today (local time) and
     * the current hour has reached {@code purgeHour}. A never-run install
     * (lastRun = 0) is therefore due as soon as that hour has passed today.
     */
    static boolean isDue(long lastRunEpochMs, int purgeHour, LocalDateTime now) {
        if (now.getHour() < purgeHour) return false;
        if (lastRunEpochMs <= 0) return true;
        LocalDate lastDay = Instant.ofEpochMilli(lastRunEpochMs).atZone(ZoneId.systemDefault()).toLocalDate();
        return lastDay.isBefore(now.toLocalDate());
    }

    // ── The purge itself ─────────────────────────────────────────────────

    /**
     * Runs the retention rules using the current settings. Blocking.
     *
     * @return the result, or {@code null} if another purge is already running
     */
    public Result runPurge(boolean dryRun, String trigger) {
        if (!running.compareAndSet(false, true)) {
            logger.accept("Data retention: a purge is already running — skipped (" + trigger + ")");
            return null;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            logger.accept("Data retention: " + (dryRun ? "dry run" : "purge") + " starting (" + trigger + ")");
            RunHistoryService.PurgeReport db = history.purge(
                    AppSettings.getPurgeRunHistoryDays(),
                    AppSettings.getPurgeEventHistoryDays(),
                    AppSettings.getPurgeRunDetailsDays(),
                    AppSettings.getPurgeMaxDbMb(),
                    dryRun);

            List<ScheduledTask> tasks;
            try { tasks = storage.loadTasks(); }
            catch (Exception e) { tasks = List.of(); }   // empty list => no folder is treated as an orphan
            TaskLogService.PurgeStats lg = logs.purgeLogs(tasks,
                    AppSettings.getPurgeTaskLogDays(), AppSettings.getPurgeOrphanLogDays(), dryRun);

            String summary = describe(dryRun, db, lg);
            logger.accept("Data retention: " + summary);

            if (!dryRun) {
                Map<String, String> state = new LinkedHashMap<>();
                state.put(AppSettings.KEY_PURGE_LAST_RUN, String.valueOf(System.currentTimeMillis()));
                state.put(AppSettings.KEY_PURGE_LAST_RESULT, summary);
                try { AppSettings.setAll(state); }
                catch (Exception e) { logger.accept("[WARN] Could not save purge status: " + e.getMessage()); }

                boolean failed = db.note.startsWith("failed") || lg.failures > 0;
                history.recordActivityEvent(PSEUDO_TASK_ID, "Data retention", null,
                        failed ? TaskRunRecord.Status.FAILED : TaskRunRecord.Status.SUCCESS,
                        "Purge (" + trigger.toLowerCase() + "): " + summary, summary, startedAt, LocalDateTime.now());
            }
            return new Result(dryRun, summary, db, lg);
        } finally {
            running.set(false);
        }
    }

    private static String describe(boolean dryRun, RunHistoryService.PurgeReport db, TaskLogService.PurgeStats lg) {
        StringBuilder sb = new StringBuilder(dryRun ? "DRY RUN, nothing changed. Would remove: " : "");
        sb.append("run history ").append(db.runRowsDeleted).append(" row(s)");
        sb.append(", events ").append(db.eventRowsDeleted).append(" row(s)");
        if (db.detailsCleared > 0) sb.append(", run logs cleared on ").append(db.detailsCleared).append(" row(s)");
        if (db.sizeCapRowsDeleted > 0) sb.append(", ").append(db.sizeCapRowsDeleted).append(" more row(s) for the size cap");
        sb.append("; task logs ").append(lg.archivesDeleted).append(" archive(s)");
        if (lg.orphanFoldersDeleted > 0) sb.append(" + ").append(lg.orphanFoldersDeleted).append(" orphan folder(s)");
        sb.append(" (").append(formatBytes(lg.bytesFreed)).append(")");
        if (!dryRun) {
            sb.append("; run_history.db ").append(formatBytes(db.fileBytesBefore));
            sb.append(" -> ").append(formatBytes(db.fileBytesAfter));
            if (db.vacuumed) sb.append(" (compacted)");
        }
        if (lg.failures > 0) sb.append("; ").append(lg.failures).append(" file(s) could not be deleted");
        if (!db.note.isEmpty()) sb.append("; ").append(db.note);
        return sb.toString();
    }

    static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    // ── Command line ─────────────────────────────────────────────────────

    /** {@code java -cp Monitoring-Tool.jar service.MaintenanceService [dataDir] [--dry-run]} */
    public static void main(String[] args) {
        boolean dryRun = false;
        String dataDir = null;
        for (String a : args) {
            if ("--dry-run".equalsIgnoreCase(a)) dryRun = true;
            else if (dataDir == null) dataDir = a;
        }
        if (dataDir == null) {
            dataDir = util.AppConfig.readValue("dataDir");
            if (dataDir == null || dataDir.isEmpty()) dataDir = "C:\\OpsTools\\Data";
        }
        System.out.println("Data directory : " + dataDir);
        System.out.println("Settings read  : " + AppSettings.filePath());

        XmlStorageService storage = new XmlStorageService(dataDir);
        RunHistoryService history = new RunHistoryService(new File(dataDir).getAbsolutePath());
        TaskLogService logs = new TaskLogService(new File(dataDir).getAbsolutePath());
        try {
            MaintenanceService svc = new MaintenanceService(storage, history, logs, System.out::println);
            Result r = svc.runPurge(dryRun, "CLI");
            System.out.println(r == null ? "Another purge is already running." : r.summary());
        } finally {
            history.close();
            logs.closeAll();
        }
    }
}
