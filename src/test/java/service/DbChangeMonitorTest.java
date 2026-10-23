package service;

import static org.junit.Assert.*;

import model.ScheduledTask;
import model.TaskRunRecord;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/** Covers the v1 event-driven plumbing: cross-connection change signals, the task snapshot cache and the command Poller. */
public class DbChangeMonitorTest {

    private File dir;
    private XmlStorageService storage;
    private DbChangeMonitor monitor;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("dbchange").toFile();
        storage = new XmlStorageService(dir.getAbsolutePath());
        monitor = DbChangeMonitor.forDataDir(dir);
        Thread.sleep(1200); // let the monitor take its baseline reading
    }

    private Connection raw(String file) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, file).getAbsolutePath());
        try (Statement st = c.createStatement()) { st.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }

    private static ScheduledTask task(String name) {
        ScheduledTask t = new ScheduledTask();
        t.setName(name);
        t.setTaskType(ScheduledTask.TaskType.FILE_TRANSFER);
        t.setStatus(ScheduledTask.TaskStatus.PENDING);
        return t;
    }

    @Test
    public void snapshotIsReusedUntilTasksChange() {
        assertSame(storage.loadTasksSnapshot(), storage.loadTasksSnapshot());
    }

    @Test
    public void ownSaveInvalidatesSnapshotImmediately() {
        List<ScheduledTask> before = storage.loadTasksSnapshot();
        assertTrue(storage.saveTask(task("probe")));
        List<ScheduledTask> after = storage.loadTasksSnapshot();
        assertNotSame(before, after);
        assertEquals(1, after.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void snapshotIsReadOnly() {
        storage.loadTasksSnapshot().add(new ScheduledTask());
    }

    @Test
    public void foreignTasksWriteIsSeenThroughTrigger() throws Exception {
        assertTrue(storage.saveTask(task("probe")));
        storage.loadTasksSnapshot();
        long g0 = monitor.generation(DbChangeMonitor.Topic.TASKS);
        try (Connection c = raw("app.db"); Statement st = c.createStatement()) {
            st.execute("UPDATE tasks SET name='changed-elsewhere'"); // stands in for the other JVM
        }
        assertNotEquals(g0, monitor.awaitChange(DbChangeMonitor.Topic.TASKS, g0, 5000));
        assertEquals("changed-elsewhere", storage.loadTasksSnapshot().get(0).getName());
    }

    @Test
    public void commandTrafficWakesAppButNotTasks() throws Exception {
        long tasks0 = monitor.generation(DbChangeMonitor.Topic.TASKS);
        long app0 = monitor.generation(DbChangeMonitor.Topic.APP);
        CommandQueueService.enqueue(dir, "x", CommandQueueService.Action.REFRESH, "test");
        assertNotEquals(app0, monitor.awaitChange(DbChangeMonitor.Topic.APP, app0, 5000));
        Thread.sleep(800);
        assertEquals(tasks0, monitor.generation(DbChangeMonitor.Topic.TASKS));
    }

    @Test
    public void pollerDrainsQueueOnOneConnection() throws Exception {
        CommandQueueService.enqueue(dir, "x", CommandQueueService.Action.REFRESH, "test");
        try (CommandQueueService.Poller p = new CommandQueueService.Poller(dir)) {
            List<CommandQueueService.Command> cmds = p.pollPending();
            assertEquals(1, cmds.size());
            assertEquals(CommandQueueService.Action.REFRESH, cmds.get(0).action());
            p.markDone(cmds.get(0).id());
            assertTrue(p.pollPending().isEmpty());
        }
    }

    @Test
    public void runHistoryIsWalAndSignalsRuns() throws Exception {
        RunHistoryService rhs = new RunHistoryService(dir.getAbsolutePath());
        try (Connection c = raw("run_history.db"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
        Thread.sleep(800); // monitor picks up the file and baselines it
        long r0 = monitor.generation(DbChangeMonitor.Topic.RUNS);
        rhs.recordRun("t1", "probe", "FILE_TRANSFER", TaskRunRecord.Status.SUCCESS, "ok", "details",
                LocalDateTime.now().minusSeconds(2), LocalDateTime.now());
        assertNotEquals(r0, monitor.awaitChange(DbChangeMonitor.Topic.RUNS, r0, 5000));
    }

    @Test
    public void idleDatabaseProducesNoSignals() throws Exception {
        long a = monitor.generation(DbChangeMonitor.Topic.APP) + monitor.generation(DbChangeMonitor.Topic.TASKS);
        Thread.sleep(2000);
        assertEquals(a, monitor.generation(DbChangeMonitor.Topic.APP) + monitor.generation(DbChangeMonitor.Topic.TASKS));
    }
}
