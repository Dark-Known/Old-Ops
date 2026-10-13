package ui;

import service.BackgroundJobs;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Background polling source shared by any number of UI subscribers.
 * <ul>
 *   <li>The loader runs on a worker thread, never on the EDT.</li>
 *   <li>Only the newest snapshot is delivered, on the EDT, and only if it changed.</li>
 *   <li>Loads are single-flight; {@link #refreshNow()} coalesces into one follow-up load.</li>
 *   <li>Polling runs only while at least one subscriber exists.</li>
 * </ul>
 */
public final class DataFeed<T> {
    private static final ScheduledExecutorService TICKER = service.Workers.scheduled(service.Workers.Lane.FEED);

    private final Supplier<T> loader;
    private final long periodMs;
    private final CopyOnWriteArrayList<Consumer<T>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private volatile T latest;
    private volatile boolean hasValue;
    private volatile boolean alwaysPublish;
    private volatile String name = "Live feed";
    private boolean announced;
    private ScheduledFuture<?> task;

    public DataFeed(long periodMs, Supplier<T> loader) {
        this.periodMs = periodMs;
        this.loader = loader;
    }

    /** Publish every load result even when equal to the previous one. */
    public DataFeed<T> alwaysPublish() { this.alwaysPublish = true; return this; }

    /** Name shown for this feed's background loads in the Event Monitor. */
    public DataFeed<T> named(String name) { this.name = name; return this; }

    /** Last delivered value (may be null before the first load). */
    public T latest() { return latest; }

    /** Subscribe on the EDT. Returns an unsubscribe action. */
    public Runnable subscribe(Consumer<T> onEdt) {
        synchronized (this) {
            // Adding the subscriber and reading the latest value happen under the same lock that
            // load() uses to publish, so a subscriber gets either the replay or the publish — never both.
            subscribers.add(onEdt);
            if (hasValue) { T v = latest; SwingUtilities.invokeLater(() -> onEdt.accept(v)); }
            if (task == null) task = TICKER.scheduleWithFixedDelay(this::load, 0, periodMs, TimeUnit.MILLISECONDS);
            if (!announced) {
                announced = true; // once per feed per app run — screens opening later reuse it
                BackgroundJobs.note(model.EventKind.FEED_STARTED, model.TaskRunRecord.Status.SUCCESS, name,
                        "Polling every " + (periodMs >= 1000 ? (periodMs / 1000) + " s" : periodMs + " ms")
                                + " in the background while a screen needs it", null);
            }
        }
        return () -> {
            subscribers.remove(onEdt);
            synchronized (this) {
                if (subscribers.isEmpty() && task != null) { task.cancel(false); task = null; }
            }
        };
    }

    /** Ask for a load soon (e.g. after the user changed something). */
    public void refreshNow() {
        if (loading.get()) { again.set(true); return; }
        TICKER.execute(this::load);
    }

    private void load() {
        if (!loading.compareAndSet(false, true)) { again.set(true); return; }
        try (BackgroundJobs.Job job = BackgroundJobs.begin(name, false)) {
            T v;
            try {
                v = loader.get();
            } catch (Throwable t) {
                job.fail(t);
                throw t;
            }
            List<Consumer<T>> toNotify = null;
            synchronized (this) {
                boolean changed = !hasValue || !Objects.equals(v, latest);
                latest = v;
                hasValue = true;
                if (changed || alwaysPublish) toNotify = new ArrayList<>(subscribers);
            }
            if (toNotify != null) {
                final List<Consumer<T>> targets = toNotify;
                SwingUtilities.invokeLater(() -> { for (Consumer<T> s : targets) s.accept(v); });
            }
        } catch (Throwable t) {
            System.err.println("[DataFeed] load failed: " + t);
        } finally {
            loading.set(false);
            if (again.compareAndSet(true, false)) TICKER.execute(this::load);
        }
    }
}
