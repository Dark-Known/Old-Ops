package service;

import util.AppSettings;

/**
 * One set of worker threads for the whole process, sized by the "Max task threads" setting.
 *
 * <p>Tasks and transfer sessions run on the SAME pool ({@link #pool()}). A slot is a thread: it is taken before
 * anything is submitted, so the pool never holds more busy threads than the limit and a job never waits for a
 * thread.
 *
 * <p>A running task uses one slot. Every extra transfer session or worker thread a task opens to run faster
 * (the second session onward) takes another slot from the same budget. So tasks and the sessions they scale
 * up cannot together run more than the limit. The first session of a task is covered by the task's own slot.
 *
 * <p>Scaling never waits for a slot: it asks, and if none is free it simply carries on with what it has and
 * asks again later. Only a task about to start waits for a free slot, and slots are returned as transfers
 * finish, so this cannot deadlock.
 */
public final class ThreadBudget {
    private ThreadBudget() {}

    private static final java.util.concurrent.atomic.AtomicInteger TASKS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger EXTRAS = new java.util.concurrent.atomic.AtomicInteger();
    private static final Object LOCK = new Object();

    /** The size of the budget. Read live. */
    public static int limit() { return Math.max(4, AppSettings.getMaxConcurrentTaskThreads()); }

    public static int inUse() { return TASKS.get() + EXTRAS.get(); }
    public static int extrasInUse() { return EXTRAS.get(); }
    public static int tasksRunning() { return TASKS.get(); }

    public static void taskStarted() { TASKS.incrementAndGet(); }

    private static volatile java.util.concurrent.ExecutorService pool;

    /** The one thread pool tasks and transfer sessions both run on (threads named {@code worker-N}). */
    public static java.util.concurrent.ExecutorService pool() {
        java.util.concurrent.ExecutorService p = pool;
        if (p == null) {
            synchronized (LOCK) {
                if (pool == null) pool = Workers.newCachedPool(Workers.Lane.WORK);
                p = pool;
            }
        }
        return p;
    }

    /** Waits for a free slot and takes it for a task. Pair with {@link #taskFinished()}. */
    public static void acquireTaskSlot() throws InterruptedException {
        synchronized (LOCK) {
            while (TASKS.get() + EXTRAS.get() >= limit()) LOCK.wait(250);
            TASKS.incrementAndGet();
        }
    }

    /**
     * Runs {@code tasks} in parallel: the first on the calling thread (a task's own slot), the rest on the shared
     * pool. The caller must already hold {@code tasks.size() - 1} extra slots from {@link #acquireUpTo}; each is
     * given back as its task ends. Waits for all of them and rethrows the first failure.
     */
    public static <T> java.util.List<T> runAll(java.util.List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        java.util.List<java.util.concurrent.Future<T>> futures = new java.util.ArrayList<>();
        for (int i = 1; i < tasks.size(); i++) {
            java.util.concurrent.Callable<T> c = tasks.get(i);
            futures.add(pool().submit(() -> { try { return c.call(); } finally { release(1); } }));
        }
        java.util.List<T> out = new java.util.ArrayList<>();
        Throwable first = null;
        try { out.add(tasks.get(0).call()); } catch (Throwable t) { first = t; }
        for (java.util.concurrent.Future<T> f : futures) {
            try { out.add(f.get()); }
            catch (java.util.concurrent.ExecutionException e) { if (first == null) first = e.getCause(); }
            catch (InterruptedException e) {
                for (java.util.concurrent.Future<T> g : futures) g.cancel(true);
                Thread.currentThread().interrupt();
                if (first == null) first = e;
            }
        }
        if (first == null) return out;
        if (first instanceof Exception ex) throw ex;
        if (first instanceof Error err) throw err;
        throw new RuntimeException(first);
    }

    public static void taskFinished() {
        TASKS.decrementAndGet();
        wake();
    }

    /** Takes up to {@code wanted} extra slots without waiting and returns how many it got (0 if none free). */
    public static int acquireUpTo(int wanted) { return acquireUpTo(wanted, limit()); }

    static int acquireUpTo(int wanted, int limit) {
        if (wanted <= 0) return 0;
        synchronized (LOCK) {
            int free = limit - TASKS.get() - EXTRAS.get();
            int got = Math.max(0, Math.min(wanted, free));
            EXTRAS.addAndGet(got);
            return got;
        }
    }

    public static boolean tryAcquire() { return acquireUpTo(1) == 1; }

    public static void release(int n) {
        if (n <= 0) return;
        EXTRAS.addAndGet(-n);
        wake();
    }

    private static void wake() { synchronized (LOCK) { LOCK.notifyAll(); } }

    /** For tests. */
    static void reset() { TASKS.set(0); EXTRAS.set(0); wake(); }
}
