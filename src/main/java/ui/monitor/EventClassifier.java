package ui.monitor;

import model.EventKind;
import model.ScheduledTask;
import model.TaskRunRecord;

import java.util.Map;

/**
 * Turns a stored {@link TaskRunRecord} into a {@link MonitorEvent}.
 *
 * <p>Rows written by the current scheduler carry an explicit
 * {@code event_kind}. Rows from older builds do not, so the kind is inferred
 * the same way the old monitor did — from which table the row came from and
 * the text markers the scheduler used ("Started —", "Detected ") — so
 * existing history keeps rendering sensibly after an upgrade instead of
 * showing up as a wall of unclassified notes.
 */
public final class EventClassifier {

    private EventClassifier() {}

    private static final String STARTED_PREFIX  = "Started \u2014";
    private static final String DETECTED_PREFIX = "Detected ";

    public static MonitorEvent classify(TaskRunRecord r, MonitorEvent.Table table,
                                        Map<String, ScheduledTask> tasksById) {
        EventKind kind = EventKind.parse(r.getEventKind());
        if (kind == null) kind = infer(r, table);

        // A FAILED status always escalates to ERROR regardless of the kind's default —
        // e.g. a config change that failed to save, which defaults to INFO.
        EventKind.Severity sev = kind.defaultSeverity();
        if (r.getStatus() == TaskRunRecord.Status.FAILED && sev.compareTo(EventKind.Severity.ERROR) < 0) {
            sev = EventKind.Severity.ERROR;
        }

        ScheduledTask task = tasksById != null ? tasksById.get(r.getTaskId()) : null;
        String name = r.getTaskName() != null && !r.getTaskName().isBlank() ? r.getTaskName()
                : task != null ? task.getName() : "(unknown task)";

        return new MonitorEvent(table, r.getId(), r.getTaskId(), name, r.getTaskType(),
                task != null ? task.getTransferDirection() : null,
                kind, sev, r.getStatus(), r.getStartedAt(), r.getEndedAt(), r.getDurationMs(),
                cleanSummary(kind, r.getReason()), r.getTrigger(), blankToNull(r.getRunKey()),
                r.getFailureCategory(), r.isRetryable(), r.getSuggestedAction());
    }

    static EventKind infer(TaskRunRecord r, MonitorEvent.Table table) {
        if (table == MonitorEvent.Table.RUN) {
            return switch (r.getStatus()) {
                case SUCCESS -> EventKind.RUN_SUCCEEDED;
                case FAILED  -> EventKind.RUN_FAILED;
                case SKIPPED -> EventKind.RUN_SKIPPED;
            };
        }
        String reason = r.getReason() != null ? r.getReason() : "";
        if (reason.startsWith(STARTED_PREFIX))  return EventKind.RUN_STARTED;
        if (reason.startsWith(DETECTED_PREFIX)) return EventKind.FILES_DETECTED;
        String id = r.getTaskId() != null ? r.getTaskId() : "";
        if (id.equals("HEALTH")) return EventKind.HEALTH;
        if (reason.startsWith("Internal state save failed")) return EventKind.SYSTEM_ERROR;
        if (id.equals("SETTINGS") || id.equals("CREDENTIALS") || id.equals("TASKS")) {
            return r.getStatus() == TaskRunRecord.Status.FAILED ? EventKind.SYSTEM_ERROR : EventKind.CONFIG_CHANGE;
        }
        return r.getStatus() == TaskRunRecord.Status.FAILED ? EventKind.SYSTEM_ERROR : EventKind.CONFIG_CHANGE;
    }

    /** Drops the scheduler's own lead-in text where the kind column already says it. */
    static String cleanSummary(EventKind kind, String reason) {
        if (reason == null) return "";
        String s = reason.strip();
        if (kind == EventKind.RUN_STARTED && s.startsWith(STARTED_PREFIX)) {
            s = s.substring(STARTED_PREFIX.length()).strip();
            if (!s.isEmpty()) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        } else if (kind == EventKind.FILES_DETECTED && s.startsWith(DETECTED_PREFIX)) {
            s = s.substring(DETECTED_PREFIX.length()).strip();
        }
        // One line only — a multi-line reason would blow up the row height.
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl).strip() : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
