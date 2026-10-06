package ui;

import model.EventKind;
import model.ScheduledTask;
import model.TaskRunRecord;
import service.RunHistoryService;
import service.TaskSchedulerService;
import service.queue.SchedulerStatusSnapshot;
import service.queue.TaskDueEvent;
import ui.monitor.*;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Live monitor for the event-driven scheduler: everything that happens to
 * tasks, watchers and settings, in one feed, plus the live state that has
 * not become history yet.
 *
 * <h3>Layout</h3>
 * <pre>
 *  ┌ summary chips: scheduler · workers · running · queued · watchers · attention ┐
 *  │ search · task · range · level · Pause · Options · Export                      │
 *  │ Show: Runs  Watchers  Queue  System                                           │
 *  │ ┌──────────── feed (grouped by run) ───────────┐ ┌─ details of selection ──┐ │
 *  │ │ ▸ 12:03:11  Succeeded  Invoices  Run · 3 …   │ │ what / why / files /    │ │
 *  │ │   12:03:09  Info       Invoices  Change …    │ │ timeline / raw log      │ │
 *  │ └───────────────────────────────────────────────┘ ├─ running · next · watch ┤ │
 *  └───────────────────────────────────────────────────┴─────────────────────────┘
 * </pre>
 *
 * <h3>Where data comes from</h3>
 * History (what already happened) always comes from the shared run-history
 * database both processes write to, so it survives restarts and shows the
 * Daemon's activity as well as the GUI's. Live state (running / queued /
 * watchers / workers) comes from whichever process is actually scheduling —
 * in-process for the GUI, or the Daemon's exported status snapshot.
 *
 * <h3>Performance</h3>
 * All database reads happen on a background thread. A one-row "change token"
 * is checked every second and the event query only re-runs when something was
 * actually written; the heavy {@code details} log column is never read for the
 * feed, only for the one selected row (see {@link DetailPane}). The EDT only
 * ever receives finished, classified results.
 *
 * <p>Purely observational: nothing here changes scheduler state.
 */
public class EventMonitorPanel extends JPanel {

    private static final long DAEMON_STALE_MS = SchedulerStatusSnapshot.DEFAULT_STALE_MS;
    private static final int LOAD_LIMIT = 5_000;           // rows per table
    private static final int STATS_QUERY_LIMIT = 20_000;
    private static final int REFRESH_MS = 1000;
    private static final long TASKS_REFRESH_MS = 5_000;
    private static final long STATS_REFRESH_MS = 5_000;
    private static final int MAX_TOASTS_PER_BATCH = 3;
    private static final java.time.Duration TOAST_MAX_AGE = java.time.Duration.ofSeconds(90);

    private final TaskSchedulerService scheduler;
    private final Path daemonStatusFile;
    private final MonitorPrefs prefs = new MonitorPrefs();

    private final SummaryBar summary = new SummaryBar();
    private final EventFeedPanel feed;
    private final DetailPane detail;
    private final QueuePanel queue = new QueuePanel();
    private final StatisticsPanel statsPanel = new StatisticsPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel centerHolder = new JPanel(new BorderLayout());

    private final javax.swing.Timer refreshTimer;
    private final java.util.function.Consumer<TaskRunRecord> historyListener;

    // Background loading
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-monitor-loader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean reloadAgain = new AtomicBoolean();
    private volatile boolean forceNext;
    private volatile boolean stopped;
    private volatile Map<String, ScheduledTask> latestById = Map.of();
    private volatile long lastTasksMs;
    private String lastToken;                 // loader thread only
    private int lastRangeIndex = -1;          // loader thread only
    private final AtomicBoolean statsLoading = new AtomicBoolean();
    private long lastStatsMs;                 // EDT only

    // Toasts
    private ToastManager toasts;
    private final Set<String> toastSeen = new HashSet<>();
    private boolean toastBaseline;

