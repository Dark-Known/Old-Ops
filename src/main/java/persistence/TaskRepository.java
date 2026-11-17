package persistence;

import model.ScheduledTask;

import java.util.List;

/**
 * Port: where scheduled tasks are stored. Nothing outside {@code persistence.<technology>}
 * may know what is behind it (SQLite today; SQL Server, PostgreSQL, a REST service... tomorrow).
 *
 * <p>Implementations must be safe to call from several threads.
 */
public interface TaskRepository {

    /** True while the store is open and usable right now (checked live, not from a startup snapshot). */
    boolean isConnected();

    /** Why the store could not be opened, or null when it is connected. */
    String getConnectionError();

    /** Short reason the last {@link #save}/{@link #saveRunState}/{@link #delete} failed although connected; null if it succeeded. */
    String getLastSaveError();

    /** Inserts or replaces (by id) a task, assigning an id if missing. False if it was not persisted. */
    boolean save(ScheduledTask task);

    /** Writes only the run-state fields of a task (status, times, result, retries, markers), never its settings. */
    boolean saveRunState(ScheduledTask task);

    /** Removes the task with this id; false if nothing was removed. */
    boolean delete(String id);

    /** Every stored task, freshly read; callers own the returned objects. */
    List<ScheduledTask> loadAll();

    /** Points every task that uses credential {@code oldName} at {@code newName}. Returns how many changed, or -1 on failure. */
    int renameCredentialReferences(String oldName, String newName);

    /** Releases the underlying resources. */
    void close();
}
