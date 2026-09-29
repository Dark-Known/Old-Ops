package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.XmlStorageService;

import java.util.*;

/**
 * Pure, stateless logic for turning a list of {@link TaskRunRecord} failures
 * into the same "grouped by likely cause" view both {@link StatusStrip} (the
 * compact health bar, which only needs the counts/severity to build one
 * summary line) and {@link NotificationPanel} (the health feed, which
 * needs the full grouped detail to build one card per group) already agreed
 * on before this class existed. Extracted so the two can't quietly drift out
 * of sync with each other — same grouping key, same blocking/transient
 * split, same credential-impact sentence, computed once.
 */
final class IssueGrouping {

    private IssueGrouping() {}

    static final Set<TaskRunRecord.FailureCategory> BLOCKING_CATEGORIES = EnumSet.of(
            TaskRunRecord.FailureCategory.AUTH,
            TaskRunRecord.FailureCategory.PERMISSION,
            TaskRunRecord.FailureCategory.DISK_SPACE);

    static boolean isBlocking(TaskRunRecord r) {
        return r.getFailureCategory() != null && BLOCKING_CATEGORIES.contains(r.getFailureCategory());
    }

    /**
     * Groups failures by the most specific shared cause available: same
     * task name + same failure category. Sorted blocking-first, then by
     * occurrence count, so the thing most worth acting on is always first.
     */
    static Map<String, List<TaskRunRecord>> groupByLikelyCause(List<TaskRunRecord> failures) {
        Map<String, List<TaskRunRecord>> raw = new LinkedHashMap<>();
        for (TaskRunRecord r : failures) {
            String category = r.getFailureCategory() != null ? r.getFailureCategory().name() : "UNKNOWN";
            String key = r.getTaskName() + " (" + category + ")";
            raw.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<String, List<TaskRunRecord>> sorted = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted((a, b) -> {
                    boolean ablock = isBlocking(a.getValue().get(0));
                    boolean bblock = isBlocking(b.getValue().get(0));
                    if (ablock != bblock) return ablock ? -1 : 1;
                    return Integer.compare(b.getValue().size(), a.getValue().size());
                })
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * The most recent {@code endedAt} among a group's records — used to
     * place a group in the health feed's chronological order.
     */
    static java.time.LocalDateTime mostRecent(List<TaskRunRecord> records) {
        java.time.LocalDateTime latest = null;
        for (TaskRunRecord r : records) {
            if (r.getEndedAt() != null && (latest == null || r.getEndedAt().isAfter(latest))) {
                latest = r.getEndedAt();
            }
        }
        return latest;
    }

    /**
     * For AUTH failures specifically, looks up the failing task's credential
     * id(s) and counts other enabled tasks that reference the same
     * credential — a real, computed "this affects more than just this one
     * task" signal, not an invented file/item count.
     */
    static String describeCredentialImpact(XmlStorageService storage, TaskRunRecord example) {
        if (example.getFailureCategory() != TaskRunRecord.FailureCategory.AUTH || example.getTaskId() == null) return null;
        try {
            List<ScheduledTask> all = storage.loadTasks();
            ScheduledTask failing = all.stream().filter(t -> t.getId().equals(example.getTaskId())).findFirst().orElse(null);
            if (failing == null) return null;
            Set<String> credIds = new HashSet<>();
            if (failing.getSourceCredentialId() != null) credIds.add(failing.getSourceCredentialId());
            if (failing.getTargetCredentialId() != null) credIds.add(failing.getTargetCredentialId());
            if (credIds.isEmpty()) return null;
            long othersSharingCredential = all.stream()
                    .filter(t -> !t.getId().equals(failing.getId()))
                    .filter(t -> t.getStatus() != ScheduledTask.TaskStatus.DISABLED)
                    .filter(t -> credIds.contains(t.getSourceCredentialId()) || credIds.contains(t.getTargetCredentialId()))
                    .count();
            if (othersSharingCredential == 0) return null;
            return "Also used by " + othersSharingCredential + " other enabled task"
                    + (othersSharingCredential == 1 ? "" : "s") + " \u2014 fixing this credential likely fixes those too.";
        } catch (Exception e) {
            return null;
        }
    }
}
