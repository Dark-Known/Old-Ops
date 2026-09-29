package service;

import model.Credential;
import model.ScheduledTask;

import java.io.File;
import java.util.List;

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

    /** Save (insert or replace, keyed by username) a credential. */
    public void saveCredential(Credential cred) {
        credentialDb.save(cred);
    }

    /** Delete the stored credential for the given username. */
    public void deleteCredential(String username) {
        credentialDb.delete(username);
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
        if (username == null || username.isEmpty()) return 0;
        int count = 0;
        for (ScheduledTask t : loadTasks()) {
            if (username.equals(t.getTargetUsername())
                    || username.equals(t.getBackupSourceUsername())
                    || username.equals(t.getBackupDestinationUsername())) {
                count++;
            }
        }
        return count;
    }

    // ─── Tasks (SQLite-backed — see TaskDbService) ──────────────────────────

    /** All stored tasks. */
    public List<ScheduledTask> loadTasks() {
        return taskDb.loadAll();
    }

    /** Inserts or replaces (by id) a task. Assigns an id if missing. */
    public void saveTask(ScheduledTask task) {
        taskDb.save(task);
    }

    /** Deletes the task with the given id, if any. */
    public void deleteTask(String id) {
        taskDb.delete(id);
    }
}
