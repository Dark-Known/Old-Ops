package persistence;

import static org.junit.Assert.*;

import model.Credential;
import model.ScheduledTask;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests the storage rules with in-memory repositories: no database, no files. That this is
 * possible at all is the point of the {@link TaskRepository}/{@link CredentialRepository} ports.
 */
public class RepositoryAppStorageTest {

    // ── in-memory fakes ──────────────────────────────────────────────────────

    static class FakeTasks implements TaskRepository {
        final List<ScheduledTask> rows = new ArrayList<>();
        boolean connected = true;
        String connectionError;
        String lastSaveError;
        boolean failRename;
        final AtomicInteger loads = new AtomicInteger();

        public boolean isConnected() { return connected; }
        public String getConnectionError() { return connectionError; }
        public String getLastSaveError() { return lastSaveError; }
        public boolean save(ScheduledTask t) { rows.removeIf(r -> r.getId().equals(t.getId())); rows.add(t); return true; }
        public boolean saveRunState(ScheduledTask t) { return save(t); }
        public boolean delete(String id) { return rows.removeIf(r -> r.getId().equals(id)); }
        public List<ScheduledTask> loadAll() { loads.incrementAndGet(); return new ArrayList<>(rows); }
        public int renameCredentialReferences(String o, String n) {
            if (failRename) { lastSaveError = "disk full"; return -1; }
            int c = 0;
            for (ScheduledTask t : rows) if (o.equals(t.getTargetUsername())) { t.setTargetUsername(n); c++; }
            return c;
        }
        public void close() {}
    }

    static class FakeCreds implements CredentialRepository {
        final List<Credential> rows = new ArrayList<>();
        boolean connected = true;
        String connectionError;
        String lastSaveError;

