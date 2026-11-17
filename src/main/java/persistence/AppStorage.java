package persistence;

import model.Credential;
import model.ScheduledTask;

import java.io.File;
import java.util.List;

/**
 * What the rest of the application sees of task and credential storage. The UI, scheduler and
 * transfer code depend on this interface only, so changing the database technology means
 * writing a new adapter under {@code persistence.<technology>} and changing one line where the
 * application is assembled (see {@code persistence.sqlite.SqliteStorage}); nothing else is touched.
 *
 * <p>Not a technology API: there are no connections, SQL, files or table names in here.
 */
public interface AppStorage {

    /**
     * The folder the application keeps its working files in (logs, command queue, status files...).
     * Still exposed because many non-database features write beside the database; those should
     * move behind their own ports over time, after which this can go.
     */
    File getDataDir();

    /** The sent-files memory and "no new files" tracking. */
    TransferLedger ledger();

    // ─── Health ──────────────────────────────────────────────────────────────

    /** True only if BOTH tasks and credentials are connected. */
    boolean isConnected();

    /** Actionable reason the store is not connected, or null when it is. */
    String getConnectionError();

    /** Reason the last task save failed although connected; null if it succeeded. */
    String getLastTaskSaveError();

    /** Same as {@link #getLastTaskSaveError()}, for credentials. */
    String getLastCredentialSaveError();

    // ─── Credentials ─────────────────────────────────────────────────────────

    Credential loadCredentialByName(String name);

    Credential loadCredentialById(String id);

    /** True when another credential already has this name (ignoring case). */
    boolean isCredentialNameTaken(String name, String exceptId);

    /**
     * Saves a credential. If an existing one was renamed, the tasks that use it are pointed at the
     * new name in the same step, and the rename is undone if that fails.
     */
    boolean saveCredential(Credential credential);

    boolean deleteCredential(String name);

    List<Credential> loadAllCredentials();

    /** How many tasks reference this credential name (transfer target, backup source or destination). */
    int countTasksUsingCredential(String name);

    /** Same count over an already-loaded list, for callers counting many credentials with one load. */
    static int countTasksUsingCredential(List<ScheduledTask> tasks, String name) {
        if (name == null || name.isEmpty()) return 0;
        int count = 0;
        for (ScheduledTask t : tasks) {
            if (name.equals(t.getTargetUsername())
                    || name.equals(t.getBackupSourceUsername())
                    || name.equals(t.getBackupDestinationUsername())) {
                count++;
            }
        }
        return count;
    }

    // ─── Tasks ───────────────────────────────────────────────────────────────

    /** All tasks, freshly read; callers get their own mutable objects. */
    List<ScheduledTask> loadTasks();

    /** Read-only, shared view of all tasks, reloaded only when they actually change. Do not mutate the results. */
    List<ScheduledTask> loadTasksSnapshot();

    boolean saveTask(ScheduledTask task);

    /** Saves only run-state fields; use from the scheduler and transfer code, which may hold stale tasks. */
    boolean saveTaskRunState(ScheduledTask task);

    boolean deleteTask(String id);
}
