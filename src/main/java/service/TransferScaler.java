package service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Runs work units over sessions that grow with the load.
 *
 * <p>The first worker (one SFTP session) runs on the calling thread, which is the task's own thread. While units
 * are still waiting after {@code rampMs}, one more worker is added on {@code extraPool}, up to
 * {@code maxWorkers}, but only if {@code grantExtra} hands out a slot from the shared thread budget. So a few
 * small files finish on one session and never open more, while a long backlog climbs to the maximum. Workers
 * exit when the queue is empty, so sessions close as the load drops.
 */
public final class TransferScaler {
    private TransferScaler() {}

    public static final class Result {
        public final boolean allOk;
        public final int peakWorkers;
        Result(boolean allOk, int peakWorkers) { this.allOk = allOk; this.peakWorkers = peakWorkers; }
    }

    /**
     * @param extraPool  where workers after the first run (the shared worker pool)
     * @param timer      runs the short, non-blocking "should another worker start?" checks
     * @param grantExtra takes a slot from the shared budget before each extra worker; false = none free now
     * @param returnExtra gives that slot back when such a worker ends
     * @param work       runs one unit and returns success
     */
    public static <T> Result run(List<T> units, int maxWorkers, long rampMs, ExecutorService extraPool,
                                 ScheduledExecutorService timer, BooleanSupplier grantExtra, Runnable returnExtra,
                                 Predicate<T> work, Runnable betweenUnits) throws InterruptedException {
        Deque<T> queue = new ArrayDeque<>(units);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger started = new AtomicInteger(1);
        int cap = Math.max(1, Math.min(maxWorkers, units.size()));
        List<Future<?>> extras = new ArrayList<>();
        boolean[] closed = {false};

        ScheduledFuture<?> tick = cap <= 1 ? null : timer.scheduleWithFixedDelay(() -> {
            try {
                if (isEmpty(queue) || started.get() >= cap) return;
                if (!grantExtra.getAsBoolean()) return;          // no slot free: try again next tick
                synchronized (extras) {
                    if (closed[0] || isEmpty(queue) || started.get() >= cap) { returnExtra.run(); return; }
                    started.incrementAndGet();
                    try {
                        extras.add(extraPool.submit(loop(queue, failures, work, betweenUnits, returnExtra)));
                    } catch (RuntimeException e) {
                        started.decrementAndGet();
                        returnExtra.run();
                    }
                }
            } catch (RuntimeException ignored) { /* a failed check just means no scale-up this tick */ }
        }, rampMs, rampMs, TimeUnit.MILLISECONDS);

        try {
            loop(queue, failures, work, betweenUnits, null).run();   // this thread is the first session
        } finally {
            if (tick != null) tick.cancel(false);
            synchronized (extras) { closed[0] = true; }
        }
        for (Future<?> f : new ArrayList<>(extras)) {
            try { f.get(); } catch (java.util.concurrent.ExecutionException e) { failures.incrementAndGet(); }
        }
        return new Result(failures.get() == 0, started.get());
    }

    private static <T> Runnable loop(Deque<T> queue, AtomicInteger failures, Predicate<T> work,
                                     Runnable betweenUnits, Runnable onExit) {
        return () -> {
            try {
                boolean first = true;
                while (true) {
                    T unit;
                    synchronized (queue) { unit = queue.poll(); }
                    if (unit == null || Thread.currentThread().isInterrupted()) return;
                    if (!first && betweenUnits != null) betweenUnits.run();
                    first = false;
                    boolean ok;
                    try { ok = work.test(unit); } catch (RuntimeException e) { ok = false; }
                    if (!ok) failures.incrementAndGet();
                }
            } finally {
                if (onExit != null) onExit.run();
            }
        };
    }

    private static <T> boolean isEmpty(Deque<T> queue) {
        synchronized (queue) { return queue.isEmpty(); }
    }
}
