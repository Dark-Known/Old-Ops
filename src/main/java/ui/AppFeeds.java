package ui;

import model.ScheduledTask;
import service.DaemonStatusStream;
import service.XmlStorageService;
import service.queue.SchedulerStatusSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, process-wide background feeds. Screens read from these in-memory
 * snapshots instead of hitting disk / SQLite from Swing timers on the EDT.
 */
public final class AppFeeds {
    private AppFeeds() {}

    /** Daemon liveness plus its last exported status (null snapshot when not alive). */
    public record Daemon(boolean alive, SchedulerStatusSnapshot snapshot) {
        public static final Daemon OFFLINE = new Daemon(false, null);
    }

    private static final ConcurrentHashMap<Path, DataFeed<Daemon>> DAEMON = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<XmlStorageService, DataFeed<List<ScheduledTask>>> TASKS = new ConcurrentHashMap<>();

    /**
     * Daemon liveness plus status. Preferred source is the Daemon's push stream ({@link DaemonStatusStream}):
     * pure in-memory, updated the instant the Daemon's state changes (each pushed event triggers an immediate
     * reload below). Only when the stream is not live - older Daemon, control API disabled, Daemon restarting -
     * does it fall back to the status file, which is now read and parsed <em>once</em> per load (it used to be
     * parsed twice: once to test freshness, again to use it).
     */
    public static DataFeed<Daemon> daemon(Path statusFile) {
        return DAEMON.computeIfAbsent(statusFile, f -> {
            java.io.File dataDir = f.toAbsolutePath().getParent().toFile();
            DaemonStatusStream stream = DaemonStatusStream.forDataDir(dataDir);
            DataFeed<Daemon> feed = new DataFeed<Daemon>(1000, () -> daemonNow(f)).named("Daemon status feed");
            stream.addListener(feed::refreshNow); // push: reload immediately instead of waiting for the next tick
            return feed;
        });
    }

    /**
     * Synchronous "is the Daemon up, and what is its status" for code that needs an answer now rather than a
     * subscription: the push stream when it is live (in-memory, no I/O), otherwise the status file read once.
     * Safe to call from any thread; do not call from the EDT if the file fallback may be hit.
     */
    public static Daemon daemonNow(Path statusFile) {
        java.io.File dataDir = statusFile.toAbsolutePath().getParent().toFile();
        SchedulerStatusSnapshot pushed = DaemonStatusStream.forDataDir(dataDir).latestIfLive();
        if (pushed != null) return new Daemon(true, pushed);
        SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.read(statusFile);
        return snap != null && snap.isFresh(SchedulerStatusSnapshot.DEFAULT_STALE_MS)
                ? new Daemon(true, snap) : Daemon.OFFLINE;
    }

    public static DataFeed<List<ScheduledTask>> tasks(XmlStorageService storage) {
        return TASKS.computeIfAbsent(storage, s -> new DataFeed<List<ScheduledTask>>(2000, s::loadTasksSnapshot).named("Task list feed"));
    }
}