        public boolean isConnected() { return connected; }
        public String getConnectionError() { return connectionError; }
        public String getLastSaveError() { return lastSaveError; }
        public void setLastSaveError(String m) { lastSaveError = m; }
        public Credential loadByName(String n) { return rows.stream().filter(c -> n.equals(c.getName())).findFirst().orElse(null); }
        public Credential loadById(String id) { return rows.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null); }
        public boolean isNameTaken(String n, String except) {
            return rows.stream().anyMatch(c -> c.getName().equalsIgnoreCase(n) && !c.getId().equals(except));
        }
        public boolean save(Credential c) {
            Credential existing = loadById(c.getId());
            if (existing != null) rows.remove(existing);
            // store a copy so later edits to the caller's object do not leak into the "database"
            Credential copy = new Credential();
            copy.setId(c.getId()); copy.setName(c.getName());
            rows.add(copy);
            return true;
        }
        public boolean delete(String n) { return rows.removeIf(c -> n.equals(c.getName())); }
        public List<Credential> loadAll() { return new ArrayList<>(rows); }
        public void close() {}
    }

    private FakeTasks tasks;
    private FakeCreds creds;
    private AtomicLong changeToken;
    private RepositoryAppStorage storage;

    @Before public void setUp() {
        tasks = new FakeTasks();
        creds = new FakeCreds();
        changeToken = new AtomicLong();
        storage = new RepositoryAppStorage(new File("data"), tasks, creds, "db.file", changeToken::get);
    }

    private static ScheduledTask task(String id, String credentialName) {
        ScheduledTask t = new ScheduledTask();
        t.setId(id);
        t.setTargetUsername(credentialName);
        return t;
    }

    private static Credential credential(String id, String name) {
        Credential c = new Credential();
        c.setId(id);
        c.setName(name);
        return c;
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Test public void connectedOnlyWhenBothRepositoriesAre() {
        assertTrue(storage.isConnected());
        assertNull(storage.getConnectionError());
        creds.connected = false;
        assertFalse(storage.isConnected());
    }

    @Test public void aConnectionErrorNamesTheReasonAndWhereToLook() {
        tasks.connected = false;
        tasks.connectionError = "no such file";
        assertEquals("no such file  (db.file)", storage.getConnectionError());
    }

    @Test public void theCredentialReasonIsUsedWhenTasksGiveNone() {
        creds.connected = false;
        creds.connectionError = "locked";
        assertEquals("locked  (db.file)", storage.getConnectionError());
    }

    @Test public void anUnexplainedDisconnectStillGetsAMessage() {
        tasks.connected = false;
        assertEquals("unknown error  (db.file)", storage.getConnectionError());
    }

    // ── credential rename cascade ────────────────────────────────────────────

    @Test public void renamingACredentialRepointsTheTasksThatUseIt() {
        creds.save(credential("c1", "old"));
        tasks.save(task("t1", "old"));
        tasks.save(task("t2", "other"));

        assertTrue(storage.saveCredential(credential("c1", "new")));

        assertEquals("new", tasks.rows.get(0).getTargetUsername());
        assertEquals("other", tasks.rows.get(1).getTargetUsername());
        assertEquals("new", creds.loadById("c1").getName());
    }

    @Test public void aFailedCascadePutsTheOldNameBackAndExplainsWhy() {
        creds.save(credential("c1", "old"));
        tasks.save(task("t1", "old"));
        tasks.failRename = true;

        assertFalse(storage.saveCredential(credential("c1", "new")));

        assertEquals("the credential keeps its old name", "old", creds.loadById("c1").getName());
        assertEquals("old", tasks.rows.get(0).getTargetUsername());
        assertEquals("Could not update the tasks that use this credential (disk full); the rename was not applied",
                storage.getLastCredentialSaveError());
    }

    @Test public void savingWithoutRenamingDoesNotTouchTasks() {
        creds.save(credential("c1", "same"));
        tasks.failRename = true; // would blow up if the cascade ran
        assertTrue(storage.saveCredential(credential("c1", "same")));
    }

    @Test public void aBrandNewCredentialNeedsNoCascade() {
        tasks.failRename = true;
        assertTrue(storage.saveCredential(credential("c9", "fresh")));
        assertNotNull(creds.loadById("c9"));
    }

    @Test public void aFailedCredentialSaveIsReportedAndNothingCascades() {
        CredentialRepository refusing = new FakeCreds() { @Override public boolean save(Credential c) { return false; } };
        FakeTasks t = new FakeTasks();
        RepositoryAppStorage s = new RepositoryAppStorage(new File("d"), t, refusing, null, () -> 0);
        assertFalse(s.saveCredential(credential("c1", "x")));
    }

    // ── usage counts ─────────────────────────────────────────────────────────

    @Test public void countsTasksByTargetSourceAndDestinationCredential() {
        ScheduledTask a = task("a", "cred");
        ScheduledTask b = task("b", null); b.setBackupSourceUsername("cred");
        ScheduledTask c = task("c", null); c.setBackupDestinationUsername("cred");
        ScheduledTask d = task("d", "someone-else");
        List<ScheduledTask> all = List.of(a, b, c, d);
        assertEquals(3, AppStorage.countTasksUsingCredential(all, "cred"));
    }

    @Test public void aBlankNameCountsNothing() {
        assertEquals(0, AppStorage.countTasksUsingCredential(List.of(task("a", "")), ""));
        assertEquals(0, AppStorage.countTasksUsingCredential(List.of(task("a", "x")), null));
    }

    @Test public void theInstanceCountReadsFromTheRepository() {
        tasks.save(task("a", "cred"));
        tasks.save(task("b", "cred"));
        assertEquals(2, storage.countTasksUsingCredential("cred"));
    }

    // ── task snapshot ────────────────────────────────────────────────────────

    @Test public void theSnapshotIsReusedUntilSomethingChanges() {
        tasks.save(task("a", null));
        List<ScheduledTask> first = storage.loadTasksSnapshot();
        List<ScheduledTask> second = storage.loadTasksSnapshot();
        assertSame(first, second);
        assertEquals(1, tasks.loads.get());
    }

    @Test public void aLocalWriteRefreshesTheSnapshot() {
        storage.loadTasksSnapshot();
        storage.saveTask(task("a", null));
        List<ScheduledTask> after = storage.loadTasksSnapshot();
        assertEquals(1, after.size());
        assertEquals(2, tasks.loads.get());
    }

    @Test public void runStateAndDeleteWritesAlsoRefreshTheSnapshot() {
        tasks.save(task("a", null));
        storage.loadTasksSnapshot();
        storage.saveTaskRunState(task("a", null));
        storage.loadTasksSnapshot();
        assertEquals("run-state write invalidates", 2, tasks.loads.get());
        assertTrue(storage.deleteTask("a"));
        assertTrue(storage.loadTasksSnapshot().isEmpty());
        assertEquals("delete invalidates", 3, tasks.loads.get());
    }

    @Test public void anotherProcessChangingTheTasksRefreshesTheSnapshot() {
        storage.loadTasksSnapshot();
        changeToken.incrementAndGet();      // e.g. the daemon wrote to the tasks table
        storage.loadTasksSnapshot();
        assertEquals(2, tasks.loads.get());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void theSnapshotCannotBeModifiedByCallers() {
        storage.loadTasksSnapshot().add(task("x", null));
    }

    @Test public void loadTasksAlwaysReadsFreshAndHandsOutAnOwnedList() {
        tasks.save(task("a", null));
        List<ScheduledTask> one = storage.loadTasks();
        List<ScheduledTask> two = storage.loadTasks();
        assertNotSame(one, two);
        assertEquals(2, tasks.loads.get());
    }

    @Test public void theDataDirIsWhatWasGiven() {
        assertEquals(new File("data"), storage.getDataDir());
    }

    // ── built-in Local credential ────────────────────────────────────────────

    @Test public void theLocalCredentialIsAlwaysListedFirst() {
        creds.rows.add(credential("c1", "Prod"));
        List<Credential> all = storage.loadAllCredentials();
        assertTrue(all.get(0).isLocal());
        assertEquals(2, all.size());
        assertTrue(storage.loadCredentialByName(Credential.LOCAL_NAME).isLocal());
        assertTrue(storage.loadCredentialById(Credential.LOCAL_ID).isLocal());
    }

    @Test public void theLocalCredentialCannotBeDeletedOrOverwritten() {
        assertFalse(storage.deleteCredential(Credential.LOCAL_NAME));
        assertFalse(storage.saveCredential(credential("x", "local (this computer)")));
        assertTrue(creds.rows.isEmpty());
        assertTrue(storage.loadAllCredentials().get(0).isLocal());
    }
}
