package service;

import model.TaskRunRecord;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * v2: UI reads use their own read-only connection and must never queue behind the write lock.
 */
public class RunHistoryReadSplitTest {

    private File dir;
    private RunHistoryService rhs;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("rhsplit").toFile();
        rhs = new RunHistoryService(dir.getAbsolutePath());
    }

    private void record(String name, TaskRunRecord.Status st) {
        rhs.recordRun("id-" + name, name, "FILE_TRANSFER", st, "r", "d",
                LocalDateTime.now().minusSeconds(3), LocalDateTime.now());
    }

    @Test
    public void readSeesCommittedWriteImmediately() {
        record("a", TaskRunRecord.Status.FAILED);
        List<TaskRunRecord> failed = rhs.queryRunSummaries(TaskRunRecord.Status.FAILED, LocalDateTime.now().minusHours(1), 10);
        assertEquals(1, failed.size());
        String t1 = rhs.getChangeToken();
        record("b", TaskRunRecord.Status.SUCCESS);
        assertNotEquals("change token must move after a write", t1, rhs.getChangeToken());
        assertEquals(2, rhs.queryRuns(null, null, 10).size());
    }

    @Test(timeout = 15_000)
    public void readsDoNotWaitForTheWriteLock() throws Exception {
        record("a", TaskRunRecord.Status.FAILED);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (rhs) { // simulates a long write / purge holding the writer lock
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        writer.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            long t0 = System.nanoTime();
            List<TaskRunRecord> rows = rhs.queryRunSummaries(null, 10);
            String token = rhs.getChangeToken();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals(1, rows.size());
            assertFalse(token.isEmpty());
            assertTrue("reads blocked behind the write lock for " + ms + " ms", ms < 1500);
        } finally {
            release.countDown();
            writer.join();
        }
    }

    @Test
    public void monitorReportsItsWork() throws Exception {
        DbChangeMonitor mon = DbChangeMonitor.forDataDir(dir);
        Thread.sleep(1200);
        long before = mon.signalCount(DbChangeMonitor.Topic.RUNS);
        record("c", TaskRunRecord.Status.SUCCESS);
        long gen = mon.generation(DbChangeMonitor.Topic.RUNS);
        mon.awaitChange(DbChangeMonitor.Topic.RUNS, gen, 5000);
        assertTrue(mon.signalCount(DbChangeMonitor.Topic.RUNS) > before);
        String d = mon.describe();
        assertTrue(d, d.contains("polls=") && d.contains("signals[") && d.contains("reconnects="));
    }
}
