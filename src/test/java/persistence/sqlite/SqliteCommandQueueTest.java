package persistence.sqlite;

import persistence.CommandQueue;

import persistence.CommandQueue;
import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.*;
import java.util.List;

public class SqliteCommandQueueTest {

    private static Connection open(File dir) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "app.db").getAbsolutePath());
    }

    @Test
    public void freshCommandIsPolledAndStaleOneExpires() throws Exception {
        File dir = Files.createTempDirectory("opstool-q").toFile();
        SqliteCommandQueue queue = new SqliteCommandQueue(dir);
        queue.enqueue("fresh", CommandQueue.Action.RUN_NOW, "alice");
        queue.enqueue("stale", CommandQueue.Action.RUN_NOW, "bob");
        try (Connection c = open(dir); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE gui_commands SET requested_at='2020-01-01T00:00:00.000' WHERE task_id='stale'");
        }
        List<CommandQueue.Command> pending;
        try (CommandQueue.Poller poller = queue.poller()) { pending = poller.pollPending(); }
        assertEquals(1, pending.size());
        assertEquals("fresh", pending.get(0).taskId());
        try (Connection c = open(dir); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, detail FROM gui_commands WHERE task_id='stale'")) {
            assertTrue(rs.next());
            assertEquals("FAILED", rs.getString(1));
            assertTrue(rs.getString(2).startsWith("Expired"));
        }
    }
}
