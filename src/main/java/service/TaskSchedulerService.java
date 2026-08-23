package service;

import model.ScheduledTask.*;
import model.ScheduledTask;
import model.TaskRunRecord;
import service.TaskLogService;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
    private final ConcurrentMap<String, ScheduledFuture<?>> shortIntervalFutures = new ConcurrentHashMap<>();
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
                // Tasks with their own dedicated short-interval timer (see
                // setupShortIntervalTasks) are already being polled and fired
                // independently, on their own faster cadence. Without this
                // skip, the global tick evaluated EVERY task including
                // those — so a fast test task (e.g. a 5s/10s INTERVAL_SECONDS
                // task) could be picked up here AND by its own timer within
                // the same in-process moment, both seeing it as due and both
                // racing to execute it. That's a same-process double-fire,
                // not an actual second GUI/Daemon process — but it produces
                // the exact same "already running in another process" skip
                // log via the cross-process file lock in executeTask(),
                // which is misleading when only one process is running at
                // all. The dedicated timer already performs this same due
                // check and stale-recovery, so it's safe to leave it to that.
                if (shortIntervalFutures.containsKey(task.getId())) continue;
                if (task.getStatus() == TaskStatus.RUNNING) {
                    if (isStaleRunning(task, now)) {
                        emit(task, "[WARN] Detected stale RUNNING task; cancelling and resetting.");
                        try {
                            cancelTask(task.getId());
                        } catch (Exception ignored) {}
                        task.setLastStartedAt(null);
                        task.setLastRunResult("FAILED (stale)");
                        task.setStatus(TaskStatus.FAILED);
                        storage.saveTask(task);
                        lastActivityMillis.remove(task.getId());
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
                                emit(t, "[WARN] Detected stale short-interval RUNNING task; cancelling and resetting.");
                                try {
                                    cancelTask(t.getId());
                                } catch (Exception ignored) {}
                                t.setLastStartedAt(null);
                                t.setLastRunResult("FAILED (stale)");
                                t.setStatus(TaskStatus.FAILED);
                                storage.saveTask(t);
                                lastActivityMillis.remove(t.getId());
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

            // compute() is used instead of the get()-then-put() pattern above so the
            // "is a timer already registered?" check and the registration itself are
            // one atomic operation per task ID. Without this, two threads calling
            // setupShortIntervalTasks() around the same moment (the periodic 60s poll
            // tick and scheduler.refresh(), which the Task Manager panel calls on the
            // EDT after every save/edit/delete) could both see no existing timer and
            // both register one. The second put() would silently orphan the first
            // timer — it keeps firing every interval forever with no way to cancel
            // it, so the task now has two independent timers racing each other. The
            // loser of that race hits OverlappingFileLockException (a same-JVM,
            // same-process lock conflict — its getMessage() is null) in executeTask's
            // file lock, which is exactly the confusing
            // "Could not set up cross-process task lock (null)" + "already running in
            // another process" pair even when only this one GUI process is running.
            shortIntervalFutures.compute(task.getId(), (id, currentFuture) -> {
                if (currentFuture != null && !currentFuture.isCancelled()) {
                    return currentFuture; // another thread already registered one — keep it
                }
                ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(r, 0, interval, TimeUnit.SECONDS);
                log.info("Scheduled short-interval task " + task.getId() + " every " + interval + "s");
                return fut;
            });
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
            // process (see the comment in setupShortIntervalTasks() for the race
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
                        success = transferService.executeTransfer(task, emitCap);
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
