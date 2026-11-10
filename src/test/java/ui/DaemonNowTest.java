package ui;

import org.junit.Test;
import service.DaemonControlServer;
import service.StatusBroadcaster;
import service.queue.SchedulerStatusExporter;
import service.queue.SchedulerStatusSnapshot;
import service.queue.TaskDueEvent;
import service.queue.TaskWorkerPool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * v3: GUI-side source selection. Stream when live, the status file otherwise, "offline" when neither.
 */
public class DaemonNowTest {

    private static void export(SchedulerStatusExporter e, int pool, int active) {
        e.export(pool, active, List.<TaskDueEvent>of(), List.<TaskWorkerPool.ActivityEntry>of(),
                List.<SchedulerStatusSnapshot.WatchEntry>of(), List.<SchedulerStatusSnapshot.FireEntry>of(),
                List.<String>of(), List.<SchedulerStatusSnapshot.SessionEntry>of());
    }

    @Test
    public void streamWhenLiveThenFileThenOffline() throws Exception {
        File dir = Files.createTempDirectory("daemonnow").toFile();
        Path file = new File(dir, "scheduler-status-daemon.dat").toPath();

        assertFalse("nothing running", AppFeeds.daemonNow(file).alive());

        SchedulerStatusExporter e = new SchedulerStatusExporter(file, "daemon", 5000);
        StatusBroadcaster bus = new StatusBroadcaster();
        e.setListener(bus::publish);
        export(e, 4, 0); // writes the file; no server yet, so only the file path can answer
        AppFeeds.Daemon viaFile = AppFeeds.daemonNow(file);
        assertTrue("fresh file is accepted when there is no stream", viaFile.alive());
        assertEquals(4, viaFile.snapshot().getPoolSize());

        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, w) -> {
        }, bus)) {
            long end = System.currentTimeMillis() + 6000;
            AppFeeds.Daemon d;
            do {
                Thread.sleep(50);
                d = AppFeeds.daemonNow(file);
            }
            while (System.currentTimeMillis() < end && !(d.alive() && d.snapshot() != null
                    && service.DaemonStatusStream.forDataDir(dir).isConnected()));
            assertTrue("stream connected", service.DaemonStatusStream.forDataDir(dir).isConnected());

            // Prove the stream, not the file, is now the source: remove the file entirely. Nothing but the
            // push stream can still say the Daemon is up.
            Files.delete(file);
            AppFeeds.Daemon viaStream = AppFeeds.daemonNow(file);
            assertTrue("alive with no status file at all => served by the stream", viaStream.alive());
            assertEquals(4, viaStream.snapshot().getPoolSize());

            // ...and a state change reaches the GUI through the stream.
            export(e, 4, 5);
            long until = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < until && AppFeeds.daemonNow(file).snapshot().getActiveWorkers() != 5)
                Thread.sleep(25);
            assertEquals(5, AppFeeds.daemonNow(file).snapshot().getActiveWorkers());
        }

        // daemon gone: stream drops; with the file deleted too, the answer is offline
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end && service.DaemonStatusStream.forDataDir(dir).isConnected())
            Thread.sleep(50);
        Files.deleteIfExists(file);
        assertFalse(AppFeeds.daemonNow(file).alive());
    }
}
