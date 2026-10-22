package service;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

/** Needs the sqlite-jdbc driver on the classpath (it is a normal dependency of the project). */
public class TaskDbMigrationTest {

    private static Set<String> columns(File dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "app.db").getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(tasks)")) {
            Set<String> s = new HashSet<>();
            while (rs.next()) s.add(rs.getString("name"));
            return s;
        }
    }

    @Test
    public void oldTableMissingNewerColumnsIsUpgradedAndKeepsItsRows() throws Exception {
        File dir = Files.createTempDirectory("opstool-mig").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "app.db").getAbsolutePath());
             Statement st = c.createStatement()) {
            // an install from an older build: only a few columns
            st.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, name TEXT, task_type TEXT, status TEXT)");
            st.execute("INSERT INTO tasks (id, name, task_type, status) VALUES ('t1','Old task','FILE_TRANSFER','PENDING')");
        }
        assertFalse(columns(dir).contains("retry_count"));

        TaskDbService svc = new TaskDbService(dir);
        assertTrue(svc.isConnected());
        assertNull(svc.getConnectionError());

        Set<String> cols = columns(dir);
        for (String expected : new String[]{"retry_count", "mail_client_id", "watcher_enabled", "last_known_remote_file_size", "created_at"}) {
            assertTrue("missing " + expected, cols.contains(expected));
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "app.db").getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM tasks WHERE id='t1'")) {
            assertTrue(rs.next());
            assertEquals("Old task", rs.getString(1));
        }
        assertEquals("old row is loadable through the service", 1, svc.loadAll().size());
        svc.close();

        // second start is a no-op
        TaskDbService again = new TaskDbService(dir);
        assertNull(again.getConnectionError());
        assertEquals(1, again.loadAll().size());
        again.close();
    }

    @Test
    public void freshDatabaseGetsAllColumns() throws Exception {
        File dir = Files.createTempDirectory("opstool-mig").toFile();
        TaskDbService svc = new TaskDbService(dir);
        assertNull(svc.getConnectionError());
        assertTrue(columns(dir).size() >= 45);
        svc.close();
    }
}
