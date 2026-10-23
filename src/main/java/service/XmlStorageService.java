package service;

import model.Credential;
import model.ScheduledTask;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin facade in front of the app's task and credential storage.
 *
 * <p>Despite the class name (kept for backward compatibility with the many
 * callers already holding a reference to it), nothing here reads or writes
 * XML directly anymore — both tasks and credentials live in SQLite, in the
 * shared {@code <dataDir>/app.db} file:
 * <ul>
 *   <li>Tasks — see {@link TaskDbService} ({@code tasks} table). Used to be
 *       {@code <dataDir>/tasks.xml}, one hand-rolled temp-file-then-rename
 *       write per save; that legacy file, if found, is imported once and
 *       renamed to {@code tasks.xml.migrated} (see
 *       {@code TaskDbService}'s constructor).</li>
 *   <li>Credentials — see {@link CredentialDbService} ({@code credentials}
 *       table). Used to be one {@code creds_<username>.xml} file per user;
 *       same one-time, non-destructive migration pattern.</li>
 * </ul>
 * Both service classes handle their own legacy-file migration independently
 * the first time this app.db is opened for a given data directory — this
 * class exists only so existing callers (UI panels, the scheduler, etc.)
 * keep one place to go for either kind of data, same as before the switch
 * to SQLite.
 */
public class XmlStorageService {

    private final File dataDir;
    private final TaskDbService taskDb;
    private final CredentialDbService credentialDb;

    public XmlStorageService(String dataDirPath) {
        this.dataDir = new File(dataDirPath);
        this.dataDir.mkdirs();
        this.taskDb = new TaskDbService(this.dataDir);
        this.credentialDb = new CredentialDbService(this.dataDir);
    }

    public File getDataDir() {
        return dataDir;
    }

    /**
     * True only if BOTH the tasks and credentials tables in app.db opened
     * successfully. Previously a failed connection was invisible: loadTasks()
     * quietly returned an empty list and saveTask() quietly did nothing, so
     * "no data in the UI" and "can't create a task" looked identical to an
     * empty-but-healthy install. Check this at startup (see MainWindow) and
     * whenever a save unexpectedly appears to do nothing.
     */
    public boolean isConnected() {
        return taskDb.isConnected() && credentialDb.isConnected();
    }

    /**
     * Human-readable reason the database isn't connected, or null if it is.
     * Combines whichever of the two tables failed (usually both, since they
     * share one connection to the same app.db file) with the exact file path
     * that was attempted, so the message is actionable without opening a log.
     */
    public String getConnectionError() {
        if (isConnected()) return null;
        String reason = taskDb.getConnectionError() != null
                ? taskDb.getConnectionError()
                : credentialDb.getConnectionError();
        String path = new File(dataDir, "app.db").getAbsolutePath();
        return (reason != null ? reason : "unknown error") + "  (" + path + ")";
    }

    /**
     * Short reason the most recent {@link #saveTask} call failed even though
     * the database IS connected — a transient write error (lock contention
     * with the Daemon, a constraint violation, disk full), not a dropped
     * connection. Null if the last save succeeded or none was attempted.
     * Callers should check {@link #isConnected()} first: if that's false the
     * real story is {@link #getConnectionError()} instead.
     */
    public String getLastTaskSaveError() {
        return taskDb.getLastSaveError();
    }

    /** Same as {@link #getLastTaskSaveError()}, for {@link #saveCredential}. */
    public String getLastCredentialSaveError() {
        return credentialDb.getLastSaveError();
    }

    // ─── Credentials (SQLite-backed — see CredentialDbService) ──────────────

    /** Returns the legacy creds_<username>.xml path. Retained only so old migration/cleanup tooling can find it; credentials themselves now live in app.db. */
    public File credFileForUser(String username) {
        // Sanitise username so it is safe as a filename
        String safe = username.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return new File(dataDir, "creds_" + safe + ".xml");
    }

    /** Look up credentials by username. */
    public Credential loadCredentialByUsername(String username) {
        return credentialDb.loadByUsername(username);
    }

    /** Save (insert or replace, keyed by username) a credential. Returns
     *  whether it was actually persisted — false means app.db isn't
     *  connected; see {@link #getConnectionError()}. */
    public boolean saveCredential(Credential cred) {
        return credentialDb.save(cred);
    }

    /** Delete the stored credential for the given username. Returns whether
     *  it was actually removed — false means app.db isn't connected or the
     *  delete failed; see {@link #isConnected()} / {@link #getLastCredentialSaveError()}. */
    public boolean deleteCredential(String username) {
        return credentialDb.delete(username);
    }

    /** List every credential stored. */
    public List<Credential> loadAllCredentials() {
        return credentialDb.loadAll();
    }

    /**
     * Counts how many tasks currently reference this username as a
     * File Transfer target, a Backup source, or a Backup destination
     * credential — the three places a task can point at a saved
     * credential by username (see ScheduledTask#targetUsername,
     * #backupSourceUsername, #backupDestinationUsername). Used by the
     * Credential Manager to show an in-use count and warn before deleting
     * a credential something still depends on.
     */
    public int countTasksUsingCredential(String username) {
        return countTasksUsingCredential(loadTasks(), username);
    }

    /** Same count over an already-loaded task list — lets callers that count many
     *  credentials load the task table once instead of once per credential. */
    public static int countTasksUsingCredential(List<ScheduledTask> tasks, String username) {
        if (username == null || username.isEmpty()) return 0;
        int count = 0;
        for (ScheduledTask t : tasks) {
            if (username.equals(t.getTargetUsername())
                    || username.equals(t.getBackupSourceUsername())
                    || username.equals(t.getBackupDestinationUsername())) {
                count++;
            }
        }
        return count;
    }

    // ─── Tasks (SQLite-backed — see TaskDbService) ──────────────────────────

    /** All stored tasks, freshly read. Callers get their own mutable objects - use this when you
     *  intend to edit and {@link #saveTask} the result. Display-only code should prefer
     *  {@link #loadTasksSnapshot()}. */
    public List<ScheduledTask> loadTasks() {
        return taskDb.loadAll();
    }

    // Snapshot cache for read-only consumers (status bar, watcher monitor, event monitor, feeds).
    // Valid while neither this process (localEpoch) nor any other (monitor TASKS generation) has
    // touched the tasks table; the age cap is a safety net should the change signal ever stall.
    private static final long SNAPSHOT_MAX_AGE_MS = 30_000;
    private final AtomicLong localEpoch = new AtomicLong();
    private volatile TaskSnapshot snapshot;

    private record TaskSnapshot(List<ScheduledTask> tasks, long monitorGen, long epoch, long loadedAtMs) {}

    /**
     * Read-only view of all tasks, shared by every caller until the table actually changes.
     * The list is unmodifiable and its {@link ScheduledTask} objects are shared - <b>do not mutate
     * them</b>; use {@link #loadTasks()} for anything you will edit and save.
     */
    public List<ScheduledTask> loadTasksSnapshot() {
        DbChangeMonitor monitor = DbChangeMonitor.forDataDir(dataDir);
        // Read the version stamps BEFORE loading: a write landing mid-load then bumps them and the
        // very next call reloads, instead of the stale list being stamped as current.
        long gen = monitor.generation(DbChangeMonitor.Topic.TASKS);
        long epoch = localEpoch.get();
        long now = System.currentTimeMillis();
        TaskSnapshot s = snapshot;
        if (s != null && s.monitorGen == gen && s.epoch == epoch && now - s.loadedAtMs < SNAPSHOT_MAX_AGE_MS) {
            return s.tasks;
        }
        List<ScheduledTask> fresh = java.util.Collections.unmodifiableList(taskDb.loadAll());
        snapshot = new TaskSnapshot(fresh, gen, epoch, now);
        return fresh;
    }

    /** Inserts or replaces (by id) a task. Assigns an id if missing. Returns
     *  whether it was actually persisted — false means app.db isn't
     *  connected; see {@link #getConnectionError()}. */
    public boolean saveTask(ScheduledTask task) {
        localEpoch.incrementAndGet();
        try { return taskDb.save(task); } finally { localEpoch.incrementAndGet(); }
    }

    /** Deletes the task with the given id, if any. Returns whether it was
     *  actually removed — false means app.db isn't connected or the delete
     *  failed; see {@link #isConnected()} / {@link #getLastTaskSaveError()}. */
    public boolean deleteTask(String id) {
        localEpoch.incrementAndGet();
        try { return taskDb.delete(id); } finally { localEpoch.incrementAndGet(); }
    }
}
