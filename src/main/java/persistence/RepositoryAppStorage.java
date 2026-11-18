package persistence;

import model.Credential;
import model.ScheduledTask;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * {@link AppStorage} built from any {@link TaskRepository} and {@link CredentialRepository}.
 * Holds the rules that do not depend on a database: the credential-rename cascade over tasks,
 * usage counts, and the shared read-only task snapshot. Adapters only supply the repositories.
 */
public class RepositoryAppStorage implements AppStorage {

    /** Longest a cached task snapshot is trusted, should the change signal ever stall. */
    private static final long SNAPSHOT_MAX_AGE_MS = 30_000;

    private final File dataDir;
    private final TaskRepository tasks;
    private final CredentialRepository credentials;
    private final String connectionHint;
    private final LongSupplier tasksChangeToken;
    private final java.util.function.Supplier<TransferLedger> ledger;

    private final AtomicLong localEpoch = new AtomicLong();
    private volatile TaskSnapshot snapshot;

    private record TaskSnapshot(List<ScheduledTask> tasks, long changeToken, long epoch, long loadedAtMs) {}

    /**
     * @param dataDir          the application's working folder
     * @param tasks            where tasks live
     * @param credentials      where credentials live
     * @param connectionHint   appended to a connection error so it is actionable (e.g. the database path); may be null
     * @param tasksChangeToken a number that changes whenever <em>any</em> process changes the tasks; lets the
     *                         snapshot be reused until then. Return a constant to disable cross-process detection
     *                         (the snapshot then refreshes on local writes and after {@link #SNAPSHOT_MAX_AGE_MS}).
     */
    public RepositoryAppStorage(File dataDir, TaskRepository tasks, CredentialRepository credentials,
                                String connectionHint, LongSupplier tasksChangeToken) {
        this(dataDir, tasks, credentials, connectionHint, tasksChangeToken, () -> {
            throw new UnsupportedOperationException("no transfer ledger configured for this storage");
        });
    }

    /** As above, plus where the sent-files memory comes from (asked for on each use, so it may be created lazily). */
    public RepositoryAppStorage(File dataDir, TaskRepository tasks, CredentialRepository credentials,
                                String connectionHint, LongSupplier tasksChangeToken,
                                java.util.function.Supplier<TransferLedger> ledger) {
        this.ledger = ledger;
        this.dataDir = dataDir;
        this.tasks = tasks;
        this.credentials = credentials;
        this.connectionHint = connectionHint;
        this.tasksChangeToken = tasksChangeToken;
    }

    @Override public File getDataDir() { return dataDir; }

    @Override public TransferLedger ledger() { return ledger.get(); }

    // ─── Health ──────────────────────────────────────────────────────────────

    @Override
    public boolean isConnected() {
        return tasks.isConnected() && credentials.isConnected();
    }

    @Override
    public String getConnectionError() {
        if (isConnected()) return null;
        String reason = tasks.getConnectionError() != null
                ? tasks.getConnectionError()
                : credentials.getConnectionError();
        return (reason != null ? reason : "unknown error")
                + (connectionHint != null ? "  (" + connectionHint + ")" : "");
    }

    @Override public String getLastTaskSaveError()       { return tasks.getLastSaveError(); }
    @Override public String getLastCredentialSaveError() { return credentials.getLastSaveError(); }

    // ─── Credentials ─────────────────────────────────────────────────────────

    // The built-in "Local (this computer)" entry is always present, first in the list, and never stored,
    // renamed or deleted: a task that uses it works on this machine's folders instead of connecting to a server.

    @Override public Credential loadCredentialByName(String name) {
        return Credential.isLocalName(name) ? Credential.local() : credentials.loadByName(name);
    }
    @Override public Credential loadCredentialById(String id) {
        return Credential.LOCAL_ID.equals(id) ? Credential.local() : credentials.loadById(id);
    }
    @Override public boolean isCredentialNameTaken(String name, String exceptId) {
        return Credential.isLocalName(name) || credentials.isNameTaken(name, exceptId);
    }
    @Override public boolean deleteCredential(String name) {
        if (Credential.isLocalName(name)) {
            credentials.setLastSaveError("The built-in \"" + Credential.LOCAL_NAME + "\" entry cannot be deleted");
            return false;
        }
        return credentials.delete(name);
    }
    @Override public List<Credential> loadAllCredentials() {
        List<Credential> all = new java.util.ArrayList<>();
        all.add(Credential.local());
        for (Credential c : credentials.loadAll()) if (!c.isLocal()) all.add(c);
        return all;
    }

    @Override
    public synchronized boolean saveCredential(Credential cred) {
        if (cred.isLocal() || Credential.isLocalName(cred.getName())) {
            credentials.setLastSaveError("\"" + Credential.LOCAL_NAME + "\" is built in and cannot be changed or reused as a name");
            return false;
        }
        Credential before = cred.getId() == null ? null : credentials.loadById(cred.getId());
        String oldName = before != null ? before.getName() : null;
        if (!credentials.save(cred)) return false;
        if (oldName != null && !oldName.equals(cred.getName())) {
            if (tasks.renameCredentialReferences(oldName, cred.getName()) < 0) {
                String why = tasks.getLastSaveError();
                credentials.save(before);   // put the old name back: tasks still use it
                credentials.setLastSaveError("Could not update the tasks that use this credential"
                        + (why != null ? " (" + why + ")" : "") + "; the rename was not applied");
                return false;
            }
        }
        return true;
    }

    @Override
    public int countTasksUsingCredential(String name) {
        return AppStorage.countTasksUsingCredential(loadTasks(), name);
    }

    // ─── Tasks ───────────────────────────────────────────────────────────────

    @Override public List<ScheduledTask> loadTasks() { return tasks.loadAll(); }

    @Override
    public List<ScheduledTask> loadTasksSnapshot() {
        // Read the version stamps BEFORE loading: a write landing mid-load then bumps them and the
        // very next call reloads, instead of the stale list being stamped as current.
        long token = tasksChangeToken.getAsLong();
        long epoch = localEpoch.get();
        long now = System.currentTimeMillis();
        TaskSnapshot s = snapshot;
        if (s != null && s.changeToken == token && s.epoch == epoch && now - s.loadedAtMs < SNAPSHOT_MAX_AGE_MS) {
            return s.tasks;
        }
        List<ScheduledTask> fresh = Collections.unmodifiableList(tasks.loadAll());
        snapshot = new TaskSnapshot(fresh, token, epoch, now);
        return fresh;
    }

    @Override
    public boolean saveTask(ScheduledTask task) {
        localEpoch.incrementAndGet();
        try { return tasks.save(task); } finally { localEpoch.incrementAndGet(); }
    }

    @Override
    public boolean saveTaskRunState(ScheduledTask task) {
        localEpoch.incrementAndGet();
        try { return tasks.saveRunState(task); } finally { localEpoch.incrementAndGet(); }
    }

    @Override
    public boolean deleteTask(String id) {
        localEpoch.incrementAndGet();
        try { return tasks.delete(id); } finally { localEpoch.incrementAndGet(); }
    }
}
