package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.XmlStorageService;
import service.queue.SchedulerStatusSnapshot;

import javax.swing.Timer;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * Polls for the moment a watcher-enabled task's trigger mode falls back from
 * push (native OS watch / remote SSH push) to plain polling — or is newly
 * confirmed unsupported — and fires a callback so the rest of the UI can
 * surface it (toast, notification bell, notification panel).
 *
 * <p>Deliberately cross-process: it prefers the headless Daemon's exported
 * status snapshot (same file {@link EventMonitorPanel}/{@link MainWindow}
 * already read) when the Daemon is alive and fresh, and only falls back to
 * asking this process's own in-process {@link TaskSchedulerService} when the
 * Daemon isn't running this task. This matters because in the normal setup
 * (see {@code MainWindow}'s constructor) the Daemon — not the GUI — is the
 * one actually scheduling tasks, so a purely in-process listener on the
 * GUI's own scheduler would silently never fire.
 *
 * <p>Runs on a plain {@link Timer} (fires on the EDT), matching the existing
 * pattern {@link NotificationBell}'s badge-refresh timer already uses for
 * periodic {@code storage.loadTasks()} polling.
 */
public class WatchStatusMonitor {

    /** One observed fallback (or newly-confirmed-unsupported) transition. */
    public record Event(String taskId, String taskName, String fromMode, String toMode,
                         String detail, LocalDateTime at) {}

    private static final long DAEMON_STALE_MS = SchedulerStatusSnapshot.DEFAULT_STALE_MS;
    private static final int POLL_INTERVAL_MS = 5000;
    private static final int MAX_EVENTS = 100;

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private final Path daemonStatusFile;
    private final Consumer<Event> onNotableTransition;

    // taskId -> last-seen mode name (raw TaskSchedulerService.WatchMode#name()).
    // Not persisted across restarts — a task's mode is simply "not yet known"
    // on first poll after launch, which is intentionally NOT treated as a
    // transition (we only want to notify on an actual *change*, not on the
    // first observation of the app's current state).
    private final Map<String, String> lastKnownMode = new HashMap<>();
    private final Deque<Event> recentEvents = new ArrayDeque<>();
    private final Timer timer;

    public WatchStatusMonitor(XmlStorageService storage, TaskSchedulerService scheduler,
                               Consumer<Event> onNotableTransition) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.daemonStatusFile = storage.getDataDir().toPath().resolve("scheduler-status-daemon.dat");
        this.onNotableTransition = onNotableTransition;
        this.timer = new Timer(POLL_INTERVAL_MS, e -> poll());
        this.timer.setInitialDelay(3000);
    }

    public void start() {
        timer.start();
        poll(); // establish baseline immediately rather than waiting for the first tick
    }

    public void stop() {
        timer.stop();
    }

    /** Most-recent-first list of recent fallback transitions, for display in NotificationPanel. */
    public synchronized List<Event> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public synchronized int getEventCount() {
        return recentEvents.size();
    }

    /** Clears the recorded event history (and so the bell badge's contribution
     *  from it) — does not affect live watch/push state, only this local
     *  record of past transitions. Called from the Watcher tab's "Clear"
     *  button once the operator has seen and dealt with the fallbacks. */
    public synchronized void clearEvents() {
        recentEvents.clear();
    }

    private void poll() {
        boolean daemonFresh = SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS);
        SchedulerStatusSnapshot snap = daemonFresh ? SchedulerStatusSnapshot.read(daemonStatusFile) : null;
        Map<String, SchedulerStatusSnapshot.WatchEntry> daemonEntries = new HashMap<>();
        if (snap != null) {
            for (SchedulerStatusSnapshot.WatchEntry w : snap.getWatchEntries()) {
                daemonEntries.put(w.taskId(), w);
            }
        }

        for (ScheduledTask t : storage.loadTasks()) {
            if (t.getTaskType() != ScheduledTask.TaskType.FILE_TRANSFER || !t.isWatcherEnabled()) continue;

            String mode;
            String detail;
            SchedulerStatusSnapshot.WatchEntry daemonEntry = daemonEntries.get(t.getId());
            if (daemonEntry != null) {
                mode = daemonEntry.mode();
                detail = daemonEntry.detail();
            } else {
                // Daemon not alive, or alive but hasn't mentioned this task yet
                // (e.g. brand new, or GUI is itself the active scheduler) —
                // fall back to this process's own live view.
                TaskSchedulerService.WatchStatus status = scheduler.getWatchStatus(t);
                mode = status.mode().name();
                detail = status.detail();
            }
            if ("NOT_APPLICABLE".equals(mode)) continue;

            String prev = lastKnownMode.put(t.getId(), mode);
            if (prev != null && isNotable(prev, mode)) {
                Event ev = new Event(t.getId(), t.getName(), prev, mode, detail, LocalDateTime.now());
                synchronized (this) {
                    recentEvents.addFirst(ev);
                    while (recentEvents.size() > MAX_EVENTS) recentEvents.removeLast();
                }
                if (onNotableTransition != null) onNotableTransition.accept(ev);
            }
        }
    }

    /** True for "was actively pushing, now polling" and for "just confirmed
     *  unsupported" — the two transitions worth interrupting the operator for.
     *  Plain POLLING_ONLY -&gt; POLLING_ONLY re-evaluations, and the very first
     *  observation of any task's mode, are not notable. */
    private boolean isNotable(String prev, String cur) {
        boolean wasPushing = prev.equals("NATIVE_WATCH") || prev.equals("REMOTE_PUSH");
        boolean nowPolling = cur.equals("POLLING_ONLY") || cur.equals("POLLING_ONLY_UNSUPPORTED");
        boolean newlyUnsupported = cur.equals("POLLING_ONLY_UNSUPPORTED") && !prev.equals("POLLING_ONLY_UNSUPPORTED");
        return (wasPushing && nowPolling) || newlyUnsupported;
    }
}
