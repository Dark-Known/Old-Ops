package persistence;

import model.EventKind;
import model.TaskRunRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Port: events raised by the application's own background threads (not by tasks). Kept apart from
 * {@link RunHistory} so it can be purged independently.
 */
public interface BackgroundLog {

    /** False when the store could not be opened; recording is then a no-op. */
    boolean isOpen();

    void addListener(Consumer<TaskRunRecord> listener);

    void removeListener(Consumer<TaskRunRecord> listener);

    /** Records one event and returns its id, or -1 if it was not stored. */
    long record(EventKind kind, String name, TaskRunRecord.Status status, String message, String details,
                LocalDateTime start, LocalDateTime end);

    /** Lightweight rows for list views, newest first. */
    List<TaskRunRecord> querySummaries(LocalDateTime from, int limit);

    TaskRunRecord getById(long id);

    long rowCount();

    /** An opaque value that changes whenever anything is recorded, by any process; compare for equality only. */
    String getChangeToken();

    long sizeBytes();

    /** Small key/value bookkeeping stored beside the log (e.g. when the last purge ran). */
    String meta(String key);

    void setMeta(String key, String value);

    PurgeResult purge(PurgePolicy policy, BooleanSupplier cancelled);

    void close();
}
