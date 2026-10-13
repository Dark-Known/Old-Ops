package ui;

import service.BackgroundJobs;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;

/** Logs any single EDT event that takes longer than a threshold, with the stack that was blocking it. */
public final class EdtWatchdog extends EventQueue {
    private final long fallbackMs;
    private volatile long startedAt;
    private volatile boolean busy;
    private volatile int depth;
    private volatile StackTraceElement[] sample;
    private volatile Thread edt;

    private EdtWatchdog(long fallbackMs) { this.fallbackMs = fallbackMs; }

    /** The user's "report a UI stall after" setting, read live; the start argument is only the fallback. */
    private long thresholdMs() {
        try { return service.ActivityVisibility.uiStallMs(); } catch (RuntimeException e) { return fallbackMs; }
    }

    public static void start(long thresholdMs) {
        EdtWatchdog q = new EdtWatchdog(thresholdMs);
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(q);
        service.Workers.service("edt-watchdog", q::watch);
    }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        if (depth == 0) { edt = Thread.currentThread(); startedAt = System.nanoTime(); sample = null; busy = true; }
        depth++;
        try {
            super.dispatchEvent(event);
        } finally {
            depth--;
            if (depth == 0) {
                busy = false;
                long ms = (System.nanoTime() - startedAt) / 1_000_000;
                StackTraceElement[] s = sample;
                if (ms >= thresholdMs() && s != null) {
                    StringBuilder sb = new StringBuilder("[EDT stall] ").append(ms).append(" ms in ")
                            .append(event.getClass().getSimpleName());
                    int n = 0;
                    for (StackTraceElement e : s) {
                        if (e.getClassName().startsWith("java.") || e.getClassName().startsWith("javax.")
                                || e.getClassName().startsWith("sun.")) continue;
                        sb.append("\n    at ").append(e);
                        if (++n >= 6) break;
                    }
                    System.err.println(sb);
                    // Also leave a trace in the Event Monitor (at most one every 10 s).
                    BackgroundJobs.noteThrottled(model.EventKind.UI_STALL, model.TaskRunRecord.Status.SUCCESS,
                            "UI thread", "Window was unresponsive for " + ms + " ms handling "
                                    + event.getClass().getSimpleName(), sb.toString(), 10_000);
                }
            }
        }
    }

    private void watch() {
        while (true) {
            try { Thread.sleep(Math.max(20, thresholdMs() / 3)); } catch (InterruptedException e) { return; }
            Thread t = edt;
            if (busy && t != null && (System.nanoTime() - startedAt) / 1_000_000 >= thresholdMs() / 2) {
                sample = t.getStackTrace();
            }
        }
    }
}
