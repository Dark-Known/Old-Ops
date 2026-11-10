package service;

import org.junit.Before;
import org.junit.Test;
import service.queue.*;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * v3: the Daemon pushes status changes over a loopback stream; the status file is only the throttled fallback.
 */
public class StatusPushTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private File dir;

    private static void export(SchedulerStatusExporter e, int pool, int active) {
        e.export(pool, active, List.<TaskDueEvent>of(), List.<TaskWorkerPool.ActivityEntry>of(),
                List.<SchedulerStatusSnapshot.WatchEntry>of(), List.<SchedulerStatusSnapshot.FireEntry>of(),
                List.<String>of(), List.<SchedulerStatusSnapshot.SessionEntry>of());
    }

    private static void awaitTrue(String what, long timeoutMs, java.util.function.BooleanSupplier cond) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("timed out waiting for: " + what);
    }

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("statuspush").toFile();
    }

    private SchedulerStatusExporter exporter(String label, long heartbeatMs) {
        return new SchedulerStatusExporter(new File(dir, "scheduler-status-" + label + ".dat").toPath(), label, heartbeatMs);
    }

    // ------------------------------------------------------------------ exporter

    @Test
    public void exporterOnlyPushesRealChangesAndThrottlesFileWrites() throws Exception {
        SchedulerStatusExporter e = exporter("daemon", 700);
        List<String> pushed = new CopyOnWriteArrayList<>();
        e.setListener(pushed::add);

        export(e, 4, 0);
        assertEquals("first export is a change", 1, pushed.size());
        assertEquals("and writes the file", 1, e.filesWritten());

        for (int i = 0; i < 20; i++) export(e, 4, 0); // idle: identical state
        assertEquals("identical state is never pushed", 1, pushed.size());
        assertEquals("and does not rewrite the file", 1, e.filesWritten());

        Thread.sleep(800);
        export(e, 4, 0);
        assertEquals("heartbeat refreshes the file so readers still see 'alive'", 2, e.filesWritten());
        assertEquals("but pushes nothing", 1, pushed.size());

        export(e, 4, 2); // a worker became busy
        assertEquals("a real change is pushed at once", 2, pushed.size());
        assertEquals("file write is throttled (min 2 s apart)", 2, e.filesWritten());
        Thread.sleep(2100);
        export(e, 4, 2); // same state, but the file is owed a write
        assertEquals("the owed write lands once the gap has passed", 3, e.filesWritten());
        assertEquals(2, pushed.size());
    }

    @Test
    public void pushedTextIsTheFileFormatAndParses() throws Exception {
        SchedulerStatusExporter e = exporter("daemon", 5000);
        List<String> pushed = new ArrayList<>();
        e.setListener(pushed::add);
        e.export(8, 3, List.<TaskDueEvent>of(), List.<TaskWorkerPool.ActivityEntry>of(),
                List.of(new SchedulerStatusSnapshot.WatchEntry("t1", "NATIVE_WATCH", "native OS directory watch is active")),
                List.<SchedulerStatusSnapshot.FireEntry>of(), List.of("t1"), List.<SchedulerStatusSnapshot.SessionEntry>of());
        SchedulerStatusSnapshot fromPush = SchedulerStatusSnapshot.parse(pushed.get(0));
        SchedulerStatusSnapshot fromFile = SchedulerStatusSnapshot.read(new File(dir, "scheduler-status-daemon.dat").toPath());
        assertNotNull(fromPush);
        assertNotNull(fromFile);
        assertEquals("daemon", fromPush.getProcessLabel());
        assertEquals(8, fromPush.getPoolSize());
        assertEquals(3, fromPush.getActiveWorkers());
        assertEquals("NATIVE_WATCH", fromPush.getWatchEntries().get(0).mode());
        assertEquals(List.of("t1"), fromPush.getRunningTaskIds());
        assertEquals("stream and file carry identical state", fromFile.getActiveWorkers(), fromPush.getActiveWorkers());
        assertTrue(fromPush.isFresh(5000));
    }

    @Test
    public void nonDaemonProcessesRefreshTheirFileOnlyRarely() throws Exception {
        SchedulerStatusExporter gui = new SchedulerStatusExporter(new File(dir, "scheduler-status-gui.dat").toPath(), "gui");
        export(gui, 4, 0);
        Thread.sleep(300);
        export(gui, 4, 0);
        assertEquals("nobody reads the GUI's file for liveness, so no 2 s churn", 1, gui.filesWritten());
    }

    // ------------------------------------------------------------------ queue / pool hooks

    @Test
    public void queueAndPoolFireChangeListeners() throws Exception {
        TaskEventQueue q = new TaskEventQueue();
        AtomicInteger qChanges = new AtomicInteger();
        q.setChangeListener(qChanges::incrementAndGet);
        q.publish(new TaskDueEvent("t", LocalDateTime.now().plusHours(1), 0));
        assertEquals(1, qChanges.get());
        assertTrue(q.cancel("t"));
        assertEquals(2, qChanges.get());
        assertFalse("cancelling nothing is not a change", q.cancel("t"));
        assertEquals(2, qChanges.get());

        TaskEventQueue q2 = new TaskEventQueue();
        AtomicInteger poolChanges = new AtomicInteger();
        TaskWorkerPool pool = new TaskWorkerPool(q2, 1, ev -> {
        });
        pool.setChangeListener(poolChanges::incrementAndGet);
        pool.start();
        try {
            q2.publish(new TaskDueEvent("t2", LocalDateTime.now(), 0));
            awaitTrue("worker start + finish notifications", 3000, () -> poolChanges.get() >= 2);
        } finally {
            pool.stop();
        }
    }

    // ------------------------------------------------------------------ the live stream, end to end

    @Test
    public void changesArriveByPushQuicklyAndIdleIsSilent() throws Exception {
        try (Rig r = new Rig(dir, 300)) {
            export(r.exporter, 4, 0);
            awaitTrue("stream live with first status", 5000, () -> r.stream.latestIfLive() != null);
            assertEquals(4, r.stream.latestIfLive().getPoolSize());

            long before = r.stream.eventsReceived();
            long t0 = System.nanoTime();
            export(r.exporter, 4, 3);
            awaitTrue("pushed change", 3000, () -> r.stream.latestIfLive().getActiveWorkers() == 3);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue("push latency " + ms + " ms", ms < 1500);
            assertEquals(before + 1, r.stream.eventsReceived());

            for (int i = 0; i < 10; i++) export(r.exporter, 4, 3); // no real change
            Thread.sleep(1200); // several heartbeats go by
            assertEquals("idle sends no status events, only heartbeats", before + 1, r.stream.eventsReceived());
            assertNotNull("heartbeats keep the stream live while idle", r.stream.latestIfLive());
            assertEquals("a single connection is reused", 1, r.stream.connects());
        }
    }

    @Test
    public void newSubscriberGetsCurrentStateImmediately() throws Exception {
        StatusBroadcaster bus = new StatusBroadcaster();
        SchedulerStatusExporter e = exporter("daemon", 5000);
        e.setListener(bus::publish);
        export(e, 6, 1); // state exists BEFORE any client connects
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, w) -> {
        }, bus, 300)) {
            DaemonStatusStream st = new DaemonStatusStream(dir).start();
            try {
                awaitTrue("seeded state", 5000, () -> st.latestIfLive() != null);
                assertEquals(6, st.latestIfLive().getPoolSize());
            } finally {
                st.stop();
            }
        }
    }

    @Test
    public void daemonGoingAwayFlipsLivenessAndStreamReconnectsToTheNextOne() throws Exception {
        Rig r = new Rig(dir, 300);
        try {
            export(r.exporter, 4, 0);
            awaitTrue("live", 5000, () -> r.stream.latestIfLive() != null);
            r.server.close();
            awaitTrue("liveness drops when the daemon goes away", 5000, () -> !r.stream.isConnected());
            assertNull("callers must fall back to the file", r.stream.latestIfLive());

            // a restarted daemon (new port, new token) is picked up by the same client
            StatusBroadcaster bus2 = new StatusBroadcaster();
            r.exporter.setListener(bus2::publish);
            try (DaemonControlServer s2 = DaemonControlServer.start(dir, (a, id, w) -> {
            }, bus2, 300)) {
                export(r.exporter, 9, 0);
                awaitTrue("reconnected to the restarted daemon", 15000,
                        () -> r.stream.latestIfLive() != null && r.stream.latestIfLive().getPoolSize() == 9);
                assertTrue(r.stream.connects() >= 2);
            }
        } finally {
            r.stream.stop();
        }
    }

    @Test
    public void statusEndpointsRequireTheTokenAndCapStreams() throws Exception {
        StatusBroadcaster bus = new StatusBroadcaster();
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, w) -> {
        }, bus, 300)) {
            String base = "http://127.0.0.1:" + s.port();

            HttpResponse<String> noTok = http.send(HttpRequest.newBuilder(URI.create(base + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noTok.statusCode());
            HttpResponse<String> badTok = http.send(HttpRequest.newBuilder(URI.create(base + "/status/stream"))
                    .header(DaemonControlServer.TOKEN_HEADER, "nope").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, badTok.statusCode());

            HttpRequest.Builder authed = HttpRequest.newBuilder(URI.create(base + "/status"))
                    .header(DaemonControlServer.TOKEN_HEADER, s.token());
            assertEquals("no status exported yet", 204, http.send(authed.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            bus.publish("PROC|daemon|1|0|4|0\n");
            HttpResponse<String> ok = http.send(authed.GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().startsWith("PROC|daemon"));

            // stream cap: 4 allowed, the 5th is refused (each stream holds a server thread)
            List<InputStream> open = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + "/status/stream"))
                                    .header(DaemonControlServer.TOKEN_HEADER, s.token()).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    assertEquals(200, r.statusCode());
                    open.add(r.body());
                }
                awaitTrue("4 subscribers registered", 3000, () -> bus.subscriberCount() == 4);
                HttpResponse<String> fifth = http.send(HttpRequest.newBuilder(URI.create(base + "/status/stream"))
                        .header(DaemonControlServer.TOKEN_HEADER, s.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, fifth.statusCode());

                // commands still work while all stream threads are busy
                assertTrue(DaemonControlClient.send(dir, CommandQueueService.Action.REFRESH, "x", "t"));
            } finally {
                for (InputStream in : open) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            awaitTrue("subscribers released when clients disconnect", 5000, () -> bus.subscriberCount() == 0);
        }
    }

    @Test
    public void slowSubscriberOnlyEverHoldsTheLatestStatus() throws Exception {
        StatusBroadcaster bus = new StatusBroadcaster();
        StatusBroadcaster.Subscription sub = bus.subscribe(4);
        for (int i = 0; i < 1000; i++) bus.publish("v" + i);
        assertEquals("no backlog: a stalled client skips straight to the newest state", "v999", sub.next(100));
        assertNull(sub.next(50));
        StatusBroadcaster.Subscription late = bus.subscribe(4);
        assertEquals("v999", late.next(100));
    }

    private static final class Rig implements AutoCloseable {
        final StatusBroadcaster bus = new StatusBroadcaster();
        final DaemonControlServer server;
        final DaemonStatusStream stream;
        final SchedulerStatusExporter exporter;

        Rig(File dir, long heartbeatMs) throws Exception {
            exporter = new SchedulerStatusExporter(new File(dir, "scheduler-status-daemon.dat").toPath(), "daemon", 5000);
            exporter.setListener(bus::publish);
            server = DaemonControlServer.start(dir, (a, id, who) -> {
            }, bus, heartbeatMs);
            stream = new DaemonStatusStream(dir).start();
        }

        @Override
        public void close() {
            stream.stop();
            server.close();
        }
    }
}
