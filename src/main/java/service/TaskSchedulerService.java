package service;

import model.ScheduledTask.*;
import model.ScheduledTask;
import model.TaskRunRecord;
import service.TaskLogService;
import service.queue.TaskDueEvent;
import service.queue.TaskEventQueue;
import service.queue.SchedulerStatusSnapshot;
import service.queue.TaskWorkerPool;
import service.watch.LocalWatchManager;
import service.watch.RemotePushWatcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Event-driven task scheduler. Rather than polling a task list on a shared
 * tick, each task's next-due instant is computed once and published as a
 * {@link TaskDueEvent} onto a {@link TaskEventQueue} (an in-process,
 * DelayQueue-backed "broker"). A {@link TaskWorkerPool} of independent
 * worker threads blocks on that queue and executes events exactly when
 * they come due — no shared tick, no poll-interval phase misalignment.
 * A lightweight periodic reconcile pass remains only as a self-healing
 * safety net (new/edited tasks, stale RUNNING recovery, clock skew) — it
 * no longer decides what fires.
 * Supports: RUN_NOW, ONCE, DAILY, WEEKLY, INTERVAL_MINUTES, INTERVAL_SECONDS.
 */
public class TaskSchedulerService {

    private static final Logger log = Logger.getLogger(TaskSchedulerService.class.getName());
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    // How much delivery jitter (worker pool momentarily saturated, GC pause,
    // etc.) is tolerated before an onTaskDue() delivery is treated as "still
    // the same occurrence" rather than "stale, re-arm for the real next one".
    private static final long DUE_TOLERANCE_MS = 5_000L;

