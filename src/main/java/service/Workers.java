package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place the application gets its threads from.
 *
 * <p>Scheduled/manual task runs have their own queue-fed pool ({@code TaskWorkerPool}, threads named
 * {@code task-worker-N}). Everything else — UI-triggered I/O, live feeds, parallel transfer
 * sub-work, file-watcher debouncing — gets a named, counted <em>lane</em> from here, so
 * <ul>
 *   <li>every thread in the process has a role-based name ({@code ui-worker-1}, {@code transfer-worker-3}…),</li>
 *   <li>there is one live view of how busy each lane is ({@link #snapshot()}), and</li>
 *   <li>a long job in one lane can never starve another (a UI query is never queued behind a backup).</li>
 * </ul>
 * Lanes are deliberately separate from the task pool: work that a task spawns (parallel file
 * transfers) must not wait for a free <em>task</em> worker — that is a classic pool self-deadlock.
 */
public final class Workers {
    private Workers() {}

    /** Roles a thread can have. Sizes are the default thread count; TRANSFER and WATCH size per pool. */
    public enum Lane {
        UI("ui-worker", "UI actions & screen loads"),
        ORDERED("ordered-worker", "Ordered writes (audit log)"),
        FEED("feed-worker", "Live feeds & polling"),
        TRANSFER("transfer-worker", "Parallel file transfers"),
        WATCH("watch-worker", "File-watch debouncing & listeners"),
        SCHEDULER("scheduler", "Scheduler ticks"),
        SERVICE("service", "Long-running service loops");

        final String prefix; public final String title;
        Lane(String prefix, String title) { this.prefix = prefix; this.title = title; }
    }

    /** Point-in-time load of one lane. */
    public record LaneStat(Lane lane, int threads, int active, int queued, long completed) {}

    private static final class Stat {
        final AtomicInteger threads = new AtomicInteger(), active = new AtomicInteger();
        final AtomicLong completed = new AtomicLong();
        final Set<ThreadPoolExecutor> pools = ConcurrentHashMap.newKeySet();
    }
    private static final java.util.Map<Lane, Stat> STATS = new java.util.EnumMap<>(Lane.class);
    static { for (Lane l : Lane.values()) STATS.put(l, new Stat()); }

    private static final java.util.Map<Lane, ExecutorService> SHARED = new ConcurrentHashMap<>();
    private static final java.util.Map<Lane, ScheduledExecutorService> SHARED_TIMED = new ConcurrentHashMap<>();

    /** Shared pool for a lane (created on first use). */
    public static ExecutorService pool(Lane lane) {
        return SHARED.computeIfAbsent(lane, l -> newPool(l, defaultSize(l)));
    }

    /** Shared timer-driven pool for a lane, for periodic work (feeds, reapers). */
    public static ScheduledExecutorService scheduled(Lane lane) {
        return SHARED_TIMED.computeIfAbsent(lane, l -> newScheduledPool(l, Math.max(1, defaultSize(l))));
    }

    /** A private fixed-size pool in {@code lane}; threads are named after the lane and counted. */
    public static ExecutorService newPool(Lane lane, int size) {
        Stat st = STATS.get(lane);
        ThreadPoolExecutor ex = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), factory(lane)) {
            @Override protected void beforeExecute(Thread t, Runnable r) { st.active.incrementAndGet(); }
            @Override protected void afterExecute(Runnable r, Throwable t) { st.active.decrementAndGet(); st.completed.incrementAndGet(); }
        };
        ex.allowCoreThreadTimeOut(true);
        st.pools.add(ex);
        return ex;
    }

    /** Release a private pool made with {@link #newPool}. */
    public static void shutdown(ExecutorService ex) {
        ex.shutdown();
        if (ex instanceof ThreadPoolExecutor tpe) for (Stat s : STATS.values()) s.pools.remove(tpe);
    }

    /** Stop a private pool now (interrupts running work) and forget it. */
    public static void shutdownNow(ExecutorService ex) {
        ex.shutdownNow();
        if (ex instanceof ThreadPoolExecutor tpe) for (Stat s : STATS.values()) s.pools.remove(tpe);
    }

    /** A private cached pool (grows on demand, idle threads retire) for bursty, I/O-bound listeners. */
    public static ExecutorService newCachedPool(Lane lane) {
        Stat st = STATS.get(lane);
        ThreadPoolExecutor ex = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory(lane)) {
            @Override protected void beforeExecute(Thread t, Runnable r) { st.active.incrementAndGet(); }
            @Override protected void afterExecute(Runnable r, Throwable t) { st.active.decrementAndGet(); st.completed.incrementAndGet(); }
        };
        st.pools.add(ex);
        return ex;
    }

    /** A private timer-driven pool in {@code lane}. */
    public static ScheduledExecutorService newScheduled(Lane lane, int size) { return newScheduledPool(lane, size); }

    private static ScheduledExecutorService newScheduledPool(Lane lane, int size) {
        Stat st = STATS.get(lane);
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(size, factory(lane)) {
            @Override protected void beforeExecute(Thread t, Runnable r) { st.active.incrementAndGet(); }
            @Override protected void afterExecute(Runnable r, Throwable t) { st.active.decrementAndGet(); st.completed.incrementAndGet(); }
        };
        ex.setRemoveOnCancelPolicy(true);
        st.pools.add(ex);
        return ex;
    }

    /**
     * Start a long-running service loop (command poller, directory watcher…) on a named, counted
     * thread. These are supervised rather than pooled: they live as long as their service does.
     */
    public static Thread service(String name, Runnable loop) {
        Stat st = STATS.get(Lane.SERVICE);
        Thread t = new Thread(() -> {
            st.threads.incrementAndGet(); st.active.incrementAndGet();
            try { loop.run(); }
            finally { st.active.decrementAndGet(); st.threads.decrementAndGet(); st.completed.incrementAndGet(); }
        }, "service-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Thread factory for an executor owned by a third party (tracks the thread count only). */
    public static ThreadFactory factory(Lane lane) {
        Stat st = STATS.get(lane);
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(() -> {
                st.threads.incrementAndGet();
                try { r.run(); } finally { st.threads.decrementAndGet(); }
            }, lane.prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Current load of every lane that has been used. */
    public static List<LaneStat> snapshot() {
        List<LaneStat> out = new ArrayList<>();
        for (Lane l : Lane.values()) {
            Stat s = STATS.get(l);
            int queued = 0;
            for (ThreadPoolExecutor p : s.pools) if (!(p instanceof ScheduledThreadPoolExecutor)) queued += p.getQueue().size(); // a timer pool's queue is future ticks, not waiting work
            int threads = s.threads.get();
            if (threads == 0 && s.completed.get() == 0 && queued == 0) continue;
            out.add(new LaneStat(l, threads, s.active.get(), queued, s.completed.get()));
        }
        out.sort(Comparator.comparing(x -> x.lane().ordinal()));
        return out;
    }

    /**
     * Worker threads busy right now outside the task pool: UI actions, ordered writes, live feeds,
     * transfer sub-work and watcher debouncing. (Service loops and scheduler timers are always "on"
     * by design, so they are not counted as work.) This is what "0/20 workers" did not show.
     */
    public static int busyBackground() {
        int n = 0;
        for (Lane l : new Lane[]{Lane.UI, Lane.ORDERED, Lane.FEED, Lane.TRANSFER, Lane.WATCH}) n += STATS.get(l).active.get();
        return n;
    }

    /** One line per active lane ("UI actions & screen loads: 1 busy, 2 queued"); empty when nothing has run yet. */
    public static List<String> summaryLines() {
        List<String> out = new ArrayList<>();
        for (LaneStat s : snapshot()) {
            out.add(s.lane().title + ": " + s.active() + " busy" + (s.queued() > 0 ? ", " + s.queued() + " queued" : ""));
        }
        return out;
    }

    /** One-line summary for tooltips, e.g. "UI 1 busy · feeds 0 busy · transfers 3 busy, 12 queued". */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (LaneStat s : snapshot()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s.lane().title).append(": ").append(s.active()).append(" busy");
            if (s.queued() > 0) sb.append(", ").append(s.queued()).append(" queued");
        }
        return sb.length() == 0 ? "idle" : sb.toString();
    }

    private static int defaultSize(Lane l) {
        return switch (l) { case UI -> 3; case ORDERED -> 1; case FEED -> 2; case WATCH -> 2; case TRANSFER -> 4; case SERVICE -> 1; case SCHEDULER -> 4; };
    }
}
