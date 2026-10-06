package ui.monitor;

import model.EventKind;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the operator has asked to see. Split into two stages on purpose:
 * <ul>
 *   <li><b>Event-level</b> ({@link #acceptsEvent}) — time range, category and
 *       severity. Applied before grouping, so a hidden debug event simply
 *       doesn't appear as a child of its run.</li>
 *   <li><b>Run-level</b> ({@link #acceptsGroup}) — free-text search and task.
 *       Applied to a whole run: searching for a file name finds the run that
 *       handled it and still shows that run's full timeline, rather than a
 *       lone orphaned "Files detected" row.</li>
 * </ul>
 */
public record EventFilter(String text, String taskId, Set<EventKind.Category> categories,
                          EventKind.Severity minSeverity, LocalDateTime since) {

    public static EventFilter none() {
        return new EventFilter("", null, EnumSet.allOf(EventKind.Category.class),
                EventKind.Severity.DEBUG, null);
    }

    public boolean acceptsEvent(MonitorEvent e) {
        if (since != null && e.time().isBefore(since)) return false;
        if (!categories.contains(e.kind().category())) return false;
        return e.severity().compareTo(minSeverity) >= 0;
    }

    /** Range + level only (ignores category) — used to compute the per-category chip counts. */
    public boolean acceptsIgnoringCategory(MonitorEvent e) {
        if (since != null && e.time().isBefore(since)) return false;
        return e.severity().compareTo(minSeverity) >= 0;
    }

    public boolean acceptsGroup(List<MonitorEvent> events) {
        if (taskId != null) {
            boolean any = false;
            for (MonitorEvent e : events) if (taskId.equals(e.taskId())) { any = true; break; }
            if (!any) return false;
        }
        String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        for (String token : q.split("\\s+")) {          // every word must match somewhere in the run
            boolean found = false;
            for (MonitorEvent e : events) {
                if (matches(e, token)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean matches(MonitorEvent e, String token) {
        return contains(e.taskName(), token) || contains(e.summary(), token)
                || contains(e.kind().label(), token) || contains(e.trigger(), token)
                || contains(e.taskType(), token) || contains(e.suggestedAction(), token)
                || (e.failureCategory() != null && e.failureCategory().name().toLowerCase(Locale.ROOT).contains(token))
                || contains(e.severity().label(), token);
    }

    private static boolean contains(String hay, String token) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(token);
    }

    public boolean isDefault() {
        return (text == null || text.isBlank()) && taskId == null
                && categories.size() == EventKind.Category.values().length;
    }

    public EventFilter withText(String t)                        { return new EventFilter(t, taskId, categories, minSeverity, since); }
    public EventFilter withTask(String id)                       { return new EventFilter(text, id, categories, minSeverity, since); }
    public EventFilter withCategories(Set<EventKind.Category> c) { return new EventFilter(text, taskId, c, minSeverity, since); }
    public EventFilter withMinSeverity(EventKind.Severity s)     { return new EventFilter(text, taskId, categories, s, since); }
    public EventFilter withSince(LocalDateTime s)                { return new EventFilter(text, taskId, categories, minSeverity, s); }
}
