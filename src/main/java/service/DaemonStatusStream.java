package service;

import service.queue.SchedulerStatusSnapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GUI side of the Daemon's status push ({@code GET /status/stream}, see {@link DaemonControlServer}).
 *
 * <p>Holds <b>one</b> long-lived connection per data directory. The Daemon pushes a status event the moment
 * its state changes and a heartbeat every few seconds, so the GUI learns about changes in well under a second
 * with no file reads, and learns the Daemon is gone as soon as the connection drops (or goes silent), instead
 * of inferring it from a status file getting old.
 *
 * <p>This is an optimisation layered over the status file, never a replacement: whenever the stream is not
 * live (older Daemon, control API disabled, Daemon restarting) {@link #latestIfLive()} returns {@code null}
 * and callers fall back to reading the file exactly as before. The loop reconnects with backoff forever.
 */
public final class DaemonStatusStream {

    private static final Logger log = Logger.getLogger(DaemonStatusStream.class.getName());

    /** No bytes (not even a heartbeat) for this long and the connection is treated as dead and recycled. */
    static final long STALL_MS = 15_000;
    private static final long WATCHDOG_EVERY_MS = 5_000;
    private static final long BACKOFF_START_MS = 1_000, BACKOFF_MAX_MS = 10_000;
    /** While there is no control file (Daemon not running) just look again this often: a stat is trivial. */
    private static final long WAITING_FOR_DAEMON_MS = 2_000;

    private static final ConcurrentHashMap<String, DaemonStatusStream> SHARED = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daemon-status-watchdog");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    /** The shared, already-started stream for a data directory. */
    public static DaemonStatusStream forDataDir(File dataDir) {
        String key = dataDir.getAbsoluteFile().toPath().normalize().toString();
        return SHARED.computeIfAbsent(key, k -> new DaemonStatusStream(dataDir).start());
    }

    private final File dataDir;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile SchedulerStatusSnapshot latest;
    private volatile boolean connected;
    private volatile long lastByteMs;
    private volatile boolean stopped;
    private volatile long eventsReceived, connects;
    private Thread thread;

    /** Not started and not shared: for tests. Production code uses {@link #forDataDir}. */
    DaemonStatusStream(File dataDir) { this.dataDir = dataDir; }

    DaemonStatusStream start() {
        if (thread != null) return this;
        thread = new Thread(this::runLoop, "daemon-status-stream");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    void stop() {
        stopped = true;
        if (thread != null) thread.interrupt();
    }

    // ------------------------------------------------------------------ public API

    /**
     * The Daemon's latest pushed status, or {@code null} if the stream is not live right now (never connected,
     * dropped, or silent too long) - the caller then falls back to the status file.
     */
    public SchedulerStatusSnapshot latestIfLive() {
        SchedulerStatusSnapshot s = latest;
        if (s == null || !connected) return null;
        return System.currentTimeMillis() - lastByteMs <= STALL_MS ? s : null;
    }

    public boolean isConnected() { return connected; }

    /** Number of status events received so far (diagnostics and tests). */
    public long eventsReceived() { return eventsReceived; }

    /** Number of successful connections so far (diagnostics and tests). */
    public long connects() { return connects; }

    /** Runs {@code onChange} (on the stream thread) when a status arrives or liveness flips. Returns an unsubscribe action. */
    public Runnable addListener(Runnable onChange) {
        listeners.add(onChange);
        return () -> listeners.remove(onChange);
    }

    // ------------------------------------------------------------------ connection loop

    private void runLoop() {
        long backoff = BACKOFF_START_MS;
        while (!stopped) {
            DaemonControlClient.Control control = DaemonControlClient.readControl(dataDir);
            if (control == null) { // Daemon not running, or control API disabled: no backoff growth, just keep looking
                try { Thread.sleep(WAITING_FOR_DAEMON_MS); } catch (InterruptedException e) { return; }
                continue;
            }
            boolean gotAnyEvent = false;
            try {
                gotAnyEvent = connectAndRead(control);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.log(Level.FINE, "Daemon status stream unavailable: " + e);
            }
            latest = null; // never serve a previous Daemon instance's state after a drop/restart
            if (connected) { connected = false; notifyListeners(); } // liveness flipped: let callers fall back now
            if (stopped) return;
            backoff = gotAnyEvent ? BACKOFF_START_MS : Math.min(backoff * 2, BACKOFF_MAX_MS);
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
        }
    }

    /** One connection's lifetime. Returns whether it delivered at least one status (resets the backoff). */
    private boolean connectAndRead(DaemonControlClient.Control c) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + c.port() + "/status/stream"))
                .header(DaemonControlServer.TOKEN_HEADER, c.token())
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            return false; // 403 (stale token after a Daemon restart), 503 (too many streams), 404 (old Daemon)...
        }

        InputStream body = resp.body();
        lastByteMs = System.currentTimeMillis();
        connected = true;
        connects++;
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (System.currentTimeMillis() - lastByteMs > STALL_MS) {
                try { body.close(); } catch (Exception ignored) { } // unblocks readLine() below
            }
        }, WATCHDOG_EVERY_MS, WATCHDOG_EVERY_MS, TimeUnit.MILLISECONDS);

        boolean any = false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            List<String> data = new ArrayList<>();
            String event = null, line;
            while ((line = br.readLine()) != null) {
                lastByteMs = System.currentTimeMillis();
                if (line.isEmpty()) { // blank line ends one event
                    if ("status".equals(event) && !data.isEmpty() && accept(data)) any = true;
                    data = new ArrayList<>();
                    event = null;
                } else if (line.startsWith(":")) {
                    // comment / heartbeat: the byte timestamp above is all it is for
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String v = line.substring(5);
                    data.add(v.startsWith(" ") ? v.substring(1) : v);
                }
            }
        } catch (java.io.IOException e) {
            // connection closed or recycled by the watchdog
        } finally {
            watchdog.cancel(false);
        }
        return any;
    }

    private boolean accept(List<String> lines) {
        SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.parse(lines);
        if (snap == null) return false;
        latest = snap;
        eventsReceived++;
        notifyListeners();
        return true;
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (RuntimeException e) { log.log(Level.WARNING, "status listener failed", e); }
        }
    }
}