    private final XmlStorageService storage;
    private final TransferService transferService;
    private final TaskLogService logService;
    private final RunHistoryService runHistoryService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    // Bounded, NOT Executors.newCachedThreadPool(). A cached pool creates a
    // brand-new thread for every task submitted while none are idle, with
    // no ceiling — each thread reserves its own stack (OS-default is
    // typically several hundred KB–1MB), so any burst of task submissions
    // (a backlog of due tasks after the GUI was closed a while, retries,
    // or previously the same-process double-scheduling race fixed above)
    // could spawn hundreds of threads and visibly balloon the process's
    // memory footprint without a single object leak anywhere. A fixed pool
    // sized to a realistic worst-case concurrent-task count keeps that
    // bounded; excess submissions simply queue instead of spawning more
    // threads. Configurable since a very large deployment may genuinely
    // want more parallel transfers.
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(4, util.AppSettings.getMaxConcurrentTaskThreads()));
    // The in-process "broker": holds one pending TaskDueEvent per eligible
    // task, releasing each to a worker exactly at its due instant. Replaces
    // the old shared poll tick + separate short-interval-timer map.
    private final TaskEventQueue eventQueue = new TaskEventQueue();
    // Independent worker threads draining eventQueue; execution concurrency
    // is controlled by pool size, same knob as before (AppSettings.getMaxConcurrentTaskThreads()).
    private final TaskWorkerPool workerPool = new TaskWorkerPool(
            eventQueue, Math.max(4, util.AppSettings.getMaxConcurrentTaskThreads()), this::onTaskDue);
    // Push-notification producers that feed eventQueue directly, in addition
    // to (not instead of) the normal poll-based publishNextOccurrence path —
    // see each class's javadoc for exactly what it replaces and what it
    // leaves as a safety net.
    //   - LocalWatchManager:   OUTBOUND watcher tasks — OS-level directory
    //     notifications (ReadDirectoryChangesW/inotify/FSEvents) via
    //     java.nio.file.WatchService. Always available, no polling left
    //     once native watch registration succeeds.
    //   - RemotePushWatcher: INBOUND watcher tasks — best-effort remote
    //     push via SSH-exec: inotifywait when the target is Linux,
    //     PowerShell FileSystemWatcher when it's Windows, provided the SSH
    //     server allows exec; otherwise it gets out of the way and the
    //     existing scheduled poll (computeNextFireDelayMs) keeps running
    //     unchanged.
    // Constructed here (not as a plain field initializer) because both need
    // this.storage, which isn't assigned until the constructor body runs.
    private final LocalWatchManager localWatchManager = new LocalWatchManager(this::onWatchWakeup);
    private final RemotePushWatcher remotePushWatcher;
    // Tracks currently running task futures so they can be cancelled on request
     private final ConcurrentMap<String, Future<?>> runningTaskFutures = new ConcurrentHashMap<>();
     private final ConcurrentMap<String, TaskMetrics> taskMetrics = new ConcurrentHashMap<>();
     // Last time ANY log line was emitted for a task's current run (wall-clock
     // millis). Used by isStaleRunning() to catch a run whose connection has
     // gone silent — e.g. network dropped mid-transfer — much faster than
     // waiting out the full "since start" threshold; see emit() and
     // getStaleInactivityThresholdMinutes.
     private final ConcurrentMap<String, Long> lastActivityMillis = new ConcurrentHashMap<>();
     // Base stale threshold, configurable live from the Settings panel
     // (util.AppSettings#KEY_STALE_RUNNING_THRESHOLD_MINUTES, default 30
     // minutes — see getStaleRunningThreshold below). For interval-based
     // tasks it's additionally scaled up relative to their own interval, to
     // accommodate longer-running operations like large file transfers.

    /** Poll loop interval in seconds (default 60). If a task requests INTERVAL_SECONDS
     * smaller than this value we schedule it with its own dedicated timer so it can
     * run at higher frequency without depending on the global poll loop. */
    private final int pollIntervalSeconds;

    /** Callback: (taskId, logLine) -> void — for UI log panel updates */
    private BiConsumer<String, String> logCallback;

    public TaskSchedulerService(XmlStorageService storage, TransferService transferService) {
        this(storage, transferService, 60);
    }

    /** Create scheduler with a custom poll interval (in seconds). */
    public TaskSchedulerService(XmlStorageService storage,TransferService transferService, int pollIntervalSeconds) {
        this.storage = storage;
        this.transferService = transferService;
        this.logService = new TaskLogService(storage.getDataDir().getAbsolutePath());
        this.runHistoryService = new RunHistoryService(storage.getDataDir().getAbsolutePath());
        // Keep the run-history database from growing unbounded — every run
        // (success, failure, or skip) adds a row. 90 days is a generous
        // default; adjust here if a different retention window is needed.
        this.runHistoryService.pruneOlderThan(90);
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.remotePushWatcher = new RemotePushWatcher(storage, this::onWatchWakeup);
    }

    /**
     * Called by {@link LocalWatchManager} or {@link RemotePushWatcher} the
     * moment they observe a change for a watcher-enabled task. Publishes a
     * "due now" event through the normal queue — {@link #onTaskDue} then
     * re-validates the task's live status and does all the real filtering
     * (only-if-actually-changed, batching, etc.), so this is safe to call
     * even on a false alarm (e.g. a temp file that got deleted again) or
     * while the task happens to already be running (onTaskDue no-ops then).
     */
    private void onWatchWakeup(String taskId, java.util.Set<String> changedFileNames) {
        eventQueue.publish(new TaskDueEvent(taskId, LocalDateTime.now(), 0, true, changedFileNames));
    }

    /** How a watcher-enabled task is currently being triggered — for UI display only. */
    public enum WatchMode {
        /** OUTBOUND task with a live OS-level directory watch registered — instant. */
        NATIVE_WATCH,
        /** INBOUND task with a live remote push listener (inotifywait on Linux,
         *  PowerShell FileSystemWatcher on Windows) — instant. */
        REMOTE_PUSH,
        /** No push mechanism currently active; relying solely on the scheduled poll interval. */
        POLLING_ONLY,
        /** Push was attempted for this task but the remote host doesn't support it
         *  (no inotify-tools, or exec refused) — same as POLLING_ONLY but distinguishes
         *  "tried and can't" from "not applicable yet" for the UI. */
        POLLING_ONLY_UNSUPPORTED,
        /** Not a watcher-enabled FILE_TRANSFER task. */
        NOT_APPLICABLE
    }

    /**
     * {@code mode} plus a short human-readable {@code detail} explaining *why*
     * — critical for telling "never attempted" apart from "tried and this is
     * exactly what went wrong" apart from "confirmed working". See
     * {@link #getWatchStatus}.
     */
    public record WatchStatus(WatchMode mode, String detail) {}

    /** Convenience wrapper around {@link #getWatchStatus} for callers that
     *  only need the mode, not the explanation. Prefer getWatchStatus in new code. */
    public WatchMode getWatchMode(ScheduledTask task) {
        return getWatchStatus(task).mode();
    }

    /**
     * Best-effort, point-in-time read of how {@code task} is currently being
     * triggered, with a plain-English reason attached at every branch — so the
     * UI never has to show a bare "Polling only" without being able to say
     * whether that's because push was never eligible, was tried and failed
     * (and why), or just hasn't been evaluated yet.
     *
     * <p>Reflects only this process's watch managers — if the headless Daemon
     * (not this GUI process) is the active scheduler, this will under-report
     * (it can't see another process's live SSH/watch state); see
     * {@code SchedulerStatusExporter}/{@code SchedulerStatusSnapshot} for the
     * cross-process version of this same information.
     */
    public WatchStatus getWatchStatus(ScheduledTask task) {
        if (task == null || task.getTaskType() != ScheduledTask.TaskType.FILE_TRANSFER) {
            return new WatchStatus(WatchMode.NOT_APPLICABLE, "not a file-transfer task");
        }
        if (!task.isWatcherEnabled()) {
            return new WatchStatus(WatchMode.NOT_APPLICABLE, "watcher is not enabled for this task");
        }

        if (task.getTransferDirection() == ScheduledTask.TransferDirection.OUTBOUND) {
            if (localWatchManager.isWatching(task.getId())) {
                return new WatchStatus(WatchMode.NATIVE_WATCH, "native OS directory watch is active");
            }
            String reason = localWatchManager.getReason(task.getId());
            return new WatchStatus(WatchMode.POLLING_ONLY,
                    reason != null ? reason : "not yet evaluated — waiting for the next reconcile pass");
        }

        if (task.getTransferDirection() == ScheduledTask.TransferDirection.INBOUND) {
            if (task.getTransferMode() != ScheduledTask.TransferMode.LATEST_ONLY) {
                // This is the single most common reason remote push never even
                // gets attempted — surface it explicitly rather than letting it
                // look identical to "tried and it's not supported".
                return new WatchStatus(WatchMode.POLLING_ONLY,
                        "remote push requires transfer mode \"Latest Only\" (current: "
                                + task.getTransferMode() + ") — not attempted");
            }
            if (remotePushWatcher.isPushActive(task.getId())) {
                String reason = remotePushWatcher.getReason(task.getId());
                return new WatchStatus(WatchMode.REMOTE_PUSH, reason != null ? reason : "connected");
            }
            if (remotePushWatcher.isRecentlyUnsupported(task.getId())) {
                String reason = remotePushWatcher.getReason(task.getId());
                return new WatchStatus(WatchMode.POLLING_ONLY_UNSUPPORTED,
                        reason != null ? reason : "remote push unavailable");
            }
            return new WatchStatus(WatchMode.POLLING_ONLY, "not yet attempted — waiting for the next reconcile pass");
        }

        return new WatchStatus(WatchMode.NOT_APPLICABLE, "");
    }

    /**
     * Forces an immediate reconnect attempt for a single watcher task — the
     * "Reconnect" action in the UI's watcher-info popup. Re-registers the
     * native directory watch (OUTBOUND) or restarts the remote SSH push
     * listener (INBOUND) right now, rather than waiting for the next
     * reconcile sweep (up to ~30s) or, for a remote push that was recently
     * marked unsupported, the 1-hour backoff window. Safe to call for any
     * task id — no-ops harmlessly if the task doesn't exist or isn't
     * watcher-eligible (with the reason updated accordingly, visible via
     * the next {@link #getWatchStatus} call).
     *
     * <p>Returns immediately — the actual reconnect (filesystem watch
     * registration, or SSH connect) may still be in progress when this
     * returns; callers should re-check {@link #getWatchStatus} a couple of
     * seconds later for the outcome.
     */
    public void reconnectWatch(String taskId) {
        if (taskId == null) return;
        ScheduledTask task = storage.loadTasks().stream()
                .filter(t -> taskId.equals(t.getId()))
                .findFirst().orElse(null);
        if (task == null) return;
        if (task.getTransferDirection() == ScheduledTask.TransferDirection.OUTBOUND) {
            localWatchManager.forceReconnect(task);
        } else if (task.getTransferDirection() == ScheduledTask.TransferDirection.INBOUND) {
            remotePushWatcher.forceReconnect(task);
        }
    }

    public void setLogCallback(BiConsumer<String, String> cb) {
        this.logCallback = cb;
    }

    public TaskLogService getLogService() {
        return logService;
    }

    public RunHistoryService getRunHistoryService() {
        return runHistoryService;
    }

    public XmlStorageService getStorage() {
        return storage;
    }

    public TaskMetrics getTaskMetrics(String taskId) {
        return taskId == null ? null : taskMetrics.get(taskId);
    }

    // ── Monitoring accessors ────────────────────────────────────────────
    // Read-only views onto the event queue / worker pool for the Event
    // Monitor GUI (see ui.EventMonitorPanel). None of these affect
    // scheduling or execution; they're plain snapshots for display.

    /** Every task's pending TaskDueEvent, soonest-due first. Non-destructive. */
    public List<service.queue.TaskDueEvent> getPendingEvents() {
        return eventQueue.snapshotPending();
    }

    /** Total configured worker threads (the execution-concurrency ceiling). */
    public int getWorkerPoolSize() {
        return workerPool.getWorkerCount();
    }

    /** Workers currently executing a task right now. */
    public int getActiveWorkerCount() {
        return workerPool.getActiveWorkerCount();
    }

    /** Newest-first feed of the last {@code limit} events a worker has handled. */
    public List<service.queue.TaskWorkerPool.ActivityEntry> getRecentActivity(int limit) {
        return workerPool.getRecentActivity(limit);
    }

    // ── Cross-process status export ─────────────────────────────────────
    // Lets a scheduler running in one JVM (typically the headless Daemon)
    // publish its live state to a shared file that another process
    // (typically the GUI's Event Monitor window) can read back — see
    // service.queue.SchedulerStatusExporter / SchedulerStatusSnapshot.
    private service.queue.SchedulerStatusExporter statusExporter;
    private ScheduledFuture<?> statusExportFuture;
    // Populated at the top of every reconcileSchedules() sweep; read by
    // exportStatus() (which runs on its own faster 2s tick) so the "watch
    // mode" line in each status export doesn't need its own storage.loadTasks()
    // call every 2s — reconcile's 30s-ish cadence is fresh enough for a
    // status display, and volatile gives exportStatus a safe, tear-free read
    // of whatever reconcile last saw.
    private volatile List<ScheduledTask> lastLoadedTasks = java.util.Collections.emptyList();
    // Last WatchMode we logged for each task, purely so exportStatus() can log
    // a clear line the moment a task's trigger mode changes (e.g. "push
    // stopped working, now polling") instead of the Daemon's log silently
    // saying nothing — this is the same signal ui.WatchStatusMonitor polls
    // for cross-process, but logging it here too means it's visible even
    // when no GUI is attached to a headless Daemon at all.
    private final Map<String, WatchMode> lastLoggedWatchMode = new ConcurrentHashMap<>();

    /**
     * Enables periodic status export to {@code <dataDir>/scheduler-status-<processLabel>.dat},
     * beginning immediately (a 1s initial delay, then every 2s) regardless
     * of whether {@link #start()} has been called yet. Call this once per
     * scheduler instance. {@code processLabel} should be a short,
     * filesystem-safe tag identifying which process this is (e.g. "gui" or
     * "daemon") so both can export to the same directory without colliding.
     */
    public void enableStatusExport(String dataDir, String processLabel) {
        java.nio.file.Path file = java.nio.file.Path.of(dataDir, "scheduler-status-" + processLabel + ".dat");
        this.statusExporter = new service.queue.SchedulerStatusExporter(file, processLabel);
        if (statusExportFuture == null || statusExportFuture.isCancelled()) {
            statusExportFuture = scheduler.scheduleAtFixedRate(this::exportStatus, 1, 2, TimeUnit.SECONDS);
        }
    }

    private void exportStatus() {
        if (statusExporter == null) return;
        try {
            List<SchedulerStatusSnapshot.WatchEntry> watchEntries = new ArrayList<>();
            for (ScheduledTask t : lastLoadedTasks) {
                WatchStatus status = getWatchStatus(t);
                if (status.mode() == WatchMode.NOT_APPLICABLE) continue;
                watchEntries.add(new SchedulerStatusSnapshot.WatchEntry(t.getId(), status.mode().name(), status.detail()));
                logWatchTransitionIfNotable(t, status);
            }
            statusExporter.export(getWorkerPoolSize(), getActiveWorkerCount(), getPendingEvents(),
                    getRecentActivity(30), watchEntries);
        } catch (Exception e) {
            log.fine("Status export tick failed: " + e.getMessage());
        }
    }

    /** Logs a WARNING the moment a watcher task's trigger mode degrades from
     *  push (NATIVE_WATCH/REMOTE_PUSH) to polling, or is newly confirmed
     *  unsupported — so it's visible in this process's own log even with no
     *  GUI attached. Silent otherwise (including on the very first observation
     *  of a task, and on POLLING_ONLY -> POLLING_ONLY re-evaluations, both of
     *  which are non-events here). */
    private void logWatchTransitionIfNotable(ScheduledTask t, WatchStatus status) {
        WatchMode prev = lastLoggedWatchMode.put(t.getId(), status.mode());
        if (prev == null || prev == status.mode()) return;
        boolean wasPushing = prev == WatchMode.NATIVE_WATCH || prev == WatchMode.REMOTE_PUSH;
        boolean nowPolling = status.mode() == WatchMode.POLLING_ONLY || status.mode() == WatchMode.POLLING_ONLY_UNSUPPORTED;
        boolean newlyUnsupported = status.mode() == WatchMode.POLLING_ONLY_UNSUPPORTED && prev != WatchMode.POLLING_ONLY_UNSUPPORTED;
        if ((wasPushing && nowPolling) || newlyUnsupported) {
            log.warning("Watcher task '" + t.getName() + "' (" + t.getId() + "): trigger mode changed "
                    + prev + " -> " + status.mode() + " — " + status.detail());
        }
    }

    private TaskMetrics ensureMetrics(String taskId) {
        return taskMetrics.computeIfAbsent(taskId, TaskMetrics::new);
    }

    public static class TaskMetrics {
        private final String taskId;
        private volatile boolean running;
        private volatile long lastDurationMs;
        private volatile long lastHeapUsedBytes;
        private volatile long lastHeapCommittedBytes;
        private volatile long lastHeapMaxBytes;
        private volatile long lastCpuTimeMs;
        private volatile int lastThreadCount;
        private volatile String lastResult = "Unknown";
        private volatile String lastStatus = "UNKNOWN";
        private volatile long currentHeapUsedBytes;
        private volatile long currentCpuTimeMs;
        private volatile int currentThreadCount;

        public TaskMetrics(String taskId) {
            this.taskId = taskId;
        }

        public String getTaskId() { return taskId; }
        public boolean isRunning() { return running; }
        public void setRunning(boolean running) { this.running = running; }
        public long getLastDurationMs() { return lastDurationMs; }
        public void setLastDurationMs(long lastDurationMs) { this.lastDurationMs = lastDurationMs; }
        public long getLastHeapUsedBytes() { return lastHeapUsedBytes; }
        public void setLastHeapUsedBytes(long lastHeapUsedBytes) { this.lastHeapUsedBytes = lastHeapUsedBytes; }
        public long getLastHeapCommittedBytes() { return lastHeapCommittedBytes; }
        public void setLastHeapCommittedBytes(long lastHeapCommittedBytes) { this.lastHeapCommittedBytes = lastHeapCommittedBytes; }
        public long getLastHeapMaxBytes() { return lastHeapMaxBytes; }
        public void setLastHeapMaxBytes(long lastHeapMaxBytes) { this.lastHeapMaxBytes = lastHeapMaxBytes; }
        public long getLastCpuTimeMs() { return lastCpuTimeMs; }
        public void setLastCpuTimeMs(long lastCpuTimeMs) { this.lastCpuTimeMs = lastCpuTimeMs; }
        public int getLastThreadCount() { return lastThreadCount; }
        public void setLastThreadCount(int lastThreadCount) { this.lastThreadCount = lastThreadCount; }
        public String getLastResult() { return lastResult; }
        public void setLastResult(String lastResult) { this.lastResult = lastResult; }
        public String getLastStatus() { return lastStatus; }
        public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
        public long getCurrentHeapUsedBytes() { return currentHeapUsedBytes; }
        public void setCurrentHeapUsedBytes(long currentHeapUsedBytes) { this.currentHeapUsedBytes = currentHeapUsedBytes; }
        public long getCurrentCpuTimeMs() { return currentCpuTimeMs; }
        public void setCurrentCpuTimeMs(long currentCpuTimeMs) { this.currentCpuTimeMs = currentCpuTimeMs; }
        public int getCurrentThreadCount() { return currentThreadCount; }
        public void setCurrentThreadCount(int currentThreadCount) { this.currentThreadCount = currentThreadCount; }
    }

    private void refreshMetrics(String taskId, boolean running) {
        if (taskId == null) return;
        TaskMetrics metrics = ensureMetrics(taskId);
        metrics.setRunning(running);
        Runtime rt = Runtime.getRuntime();
        metrics.setCurrentHeapUsedBytes(rt.totalMemory() - rt.freeMemory());
        metrics.setCurrentCpuTimeMs(THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? THREAD_BEAN.getCurrentThreadCpuTime() / 1_000_000 : 0);
        metrics.setCurrentThreadCount(THREAD_BEAN.getThreadCount());
    }

    private void recordMetricsOnComplete(String taskId, boolean success, long durationMs, long cpuTimeMs) {
        if (taskId == null) return;
        TaskMetrics metrics = ensureMetrics(taskId);
        Runtime rt = Runtime.getRuntime();
        metrics.setRunning(false);
        metrics.setLastDurationMs(durationMs);
        metrics.setLastCpuTimeMs(cpuTimeMs);
        metrics.setLastHeapUsedBytes(rt.totalMemory() - rt.freeMemory());
        metrics.setLastHeapCommittedBytes(rt.totalMemory());
        metrics.setLastHeapMaxBytes(rt.maxMemory());
        metrics.setLastThreadCount(THREAD_BEAN.getThreadCount());
        metrics.setLastResult(success ? "SUCCESS" : "FAILED");
        metrics.setLastStatus(success ? "SUCCESS" : "FAILED");
        metrics.setCurrentHeapUsedBytes(rt.totalMemory() - rt.freeMemory());
        metrics.setCurrentThreadCount(THREAD_BEAN.getThreadCount());
    }

    // Whether this instance's worker pool/reconcile loop has actually been
    // started. Lets a caller (see ui.MainWindow) hold a scheduler object for
    // read-only access (task list, run history) without it firing anything —
    // e.g. while a headless Daemon process is the active scheduler for this
    // data directory — and later promote it to active with a plain start()
    // call. TaskWorkerPool.start() is only safe to call once per instance
    // (see its own javadoc), so this flag also guards against a double
    // start() accidentally tearing that invariant.
    private volatile boolean started = false;

    /** True once {@link #start()} has actually started the worker pool. */
    public boolean isStarted() {
        return started;
    }

    /** Start the event-driven scheduler: worker pool + safety-net reconcile sweep.
     *  Safe to call more than once — every call after the first is a no-op. */
    public void start() {
        if (started) return;
        started = true;
        workerPool.start();
        localWatchManager.start();
        remotePushWatcher.start();
        // Reconcile is a self-healing safety net only (new/edited tasks, stale
        // RUNNING recovery, clock skew) — it is NOT the firing mechanism.
        // The 30s cadence is deliberately decoupled from pollIntervalSeconds:
        // it no longer determines how promptly tasks fire, since publish()
        // schedules each task's own precise TaskDueEvent immediately.
        scheduler.scheduleAtFixedRate(this::reconcileSchedules, 5, 30, TimeUnit.SECONDS);
        reconcileSchedules();
        log.info("Task scheduler started (event-driven; " + eventQueue.size() + " task(s) pending).");
    }

    public void stop() {
        started = false;
        workerPool.stop();
        localWatchManager.stop();
        remotePushWatcher.stop();
        if (statusExportFuture != null) statusExportFuture.cancel(false);
        scheduler.shutdownNow();
        executor.shutdownNow();
        logService.closeAll();
        runHistoryService.close();
        log.info("TaskSchedulerService stopped.");
    }

    /** Immediately execute a task regardless of schedule. */
    public void runNow(String taskId) {
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst().ifPresent(t -> {
            if (t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.RETRYING) {
                emit(t, "[INFO] Task is already active and will not be requeued.");
                return;
            }
            // Drop any pending scheduled occurrence for this task — it's about
            // to run now instead, and executeTask's completion path will
            // publish the correct next occurrence once this run finishes.
            eventQueue.cancel(t.getId());
            t.setStatus(TaskStatus.RUNNING);
            t.setLastStartedAt(LocalDateTime.now());
            storage.saveTask(t);
            refreshMetrics(t.getId(), true);
            Future<?> f = executor.submit(() -> executeTask(t));
            runningTaskFutures.put(t.getId(), f);
        });
    }

    private Duration getStaleRunningThreshold(ScheduledTask task) {
        // Read live on every check (util.AppSettings does a cheap mtime-checked
        // reload) so an admin's Settings-panel edit takes effect on the very
        // next scheduler poll, no restart required.
        Duration base = Duration.ofMinutes(util.AppSettings.getStaleRunningThresholdMinutes());
        if (task == null) return base;
        switch (task.getScheduleType()) {
            case INTERVAL_SECONDS:
                if (task.getIntervalSeconds() > 0) {
                    long threshold = Math.max(base.getSeconds(), task.getIntervalSeconds() * 3L);
                    return Duration.ofSeconds(threshold);
                }
                break;
            case INTERVAL_MINUTES:
                if (task.getIntervalMinutes() > 0) {
                    long thresholdMins = Math.max(base.toMinutes(), task.getIntervalMinutes() * 2L);
                    return Duration.ofMinutes(thresholdMins);
                }
                break;
            default:
                break;
        }
        return base;
    }

    private boolean isStaleRunning(ScheduledTask task, LocalDateTime now) {
        if (task == null || task.getStatus() != TaskStatus.RUNNING) return false;
        if (task.getLastStartedAt() == null) return true;
        if (task.getLastStartedAt().plus(getStaleRunningThreshold(task)).isBefore(now)) return true;

        // Second, independent check: has this run gone completely silent
        // (no log line at all) for longer than the inactivity threshold?
        // This is what actually catches "network died mid-transfer" quickly
        // — the WinSCP process is still alive from the OS's point of view
        // (blocked on a socket read that will never return), so the "since
        // start" check above won't fire for the full 30 minutes even though
        // no bytes have moved in ages. A task that's still genuinely
        // progressing keeps resetting this clock via emit(), so a long but
        // active transfer is never penalized by it.
        Long lastActivity = lastActivityMillis.get(task.getId());
        if (lastActivity == null) return false; // no activity recorded yet — fall back to the start-time check only
        Duration inactivity = Duration.ofMillis(System.currentTimeMillis() - lastActivity);
        return inactivity.toMinutes() >= util.AppSettings.getStaleInactivityThresholdMinutes();
    }

    /**
     * Safety-net sweep: publishes (or refreshes) a {@link TaskDueEvent} for
     * every eligible task, and recovers stale RUNNING tasks. Idempotent and
     * cheap to call repeatedly — {@link TaskEventQueue#publish} transparently
     * replaces any stale pending event for the same task, so this can run on
     * its own periodic cadence AND be called immediately from {@link #refresh()}
     * without ever double-scheduling a task.
     */
    private void reconcileSchedules() {
        try {
            List<ScheduledTask> tasks = storage.loadTasks();
            lastLoadedTasks = tasks;

            // Cheap, idempotent — registers/deregisters native watches to
            // match current task config. Does not itself decide when a task
            // fires; see onWatchWakeup().
            localWatchManager.sync(tasks);
            remotePushWatcher.sync(tasks);

            LocalDateTime now = LocalDateTime.now();

            for (ScheduledTask task : tasks) {
                if (task.getStatus() == TaskStatus.DISABLED || task.getStatus() == TaskStatus.RETRYING) {
                    eventQueue.cancel(task.getId());
                    continue;
                }
                if (task.getStatus() == TaskStatus.RUNNING) {
                    if (isStaleRunning(task, now)) {
                        emit(task, "[WARN] Detected stale RUNNING task; cancelling and resetting.");
                        try {
                            cancelTask(task.getId());
                        } catch (Exception ignored) {}
                        task.setLastStartedAt(null);
                        task.setLastRunResult("FAILED (stale)");
                        task.setStatus(TaskStatus.PENDING);
                        storage.saveTask(task);
                        lastActivityMillis.remove(task.getId());
                        // falls through to get (re)published below
                    } else {
                        continue; // already running; its own completion will publish the next occurrence
                    }
                }

                publishNextOccurrence(task, now);
            }
        } catch (Exception e) {
            log.warning("Scheduler reconcile pass failed: " + e.getMessage());
            emit(null, "[ERROR] Scheduler reconcile pass failed: " + e.getMessage());
        }
    }

    /**
     * Computes this task's next due instant and publishes a {@link TaskDueEvent}
     * for it. If the task has nothing further to schedule (e.g. a ONCE task
     * that already ran, or an invalid config), any existing pending event for
     * it is cancelled instead.
     */
    private void publishNextOccurrence(ScheduledTask task, LocalDateTime now) {
        Long delayMs = computeNextFireDelayMs(task, now);
        if (delayMs == null) {
            eventQueue.cancel(task.getId());
            return;
        }
        LocalDateTime dueAt = now.plus(Duration.ofMillis(delayMs));
        eventQueue.publish(new TaskDueEvent(task.getId(), dueAt, 0));
    }

    /**
     * Milliseconds from {@code now} until this task's next occurrence becomes
     * due, or {@code null} if it has no future occurrence to schedule. This is
     * the per-schedule-type "when is this next due" computation, replacing
     * the old boolean poll check — it computes the exact target instant once,
     * up front, so the event queue can deliver it precisely instead of
     * relying on a shared tick to notice it after the fact. Also used by
     * {@link #onTaskDue} (via {@code DUE_TOLERANCE_MS}) to re-validate a
     * task at delivery time.
     */
    private Long computeNextFireDelayMs(ScheduledTask task, LocalDateTime now) {
        if (task.isWatcherEnabled() && task.getInboundWatcherPollIntervalMinutes() > 0) {
            LocalDateTime last = task.getLastRunAt();
            LocalDateTime next = last == null ? now : last.plusMinutes(task.getInboundWatcherPollIntervalMinutes());
            return millisUntil(next, now);
        }

        switch (task.getScheduleType()) {
            case RUN_NOW:
                return task.getLastRunAt() == null ? 0L : null;

            case ONCE:
                if (task.getLastRunAt() != null || task.getScheduledAt() == null) return null;
                return millisUntil(task.getScheduledAt(), now);

            case DAILY: {
                if (task.getCronExpression() == null) return null;
                LocalTime target;
                try {
                    target = LocalTime.parse(task.getCronExpression(), DateTimeFormatter.ofPattern("HH:mm"));
                } catch (Exception e) {
                    return null;
                }
                LocalDateTime next = now.toLocalDate().atTime(target);
                boolean alreadyRanToday = task.getLastRunAt() != null
                        && !task.getLastRunAt().toLocalDate().isBefore(now.toLocalDate());
                if (!next.isAfter(now) || alreadyRanToday) {
                    next = next.plusDays(1);
                }
                return millisUntil(next, now);
            }

            case WEEKLY: {
                if (task.getCronExpression() == null) return null;
                String[] parts = task.getCronExpression().split(" ");
                if (parts.length < 2) return null;
                DayOfWeek targetDay = parseDayOfWeek(parts[0]);
                if (targetDay == null) return null;
                LocalTime target;
                try {
                    target = LocalTime.parse(parts[1], DateTimeFormatter.ofPattern("HH:mm"));
                } catch (Exception e) {
                    return null;
                }
                LocalDateTime next = now.toLocalDate().atTime(target);
                // Walk forward to the next matching day/time that's actually
                // still ahead of now; bounded loop (max 7 steps) so a bad/
                // unmatched day name can never spin forever.
                for (int i = 0; i < 8 && (next.getDayOfWeek() != targetDay || !next.isAfter(now)); i++) {
                    next = next.plusDays(1).with(target);
                }
                return millisUntil(next, now);
            }

            case INTERVAL_MINUTES: {
                if (task.getIntervalMinutes() <= 0) return null;
                LocalDateTime next = task.getLastRunAt() == null
                        ? now : task.getLastRunAt().plusMinutes(task.getIntervalMinutes());
                return millisUntil(next, now);
            }

            case INTERVAL_SECONDS: {
                if (task.getIntervalSeconds() <= 0) return null;
                LocalDateTime next = task.getLastRunAt() == null
                        ? now : task.getLastRunAt().plusSeconds(task.getIntervalSeconds());
                return millisUntil(next, now);
            }

            default:
                return null;
        }
    }

    private static long millisUntil(LocalDateTime target, LocalDateTime now) {
        return Math.max(0L, Duration.between(now, target).toMillis());
    }

    /** Parses "MON", "MONDAY", etc. (case-insensitive, prefix-tolerant) to a DayOfWeek, or null if unrecognized. */
    private static DayOfWeek parseDayOfWeek(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String norm = raw.trim().toUpperCase();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(norm) || d.name().startsWith(norm) || norm.startsWith(d.name().substring(0, 3))) {
                return d;
            }
        }
        return null;
    }

    /**
     * Invoked by a worker thread (see {@link TaskWorkerPool}) exactly when a
     * task's event comes due. Re-validates against the freshest stored state
     * — the task may have been edited, disabled, or cancelled between
     * publish and delivery — then executes synchronously on this worker
     * thread (pool size is the concurrency limit) and, for recurring
     * schedule types, the next occurrence is published from executeTask's
     * completion path once lastRunAt is actually persisted.
     */
    private void onTaskDue(TaskDueEvent event) {
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(event.getTaskId())).findFirst().ifPresentOrElse(task -> {
            LocalDateTime now = LocalDateTime.now();
            if (task.getStatus() == TaskStatus.DISABLED || task.getStatus() == TaskStatus.RETRYING
                    || task.getStatus() == TaskStatus.RUNNING) {
                return; // reconcile sweep will pick it back up if it becomes eligible again
            }
            if (event.isImmediate()) {
                // Push wake-up (LocalWatchManager / RemotePushWatcher saw a
                // real change) — a file genuinely changed, so this must run
                // now, not be deferred back to the nominal poll interval.
                // The only reason to bail here is if the task is no longer
                // schedulable/watchable at all (e.g. watcher was just
                // disabled, or the task was edited into an invalid state) —
                // computeNextFireDelayMs returning null is exactly that
                // signal, regardless of what delay it would otherwise report.
                if (computeNextFireDelayMs(task, now) == null) {
                    eventQueue.cancel(task.getId());
                    return;
                }
            } else {
                // Re-validate against freshly computed state rather than the old
                // strict "current minute" isDue() check: an event is delivered
                // essentially exactly at its target instant, but a saturated
                // worker pool could hand it to a thread a little late. A tight
                // exact-minute match (fine for a 60s poll tick) would then wrongly
                // treat a DAILY/WEEKLY task as "missed" and skip a whole day/week.
                // DUE_TOLERANCE_MS absorbs that scheduling jitter; anything beyond
                // it really does mean the task was edited/cancelled meanwhile, so
                // we re-arm against the fresh config instead of firing stale.
                Long freshDelayMs = computeNextFireDelayMs(task, now);
                if (freshDelayMs == null) {
                    eventQueue.cancel(task.getId());
                    return;
                }
                if (freshDelayMs > DUE_TOLERANCE_MS) {
                    publishNextOccurrence(task, now);
                    return;
                }
            }
            task.setStatus(TaskStatus.RUNNING);
            task.setLastStartedAt(now);
            storage.saveTask(task);
            refreshMetrics(task.getId(), true);
            executeTask(task, event.getChangedFileNames());
        }, () -> { /* task deleted since the event was published — nothing to do */ });
    }

    /** Public: publish/refresh due-events for all tasks immediately — called
     * by the UI on the EDT after every save/edit/delete so changes take
     * effect right away instead of waiting for the next reconcile sweep. */
    public void refresh() {
        reconcileSchedules();
    }

    /** Attempt to cancel a task: removes its pending queue event and cancels running future if present. */
    public boolean cancelTask(String taskId) {
        boolean cancelledAny = eventQueue.cancel(taskId);
        Future<?> f = runningTaskFutures.remove(taskId);
        if (f != null) {
            cancelledAny = f.cancel(true) || cancelledAny;
        }
        // Also attempt to terminate any external process associated with the task
        try {
            if (transferService != null) transferService.cancelRunningTask(taskId);
        } catch (Exception ignored) {}
        return cancelledAny;
    }

    /**
     * How many WinSCP/SFTP sessions are currently open for this task right
     * now — see {@link TransferService#getActiveSessionCount(String)}.
     * Normally 0 or 1; can briefly exceed 1 when
     * {@code AppSettings.getTransferBatchConcurrency()} &gt; 1 and several
     * batches are running in parallel. Safe to poll from the UI on a timer.
     */
    public int getActiveSessionCount(String taskId) {
        return transferService != null ? transferService.getActiveSessionCount(taskId) : 0;
    }

    /** Total WinSCP/SFTP sessions open across every task right now. */
    public int getTotalActiveSessionCount() {
        return transferService != null ? transferService.getTotalActiveSessionCount() : 0;
    }

    /**
     * IDs of tasks that are actively executing right now (i.e. have a live
     * future in {@link #runningTaskFutures}). Used by the Settings panel to
     * offer restarting in-flight tasks when the log level changes, so their
     * logs come out consistently at the new level for the whole run instead
     * of switching level partway through.
     */
    public List<String> getRunningTaskIds() {
        return new ArrayList<>(runningTaskFutures.keySet());
    }

    /**
     * Cancels a task's in-flight run (if any) and immediately re-executes it
     * from the beginning, so its log file is generated entirely under
     * whatever log level is currently configured — rather than starting
     * with the old level and switching mid-run. Used by the Settings panel's
     * "restart running tasks?" prompt after a log-level change; also usable
     * standalone as a general "restart this task" action.
     */
    public void restartTask(String taskId) {
        cancelTask(taskId);
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst().ifPresent(t -> {
            t.setStatus(TaskStatus.PENDING);
            storage.saveTask(t);
        });
        refreshMetrics(taskId, false);
        runNow(taskId);
    }

    private void executeTask(ScheduledTask task) {
        executeTask(task, java.util.Collections.emptySet());
    }

    /**
     * @param changedFileNames filenames the watch layer actually observed changing
     *     for this run (empty if this is an ordinary scheduled/manual run, or the
     *     watcher only knows "something changed" — e.g. an OVERFLOW event). Passed
     *     straight through to {@link TransferService#executeTransfer(ScheduledTask,
     *     java.util.function.Consumer, java.util.Set)} — see that method for how it's used.
     */
    private void executeTask(ScheduledTask task, java.util.Set<String> changedFileNames) {
        final long startNanos = System.nanoTime();
        final long startCpuNanos = THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? THREAD_BEAN.getCurrentThreadCpuTime() : 0L;
        final LocalDateTime runStartedAt = LocalDateTime.now();

        // Captures every line emitted for THIS run only (as opposed to
        // logService, which is the running text log for the task overall)
        // so it can be stored as the "details" for this one run's history
        // row once the run finishes.
        final StringBuilder runLog = new StringBuilder();
        final java.util.function.Consumer<String> emitCap = line -> {
            runLog.append(line).append('\n');
            emit(task, line);
        };

        // ─── Cross-process execution lock ───────────────────────────────────
        // The GUI's in-app scheduler and the standalone Daemon each poll
        // tasks.xml independently and can both decide the same task is due
        // at nearly the same moment (especially INTERVAL_SECONDS tasks).
        // Without this, BOTH processes actually execute the task — for a
        // mail task that means fetching/marking-as-read/moving the same
        // messages twice, and racing each other for the OAuth2 refresh
        // token (Microsoft rotates it on every exchange, so whichever
        // process loses the race reads a token that's already been
        // invalidated by the winner) — producing exactly the "fails once
        // with 'not authorized', succeeds on the very next run" pattern.
        // A non-blocking file lock, held for the whole execution and
        // released when it finishes, ensures only one process ever runs a
        // given task at a time; the loser cleanly skips instead of racing.
        Path lockPath = storage.getDataDir().toPath().resolve("task-locks").resolve(task.getId() + ".lock");
        FileChannel lockChannel = null;
        FileLock fileLock = null;
        boolean sameJvmDoubleFire = false;
        try {
            Files.createDirectories(lockPath.getParent());
            lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Thrown (with no message) when THIS JVM already holds a lock on this
            // file — i.e. this task somehow got scheduled twice within this same
            // process (this used to also be reachable via a same-JVM double-timer race
            // this used to be caused by). Distinguish it from real cross-process
            // contention, where tryLock() just returns null without throwing.
            sameJvmDoubleFire = true;
            emit(task, "[WARN] This task appears to be running twice at once within this "
                    + "same application instance (not a separate GUI/Daemon process) — skipping "
                    + "this duplicate run. If this keeps happening, please report it.");
        } catch (Exception e) {
            emit(task, "[WARN] Could not set up cross-process task lock (" + e.getMessage()
                    + ") — proceeding without it.");
        }

        if (lockChannel != null && fileLock == null) {
            // Another process (GUI or Daemon) already holds the lock for this task —
            // or, if sameJvmDoubleFire is true, it's actually this same process (see above).
            String reason = sameJvmDoubleFire
                    ? "Task '" + task.getName() + "' is already running elsewhere in this same application instance."
                    : "Task '" + task.getName() + "' is already running in another process (GUI or Daemon) at this tick.";
            emit(task, "[INFO] Skipped: " + reason);
            runHistoryService.recordRun(task.getId(), task.getName(), task.getTaskType().name(),
                    TaskRunRecord.Status.SKIPPED, reason, reason, runStartedAt, LocalDateTime.now());
            try { lockChannel.close(); } catch (IOException ignored) {}
            try { runningTaskFutures.remove(task.getId()); } catch (Exception ignored) {}
            lastActivityMillis.remove(task.getId());
            return;
        }

        try {
        emitCap.accept("=== Starting task: " + task.getName() + " ===");
        emitCap.accept("[DEBUG] Task ID: " + task.getId());
        emitCap.accept("[DEBUG] Task Type: " + task.getTaskType());
        emitCap.accept("[DEBUG] Schedule Type: " + task.getScheduleType());
        if (task.getTaskType() == ScheduledTask.TaskType.FILE_TRANSFER) {
            // Transfer Mode only applies to file-transfer tasks — logging it
            // unconditionally printed a leftover/default value for every
            // mail task too, which was just noise (and misleading, since
            // that field isn't actually used for anything on mail tasks).
            emitCap.accept("[DEBUG] Transfer Mode: " + (task.getTransferMode() != null ? task.getTransferMode().name() : "NULL"));
        }
        
        boolean success;
        boolean skipped = false;
        String skipReason = null;
        String failureReason = null;
        try {
            switch (task.getTaskType()) {
                case FILE_TRANSFER:
                    try {
                        success = transferService.executeTransfer(task, emitCap, changedFileNames);
                    }
                    catch (TransferService.WatcherSkipException e) {
                        emitCap.accept("[INFO] Inbound watcher skipped transfer: " + e.getMessage());
                        success = true;
                        skipped = true;
                        skipReason = e.getMessage();
                    }
                    break;
                case OUTLOOK_MAIL:
                    try {
                        success = transferService.executeImapMailTask(task, emitCap);
                    }
                    catch (TransferService.WatcherSkipException e) {
                        emitCap.accept("[INFO] Mail watcher skipped run: " + e.getMessage());
                        success = true;
                        skipped = true;
                        skipReason = e.getMessage();
                    }
                    break;
                case BACKUP:
                    success = transferService.executeBackup(task, emitCap);
                    break;
                default:
                    emitCap.accept("[ERROR] Unknown task type: " + task.getTaskType());
                    success = false;
                    failureReason = "Unknown task type: " + task.getTaskType();
            }
        } catch (Exception e) {
            emitCap.accept("[ERROR] Unexpected error: " + e.getMessage());
            e.printStackTrace();
            success = false;
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        if (!success && task.getRetryCount() > 0) {
            String retryReason = failureReason != null ? failureReason : lastErrorLine(runLog.toString());
            emitCap.accept("[INFO] Task failed and will be retried " + task.getRetryCount() + " time(s).\n");
            runHistoryService.recordRun(task.getId(), task.getName(), task.getTaskType().name(),
                    TaskRunRecord.Status.FAILED, retryReason, runLog.toString(), runStartedAt, LocalDateTime.now());
            task.setStatus(TaskStatus.RETRYING);
            task.setLastStartedAt(null);
            storage.saveTask(task);
            lastActivityMillis.remove(task.getId());
            scheduler.schedule(() -> retryTask(task), 5, TimeUnit.SECONDS);
            return;
        }

        // Persist result
        final boolean finalSuccess = success;
        final boolean finalSkipped = skipped;
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(task.getId())).findFirst().ifPresent(t -> {
            t.setLastRunAt(LocalDateTime.now());
            if (finalSkipped) {
                t.setLastRunResult("SKIPPED");
            } else {
                t.setLastRunResult(finalSuccess ? "SUCCESS" : "FAILED");
            }
            // For recurring tasks, reset to PENDING if successful so they can run again
            if ((!finalSkipped && finalSuccess && (t.getScheduleType() == ScheduleType.INTERVAL_MINUTES
                    || t.getScheduleType() == ScheduleType.INTERVAL_SECONDS))
                    || finalSkipped) {
                t.setStatus(TaskStatus.PENDING);
                emit(task, "[INFO] Task reset to PENDING for next scheduled run");
            } else {
                t.setStatus(finalSuccess ? TaskStatus.SUCCESS : TaskStatus.FAILED);
            }
            storage.saveTask(t);
            // Publish the task's next occurrence immediately — this is what
            // eliminates the old "wait for the next shared poll tick" delay.
            // Works uniformly for every schedule type: computeNextFireDelayMs
            // returns null (nothing to publish) for schedule types with no
            // future occurrence, e.g. a ONCE task that just ran.
            if (t.getStatus() != TaskStatus.DISABLED) {
                publishNextOccurrence(t, LocalDateTime.now());
            }
        });

        LocalDateTime runEndedAt = LocalDateTime.now();
        TaskRunRecord.Status historyStatus = finalSkipped ? TaskRunRecord.Status.SKIPPED
                : (finalSuccess ? TaskRunRecord.Status.SUCCESS : TaskRunRecord.Status.FAILED);
        String historyReason;
        if (finalSkipped) {
            historyReason = skipReason != null ? skipReason : "Skipped (no specific reason captured).";
        } else if (finalSuccess) {
            historyReason = lastInfoLine(runLog.toString());
        } else {
            historyReason = failureReason != null ? failureReason : lastErrorLine(runLog.toString());
        }
        runHistoryService.recordRun(task.getId(), task.getName(), task.getTaskType().name(),
                historyStatus, historyReason, runLog.toString(), runStartedAt, runEndedAt);

        emit(task, "=== Task " + task.getName() + " finished: " + (success ? "SUCCESS" : "FAILED") + " ===");
        task.setLastStartedAt(null);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        long cpuMs = THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? (THREAD_BEAN.getCurrentThreadCpuTime() - startCpuNanos) / 1_000_000 : 0L;
        recordMetricsOnComplete(task.getId(), success, durationMs, cpuMs);
        // Clean up running future mapping
        try { runningTaskFutures.remove(task.getId()); } catch (Exception ignored) {}
        lastActivityMillis.remove(task.getId());
        } finally {
            // Always release the cross-process lock, however this run ended
            // (normal completion, exception, or the early "retry scheduled" return).
            if (fileLock != null) {
                try { fileLock.release(); } catch (IOException ignored) {}
            }
            if (lockChannel != null) {
                try { lockChannel.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Last "[ERROR] ..." line in a captured run log, with the tag stripped — or a generic fallback. */
    private static String lastErrorLine(String runLogText) {
        String[] lines = runLogText.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("[ERROR]")) return l.substring("[ERROR]".length()).trim();
        }
        return "Failed — see run details for the full log.";
    }

    /** Last "[INFO] ..." line in a captured run log (skipping boilerplate start/debug lines), with the tag stripped. */
    private static String lastInfoLine(String runLogText) {
        String[] lines = runLogText.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("[INFO]") && !l.startsWith("[INFO] Task reset to PENDING")) {
                return l.substring("[INFO]".length()).trim();
            }
        }
        return "Completed successfully.";
    }

    private void retryTask(ScheduledTask task) {
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(task.getId())).findFirst().ifPresent(t -> {
            if (t.getRetryCount() > 0) {
                t.setRetryCount(t.getRetryCount() - 1);
                t.setStatus(TaskStatus.PENDING);
                storage.saveTask(t);
                runNow(t.getId());
            } else {
                t.setStatus(TaskStatus.FAILED);
                storage.saveTask(t);
            }
        });
    }

    private void emit(ScheduledTask task, String line) {
        String taskId = task != null ? task.getId() : null;
        String taskName = task != null ? task.getName() : null;
        if (taskId != null) lastActivityMillis.put(taskId, System.currentTimeMillis());
        log.info("[" + taskId + "] " + line);
        logService.log(taskId, taskName, line);
        if (logCallback != null) {
            logCallback.accept(taskId, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "  " + line);
        }
    }
}
