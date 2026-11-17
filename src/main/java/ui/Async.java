package ui;

import service.BackgroundJobs;
import service.Workers;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tiny helpers for keeping blocking work (SQLite, files, processes) off the
 * Swing Event Dispatch Thread. Results are always delivered back on the EDT.
 */
public final class Async {
    private Async() {}

    private static final ExecutorService POOL = Workers.pool(Workers.Lane.UI);
    /** Single thread: preserves submission order (used for audit-log writes). */
    private static final ExecutorService SERIAL = Workers.newPool(Workers.Lane.ORDERED, 1);
    /** For user dialogs that legitimately block for minutes (device-code sign-in): never ties up the shared UI lane. */
    private static final ExecutorService LONG = Workers.newCachedPool(Workers.Lane.UI);
    private static final ConcurrentHashMap<Object, AtomicLong> LATEST = new ConcurrentHashMap<>();

    /** Fire-and-forget background work. */
    public static void run(Runnable work) { run("Background task", false, work); }

    /**
     * Named background work. {@code audited} jobs (user-triggered actions) always record a
     * start and finish event in the Event Monitor; others only when they fail or run slow.
     * The work receives its {@link BackgroundJobs.Job} so it can report a soft failure.
     */
    public static void run(String name, boolean audited, Runnable work) {
        runJob(name, audited, j -> work.run());
    }

    public static void runJob(String name, boolean audited, java.util.function.Consumer<BackgroundJobs.Job> work) {
        POOL.execute(() -> {
            try (BackgroundJobs.Job j = BackgroundJobs.begin(name, audited)) {
                try { work.accept(j); } catch (Throwable t) { j.fail(t); report(t); }
            }
        });
    }

    /**
     * Run a SwingWorker on a named worker lane instead of Swing's private, unnamed pool, and record it as a
     * user-triggered event. {@code longRunning} workers get their own thread so they never starve screen loads.
     */
    public static void execute(String name, boolean audited, boolean longRunning, javax.swing.SwingWorker<?, ?> w) {
        (longRunning ? LONG : POOL).execute(() -> {
            try (BackgroundJobs.Job j = BackgroundJobs.begin(name, audited)) { w.run(); }
        });
    }

    /** Background work that must run in submission order. */
    public static void inOrder(Runnable work) {
        SERIAL.execute(() -> {
            try { BackgroundJobs.track("Audit log write", false, work); } catch (Throwable t) { report(t); }
        });
    }

    /** Run {@code work} in the background and hand the result to {@code onEdt} on the EDT. */
    public static <T> void call(Supplier<T> work, Consumer<T> onEdt, Consumer<Throwable> onError) {
        call("Background load", work, onEdt, onError);
    }

    public static <T> void call(String name, Supplier<T> work, Consumer<T> onEdt, Consumer<Throwable> onError) {
        POOL.execute(() -> {
            T result;
            try (BackgroundJobs.Job job = BackgroundJobs.begin(name, false)) {
                try {
                    result = work.get();
                } catch (Throwable t) {
                    job.fail(t);
                    if (onError != null) SwingUtilities.invokeLater(() -> onError.accept(t));
                    else report(t);
                    return;
                }
            }
            SwingUtilities.invokeLater(() -> onEdt.accept(result));
        });
    }

    /**
     * Latest-wins variant: if a newer call with the same key is submitted before
     * this one finishes, this result is discarded (stale-result guard).
     */
    public static <T> void latest(Object key, Supplier<T> work, Consumer<T> onEdt, Consumer<Throwable> onError) {
        AtomicLong gen = LATEST.computeIfAbsent(key, k -> new AtomicLong());
        long mine = gen.incrementAndGet();
        call(nameFor(key), work, r -> { if (gen.get() == mine) onEdt.accept(r); },
                t -> { if (gen.get() == mine && onError != null) onError.accept(t); });
    }

    /** Readable job name for the Event Monitor: a String key is used as-is, an object becomes "<Class> refresh". */
    private static String nameFor(Object key) {
        if (key instanceof String s) return s;
        return key.getClass().getSimpleName() + " refresh";
    }

    private static void report(Throwable t) {
        System.err.println("[Async] background task failed: " + t);
    }
}