    public EventMonitorPanel(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        this.daemonStatusFile = scheduler.getStorage().getDataDir().toPath().resolve("scheduler-status-daemon.dat");

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        feed = new EventFeedPanel(prefs);
        detail = new DetailPane(scheduler.getRunHistoryService(), feed::showOnlyTask);
        feed.setSelectionListener(detail::setRow);

        summary.set("scheduler", "Scheduler", "\u2026", null, null);
        summary.set("workers", "Workers", "\u2014", null, "Worker threads busy / total");
        summary.set("running", "Running", "0", null, "Tasks executing right now");
        summary.set("queued", "Queued", "0", null, "Runs waiting for their scheduled time");
        summary.set("watchers", "Watchers", "\u2014", null, "How watcher-enabled tasks are being triggered");
        summary.set("attention", "Attention", "\u2026", null, "Click to show only warnings and errors");
        summary.onClick("attention", () -> prefs.setLevelIndex(1));

        JPanel eventsTab = new JPanel(new BorderLayout());
        eventsTab.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        eventsTab.add(summary, BorderLayout.NORTH);
        eventsTab.add(centerHolder, BorderLayout.CENTER);

        JPanel statsTab = new JPanel(new BorderLayout());
        statsTab.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        statsTab.add(statsPanel, BorderLayout.CENTER);

        tabs.addTab("Events", VectorIcons.pulse(new Color(0x5C7A45), 14), eventsTab);
        tabs.addTab("Statistics", VectorIcons.sliders(new Color(0x8A7A66), 14), statsTab);
        add(tabs, BorderLayout.CENTER);

        statsPanel.setRangeChangeListener(hours -> refreshStats(true));
        tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == 1) refreshStats(true); });

        layoutSide();
        prefs.addListener(() -> { layoutSide(); requestLoad(true); });

        // Same-process writes arrive instantly through these listeners; the 1s timer plus the
        // change token below cover the headless Daemon, which is a separate JVM and can never
        // reach them.
        historyListener = rec -> requestLoad(false);
        scheduler.getRunHistoryService().addRunListener(historyListener);
        scheduler.getRunHistoryService().addActivityListener(historyListener);

        refresh();
        requestLoad(true);
        refreshTimer = new javax.swing.Timer(REFRESH_MS, e -> refresh());
        refreshTimer.start();
    }

    /** Stops all background work. Call when the enclosing window is disposed. */
    public void stopRefreshing() {
        stopped = true;
        if (refreshTimer != null) refreshTimer.stop();
        loader.shutdownNow();
        // Each open/close of the window builds a fresh panel — without removing these, every reopen
        // would stack another listener onto the shared RunHistoryService, firing forever.
        scheduler.getRunHistoryService().removeRunListener(historyListener);
        scheduler.getRunHistoryService().removeActivityListener(historyListener);
    }

    // ── Layout ──────────────────────────────────────────────────────

    private JComponent framed(JComponent c, int minWidth) {
        JPanel p = new JPanel(new BorderLayout());
        Color border = UIManager.getColor("Component.borderColor");
        p.setBorder(BorderFactory.createLineBorder(border != null ? border : Color.LIGHT_GRAY));
        p.add(c, BorderLayout.CENTER);
        p.setMinimumSize(new Dimension(minWidth, 80));
        return p;
    }

    /** Rebuilds the feed/side-pane arrangement to match the Options toggles. */
    private void layoutSide() {
        centerHolder.removeAll();
        boolean d = prefs.showDetail(), q = prefs.showQueue();
        JComponent side = null;
        if (d && q) {
            JSplitPane v = new JSplitPane(JSplitPane.VERTICAL_SPLIT, framed(detail, 280), framed(queue, 280));
            v.setResizeWeight(0.66);
            v.setBorder(null);
            v.setContinuousLayout(true);
            v.setDividerSize(8);
            SwingUtilities.invokeLater(() -> v.setDividerLocation(0.66));
            side = v;
        } else if (d) {
            side = framed(detail, 280);
        } else if (q) {
            side = framed(queue, 280);
        }
        if (side == null) {
            centerHolder.add(feed, BorderLayout.CENTER);
        } else {
            JSplitPane h = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, feed, side);
            h.setResizeWeight(0.72);
            h.setBorder(null);
            h.setContinuousLayout(true);
            h.setDividerSize(8);
            feed.setMinimumSize(new Dimension(420, 120));
            centerHolder.add(h, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> h.setDividerLocation(0.72));
        }
        centerHolder.revalidate();
        centerHolder.repaint();
    }

    // ── Background loading ──────────────────────────────────────────

    /** Asks for a (re)load of the event history. Cheap to call often: it coalesces, and skips the query when nothing changed. */
    private void requestLoad(boolean force) {
        if (stopped) return;
        if (force) forceNext = true;
        if (!loading.compareAndSet(false, true)) { reloadAgain.set(true); return; }
        final boolean f = forceNext;
        forceNext = false;
        try {
            loader.execute(() -> load(f));
        } catch (RejectedExecutionException e) {
            loading.set(false);
        }
    }

    private void load(boolean force) {
        try {
            RunHistoryService rhs = scheduler.getRunHistoryService();
            long now = System.currentTimeMillis();
            if (force || now - lastTasksMs > TASKS_REFRESH_MS) {
                try {
                    latestById = scheduler.getStorage().loadTasks().stream()
                            .collect(Collectors.toMap(ScheduledTask::getId, t -> t, (a, b) -> a));
                    lastTasksMs = now;
                } catch (Exception ignored) { /* keep the previous snapshot */ }
            }

            // Read the token BEFORE querying: a row written mid-query then changes the token and is
            // picked up on the next pass instead of being missed.
            String token = rhs.getChangeToken();
            int rangeIdx = prefs.rangeIndex();
            if (!force && token.equals(lastToken) && rangeIdx == lastRangeIndex) return;

            MonitorPrefs.Range range = prefs.range();
            LocalDateTime from = range.window() != null ? LocalDateTime.now().minus(range.window()) : null;
            List<TaskRunRecord> runs = rhs.queryRunSummaries(from, LOAD_LIMIT);
            List<TaskRunRecord> acts = rhs.queryActivitySummaries(from, LOAD_LIMIT);

            Map<String, ScheduledTask> byId = latestById;
            List<MonitorEvent> events = new ArrayList<>(runs.size() + acts.size());
            for (TaskRunRecord r : runs) events.add(EventClassifier.classify(r, MonitorEvent.Table.RUN, byId));
            for (TaskRunRecord r : acts) events.add(EventClassifier.classify(r, MonitorEvent.Table.ACTIVITY, byId));
            boolean limited = runs.size() >= LOAD_LIMIT || acts.size() >= LOAD_LIMIT;

            lastToken = token;
            lastRangeIndex = rangeIdx;
            SwingUtilities.invokeLater(() -> {
                if (stopped) return;
                feed.setEvents(events, limited);
                handleToasts(events);
            });
        } catch (Exception e) {
            // A locked/busy database just means "try again next tick" — never surface it as an error.
        } finally {
            loading.set(false);
            if (reloadAgain.getAndSet(false) && !stopped) SwingUtilities.invokeLater(() -> requestLoad(false));
        }
    }

    // ── Periodic EDT refresh: live scheduler state ──────────────────

    private void refresh() {
        if (stopped) return;
        Map<String, ScheduledTask> byId = latestById;
        boolean guiActive = scheduler.isStarted();
        boolean daemonFresh = !guiActive && SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS);
        SchedulerStatusSnapshot snap = daemonFresh ? SchedulerStatusSnapshot.read(daemonStatusFile) : null;
        boolean anyActive = guiActive || snap != null;

        List<QueuePanel.RunningRow> running = new ArrayList<>();
        List<QueuePanel.PendingRow> pending = new ArrayList<>();
        List<QueuePanel.WatchRow> watch = new ArrayList<>();
        Set<String> runningIds = new HashSet<>();
        int pool = 0, busy = 0;
        String source = "Stopped";

        if (guiActive) {
            source = "GUI";
            pool = scheduler.getWorkerPoolSize();
            busy = scheduler.getActiveWorkerCount();
            for (var t : scheduler.getInFlightTasks()) {
                runningIds.add(t.taskId());
                running.add(new QueuePanel.RunningRow(name(byId, t.taskId()), glyph(byId, t.taskId()), t.startedAt(), null));
            }
            for (TaskDueEvent e : scheduler.getPendingEvents()) {
                pending.add(new QueuePanel.PendingRow(name(byId, e.getTaskId()), glyph(byId, e.getTaskId()), e.getDueAt(), e.getAttempt(), e.isImmediate()));
            }
        } else if (snap != null) {
            source = "Daemon";
            pool = snap.getPoolSize();
            busy = snap.getActiveWorkers();
            for (String id : snap.getRunningTaskIds()) {
                runningIds.add(id);
                ScheduledTask t = byId.get(id);
                running.add(new QueuePanel.RunningRow(name(byId, id), glyph(byId, id), t != null ? t.getLastStartedAt() : null, null));
            }
            for (SchedulerStatusSnapshot.PendingEntry e : snap.getPending()) {
                pending.add(new QueuePanel.PendingRow(name(byId, e.taskId()), glyph(byId, e.taskId()), e.dueAt(), e.attempt(), false));
            }
        }
        pending.sort(Comparator.comparing(QueuePanel.PendingRow::dueAt));
        running.sort(Comparator.comparing(r -> r.startedAt() != null ? r.startedAt() : LocalDateTime.MAX));

        // Watchers: prefer the active scheduler's own view; fall back to this process for tasks it hasn't mentioned.
        Map<String, SchedulerStatusSnapshot.WatchEntry> daemonWatch = new HashMap<>();
        if (snap != null) for (SchedulerStatusSnapshot.WatchEntry w : snap.getWatchEntries()) daemonWatch.put(w.taskId(), w);
        int instant = 0, polling = 0;
        for (ScheduledTask t : byId.values()) {
            if (t.getTaskType() != ScheduledTask.TaskType.FILE_TRANSFER || !t.isWatcherEnabled()) continue;
            String mode, why;
            SchedulerStatusSnapshot.WatchEntry de = daemonWatch.get(t.getId());
            if (de != null) { mode = de.mode(); why = de.detail(); }
            else { TaskSchedulerService.WatchStatus s = scheduler.getWatchStatus(t); mode = s.mode().name(); why = s.detail(); }
            if ("NOT_APPLICABLE".equals(mode)) continue;
            String state = mode.equals("NATIVE_WATCH") || mode.equals("REMOTE_PUSH") ? "INSTANT"
                    : mode.equals("POLLING_ONLY_UNSUPPORTED") ? "UNSUPPORTED" : "POLLING";
            if (state.equals("INSTANT")) instant++; else polling++;
            watch.add(new QueuePanel.WatchRow(t.getName(), glyph(byId, t.getId()), state, why));
        }

        feed.setRunningTaskIds(runningIds);
        feed.setTaskChoices(byId.values().stream().collect(Collectors.toMap(ScheduledTask::getId, ScheduledTask::getName, (a, b) -> a)));
        feed.setBanner(anyActive ? null
                : "No scheduler is running \u2014 tasks are not being scheduled. Past events below are still available.", true);
        feed.tick();

        queue.update(running, pending, watch,
                snap != null ? "The Daemon is the active scheduler; per-task start times come from the task list." : null);

        // Summary chips
        summary.set("scheduler", "Scheduler", source, anyActive ? AppTheme.SUCCESS_FG : AppTheme.FAILED_FG,
                anyActive ? "The " + source + " is currently scheduling tasks" : "No GUI or Daemon scheduler is running");
        summary.set("workers", "Workers", anyActive ? busy + " / " + pool + " busy" : "\u2014", busy > 0 ? AppTheme.RUNNING_FG : null, null);
        summary.set("running", "Running", String.valueOf(running.size()), running.isEmpty() ? null : AppTheme.RUNNING_FG, null);
        summary.set("queued", "Queued", String.valueOf(pending.size()), null, null);
        summary.set("watchers", "Watchers", instant + polling == 0 ? "none" : instant + " instant" + (polling > 0 ? " \u00b7 " + polling + " polling" : ""),
                polling > 0 ? AppTheme.SKIPPED_FG : null, polling > 0 ? "Some watchers fell back to scheduled polling \u2014 see the list on the right" : null);
        int errs = feed.getErrorCount(), warns = feed.getWarningCount();
        if (errs + warns == 0) {
            summary.set("attention", "Attention", "All clear", AppTheme.SUCCESS_FG, "No warnings or errors in the selected time range");
        } else {
            summary.set("attention", "Attention", (errs > 0 ? errs + (errs == 1 ? " error" : " errors") : "")
                    + (errs > 0 && warns > 0 ? " \u00b7 " : "") + (warns > 0 ? warns + (warns == 1 ? " warning" : " warnings") : ""),
                    errs > 0 ? AppTheme.FAILED_FG : AppTheme.SKIPPED_FG, "Click to show only warnings and errors");
        }

        if (tabs.getSelectedIndex() == 1) refreshStats(false);
        requestLoad(false);   // cheap: just a token check unless something was written (e.g. by the Daemon)
    }

    private static String name(Map<String, ScheduledTask> byId, String id) {
        ScheduledTask t = byId.get(id);
        return t != null ? t.getName() : "(deleted task)";
    }

    private static String glyph(Map<String, ScheduledTask> byId, String id) {
        ScheduledTask t = byId.get(id);
        return t != null ? MonitorStyle.directionGlyph(t.getTransferDirection()) : "";
    }

    // ── Statistics tab (shares the same history database) ───────────

    private void refreshStats(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastStatsMs < STATS_REFRESH_MS) return;
        if (!statsLoading.compareAndSet(false, true)) return;
        lastStatsMs = now;
        final int rangeHours = statsPanel.getSelectedRangeHours();
        try {
            loader.execute(() -> {
                try {
                    LocalDateTime nowT = LocalDateTime.now();
                    LocalDateTime windowStart = nowT.minusHours(rangeHours);
                    // One query spanning the window and the equal-length window before it (for trend deltas).
                    // Summaries only: statistics never needs the large run-log column.
                    List<TaskRunRecord> both = scheduler.getRunHistoryService()
                            .queryRunSummaries(nowT.minusHours(2L * rangeHours), STATS_QUERY_LIMIT);
                    List<TaskRunRecord> current = new ArrayList<>(), prior = new ArrayList<>();
                    for (TaskRunRecord r : both) {
                        if (r.getStartedAt() == null) continue;
                        (r.getStartedAt().isBefore(windowStart) ? prior : current).add(r);
                    }
                    SwingUtilities.invokeLater(() -> { if (!stopped) statsPanel.update(current, prior); });
                } catch (Exception ignored) {
                    // History DB briefly busy — the next refresh catches up.
                } finally {
                    statsLoading.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            statsLoading.set(false);
        }
    }

    // ── Pop-up notifications ────────────────────────────────────────

    /**
     * Pops a toast for newly arrived events, according to the operator's choice: problems only
     * (default — failures and warnings), every completed run, or nothing. Capped per batch, with one
     * summary toast for the overflow, so a burst of failures cannot bury the screen. Events older than
     * a minute or so never toast: they are history that merely loaded late (e.g. after widening the
     * time range), not something that just happened.
     */
    private void handleToasts(List<MonitorEvent> events) {
        List<MonitorEvent> fresh = new ArrayList<>();
        for (MonitorEvent e : events) if (toastSeen.add(e.key())) fresh.add(e);
        if (toastSeen.size() > 20_000) { toastSeen.clear(); toastSeen.addAll(events.stream().map(MonitorEvent::key).toList()); }
        if (!toastBaseline) { toastBaseline = true; return; }   // opening the window must not dump a toast per past event

        MonitorPrefs.ToastMode mode = prefs.toastMode();
        if (mode == MonitorPrefs.ToastMode.OFF || fresh.isEmpty()) return;

        LocalDateTime cutoff = LocalDateTime.now().minus(TOAST_MAX_AGE);
        List<MonitorEvent> pick = new ArrayList<>();
        for (MonitorEvent e : fresh) {
            if (e.time().isBefore(cutoff)) continue;
            boolean wanted = mode == MonitorPrefs.ToastMode.ALL
                    ? e.isOutcome()
                    : e.severity().compareTo(EventKind.Severity.WARN) >= 0
                        && e.kind() != EventKind.RETRY_SCHEDULED   // its failed run already toasted
                        && e.kind().category() != EventKind.Category.QUEUE;
            if (wanted) pick.add(e);
        }
        if (pick.isEmpty()) return;
        pick.sort(Comparator.comparing(MonitorEvent::time));

        ToastManager tm = ensureToasts();
        if (tm == null) return;
        int shown = Math.min(pick.size(), MAX_TOASTS_PER_BATCH);
        for (int i = 0; i < shown; i++) {
            MonitorEvent e = pick.get(i);
            boolean bad = e.severity() == EventKind.Severity.ERROR;
            boolean warn = e.severity() == EventKind.Severity.WARN;
            Color accent = bad ? AppTheme.EARTH_RUST : warn ? AppTheme.EARTH_OCHRE : AppTheme.EARTH_MOSS;
            String icon = bad ? "\u2717" : warn ? "\u26A0" : "\u2713";
            String title = e.isOutcome() ? e.taskName() : e.kind().label() + " \u2014 " + e.taskName();
            String body = MonitorStyle.time(e.time()) + (e.summary().isEmpty() ? "" : "  \u00b7  " + e.summary());
            tm.showToast(title, body, accent, icon);
        }
        if (pick.size() > shown) {
            tm.showToast((pick.size() - shown) + " more event" + (pick.size() - shown == 1 ? "" : "s"),
                    "See the Events list for the rest.", AppTheme.EARTH_OCHRE, "\u26A0");
        }
    }

    private ToastManager ensureToasts() {
        if (toasts == null) {
            Window w = SwingUtilities.getWindowAncestor(this);
            if (w != null) toasts = new ToastManager(w);
        }
        return toasts;
    }
}
