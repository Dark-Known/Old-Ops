package service.queue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A fixed pool of worker threads, each independently running a
 * take-execute-repeat loop against a {@link TaskEventQueue}.
 *
 * This is the actual "pool of worker threads consuming from a queue
 * independently" — no shared poll tick, no central dispatcher deciding
 * which worker gets which task. Each thread blocks in
 * {@link TaskEventQueue#take()} until an event is due, handles it
 * synchronously (so pool size directly controls execution concurrency —
 * the same role {@code AppSettings.getMaxConcurrentTaskThreads()} played
 * before), then loops back to take() for the next one.
 *
 * Also tracks lightweight, non-persistent monitoring state (how many
 * workers are currently busy, and a bounded feed of recently handled
 * events) purely for UI/dashboard consumption — none of this affects
 * scheduling or execution behavior.
 */
public class TaskWorkerPool {

    private static final Logger log = Logger.getLogger(TaskWorkerPool.class.getName());
    private static final int MAX_ACTIVITY_ENTRIES = 200;

    /**
     * What the handler wants to tell the pool about the event it just
     * finished handling, beyond "did it throw" — set via a {@code Supplier}
     * the handler-owning code (e.g. {@code TaskSchedulerService}) hands in,
     * since the handler itself is a plain {@code Consumer} with no return
     * value. {@code suppress=true} means "this wasn't actually an attempted
     * run — pure scheduling bookkeeping (task disabled, deleted, already
     * running elsewhere, a watcher fire that arrived mid-run, etc.) — don't
     * add a row to the Events feed for it at all"; {@code errored} lets a
     * handler report a real failure it handled internally (returned false
     * rather than throwing) so it's badged the same as a thrown exception
     * instead of quietly looking like a success; {@code note} is a short
     * human-readable reason shown in place of a bare "OK"/"ERROR" for
     * anything worth explaining (a skip reason, a failure reason, etc.).
     */
    public record HandlerOutcome(boolean suppress, boolean errored, String note) {
        public static final HandlerOutcome DEFAULT = new HandlerOutcome(false, false, null);
    }

    /** One row of the recent-activity feed, for UI display only. */
    public static final class ActivityEntry {
        private final String taskId;
        private final int attempt;
        private final LocalDateTime startedAt;
        private final LocalDateTime finishedAt;
        private final boolean errored;
        private final String errorMessage; // error text if errored, otherwise an optional informational note (e.g. a skip reason) or null

        ActivityEntry(String taskId, int attempt, LocalDateTime startedAt, LocalDateTime finishedAt,
                      boolean errored, String errorMessage) {
            this.taskId = taskId;
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.errored = errored;
            this.errorMessage = errorMessage;
        }

        public String getTaskId() { return taskId; }
        public int getAttempt() { return attempt; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public boolean isErrored() { return errored; }
        public String getErrorMessage() { return errorMessage; }
    }

    private final TaskEventQueue queue;
    private final Consumer<TaskDueEvent> handler;
    private final Supplier<HandlerOutcome> outcomeSupplier;
    private final ExecutorService workers;
    private final int workerCount;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    // Newest-first bounded feed of handled events, for the monitoring UI.
    // ConcurrentLinkedDeque so worker threads can push from addFirst() while
    // a Swing timer reads a snapshot concurrently without external locking.
    private final ConcurrentLinkedDeque<ActivityEntry> recentActivity = new ConcurrentLinkedDeque<>();
    private volatile boolean running = false;

    public TaskWorkerPool(TaskEventQueue queue, int workerCount, Consumer<TaskDueEvent> handler) {
        this(queue, workerCount, handler, () -> HandlerOutcome.DEFAULT);
    }

    public TaskWorkerPool(TaskEventQueue queue, int workerCount, Consumer<TaskDueEvent> handler,
                           Supplier<HandlerOutcome> outcomeSupplier) {
        this.queue = queue;
        this.handler = handler;
        this.outcomeSupplier = outcomeSupplier != null ? outcomeSupplier : () -> HandlerOutcome.DEFAULT;
        this.workerCount = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(this.workerCount, r -> {
            Thread t = new Thread(r, "task-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the configured number of worker threads. Safe to call once per pool instance. */
    public void start() {
        running = true;
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::runLoop);
        }
        log.info("TaskWorkerPool started with " + workerCount + " worker thread(s).");
    }

    private void runLoop() {
        while (running) {
            try {
                TaskDueEvent event = queue.take(); // blocks here — zero CPU while idle, no polling
                activeWorkers.incrementAndGet();
                LocalDateTime startedAt = LocalDateTime.now();
                boolean errored = false;
                String message = null;
                boolean suppress = false;
                try {
                    handler.accept(event);
                    HandlerOutcome outcome = outcomeSupplier.get();
                    errored = outcome.errored();
                    message = outcome.note();
                    suppress = outcome.suppress();
                } catch (Exception e) {
                    // A failure handling one event must never kill the worker
                    // thread — it just goes back to take()-ing the next one.
                    errored = true;
                    message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warning("Worker error handling task event " + event + ": " + e.getMessage());
                } finally {
                    activeWorkers.decrementAndGet();
                    if (!suppress) {
                        recordActivity(new ActivityEntry(event.getTaskId(), event.getAttempt(),
                                startedAt, LocalDateTime.now(), errored, message));
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void recordActivity(ActivityEntry entry) {
        recentActivity.addFirst(entry);
        while (recentActivity.size() > MAX_ACTIVITY_ENTRIES) {
            Iterator<ActivityEntry> it = recentActivity.descendingIterator();
            if (it.hasNext()) { it.next(); it.remove(); } else break;
        }
    }

    public void stop() {
        running = false;
        workers.shutdownNow();
    }

    /** Total configured worker threads (the execution-concurrency ceiling). */
    public int getWorkerCount() { return workerCount; }

    /** Workers currently blocked inside handler.accept(), i.e. actively executing a task right now. */
    public int getActiveWorkerCount() { return activeWorkers.get(); }

    /** Newest-first snapshot of the last {@code limit} handled events. UI-consumption only. */
    public List<ActivityEntry> getRecentActivity(int limit) {
        List<ActivityEntry> snapshot = new ArrayList<>(Math.min(limit, MAX_ACTIVITY_ENTRIES));
        for (ActivityEntry e : recentActivity) {
            if (snapshot.size() >= limit) break;
            snapshot.add(e);
        }
        return snapshot;
    }
}
