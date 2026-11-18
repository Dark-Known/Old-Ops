package persistence;

import java.io.IOException;
import java.util.List;

/**
 * Small named settings (text) and the queue of alerts waiting for delivery, kept next to the tasks so the data
 * folder does not collect a loose file per setting. Technology-neutral: obtain one through {@link AppDbs}.
 */
public interface AppDb {

    /** An alert waiting to be delivered: its id, when it was queued, and its JSON text. */
    record QueuedAlert(long id, long queuedAt, String json) { }

    /** The stored text, or null when there is none. */
    String get(String name);

    void put(String name, String value) throws IOException;

    /** Changes whenever the value is rewritten; -1 when absent, -2 when the store cannot be read. */
    long stamp(String name);

    /** Adds an alert to the queue and returns its id. */
    long enqueueAlert(long queuedAt, String json) throws IOException;

    void deleteAlert(long id);

    /** Everything waiting, oldest first. */
    List<QueuedAlert> pendingAlerts();
}
