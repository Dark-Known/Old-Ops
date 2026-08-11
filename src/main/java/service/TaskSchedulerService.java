package service;

import model.ScheduledTask.*;
import model.ScheduledTask;
import service.TaskLogService;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Background scheduler that polls tasks every minute and fires them when due.
 * Supports: RUN_NOW, ONCE, DAILY, WEEKLY, INTERVAL_MINUTES.
 */
public class TaskSchedulerService {

    private static final Logger log = Logger.getLogger(TaskSchedulerService.class.getName());
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

    private final XmlStorageService storage;
    private final TransferService transferService;
    private final TaskLogService logService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, ScheduledFuture<?>> shortIntervalFutures = new ConcurrentHashMap<>();
    // Tracks currently running task futures so they can be cancelled on request
     private final ConcurrentMap<String, Future<?>> runningTaskFutures = new ConcurrentHashMap<>();
     private final ConcurrentMap<String, TaskMetrics> taskMetrics = new ConcurrentHashMap<>();
     // Default stale threshold is 30 minutes. For interval-based tasks, it's increased to
     // accommodate longer-running operations like large file transfers.
     private static final Duration STALE_RUNNING_THRESHOLD = Duration.ofMinutes(30);

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
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
    }

    public void setLogCallback(BiConsumer<String, String> cb) {
        this.logCallback = cb;
    }

    public TaskLogService getLogService() {
        return logService;
    }

    public XmlStorageService getStorage() {
        return storage;
    }

    public TaskMetrics getTaskMetrics(String taskId) {
        return taskId == null ? null : taskMetrics.get(taskId);
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

    /** Start the background poll loop (configurable interval). */
    public void start() {
        // Global poll loop handles ONCE/DAILY/WEEKLY/INTERVAL_MINUTES and any
        // INTERVAL_SECONDS that are >= pollIntervalSeconds.
        scheduler.scheduleAtFixedRate(this::checkAndRunDueTasks, 5, this.pollIntervalSeconds, TimeUnit.SECONDS);
        // Also create dedicated timers for short-interval tasks
        setupShortIntervalTasks();
        log.info("Task scheduler started (poll interval=" + this.pollIntervalSeconds + "s).");
    }

    public void stop() {
        // Cancel per-task timers
        for (ScheduledFuture<?> f : shortIntervalFutures.values()) {
            try { f.cancel(true); } catch (Exception ignored) {}
        }
        shortIntervalFutures.clear();

        scheduler.shutdownNow();
        executor.shutdownNow();
        logService.closeAll();
        log.info("TaskSchedulerService stopped.");
    }

    /** Immediately execute a task regardless of schedule. */
    public void runNow(String taskId) {
        List<ScheduledTask> tasks = storage.loadTasks();
        tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst().ifPresent(t -> {
            if (t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.RETRYING) {
                emit(t.getId(), "[INFO] Task is already active and will not be requeued.");
                return;
            }
            t.setStatus(TaskStatus.RUNNING);
            t.setLastStartedAt(LocalDateTime.now());
            storage.saveTask(t);
            refreshMetrics(t.getId(), true);
            Future<?> f = executor.submit(() -> executeTask(t));
            runningTaskFutures.put(t.getId(), f);
        });
    }

    private Duration getStaleRunningThreshold(ScheduledTask task) {
        if (task == null) return STALE_RUNNING_THRESHOLD;
        switch (task.getScheduleType()) {
            case INTERVAL_SECONDS:
                if (task.getIntervalSeconds() > 0) {
                    long threshold = Math.max(STALE_RUNNING_THRESHOLD.getSeconds(), task.getIntervalSeconds() * 3L);
                    return Duration.ofSeconds(threshold);
                }
                break;
            case INTERVAL_MINUTES:
                if (task.getIntervalMinutes() > 0) {
                    long thresholdMins = Math.max(STALE_RUNNING_THRESHOLD.toMinutes(), task.getIntervalMinutes() * 2L);
                    return Duration.ofMinutes(thresholdMins);
                }
                break;
            default:
                break;
        }
        return STALE_RUNNING_THRESHOLD;
    }

    private boolean isStaleRunning(ScheduledTask task, LocalDateTime now) {
        if (task == null || task.getStatus() != TaskStatus.RUNNING) return false;
        if (task.getLastStartedAt() == null) return true;
        return task.getLastStartedAt().plus(getStaleRunningThreshold(task)).isBefore(now);
    }

    private void checkAndRunDueTasks() {
        try {
            // Keep short-interval timers in sync with stored tasks
            try { setupShortIntervalTasks(); } catch (Exception e) {
                log.warning("Error refreshing short-interval tasks: " + e.getMessage());
            }

            List<ScheduledTask> tasks = storage.loadTasks();
            LocalDateTime now = LocalDateTime.now();

            for (ScheduledTask task : tasks) {
                if (task.getStatus() == TaskStatus.DISABLED) continue;
                if (task.getStatus() == TaskStatus.RETRYING) continue;
                if (task.getStatus() == TaskStatus.RUNNING) {
                    if (isStaleRunning(task, now)) {
                        emit(task.getId(), "[WARN] Detected stale RUNNING task; cancelling and resetting.");
                        try {
                            cancelTask(task.getId());
                        } catch (Exception ignored) {}
                        task.setLastStartedAt(null);
                        task.setLastRunResult("FAILED (stale)");
                        task.setStatus(TaskStatus.FAILED);
                        storage.saveTask(task);
                    }
                    continue;
                }

                if (isDue(task, now)) {
                    // Mark running immediately before async execution to prevent double-fire
                    task.setStatus(TaskStatus.RUNNING);
                    task.setLastStartedAt(LocalDateTime.now());
                    storage.saveTask(task);
                    final ScheduledTask t = task;
                    refreshMetrics(t.getId(), true);
                    Future<?> f = executor.submit(() -> executeTask(t));
                    runningTaskFutures.put(t.getId(), f);
                }
            }
        } catch (Exception e) {
            log.warning("Scheduler poll failed: " + e.getMessage());
            emit(null, "[ERROR] Scheduler poll failed: " + e.getMessage());
        }
    }

    /** Ensure tasks that require high-frequency (seconds) scheduling are set up
     * with their own ScheduledFuture so they can run independently of the global
     * poll interval (e.g. every 5s). This method is safe to call repeatedly. */
    private void setupShortIntervalTasks() {
        List<ScheduledTask> tasks = storage.loadTasks();

        // Cancel timers for tasks that no longer need a dedicated timer
        for (String id : shortIntervalFutures.keySet()) {
            boolean stillNeeded = tasks.stream().anyMatch(t ->
                t.getId().equals(id)
                && t.getScheduleType() == ScheduleType.INTERVAL_SECONDS
                && t.getIntervalSeconds() > 0
                && t.getIntervalSeconds() < this.pollIntervalSeconds);
            if (!stillNeeded) {
                ScheduledFuture<?> f = shortIntervalFutures.remove(id);
                if (f != null) f.cancel(true);
            }
        }

        for (ScheduledTask task : tasks) {
            if (task.getScheduleType() !=ScheduleType.INTERVAL_SECONDS) continue;
            int interval = task.getIntervalSeconds();
            if (interval <= 0) continue;
            // Only schedule dedicated timers for intervals smaller than global poll
            if (interval >= this.pollIntervalSeconds) continue;

            ScheduledFuture<?> existing = shortIntervalFutures.get(task.getId());
            if (existing != null && !existing.isCancelled()) {
                continue; // already scheduled
            }

            Runnable r = () -> {
                try {
                    List<ScheduledTask> ts = storage.loadTasks();
                    ts.stream().filter(t -> t.getId().equals(task.getId())).findFirst().ifPresent(t -> {
                        if (t.getStatus() == TaskStatus.DISABLED) return;
                        if (t.getStatus() == TaskStatus.RETRYING) return;
                        if (t.getStatus() == TaskStatus.RUNNING) {
                            if (isStaleRunning(t, LocalDateTime.now())) {
                                emit(t.getId(), "[WARN] Detected stale short-interval RUNNING task; cancelling and resetting.");
                                try {
                                    cancelTask(t.getId());
                                } catch (Exception ignored) {}
                                t.setLastStartedAt(null);
                                t.setLastRunResult("FAILED (stale)");
                                t.setStatus(TaskStatus.FAILED);
                                storage.saveTask(t);
                            }
                            return;
                        }
                        // Mark running and persist to avoid double-run
                        t.setStatus(TaskStatus.RUNNING);
                        t.setLastStartedAt(LocalDateTime.now());
                        storage.saveTask(t);
                        refreshMetrics(t.getId(), true);
                        Future<?> f = executor.submit(() -> executeTask(t));
                        runningTaskFutures.put(t.getId(), f);
                    });
                } catch (Exception e) {
                    log.warning("Short-interval task runner error: " + e.getMessage());
                }
            };

            ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(r, 0, interval, TimeUnit.SECONDS);
            shortIntervalFutures.put(task.getId(), fut);
            log.info("Scheduled short-interval task " + task.getId() + " every " + interval + "s");
        }
    }

    /** Public: refresh timers and short-interval setup immediately. */
    public void refresh() {
        setupShortIntervalTasks();
    }

    /** Attempt to cancel a task: removes short-interval timer and cancels running future if present. */
    public boolean cancelTask(String taskId) {
        boolean cancelledAny = false;
        ScheduledFuture<?> sf = shortIntervalFutures.remove(taskId);
        if (sf != null) {
            cancelledAny = sf.cancel(true) || cancelledAny;
        }
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

    private boolean isDue(ScheduledTask task, LocalDateTime now) {
        if (task.isWatcherEnabled() && task.getInboundWatcherPollIntervalMinutes() > 0) {
            if (task.getLastRunAt() == null) return true;
            return task.getLastRunAt().plusMinutes(task.getInboundWatcherPollIntervalMinutes()).isBefore(now);
        }

        switch (task.getScheduleType()) {
            case RUN_NOW:
                // Only runs once on next poll after being saved
                return task.getLastRunAt() == null;

            case ONCE:
                return task.getScheduledAt() != null
                    && !now.isBefore(task.getScheduledAt())
                    && task.getLastRunAt() == null;

            case DAILY: {
                // cronExpression stores "HH:mm"
                if (task.getCronExpression() == null) return false;
                LocalTime target = LocalTime.parse(task.getCronExpression(),
                    DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime nowTime = now.toLocalTime();
                // Fire within the current minute window
                boolean timeMatch = nowTime.getHour() == target.getHour()
                    && nowTime.getMinute() == target.getMinute();
                if (!timeMatch) return false;
                if (task.getLastRunAt() == null) return true;
                // Don't re-run same minute
                return task.getLastRunAt().toLocalDate().isBefore(now.toLocalDate());
            }

            case WEEKLY: {
                // cronExpression stores "MON 09:00" or "TUESDAY 14:30"
                if (task.getCronExpression() == null) return false;
                String[] parts = task.getCronExpression().split(" ");
                if (parts.length < 2) return false;
                String dayName = parts[0].toUpperCase();
                LocalTime target = LocalTime.parse(parts[1], DateTimeFormatter.ofPattern("HH:mm"));
                String todayName = now.getDayOfWeek().name().substring(0, 3); // MON, TUE...
                boolean dayMatch = dayName.startsWith(todayName) || todayName.startsWith(dayName.substring(0, 3));
                boolean timeMatch = now.toLocalTime().getHour() == target.getHour()
                    && now.toLocalTime().getMinute() == target.getMinute();
                if (!dayMatch || !timeMatch) return false;
                if (task.getLastRunAt() == null) return true;
                return task.getLastRunAt().toLocalDate().isBefore(now.toLocalDate());
            }

            case INTERVAL_MINUTES: {
                if (task.getIntervalMinutes() <= 0) return false;
                if (task.getLastRunAt() == null) return true;
                return task.getLastRunAt().plusMinutes(task.getIntervalMinutes()).isBefore(now);
            }
            case INTERVAL_SECONDS: {
                if (task.getIntervalSeconds() <= 0) return false;
                if (task.getLastRunAt() == null) return true;
                return task.getLastRunAt().plusSeconds(task.getIntervalSeconds()).isBefore(now);
            }

            default:
                return false;
        }
    }

    private void executeTask(ScheduledTask task) {
        final long startNanos = System.nanoTime();
        final long startCpuNanos = THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? THREAD_BEAN.getCurrentThreadCpuTime() : 0L;

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
        try {
            Files.createDirectories(lockPath.getParent());
            lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
        } catch (Exception e) {
            emit(task.getId(), "[WARN] Could not set up cross-process task lock (" + e.getMessage()
                    + ") — proceeding without it.");
        }

        if (lockChannel != null && fileLock == null) {
            // Another process (GUI or Daemon) already holds the lock for this task.
            emit(task.getId(), "[INFO] Skipped: task '" + task.getName()
                    + "' is already running in another process (GUI or Daemon) at this tick.");
            try { lockChannel.close(); } catch (IOException ignored) {}
            try { runningTaskFutures.remove(task.getId()); } catch (Exception ignored) {}
            return;
        }

        try {
        emit(task.getId(), "=== Starting task: " + task.getName() + " ===");
        emit(task.getId(), "[DEBUG] Task ID: " + task.getId());
        emit(task.getId(), "[DEBUG] Task Type: " + task.getTaskType());
        emit(task.getId(), "[DEBUG] Schedule Type: " + task.getScheduleType());
        if (task.getTaskType() == ScheduledTask.TaskType.FILE_TRANSFER) {
            // Transfer Mode only applies to file-transfer tasks — logging it
            // unconditionally printed a leftover/default value for every
            // mail task too, which was just noise (and misleading, since
            // that field isn't actually used for anything on mail tasks).
            emit(task.getId(), "[DEBUG] Transfer Mode: " + (task.getTransferMode() != null ? task.getTransferMode().name() : "NULL"));
        }
        
        boolean success;
        boolean skipped = false;
        try {
            switch (task.getTaskType()) {
                case FILE_TRANSFER:
                    try {
                        success = transferService.executeTransfer(task, line -> emit(task.getId(), line));
                    }
                    catch (TransferService.WatcherSkipException e) {
                        emit(task.getId(), "[INFO] Inbound watcher skipped transfer: " + e.getMessage());
                        success = true;
                        skipped = true;
                    }
                    break;
                case OUTLOOK_MAIL:
                    try {
                        success = transferService.executeImapMailTask(task, line -> emit(task.getId(), line));
                    }
                    catch (TransferService.WatcherSkipException e) {
                        emit(task.getId(), "[INFO] Mail watcher skipped run: " + e.getMessage());
                        success = true;
                        skipped = true;
                    }
                    break;
                case START_SERVICE:
                case STOP_SERVICE:
                case RESTART_SERVICE:
                    success = transferService.executeServiceAction(task, line -> emit(task.getId(), line));
                    break;
                default:
                    emit(task.getId(), "[ERROR] Unknown task type: " + task.getTaskType());
                    success = false;
            }
        } catch (Exception e) {
            emit(task.getId(), "[ERROR] Unexpected error: " + e.getMessage());
            e.printStackTrace();
            success = false;
        }

        if (!success && task.getRetryCount() > 0) {
            emit(task.getId(), "[INFO] Task failed and will be retried " + task.getRetryCount() + " time(s).\n");
            task.setStatus(TaskStatus.RETRYING);
            task.setLastStartedAt(null);
            storage.saveTask(task);
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
                emit(task.getId(), "[INFO] Task reset to PENDING for next scheduled run");
            } else {
                t.setStatus(finalSuccess ? TaskStatus.SUCCESS : TaskStatus.FAILED);
            }
            storage.saveTask(t);
        });

        emit(task.getId(), "=== Task " + task.getName() + " finished: " + (success ? "SUCCESS" : "FAILED") + " ===");
        task.setLastStartedAt(null);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        long cpuMs = THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? (THREAD_BEAN.getCurrentThreadCpuTime() - startCpuNanos) / 1_000_000 : 0L;
        recordMetricsOnComplete(task.getId(), success, durationMs, cpuMs);
        // Clean up running future mapping
        try { runningTaskFutures.remove(task.getId()); } catch (Exception ignored) {}
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

    private void emit(String taskId, String line) {
        log.info("[" + taskId + "] " + line);
        logService.log(taskId, line);
        if (logCallback != null) {
            logCallback.accept(taskId, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "  " + line);
        }
    }
}
