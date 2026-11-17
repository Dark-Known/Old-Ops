package persistence;

import model.EventKind;
import model.TaskRunRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Port: the record of what tasks did. Two feeds share it: <em>runs</em> (real outcomes: success,
 * failure, skip) and <em>activity events</em> (worker start-up, detection scans, application notes).
 *
 * <p>Implementations are thread-safe. Listeners are called on whatever thread recorded the entry
 * (usually a scheduler worker, never the Swing thread) and only for entries recorded in the same
 * process; another process's writes are noticed by polling {@link #getChangeToken()}.
 *
 * <p>The current run's key and trigger come from {@link model.RunTrace}, not from a parameter.
 */
public interface RunHistory {

    // ─── Listeners ───────────────────────────────────────────────────────────

    /** Called after every recorded real outcome (success, failure or skip). */
    void addRunListener(Consumer<TaskRunRecord> listener);

    void removeRunListener(Consumer<TaskRunRecord> listener);

    /** Called after every recorded activity entry. Does not fire for runs. */
    void addActivityListener(Consumer<TaskRunRecord> listener);

    void removeActivityListener(Consumer<TaskRunRecord> listener);

    // ─── Recording ───────────────────────────────────────────────────────────

    /** Records one completed real task outcome. A safe no-op if the store failed to open. */
    void recordRun(String taskId, String taskName, String taskType,
                   TaskRunRecord.Status status, String reason, String details,
                   LocalDateTime startedAt, LocalDateTime endedAt);

    /** As above, reusing a failure classification the caller already worked out. */
    void recordRun(String taskId, String taskName, String taskType,
                   TaskRunRecord.Status status, String reason, String details,
                   LocalDateTime startedAt, LocalDateTime endedAt, TaskRunRecord preclassified);

    void recordActivityEvent(String taskId, String taskName, String taskType,
                             String message, String details, LocalDateTime startedAt, LocalDateTime endedAt);

    void recordActivityEvent(String taskId, String taskName, String taskType, TaskRunRecord.Status status,
                             String message, String details, LocalDateTime startedAt, LocalDateTime endedAt);

    /** Records a typed event (e.g. mail processed, no files) with an explicit run key and trigger. */
    void recordEvent(EventKind kind, String taskId, String taskName, String taskType, TaskRunRecord.Status status,
                     String message, String details, LocalDateTime startedAt, LocalDateTime endedAt,
                     String runKey, String trigger);

    // ─── Queries ─────────────────────────────────────────────────────────────

    List<TaskRunRecord> getRecentRuns(int limit);

    List<TaskRunRecord> getRunsForTask(String taskId, int limit);

    TaskRunRecord getRunById(long id);

    TaskRunRecord getActivityEventById(long id);

    List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status, int limit);

    List<TaskRunRecord> queryRuns(String taskId, TaskRunRecord.Status status,
                                  LocalDateTime from, LocalDateTime to, int limit);

    List<TaskRunRecord> queryActivityEvents(String taskId, LocalDateTime from, LocalDateTime to, int limit);

    /** Lightweight rows (no heavy detail text) for list views. */
    List<TaskRunRecord> queryRunSummaries(LocalDateTime from, int limit);

    List<TaskRunRecord> queryRunSummaries(TaskRunRecord.Status status, LocalDateTime from, int limit);

    List<TaskRunRecord> queryActivitySummaries(LocalDateTime from, int limit);

    /** An opaque value that changes whenever anything is recorded, by any process; compare for equality only. */
    String getChangeToken();

    // ─── Retention ───────────────────────────────────────────────────────────

    void pruneOlderThan(int keepDays);

    /** Removes the oldest rows until {@code policy} is met; stops early if {@code cancelled} turns true. */
    PurgeResult purge(PurgePolicy policy, BooleanSupplier cancelled);

    /** Current storage size in bytes, for display and size-based retention. */
    long sizeBytes();

    void close();
}
