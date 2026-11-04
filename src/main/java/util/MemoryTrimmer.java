package util;

import java.awt.Frame;
import java.awt.Window;

/**
 * Keeps the GUI's resident memory small. The live heap of the window is only
 * a few MB, but a JVM keeps whatever heap it grew to while loading tables and
 * dialogs. This daemon thread runs a collection shortly after start-up, and
 * again whenever the window has been in the background (minimised or not
 * focused) for a while, so the collector hands the unused heap back to the OS.
 * It never runs while the person is working in the window.
 */
public final class MemoryTrimmer {
    private static final long TICK_MS = 30_000;
    private static final long IDLE_MS = 60_000;
    private static final long MIN_GAP_MS = 5 * 60_000;
    private static volatile boolean started;

    private MemoryTrimmer() {}

    private static volatile java.io.File reportDir;
    private static volatile String reportWho = "app";

    /** Also writes memory-&lt;who&gt;.txt (heap, pools, top live classes) into dir every 10 minutes. */
    public static void start(java.io.File dir, String who) {
        reportDir = dir;
        reportWho = who;
        start();
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(MemoryTrimmer::loop, "memory-trimmer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        long lastTrim = 0, backgroundSince = 0, lastReport = 0;
        try {
            Thread.sleep(20_000);
            lastTrim = trim();          // after start-up, once the pages have loaded
            while (true) {
                Thread.sleep(TICK_MS);
                long now = System.currentTimeMillis();
                if (reportDir != null && now - lastReport >= 10 * 60_000L) {
                    lastReport = now;
                    MemoryReport.write(reportDir, reportWho);   // histogram forces a GC, so it shows retained objects only
                }
                // After a burst of work (a big transfer, a large folder listing, mail download) the
                // heap stays grown even though almost all of it is garbage. A collection with a small
                // live set takes tens of milliseconds, so reclaim it even while the window is in use.
                Runtime rt = Runtime.getRuntime();
                long reclaimable = rt.freeMemory();
                if (reclaimable > 96L * 1024 * 1024 && now - lastTrim >= 60_000) {
                    lastTrim = trim();
                    continue;
                }
                if (foreground()) { backgroundSince = 0; continue; }
                if (backgroundSince == 0) backgroundSince = now;
                if (now - backgroundSince >= IDLE_MS && now - lastTrim >= MIN_GAP_MS) {
                    lastTrim = trim();
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static boolean foreground() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0) continue;
            if (w.isActive()) return true;
        }
        return false;
    }

    private static long trim() {
        System.gc();
        return System.currentTimeMillis();
    }
}
