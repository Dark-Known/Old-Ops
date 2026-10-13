package ui;

import model.ScheduledTask;
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

    public static DataFeed<Daemon> daemon(Path statusFile) {
        return DAEMON.computeIfAbsent(statusFile, f -> new DataFeed<>(1000, () -> {
            if (!SchedulerStatusSnapshot.isAlive(f, SchedulerStatusSnapshot.DEFAULT_STALE_MS)) return Daemon.OFFLINE;
            SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.read(f);
            return snap == null ? Daemon.OFFLINE : new Daemon(true, snap);
        }).named("Daemon status feed"));
    }

    public static DataFeed<List<ScheduledTask>> tasks(XmlStorageService storage) {
        return TASKS.computeIfAbsent(storage, s -> new DataFeed<List<ScheduledTask>>(2000, s::loadTasks).named("Task list feed"));
    }
}
