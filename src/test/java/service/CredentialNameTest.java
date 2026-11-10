package service;

import model.Credential;
import model.ScheduledTask;
import model.ScheduledTask.TaskStatus;
import model.ScheduledTask.TaskType;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.*;

/**
 * Credentials are identified by a unique name, not by login username. Needs the sqlite-jdbc driver.
 */
public class CredentialNameTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("opstool-cred").toFile();
    }

    private static Credential cred(String name, String host, String user) {
        return new Credential(null, name, host, user, "pw-" + name, "LINUX");
    }

    private static ScheduledTask task(String id, String credentialName) {
        ScheduledTask t = new ScheduledTask();
        t.setId(id);
        t.setName("Task " + id);
        t.setTaskType(TaskType.FILE_TRANSFER);
        t.setStatus(TaskStatus.PENDING);
        t.setSourcePath("C:\\in");
        t.setTargetPath("/out");
        t.setTargetUsername(credentialName);
        return t;
    }

    @Test
    public void theSameUsernameCanExistOnTwoServers() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        assertTrue(db.save(cred("Server A admin", "10.0.0.1", "administrator")));
        assertTrue("same login, other server, must not replace the first", db.save(cred("Server B admin", "10.0.0.2", "administrator")));
        assertEquals(2, db.loadAll().size());
        assertEquals("10.0.0.1", db.loadByName("Server A admin").getHost());
        assertEquals("10.0.0.2", db.loadByName("Server B admin").getHost());
        assertEquals("pw-Server A admin", db.loadByName("Server A admin").getPassword());
    }

    @Test
    public void aNameCanOnlyBeUsedOnce_ignoringCase() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        assertTrue(db.save(cred("Prod", "h1", "u")));
        assertFalse(db.save(cred("prod", "h2", "u2")));
        assertTrue(db.getLastSaveError().contains("already exists"));
        assertEquals(1, db.loadAll().size());
        assertTrue(db.isNameTaken("PROD", null));
        assertFalse(db.isNameTaken("PROD", db.loadByName("Prod").getId()));
    }

    @Test
    public void aBlankNameDefaultsToUserAtHost() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        Credential c = cred("  ", "10.0.0.5", "svc");
        assertTrue(db.save(c));
        assertEquals("svc@10.0.0.5", db.loadAll().get(0).getName());
    }

    @Test
    public void lookupFindsTheExactNameFirstThenIgnoresCase() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        db.save(cred("Server A", "h", "u"));
        assertNotNull(db.loadByName("Server A"));
        assertNotNull(db.loadByName("server a"));
        assertNull(db.loadByName("Server B"));
        assertNull(db.loadByName(""));
        assertNull(db.loadByName(null));
    }

    @Test
    public void saveUpdatesByIdSoACredentialCanBeRenamed() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        Credential c = cred("Old", "h", "u");
        db.save(c);
        c.setName("New");
        c.setUsername("other");
        assertTrue(db.save(c));
        assertEquals(1, db.loadAll().size());
        assertNull(db.loadByName("Old"));
        assertEquals("other", db.loadByName("New").getUsername());
    }

    @Test
    public void deleteIsByName() throws Exception {
        CredentialDbService db = new CredentialDbService(tempDir());
        db.save(cred("A", "h1", "same"));
        db.save(cred("B", "h2", "same"));
        assertTrue(db.delete("A"));
        assertNull(db.loadByName("A"));
        assertTrue("the other server credential is untouched", db.loadByName("B") != null);
    }

    @Test
    public void anOldDatabaseIsUpgradedAndExistingCredentialsKeepWorkingForTheirTasks() throws Exception {
        File dir = tempDir();
        // The previous schema: username was the unique key, name was the auto-generated "user@host" label.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "app.db").getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE credentials (id TEXT PRIMARY KEY, name TEXT, host TEXT, username TEXT UNIQUE NOT NULL, password TEXT, os_type TEXT)");
            st.execute("INSERT INTO credentials VALUES ('1','svc@10.0.0.1','10.0.0.1','svc','plain-secret','LINUX')");
            st.execute("INSERT INTO credentials VALUES ('2','Administrator@win01','win01','Administrator','pw2','WINDOWS')");
        }
        CredentialDbService db = new CredentialDbService(dir);
        assertEquals(2, db.loadAll().size());
        Credential svc = db.loadByName("svc");           // tasks stored "svc" as their credential
        assertNotNull(svc);
        assertEquals("10.0.0.1", svc.getHost());
        assertEquals("plain-secret", svc.getPassword());
        assertEquals("svc", svc.getUsername());
        assertEquals("win01", db.loadByName("Administrator").getHost());
        assertTrue("and now a second 'svc' on another server is possible", db.save(cred("svc on server 2", "10.0.0.9", "svc")));
        db.close();

        CredentialDbService again = new CredentialDbService(dir);   // a second start must not migrate again or lose anything
        assertEquals(3, again.loadAll().size());
    }

    @Test
    public void renamingACredentialPointsItsTasksAtTheNewName() throws Exception {
        XmlStorageService storage = new XmlStorageService(tempDir().getAbsolutePath());
        Credential a = cred("Server A admin", "h1", "administrator");
        Credential b = cred("Server B admin", "h2", "administrator");
        assertTrue(storage.saveCredential(a));
        assertTrue(storage.saveCredential(b));
        ScheduledTask onA = task("t1", "Server A admin");
        ScheduledTask onB = task("t2", "Server B admin");
        ScheduledTask backup = task("t3", "");
        backup.setTaskType(TaskType.BACKUP);
        backup.setBackupSourceUsername("Server A admin");
        backup.setBackupDestinationUsername("Server B admin");
        for (ScheduledTask t : java.util.List.of(onA, onB, backup)) assertTrue(storage.saveTask(t));

        assertEquals(2, storage.countTasksUsingCredential("Server A admin"));
        a.setName("Orders server");
        assertTrue(storage.saveCredential(a));

        assertEquals(0, storage.countTasksUsingCredential("Server A admin"));
        assertEquals(2, storage.countTasksUsingCredential("Orders server"));
        assertEquals("tasks of the other credential are untouched", 2, storage.countTasksUsingCredential("Server B admin"));
        for (ScheduledTask t : storage.loadTasks()) {
            if (t.getId().equals("t1")) assertEquals("Orders server", t.getTargetUsername());
            if (t.getId().equals("t2")) assertEquals("Server B admin", t.getTargetUsername());
            if (t.getId().equals("t3")) {
                assertEquals("Orders server", t.getBackupSourceUsername());
                assertEquals("Server B admin", t.getBackupDestinationUsername());
            }
        }
    }

    @Test
    public void renamingToAnExistingNameIsRefusedAndChangesNothing() throws Exception {
        XmlStorageService storage = new XmlStorageService(tempDir().getAbsolutePath());
        Credential a = cred("A", "h1", "u");
        Credential b = cred("B", "h2", "u");
        storage.saveCredential(a);
        storage.saveCredential(b);
        assertTrue(storage.saveTask(task("t1", "A")));
        b.setName("a");
        assertFalse(storage.saveCredential(b));
        assertNotNull(storage.loadCredentialByName("B"));
        assertEquals(1, storage.countTasksUsingCredential("A"));
    }
}
