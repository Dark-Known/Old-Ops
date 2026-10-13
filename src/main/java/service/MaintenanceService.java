package service;

import model.TaskRunRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Housekeeping for the app's own databases: purging the background log and the run history to the
 * retention settings (age and size). It does no scheduling itself; {@link TaskSchedulerService}
 * queues {@link #JOB_PURGE} as a system job so it executes on a <b>task worker thread</b> like any
 * other work, and shows up in the workers badge, the live queue and the Event Monitor.
 */
public final class MaintenanceService {
    /** Id of the purge job as it travels through the event queue and the worker pool. */
    public static final String JOB_PURGE = "system:purge";
    private static final String META_LAST_PURGE = "last_purge_at";
    private static final String META_LAST_SUMMARY = "last_purge_summary";

    private final RunHistoryService history;
    private final BackgroundLogService backgroundLog;

    public MaintenanceService(RunHistoryService history, BackgroundLogService backgroundLog) {
        this.history = history;
        this.backgroundLog = backgroundLog;
    }

    /** True for ids of internal jobs (as opposed to ids of user-defined tasks). */
    public static boolean isSystemJob(String id) { return id != null && id.startsWith("system:"); }

    /** Name shown for a system job wherever tasks are listed (queue, workers badge). */
    public static String displayName(String id) {
        return JOB_PURGE.equals(id) ? "Purge old logs & history" : id;
    }

    /**
     * Purges both databases to their retention policies. Call from a worker thread; runs as an
     * audited background job, so its start, duration and result are recorded in the Event Monitor.
     * Each database is purged independently, so one failing does not stop the other.
     */
    public List<PurgeResult> runPurge(String trigger, BooleanSupplier cancelled) {
        List<PurgeResult> results = new ArrayList<>();
        try (BackgroundJobs.Job job = BackgroundJobs.begin("Purge old logs & history (" + trigger + ")", true)) {
            results.add(safe("Background log", () -> backgroundLog.purge(PurgePolicy.backgroundLog(), cancelled)));
            results.add(safe("Run history", () -> history.purge(PurgePolicy.runHistory(), cancelled)));
            StringBuilder sum = new StringBuilder();
            for (PurgeResult r : results) {
                if (sum.length() > 0) sum.append("; ");
                sum.append(r.summary());
                if (r.failed()) job.fail(r.summary());
            }
            job.result(sum.toString());
            backgroundLog.setMeta(META_LAST_PURGE, LocalDateTime.now().toString());
            backgroundLog.setMeta(META_LAST_SUMMARY, sum.toString());
        }
        return results;
    }

    private interface Purge { PurgeResult run(); }

    private static PurgeResult safe(String label, Purge p) {
        try { return p.run(); }
        catch (RuntimeException e) { return PurgeResult.failure(label, 0, 0, e.toString()); }
    }

    /** When the last purge finished, or null if none has run yet. */
    public LocalDateTime lastPurgeAt() {
        String v = backgroundLog.meta(META_LAST_PURGE);
        try { return v == null ? null : LocalDateTime.parse(v); } catch (Exception e) { return null; }
    }

    public String lastPurgeSummary() { return backgroundLog.meta(META_LAST_SUMMARY); }

    /** True when scheduled purging is on and the configured interval has elapsed since the last one. */
    public boolean purgeDue() {
        if (!PurgePolicy.scheduledEnabled()) return false;
        LocalDateTime last = lastPurgeAt();
        return last == null || Duration.between(last, LocalDateTime.now()).toHours() >= PurgePolicy.intervalHours()
                || LocalDateTime.now().isBefore(last); // clock moved backwards: don't wait indefinitely
    }

    public BackgroundLogService backgroundLog() { return backgroundLog; }
    public RunHistoryService history() { return history; }

    /** Moves background events that older builds stored in run history into their own database. */
    public static int migrateLegacyBackgroundRows(RunHistoryService history, BackgroundLogService bg) {
        if (!bg.isOpen() || "1".equals(bg.meta("legacy_migrated"))) return 0;
        List<TaskRunRecord> rows = history.listLegacyBackgroundRows();
        int moved = 0;
        if (!rows.isEmpty()) {
            long maxId = rows.stream().mapToLong(TaskRunRecord::getId).max().orElse(0);
            moved = bg.importRows(rows);
            if (moved != rows.size()) return 0; // import failed: leave the rows where they are, retry next start
            history.deleteLegacyBackgroundRows(maxId);
        }
        bg.setMeta("legacy_migrated", "1");
        return moved;
    }
}
