package service;

import model.EventKind;
import model.TaskRunRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the app's own background threads so they show up in the Event Monitor.
 *
 * <ul>
 *   <li><b>Live:</b> every job currently running is listed by {@link #running()} (the monitor's
 *       queue shows it).</li>
 *   <li><b>Audited jobs</b> (user-triggered actions such as installing the service) record a
 *       "started" and a "finished/failed" event with the duration.</li>
 *   <li><b>Routine jobs</b> (polls, refreshes) are not recorded when they succeed quickly — that
 *       would flood the history — but a failure or a slow run is, throttled per job name.</li>
 * </ul>
 * Events are written to their own database ({@link BackgroundLogService}) on a dedicated thread that is
 * never itself tracked, so recording can't loop.
 */
public final class BackgroundJobs {
    private BackgroundJobs() {}

    /** A job that is running right now. */
    public record Running(long id, String name, LocalDateTime startedAt, boolean audited, String thread) {}

    /** Per-name throttle for routine failure / slow events. */
    private static final long THROTTLE_MS = 60_000;

    private static final AtomicLong IDS = new AtomicLong();
    private static final ConcurrentHashMap<Long, Running> RUNNING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_NOTE = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Workers.newPool(Workers.Lane.ORDERED, 1);
    private static volatile BackgroundLogService log;

    /** Start recording events into the background-log database (call once the scheduler exists). */
    public static void attach(BackgroundLogService service) { log = service; }

    /** Jobs running now, oldest first. */
    public static List<Running> running() {
        List<Running> out = new ArrayList<>(RUNNING.values());
        out.sort(Comparator.comparing(Running::startedAt));
        return out;
    }

    /**
     * Human-readable list of what every worker lane is doing right now, one line per running job
     * ("ui-worker-2 · Load run history · 1.2 s"), longest-running first, capped at {@code max} lines.
     */
    public static List<String> liveLines(int max) {
        List<String> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        List<Running> jobs = running();
        for (Running r : jobs) {
            if (out.size() >= max) { out.add("\u2026and " + (jobs.size() - max) + " more"); break; }
            double secs = java.time.Duration.between(r.startedAt(), now).toMillis() / 1000.0;
            out.add(r.thread() + " \u00b7 " + r.name() + " \u00b7 " + String.format(java.util.Locale.ROOT, "%.1f s", secs));
        }
        return out;
    }

    /** Handle for one job; call {@link #fail} on a soft failure, then {@link #close()}. */
    public static final class Job implements AutoCloseable {
        private final long id;
        private final String name;
        private final boolean audited;
        private final LocalDateTime started = LocalDateTime.now();
        private final long t0 = System.nanoTime();
        private volatile String failure;
        private volatile Throwable error;
        private volatile String result;
        private final String thread = Thread.currentThread().getName();
        private boolean closed;

        private Job(String name, boolean audited) {
            this.id = IDS.incrementAndGet();
            this.name = name;
            this.audited = audited;
            RUNNING.put(id, new Running(id, name, started, audited, thread));
            if (audited) note(EventKind.BACKGROUND_STARTED, TaskRunRecord.Status.SUCCESS, name, "Started",
                    "Running on thread: " + thread, started, started);
        }

        /** Mark this job as failed without an exception (e.g. a non-zero exit code). */
        public void fail(String message) { this.failure = message; }

        public void fail(Throwable t) { this.error = t; }

        /** A short outcome ("removed 120 rows") appended to the finish event of an audited job. */
        public void result(String summary) { this.result = summary; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            RUNNING.remove(id);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LocalDateTime ended = LocalDateTime.now();
            String problem = failure != null ? failure : error != null ? describe(error) : null;
            if (problem != null) {
                String details = (error != null ? stack(error) : problem) + "\n\nThread: " + thread;
                note(EventKind.BACKGROUND_FAILED, TaskRunRecord.Status.FAILED, name,
                        "Failed after " + fmt(ms) + " — " + problem, details, started, ended, !audited);
            } else if (audited) {
                note(EventKind.BACKGROUND_COMPLETED, TaskRunRecord.Status.SUCCESS, name,
                        "Finished in " + fmt(ms) + (result != null ? " \u2014 " + result : ""),
                        (result != null ? result + "\n\n" : "") + "Thread: " + thread, started, ended);
            } else if (ms >= ActivityVisibility.slowJobMs()) {
                note(EventKind.BACKGROUND_SLOW, TaskRunRecord.Status.SUCCESS, name,
                        "Took " + fmt(ms) + " (your threshold is " + fmt(ActivityVisibility.slowJobMs()) + ")", null, started, ended, true);
            }
        }
    }

    /** Begin tracking a job. Use in try-with-resources; {@code audited} jobs always record start and finish. */
    public static Job begin(String name, boolean audited) { return new Job(name, audited); }

    /** Run {@code work} as a tracked job; an exception marks it failed and is rethrown. */
    public static void track(String name, boolean audited, Runnable work) {
        try (Job j = begin(name, audited)) {
            try { work.run(); } catch (RuntimeException | Error e) { j.fail(e); throw e; }
        }
    }

    /** Record a one-off event (e.g. a feed starting, a UI stall). Never blocks the caller. */
    public static void note(EventKind kind, TaskRunRecord.Status status, String name, String message, String details) {
        LocalDateTime now = LocalDateTime.now();
        note(kind, status, name, message, details, now, now, false);
    }

    /** Like {@link #note} but dropped if the same name+kind was recorded within the throttle window. */
    public static void noteThrottled(EventKind kind, TaskRunRecord.Status status, String name, String message,
                                     String details, long windowMs) {
        if (throttled(kind, name, windowMs)) return;
        note(kind, status, name, message, details);
    }

    private static void note(EventKind kind, TaskRunRecord.Status status, String name, String message,
                             String details, LocalDateTime start, LocalDateTime end) {
        note(kind, status, name, message, details, start, end, false);
    }

    private static void note(EventKind kind, TaskRunRecord.Status status, String name, String message,
                             String details, LocalDateTime start, LocalDateTime end, boolean throttle) {
        if (throttle && throttled(kind, name, THROTTLE_MS)) return;
        BackgroundLogService h = log;
        if (h == null) return;
        WRITER.execute(() -> {
            try {
                h.record(kind, name, status, message, details != null ? details : message,
                        start, end.isBefore(start) ? start : end);
            } catch (Throwable t) {
                System.err.println("[BackgroundJobs] could not record event: " + t);
            }
        });
    }

    private static boolean throttled(EventKind kind, String name, long windowMs) {
        long now = System.currentTimeMillis();
        String key = kind.name() + "|" + name;
        Long prev = LAST_NOTE.get(key);
        if (prev != null && now - prev < windowMs) return true;
        LAST_NOTE.put(key, now);
        return false;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null && !m.isBlank() ? ": " + m : "");
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static String fmt(long ms) {
        if (ms < 1000) return ms + " ms";
        Duration d = Duration.ofMillis(ms);
        if (d.toMinutes() > 0) return d.toMinutes() + " min " + (d.toSeconds() % 60) + " s";
        return String.format("%.1f s", ms / 1000.0);
    }
}
