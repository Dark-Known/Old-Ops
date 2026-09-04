package service.queue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An immutable "this task becomes due at this instant" event.
 *
 * Implements {@link Delayed} so a {@link java.util.concurrent.DelayQueue} can
 * hold it and only release it to a consumer once {@link #dueAt} has arrived.
 * This IS the scheduling mechanism for the event-driven scheduler — it
 * replaces the old shared poll tick entirely. No thread is consumed while an
 * event waits; DelayQueue.take() parks the calling worker thread until the
 * head element's delay expires.
 */
public final class TaskDueEvent implements Delayed {

    private static final AtomicLong SEQ = new AtomicLong();

    private final String taskId;
    private final LocalDateTime dueAt;
    private final int attempt;   // 0 = normal scheduled occurrence, >0 = retry attempt N
    // True for events published by a push wake-up (LocalWatchManager /
    // RemotePushWatcher observed a real filesystem/remote change) rather
    // than the normal poll-schedule publisher. See onTaskDue's use of this:
    // a push wake-up must actually run the task now — a file genuinely
    // changed — rather than being re-validated against "is this due per the
    // nominal poll interval yet?" the way an ordinary scheduled occurrence
    // is. Defaults to false via the 3-arg constructor for every other caller.
    private final boolean immediate;
    private final long sequence; // tiebreaker for events with identical dueAt, and identity for cancellation

    public TaskDueEvent(String taskId, LocalDateTime dueAt, int attempt) {
        this(taskId, dueAt, attempt, false);
    }

    public TaskDueEvent(String taskId, LocalDateTime dueAt, int attempt, boolean immediate) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
        this.attempt = attempt;
        this.immediate = immediate;
        this.sequence = SEQ.incrementAndGet();
    }

    public String getTaskId() { return taskId; }
    public LocalDateTime getDueAt() { return dueAt; }
    public int getAttempt() { return attempt; }
    public boolean isImmediate() { return immediate; }
    long getSequence() { return sequence; }

    @Override
    public long getDelay(TimeUnit unit) {
        long millis = Duration.between(LocalDateTime.now(), dueAt).toMillis();
        return unit.convert(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        if (other instanceof TaskDueEvent o) {
            int cmp = this.dueAt.compareTo(o.dueAt);
            return cmp != 0 ? cmp : Long.compare(this.sequence, o.sequence);
        }
        return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
    }

    // Identity is (sequence) only, NOT (taskId + dueAt). This lets TaskEventQueue
    // hold/replace a specific published instance for cancellation purposes
    // without accidentally colliding two distinct events for the same task
    // (e.g. a retry queued around the same time as a next-occurrence publish).
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskDueEvent e)) return false;
        return sequence == e.sequence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence);
    }

    @Override
    public String toString() {
        return "TaskDueEvent{taskId='" + taskId + "', dueAt=" + dueAt + ", attempt=" + attempt + '}';
    }
}
