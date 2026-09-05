package service.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;

/**
 * The in-process "broker" for scheduled task events.
 *
 * A {@link DelayQueue} is the actual delivery mechanism (it will not release
 * an event to a consumer until its delay has expired), and a side index
 * tracks the most recently published pending event per task so it can be
 * cancelled or transparently replaced — e.g. when a task's schedule is
 * edited, or it's disabled/deleted, the stale future occurrence must never
 * fire.
 *
 * Thread-safe: publish()/take()/cancel() may all be called concurrently from
 * the reconcile thread and worker threads.
 */
public class TaskEventQueue {

    private final DelayQueue<TaskDueEvent> queue = new DelayQueue<>();
    // Most recent pending event per task ID, keyed for cancellation/replacement.
    private final ConcurrentMap<String, TaskDueEvent> pendingByTaskId = new ConcurrentHashMap<>();

    /**
     * Publish an event. If this task already has a pending event, it is
     * removed first — this is what makes "task was edited, interval changed"
     * or "reconcile sweep re-published the same task" safe: only the most
     * recently published occurrence for a task can ever actually fire.
     *
     * <p>Exception: if both the pending event and this new one are watcher
     * fires ({@code immediate=true}), the pending one's named files are
     * merged into this one rather than discarded. Without this, a burst of
     * changes that settles (see {@code LocalWatchManager}'s debounce window)
     * before a worker thread has actually taken the previous fire off the
     * queue would silently drop that first burst's file names — outwardly
     * looking like "several files changed but only the last one got
     * transferred", even though nothing was actually wrong with detection.
     */
    public void publish(TaskDueEvent event) {
        TaskDueEvent previous = pendingByTaskId.get(event.getTaskId());
        if (previous != null && previous.isImmediate() && event.isImmediate()
                && !previous.getChangedFileNames().isEmpty()) {
            java.util.Set<String> merged = new java.util.LinkedHashSet<>(previous.getChangedFileNames());
            merged.addAll(event.getChangedFileNames());
            event = new TaskDueEvent(event.getTaskId(), event.getDueAt(), event.getAttempt(), true, merged);
        }
        TaskDueEvent removed = pendingByTaskId.put(event.getTaskId(), event);
        if (removed != null) {
            queue.remove(removed);
        }
        queue.put(event);
    }

    /**
     * Blocks the calling worker thread until an event is due, then hands it
     * over. This is the consumer-side primitive — each worker thread calls
     * this in a loop, fully independently of every other worker and of the
     * producer.
     */
    public TaskDueEvent take() throws InterruptedException {
        TaskDueEvent event = queue.take();
        // Only clear the index entry if this delivered event is still the
        // one we're tracking as "pending" for its task — a newer publish()
        // may have already replaced it in the index (and removed it from
        // the queue), in which case there's nothing to clean up here.
        pendingByTaskId.remove(event.getTaskId(), event);
        return event;
    }

    /** Cancel a task's pending event, if any (task disabled or deleted). */
    public boolean cancel(String taskId) {
        TaskDueEvent existing = pendingByTaskId.remove(taskId);
        return existing != null && queue.remove(existing);
    }

    /** Whether this task currently has a pending (not-yet-due) event. */
    public boolean hasPending(String taskId) {
        return pendingByTaskId.containsKey(taskId);
    }

    /** Number of events currently waiting in the queue. */
    public int size() {
        return queue.size();
    }

    /**
     * Non-destructive, point-in-time snapshot of every pending event,
     * ordered soonest-due first. Safe to call from a UI refresh timer —
     * unlike {@link #take()} it never removes anything from the queue.
     * Intended for monitoring/dashboard use.
     */
    public List<TaskDueEvent> snapshotPending() {
        List<TaskDueEvent> snapshot = new ArrayList<>(queue);
        snapshot.sort(Comparator.naturalOrder());
        return snapshot;
    }
}

