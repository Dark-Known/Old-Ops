package ui.monitor;

import model.ScheduledTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One visible line in the Event Monitor feed. There are three shapes:
 * <ul>
 *   <li>{@link Type#EVENT} — a single event (a watcher change, a config
 *       edit, or one child inside an expanded run).</li>
 *   <li>{@link Type#RUN} — one task execution: its Started, Detected, retry
 *       and outcome events folded into a single expandable line.</li>
 *   <li>{@link Type#BUCKET} — several consecutive identical runs of the same
 *       task ("No new files" ×200) collapsed into one line.</li>
 * </ul>
 * Immutable except for {@link #isNew}, which the panel sets for the brief
 * highlight on freshly arrived rows.
 */
public final class FeedRow {

    public enum Type { BUCKET, RUN, EVENT }

    /** What the status pill says — richer than an event's severity because a run can still be in progress. */
    public enum Outcome {
        RUNNING("Running"), SUCCEEDED("Succeeded"), FAILED("Failed"), SKIPPED("Skipped"),
        ERROR("Error"), WARNING("Warning"), INFO("Info"), DEBUG("Debug");

        private final String label;
        Outcome(String label) { this.label = label; }
        public String label() { return label; }
    }

    public final Type type;
    public final int depth;
    public final String key;
    /** Representative event: the outcome of a run, the newest run of a bucket, or the event itself. */
    public final MonitorEvent head;
    /** Events this row stands for (visible ones only): a run's events, a bucket's runs' events, or just {@code head}. */
    public final List<MonitorEvent> events;
    /** For a bucket, the member runs, newest first; empty otherwise. */
    public final List<FeedRow> members;
    /** Number of runs in a bucket / events in a run / 1. */
    public final int count;
    public final LocalDateTime time;
    public final LocalDateTime firstTime;
    public final Outcome outcome;
    public final String taskId;
    public final String taskName;
    public final String taskType;
    public final ScheduledTask.TransferDirection direction;
    public final String label;
    public final String summary;
    public final String trigger;
    /** Milliseconds, or -1 when not meaningful for this row. */
    public final long durationMs;
    /** Compact "3 · 4.2 MB" volume text, or null. */
    public final String files;
    public final boolean expandable;
    public final boolean expanded;
    private boolean isNew;

    FeedRow(Type type, int depth, String key, MonitorEvent head, List<MonitorEvent> events, List<FeedRow> members,
            int count, LocalDateTime time, LocalDateTime firstTime, Outcome outcome, String label, String summary,
            String trigger, long durationMs, String files, boolean expandable, boolean expanded) {
        this.type = type;
        this.depth = depth;
        this.key = key;
        this.head = head;
        this.events = events;
        this.members = members;
        this.count = count;
        this.time = time;
        this.firstTime = firstTime;
        this.outcome = outcome;
        this.taskId = head.taskId();
        this.taskName = head.taskName();
        this.taskType = head.taskType();
        this.direction = head.direction();
        this.label = label;
        this.summary = summary;
        this.trigger = trigger;
        this.durationMs = durationMs;
        this.files = files;
        this.expandable = expandable;
        this.expanded = expanded;
    }

    /** Same row, re-emitted at another depth / expansion state (used when flattening the tree). */
    FeedRow at(int newDepth, boolean newExpanded) {
        FeedRow r = new FeedRow(type, newDepth, key, head, events, members, count, time, firstTime, outcome, label,
                summary, trigger, durationMs, files, expandable, newExpanded);
        r.isNew = isNew;
        return r;
    }

    public boolean isNew()            { return isNew; }
    public void setNew(boolean value) { this.isNew = value; }

    /** True when this row, or anything folded into it, is a warning or error. */
    public boolean isProblem() {
        return outcome == Outcome.FAILED || outcome == Outcome.ERROR || outcome == Outcome.WARNING;
    }
}
