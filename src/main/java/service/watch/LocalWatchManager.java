package service.watch;

import model.ScheduledTask;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * OS-level push notifications for OUTBOUND file-transfer watcher tasks.
 *
 * <p>Wraps a single {@link WatchService} (backed by {@code ReadDirectoryChangesW}
 * on Windows, {@code inotify} on Linux, {@code FSEvents}/{@code kqueue} on macOS
 * — whichever the JVM's default filesystem provider uses) and fans CREATE/MODIFY
 * events out to whichever task registered that directory.
 *
 * <p>Firing a wake-up simply publishes a "due now" event through the existing
 * scheduler event queue ({@code TaskSchedulerService::onWatchWakeup}). All the
 * real work — which files actually changed, dedupe against the stored epoch,
 * batching, etc. — still happens inside {@code TransferService}'s existing
 * watcher-transfer path. This class's only job is to replace "wait for the
 * next poll tick" with "wake up the instant the OS says something changed".
 *
 * <p>The task's normal scheduled poll (see
 * {@code TaskSchedulerService#computeNextFireDelayMs}) is deliberately left
 * running as a slow safety net — some filesystems (notably certain network
 * shares/mapped drives) silently no-op a native watch registration, and this
 * way a missed OS event just means "the task ran a bit later than it could
 * have," not "the task never ran."
 *
 * <p>Thread-safe: {@link #sync} is intended to be called from the scheduler's
 * periodic reconcile pass; the watch-event loop runs on its own dedicated
 * thread.
 */
public class LocalWatchManager {

    private static final Logger log = Logger.getLogger(LocalWatchManager.class.getName());

    /** Default quiet period after the last observed change before treating a
     * file as "settled" and safe to transfer — avoids racing an in-progress write. */
    private static final long DEFAULT_SETTLE_MILLIS = 3000L;

    private final long settleMillis;
    // taskId, changedFileNames -> caller wakes the task up and (when non-empty)
    // transfers exactly those named files instead of re-scanning the directory.
    private final BiConsumer<String, Set<String>> onSettled;

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService debounceExecutor;

    // taskId -> its registered WatchKey (so we can cancel on unregister)
    private final Map<String, WatchKey> keysByTaskId = new ConcurrentHashMap<>();
    // WatchKey -> taskId (reverse lookup when an event arrives)
    private final Map<WatchKey, String> taskIdByKey = new ConcurrentHashMap<>();
    // taskId -> the directory path currently registered for it, so sync() can
    // detect "task's sourcePath was edited" and re-register.
    private final Map<String, String> registeredPathByTaskId = new ConcurrentHashMap<>();
    // taskId -> a short human-readable reason for the *current* state, so the
    // UI can tell "never attempted" apart from "tried and failed, here's why"
    // instead of both collapsing into a bare "Polling only". Updated on every
    // register()/unregister() decision; never cleared to null once a task has
    // been evaluated at least once.
    private final Map<String, String> reasonByTaskId = new ConcurrentHashMap<>();
    // Pending debounce timers, keyed by taskId (one outstanding "about to fire" per task)
    private final Map<String, ScheduledFuture<?>> pendingFires = new ConcurrentHashMap<>();
    // Filenames accumulated for a task's pending fire, merged across every event
    // that arrives before the debounce settles — e.g. three files dropped within
    // the same settle window all end up in one fire, all three named.
    private final Map<String, Set<String>> pendingNamesByTaskId = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public LocalWatchManager(BiConsumer<String, Set<String>> onSettled) {
        this(onSettled, DEFAULT_SETTLE_MILLIS);
    }

    public LocalWatchManager(BiConsumer<String, Set<String>> onSettled, long settleMillis) {
        this.onSettled = onSettled;
        this.settleMillis = settleMillis;
    }

    public synchronized void start() {
        if (running) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warning("LocalWatchManager: native directory watching unavailable on this platform ("
                    + e.getMessage() + "); OUTBOUND watcher tasks will fall back to scheduled polling only.");
            return;
        }
        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-watch-debounce");
            t.setDaemon(true);
            return t;
        });
        running = true;
        watchThread = new Thread(this::watchLoop, "local-watch-service");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("LocalWatchManager started (OS-level directory notifications for OUTBOUND watcher tasks).");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (watchThread != null) watchThread.interrupt();
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        keysByTaskId.clear();
        taskIdByKey.clear();
        registeredPathByTaskId.clear();
        reasonByTaskId.clear();
        pendingFires.clear();
        pendingNamesByTaskId.clear();
    }

    /**
     * Reconciles registrations against the current task list: registers any
     * new eligible task, re-registers any whose sourcePath changed, and
     * unregisters any that are no longer eligible (disabled, deleted,
     * direction switched, watcher turned off, etc). Cheap to call on every
     * scheduler reconcile sweep — a no-op unless something actually changed.
     */
    public void sync(List<ScheduledTask> tasks) {
        if (!running) return;

        Set<String> stillEligible = new HashSet<>();
        for (ScheduledTask t : tasks) {
            if (!isEligible(t)) continue;
            stillEligible.add(t.getId());
            String currentPath = registeredPathByTaskId.get(t.getId());
            if (currentPath != null && currentPath.equals(t.getSourcePath())) {
                continue; // already registered, unchanged
            }
            register(t.getId(), t.getSourcePath());
        }

        for (String taskId : new ArrayList<>(registeredPathByTaskId.keySet())) {
            if (!stillEligible.contains(taskId)) {
                unregister(taskId);
            }
        }
    }

    /** True if this task currently has a live native directory watch registered
     *  (i.e. it's getting instant OS-level notifications, not just polling). */
    public boolean isWatching(String taskId) {
        return registeredPathByTaskId.containsKey(taskId);
    }

    /** Short human-readable reason for this task's current state — e.g. "native
     *  watch registered", "source path is not a directory", or an IOException
     *  message from a failed registration attempt. {@code null} if this task
     *  has never been evaluated (not eligible, or sync() hasn't run yet). */
    public String getReason(String taskId) {
        return reasonByTaskId.get(taskId);
    }

    private boolean isEligible(ScheduledTask t) {
        return t.getTaskType() == ScheduledTask.TaskType.FILE_TRANSFER
                && t.isWatcherEnabled()
                && t.getTransferDirection() == ScheduledTask.TransferDirection.OUTBOUND
                && t.getStatus() != ScheduledTask.TaskStatus.DISABLED
                && t.getSourcePath() != null && !t.getSourcePath().isBlank();
    }

    /**
     * Forces this task to be re-registered right now, even if it's already
     * registered on an unchanged path — the manual "Reconnect" action from
     * the UI, for when an operator wants to retry immediately rather than
     * wait for the next reconcile sweep (normally up to ~30s). Cheap and
     * safe to call repeatedly; a no-op path check plus a single
     * {@code WatchService} registration call, both local filesystem
     * operations. Safely does nothing but update the reason if the task
     * isn't currently eligible.
     */
    public void forceReconnect(ScheduledTask task) {
        if (!running || task == null) return;
        if (!isEligible(task)) {
            unregister(task.getId());
            reasonByTaskId.put(task.getId(),
                    "not eligible for native watch (check watcher enabled, direction is OUTBOUND, and source path)");
            return;
        }
        register(task.getId(), task.getSourcePath());
    }

    private void register(String taskId, String path) {
        unregister(taskId, false); // clear any stale registration first, keep last reason until we know the new one
        try {
            Path dir = Paths.get(path);
            if (!Files.isDirectory(dir)) {
                reasonByTaskId.put(taskId, "source path is not a directory: " + path);
                log.warning("Watcher task " + taskId + ": source path is not a directory, skipping native watch: " + path);
                return;
            }
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            keysByTaskId.put(taskId, key);
            taskIdByKey.put(key, taskId);
            registeredPathByTaskId.put(taskId, path);
            reasonByTaskId.put(taskId, "native directory watch registered on " + path);
            log.info("Watcher task " + taskId + ": registered native directory watch on " + path);
        } catch (IOException e) {
            // Not fatal — the task's existing poll-based safety net keeps working.
            reasonByTaskId.put(taskId, "failed to register native watch: " + e.getMessage());
            log.warning("Watcher task " + taskId + ": failed to register native directory watch on "
                    + path + " (falling back to polling only): " + e.getMessage());
        }
    }

    private void unregister(String taskId) {
        unregister(taskId, true);
    }

    private void unregister(String taskId, boolean clearReason) {
        WatchKey key = keysByTaskId.remove(taskId);
        if (key != null) {
            key.cancel();
            taskIdByKey.remove(key);
        }
        registeredPathByTaskId.remove(taskId);
        if (clearReason) reasonByTaskId.remove(taskId);
        ScheduledFuture<?> pending = pendingFires.remove(taskId);
        if (pending != null) pending.cancel(false);
        pendingNamesByTaskId.remove(taskId);
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take(); // blocks — zero CPU while idle
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }
            String taskId = taskIdByKey.get(key);
            if (taskId != null) {
                // Read each event's context Path to get the actual filename that
                // changed, so the fire can carry "transfer exactly these files"
                // instead of just "something changed, go rescan the directory".
                // OVERFLOW carries no context (the OS dropped events because too
                // many piled up) — we still wake the task, just without a name,
                // which tells the caller to fall back to a full directory scan.
                List<WatchEvent<?>> events = key.pollEvents();
                if (!events.isEmpty()) {
                    Set<String> names = new HashSet<>();
                    for (WatchEvent<?> ev : events) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        Object ctx = ev.context();
                        if (ctx instanceof Path) {
                            names.add(((Path) ctx).getFileName().toString());
                        }
                    }
                    scheduleFire(taskId, names);
                }
            }
            boolean valid = key.reset();
            if (!valid) {
                // Directory became inaccessible (deleted/unmounted) — drop the
                // registration; sync() will re-create it once the task's
                // source path is valid again.
                taskIdByKey.remove(key);
                if (taskId != null) {
                    keysByTaskId.remove(taskId);
                    registeredPathByTaskId.remove(taskId);
                }
            }
        }
    }

    private void scheduleFire(String taskId, Set<String> names) {
        // Coalesce bursts of events (e.g. many files dropped at once) into a
        // single wake-up, settleMillis after the *last* observed change —
        // avoids hammering the scheduler with one event per file and avoids
        // racing a file that's still being written. Filenames from every event
        // in the burst are merged, so a debounced fire still names every file
        // involved, not just the last one.
        if (!names.isEmpty()) {
            pendingNamesByTaskId.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(names);
        }
        ScheduledFuture<?> existing = pendingFires.get(taskId);
        if (existing != null) existing.cancel(false);
        ScheduledFuture<?> fut = debounceExecutor.schedule(() -> {
            pendingFires.remove(taskId);
            Set<String> fired = pendingNamesByTaskId.remove(taskId);
            try {
                onSettled.accept(taskId, fired != null ? fired : Collections.emptySet());
            } catch (Exception e) {
                log.warning("Watcher task " + taskId + ": onSettled callback failed: " + e.getMessage());
            }
        }, settleMillis, TimeUnit.MILLISECONDS);
        pendingFires.put(taskId, fut);
    }
}
