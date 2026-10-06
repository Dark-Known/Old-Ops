package ui.monitor;

import model.EventKind;
import model.ScheduledTask;
import model.TaskRunRecord;

import java.time.LocalDateTime;

/**
 * One thing that happened, in the shape the Event Monitor displays it —
 * independent of which database table it came from. Built from a stored
 * {@link TaskRunRecord} by {@link EventClassifier}.
 *
 * <p>{@code summary} is already a clean one-liner (the "Started —" /
 * "Detected " prefixes the scheduler writes are stripped, since the kind
 * column says that already). The full captured log is deliberately not part
 * of this object — it can be tens of KB — and is fetched on demand for the
 * one selected event via {@code table}/{@code id}.
 */
public record MonitorEvent(
        Table table,
        long id,
        String taskId,
        String taskName,
        String taskType,
        ScheduledTask.TransferDirection direction,
        EventKind kind,
        EventKind.Severity severity,
        TaskRunRecord.Status status,
        LocalDateTime start,
        LocalDateTime end,
        long durationMs,
        String summary,
        String trigger,
        String runKey,
        TaskRunRecord.FailureCategory failureCategory,
        boolean retryable,
        String suggestedAction) {

    /** Which history table a row lives in — their id sequences are independent. */
    public enum Table { RUN, ACTIVITY }

    /** Globally unique key for this event (table + row id). */
    public String key() {
        return table.name().charAt(0) + ":" + id;
    }

    /**
     * When this event <i>happened</i>: for a run outcome, when it finished
     * (a 20-minute transfer belongs at the moment it ended, not when it
     * began); for everything else, when it was recorded.
     */
    public LocalDateTime time() {
        return isOutcome() && end != null ? end : start;
    }

    /** True for the terminal row of a run: succeeded, failed or skipped. */
    public boolean isOutcome() {
        return kind == EventKind.RUN_SUCCEEDED || kind == EventKind.RUN_FAILED || kind == EventKind.RUN_SKIPPED;
    }

    public boolean isProblem() {
        return severity.compareTo(EventKind.Severity.WARN) >= 0;
    }

    /** Task id values used for application-level notes that are not about any one task. */
    public boolean isSystemNote() {
        return kind.category() == EventKind.Category.SYSTEM;
    }
}
