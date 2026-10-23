package service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One cheap, process-wide "did anything change?" signal for the three SQLite files this app
 * shares between the GUI and the Daemon (two separate JVMs).
 *
 * <p>Before this class, every screen polled the database on its own timer (task list, failures,
 * change tokens, command queue...). Now there is <b>one</b> poller per process that runs
 * {@code PRAGMA data_version} on a dedicated, otherwise idle connection per file. SQLite bumps that
 * value whenever <em>any other connection</em> (this process or the other JVM) commits, so a single
 * integer read answers "has anything changed at all?" without touching a table. Everything else
 * (the task list, the failure badge, the command poller, the Event Monitor) only queries when
 * the relevant topic's generation moved.
 *
 * <p>Topics:
 * <ul>
 *   <li>{@link Topic#APP} - {@code app.db} committed (tasks, credentials, settings, gui_commands)</li>
 *   <li>{@link Topic#TASKS} - the {@code tasks} table specifically (via a trigger-maintained counter, so
 *       frequent {@code gui_commands} traffic does not invalidate the task cache)</li>
 *   <li>{@link Topic#RUNS} - {@code run_history.db} committed</li>
 *   <li>{@link Topic#BGLOG} - {@code background_log.db} committed</li>
 * </ul>
 *
 * <p>Listeners run on the monitor's own thread and must be cheap (flip a flag, hand off to the EDT).
 * The monitor is a signal, not a source of truth: every consumer keeps a long safety-net refresh
 * so a missed signal can only ever delay an update, never lose one.
 */
public final class DbChangeMonitor {

    public enum Topic { APP, TASKS, RUNS, BGLOG }

    private static final Logger log = Logger.getLogger(DbChangeMonitor.class.getName());
    private static final long POLL_MS = 500;

    private static final ConcurrentHashMap<String, DbChangeMonitor> INSTANCES = new ConcurrentHashMap<>();

    /** The shared monitor for a data directory (created and started on first use). */
    public static DbChangeMonitor forDataDir(File dataDir) {
        String key = dataDir.getAbsoluteFile().toPath().normalize().toString();
        return INSTANCES.computeIfAbsent(key, k -> new DbChangeMonitor(dataDir).start());
    }

    /** One watched file: its dedicated connection and the last data_version it reported. */
    private static final class Watch {
        final File file;
        final Topic topic;
        Connection conn;
        long lastVersion = Long.MIN_VALUE;
        boolean baselined;
        Watch(File file, Topic topic) { this.file = file; this.topic = topic; }
    }

    private final Watch[] watches;
    private final Map<Topic, AtomicLong> generations = new EnumMap<>(Topic.class);
    private final Map<Topic, List<Runnable>> listeners = new EnumMap<>(Topic.class);
    private final Object wake = new Object();
    // Observability: lets the Daemon log (and tests assert) how much work the signal is actually doing.
    private final LongAdder polls = new LongAdder(), reconnects = new LongAdder(), listenerErrors = new LongAdder();
    private final Map<Topic, LongAdder> fired = new EnumMap<>(Topic.class);
    private long lastTasksVer = Long.MIN_VALUE;
    private volatile long lastPollMs;
    private volatile boolean started;
    private ScheduledExecutorService ticker;

    private DbChangeMonitor(File dataDir) {
        for (Topic t : Topic.values()) {
            generations.put(t, new AtomicLong());
            fired.put(t, new LongAdder());
            listeners.put(t, new CopyOnWriteArrayList<>());
        }
        watches = new Watch[] {
                new Watch(new File(dataDir, "app.db"), Topic.APP),
                new Watch(new File(dataDir, "run_history.db"), Topic.RUNS),
                new Watch(new File(dataDir, BackgroundLogService.FILE_NAME), Topic.BGLOG),
        };
    }

    private DbChangeMonitor start() {
        if (started) return this;
        started = true;
        ticker = Workers.newScheduled(Workers.Lane.FEED, 1);
        ticker.scheduleWithFixedDelay(this::pollOnce, 0, POLL_MS, TimeUnit.MILLISECONDS);
        return this;
    }

    // ------------------------------------------------------------------ public API

    /** Monotonic counter for a topic; compare against a remembered value to detect change. */
    public long generation(Topic topic) { return generations.get(topic).get(); }

    /** Epoch-millis of the last completed poll (0 = never). Lets callers judge whether the signal is alive. */
    public long lastPollMillis() { return lastPollMs; }

    /** One-line summary for logs: poll count, signals per topic, reconnects. */
    public String describe() {
        return "polls=" + polls.sum()
                + " signals[app=" + fired.get(Topic.APP).sum() + " tasks=" + fired.get(Topic.TASKS).sum()
                + " runs=" + fired.get(Topic.RUNS).sum() + " bglog=" + fired.get(Topic.BGLOG).sum() + "]"
                + " reconnects=" + reconnects.sum() + " listenerErrors=" + listenerErrors.sum();
    }

    /** Total signals fired for a topic since start (for tests and diagnostics). */
    public long signalCount(Topic topic) { return fired.get(topic).sum(); }

    /** Runs {@code onChange} (on the monitor thread) whenever the topic changes. Returns an unsubscribe action. */
    public Runnable subscribe(Topic topic, Runnable onChange) {
        List<Runnable> l = listeners.get(topic);
        l.add(onChange);
        return () -> l.remove(onChange);
    }

    /**
     * Blocks until {@code topic}'s generation differs from {@code seen}, or {@code timeoutMs} elapses.
     * Used by the Daemon's command poller in place of a fixed 3 s sleep: it wakes within ~0.5 s of a
     * GUI enqueue and costs nothing while the database is idle.
     *
     * @return the generation at wake-up
     */
    public long awaitChange(Topic topic, long seen, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (wake) {
            while (generation(topic) == seen) {
                long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (leftMs <= 0) break;
                wake.wait(leftMs);
            }
        }
        return generation(topic);
    }

    // ------------------------------------------------------------------ polling

    private void pollOnce() {
        try {
            polls.increment();
            for (Watch w : watches) pollFile(w);
        } catch (Throwable t) {
            log.log(Level.FINE, "change poll failed", t);
        } finally {
            lastPollMs = System.currentTimeMillis();
        }
    }

    private void pollFile(Watch w) {
        if (!w.file.exists()) return; // never create an empty database just to watch it
        try {
            if (w.conn == null) {
                Class.forName("org.sqlite.JDBC");
                w.conn = DriverManager.getConnection("jdbc:sqlite:" + w.file.getAbsolutePath());
                try (Statement st = w.conn.createStatement()) { st.execute("PRAGMA busy_timeout=2000"); }
            }
            long v = readLong(w.conn, "PRAGMA data_version");
            if (v == w.lastVersion) return;
            boolean first = !w.baselined;
            w.baselined = true;
            w.lastVersion = v;
            if (first) { // baseline only; consumers load once on their own at startup
                if (w.topic == Topic.APP) lastTasksVer = readTasksVersion(w.conn);
                return;
            }

            fire(w.topic);
            if (w.topic == Topic.APP) {
                long tv = readTasksVersion(w.conn);
                if (tv != lastTasksVer) { lastTasksVer = tv; fire(Topic.TASKS); }
            }
        } catch (SQLException | ClassNotFoundException e) {
            // Busy or briefly unreadable: drop the connection and retry next tick. A missed beat only delays.
            reconnects.increment();
            closeQuietly(w);
        }
    }

    private void fire(Topic topic) {
        generations.get(topic).incrementAndGet();
        fired.get(topic).increment();
        synchronized (wake) { wake.notifyAll(); }
        for (Runnable r : listeners.get(topic)) {
            try { r.run(); } catch (Throwable t) { listenerErrors.increment(); log.log(Level.WARNING, "change listener failed", t); }
        }
    }

    /** Version counter the {@code tasks} triggers maintain; -1 if the table is not there (older database). */
    private static long readTasksVersion(Connection c) {
        try { return readLong(c, "SELECT ver FROM change_log WHERE topic='tasks'"); }
        catch (SQLException e) { return -1; }
    }

    private static long readLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static void closeQuietly(Watch w) {
        if (w.conn != null) { try { w.conn.close(); } catch (SQLException ignored) { } }
        w.conn = null;
        w.lastVersion = Long.MIN_VALUE; // after a reconnect the next reading always counts as a change (we may have missed one)
    }
}
