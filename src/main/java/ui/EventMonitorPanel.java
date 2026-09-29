package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.TaskSchedulerService;
import service.queue.SchedulerStatusSnapshot;
import service.queue.TaskDueEvent;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Comparator;

/**
 * Live dashboard for the event-driven scheduler — see
 * {@link service.queue.TaskEventQueue} / {@link service.queue.TaskWorkerPool}.
 *
 * <p>Only one scheduler is ever meant to be active at a time — the Daemon is
 * primary when it's alive, the GUI only schedules on standby (see
 * {@code ui.MainWindow}) — so rather than two always-visible tabs each
 * showing its own possibly-empty/standby state, this is a single "Events"
 * tab whose title is relabeled every refresh to say which one is currently
 * doing the scheduling ("GUI", "Daemon", or "No Scheduler Active").
 *
 * <p>Pending events are necessarily live — read straight from whichever
 * process is actually running right now (in-process for the GUI, or from
 * the Daemon's periodically-exported snapshot file). The <b>completed</b>
 * events table, though, is backed by {@link service.RunHistoryService} — the
 * shared, on-disk run-history database both processes write to — rather
 * than either process's own in-memory activity feed. Two things fall out of
 * that: this survives an app restart/redeploy (an in-memory feed can't), and
 * it's already a unified history across both processes without needing to
 * merge two separate feeds.
 *
 * <p>Statistics tab: aggregate numbers pulled from the same shared
 * run-history database, so its totals reflect activity from either process
 * regardless of which one is running right now.
 *
 * Both tabs refresh on a 1s timer. Purely observational: nothing here
 * mutates scheduler state (beyond TaskSchedulerService's own standby→active
 * promotion, which is driven by MainWindow, not by this panel).
 */
public class EventMonitorPanel extends JPanel {

    // How stale a Daemon status file can be before we call it "offline".
    // The exporter writes every 2s (see TaskSchedulerService#enableStatusExport);
    // anything past ~4x that interval means the process most likely died
    // mid-tick rather than just being between writes.
    private static final long DAEMON_STALE_MS = SchedulerStatusSnapshot.DEFAULT_STALE_MS;
    private static final int ACTIVITY_LIMIT = 100;
    // Upper bound on rows pulled per Statistics refresh, covering both the
    // selected window and the equal-length prior window (for trend deltas)
    // in one query. Range-bound now (via the `from` param on queryRuns),
    // not count-bound like the old STATS_SAMPLE_LIMIT=500 was — this is
    // just a safety ceiling for a very chatty system on the 7d view, not
    // the thing actually limiting what the stats reflect.
    private static final int STATS_QUERY_LIMIT = 20_000;
    private static final int REFRESH_MS = 1000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TaskSchedulerService scheduler;
    private final Path daemonStatusFile;

    private JTabbedPane tabs;
    private QueueMonitorView eventsView;
    private StatisticsPanel statsPanel;
    private WorkerActivityStrip workerStrip;

    private Timer refreshTimer;
    private java.util.function.Consumer<TaskRunRecord> runRecordedListener;
    private java.util.function.Consumer<TaskRunRecord> activityRecordedListener;

    // Small "an event just fired" popup, scoped to this panel/window — see
    // popEventToasts(). Lazily created once this panel is actually showing
    // in a window (needs a Window ancestor to anchor to).
    private ToastManager eventToasts;
    // Run-history row ids already popped, so the same run doesn't toast
    // twice on the next 1s refresh tick. Bounded/trimmed alongside the feed
    // itself so this can't grow unbounded over a long-running session.
    private final Set<Long> seenRunIds = new LinkedHashSet<>();
    private boolean baselineEstablished = false;
    // Latest task snapshot, refreshed every tick — read by the activity-row
    // click listener (registered once, in the constructor) so a click can
    // always resolve the clicked event's task type (FILE_TRANSFER vs other)
    // without needing to reload storage synchronously on the EDT.
    private volatile Map<String, ScheduledTask> latestById = Map.of();

    public EventMonitorPanel(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        this.daemonStatusFile = scheduler.getStorage().getDataDir().toPath().resolve("scheduler-status-daemon.dat");

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        eventsView = new QueueMonitorView();
        statsPanel = new StatisticsPanel();
        workerStrip = new WorkerActivityStrip();

        eventsView.setActivityRowClickListener((row, screenLoc) ->
                ActivityEventPopup.show(eventsView, screenLoc, row, latestById, scheduler.getRunHistoryService()));

        // Picking a different range chip changes what refreshStatsTab()
        // queries on the next tick — no need to force an immediate refresh
        // here too, the 1s timer will pick it up within a beat.
        statsPanel.setRangeChangeListener(hours -> refreshStatsTab());

        tabs = new JTabbedPane();
        tabs.addTab("Scheduler", VectorIcons.pulse(new Color(0x5C7A45), 14), wrap(eventsView));
        tabs.addTab("Statistics", VectorIcons.sliders(new Color(0x8A7A66), 14), wrap(statsPanel));
        add(workerStrip, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        refresh();
        refreshTimer = new Timer(REFRESH_MS, e -> refresh());
        refreshTimer.start();

        // Real event-bus push, not just the 1s poll above: any same-process
        // write (any tab, since they all share this one TaskSchedulerService)
        // triggers an immediate refresh instead of waiting for the next tick.
        // The poll timer stays as a safety net for cross-process activity —
        // a run the headless Daemon records can never reach these listeners,
        // since that's a separate JVM entirely.
        runRecordedListener = rec -> SwingUtilities.invokeLater(this::refresh);
        activityRecordedListener = rec -> SwingUtilities.invokeLater(this::refresh);
        scheduler.getRunHistoryService().addRunListener(runRecordedListener);
        scheduler.getRunHistoryService().addActivityListener(activityRecordedListener);
    }

    private JComponent wrap(JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    /** Stops the internal refresh timer. Call when the enclosing window is disposed. */
    public void stopRefreshing() {
        if (refreshTimer != null) refreshTimer.stop();
        if (workerStrip != null) workerStrip.dispose();
        // Each EventMonitorWindow open/close cycle constructs a fresh
        // EventMonitorPanel (see EventMonitorWindow's class doc) — without
        // removing these here, every reopen would stack another pair of
        // listeners onto the shared RunHistoryService, each firing on every
        // future event forever, a classic listener leak.
        if (runRecordedListener != null) scheduler.getRunHistoryService().removeRunListener(runRecordedListener);
        if (activityRecordedListener != null) scheduler.getRunHistoryService().removeActivityListener(activityRecordedListener);
    }

    private void refresh() {
        if (scheduler == null) return;

        Map<String, ScheduledTask> byId;
        try {
            byId = scheduler.getStorage().loadTasks().stream()
                    .collect(Collectors.toMap(ScheduledTask::getId, t -> t, (a, b) -> a));
        } catch (Exception e) {
            byId = Map.of();
        }
        latestById = byId;

        boolean daemonFresh = SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS);
        boolean guiActive = scheduler.isStarted();

        refreshEventsTab(byId, guiActive, daemonFresh);
        refreshStatsTab();

        // See WorkerActivityStrip's doc comments: whichever process (GUI or
        // Daemon) is actually on standby vs. actively scheduling determines
        // where real worker activity is happening, and the GUI can only see
        // per-task detail for its own in-process pool — a Daemon-owned run
        // only ever shows up here as an aggregate busy count from the
        // shared status file, not which task or which direction.
        if (guiActive) {
            workerStrip.refresh(scheduler.getInFlightTasks(), byId, scheduler.getWorkerPoolSize());
        } else if (daemonFresh) {
            SchedulerStatusSnapshot daemonSnapshot = SchedulerStatusSnapshot.read(daemonStatusFile);
            if (daemonSnapshot != null) {
                workerStrip.refreshFromDaemonCounts(daemonSnapshot.getPoolSize(), daemonSnapshot.getActiveWorkers());
            }
        } else {
            workerStrip.refresh(List.of(), byId, scheduler.getWorkerPoolSize());
        }
    }

    /**
     * Only one scheduler is ever meant to be firing tasks at a time — the
     * Daemon takes priority, and the GUI only runs its own scheduler when
     * the Daemon isn't alive (see ui.MainWindow). This is what determines
     * both the "Scheduler" tab's title and where pending events come from;
     * the completed-events table below it always comes from the shared
     * run-history database regardless of which process is currently active.
     */
    private void refreshEventsTab(Map<String, ScheduledTask> byId, boolean guiActive, boolean daemonFresh) {
        String activeName;
        List<QueueMonitorView.PendingRow> pendingRows;
        int poolSize = 0, activeWorkers = 0;
        boolean anyActive;

        if (guiActive) {
            activeName = "GUI";
            anyActive = true;
            List<TaskDueEvent> pending = scheduler.getPendingEvents();
            pendingRows = pending.stream()
                    .map(e -> new QueueMonitorView.PendingRow(taskName(byId, e.getTaskId()),
                            scheduleType(byId, e.getTaskId()), e.getAttempt(), e.getDueAt()))
                    .collect(Collectors.toList());
            poolSize = scheduler.getWorkerPoolSize();
            activeWorkers = scheduler.getActiveWorkerCount();
        } else if (daemonFresh) {
            activeName = "Daemon";
            SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.read(daemonStatusFile);
            anyActive = snap != null;
            if (snap != null) {
                pendingRows = snap.getPending().stream()
                        .map(e -> new QueueMonitorView.PendingRow(taskName(byId, e.taskId()),
                                scheduleType(byId, e.taskId()), e.attempt(), e.dueAt()))
                        .collect(Collectors.toList());
                poolSize = snap.getPoolSize();
                activeWorkers = snap.getActiveWorkers();
            } else {
                pendingRows = List.of();
            }
        } else {
            activeName = "No Scheduler Active";
            anyActive = false;
            pendingRows = List.of();
        }

        tabs.setTitleAt(0, activeName);

        if (!anyActive) {
            eventsView.showUnavailable("No scheduler is currently running — tasks are not being scheduled.");
            return;
        }

        List<TaskRunRecord> recentRuns;
        List<TaskRunRecord> recentActivity;
        try {
            recentRuns = scheduler.getRunHistoryService().getRecentRuns(ACTIVITY_LIMIT);
        } catch (Exception ignored) {
            recentRuns = List.of(); // run-history DB briefly locked by a write — next refresh will catch up
        }
        try {
            recentActivity = scheduler.getRunHistoryService().queryActivityEvents(null, null, null, ACTIVITY_LIMIT);
        } catch (Exception ignored) {
            recentActivity = List.of();
        }

        // Merge both tables into one chronological feed for display — a
        // "Started"/"Detected" activity row belongs in the same live feed
        // as the eventual completion row for that same run, even though
        // they now live in separate tables (see RunHistoryService's class
        // doc for why they're split). Toasts, below, deliberately stay fed
        // only from recentRuns (real outcomes) — popping a toast for every
        // single task start/detection in addition to every completion would
        // be a lot of extra noise nobody asked for.
        List<QueueMonitorView.ActivityRow> activityRows = java.util.stream.Stream.concat(
                        recentRuns.stream().map(r -> toActivityRow(r, QueueMonitorView.RunSource.RUN_HISTORY)),
                        recentActivity.stream().map(r -> toActivityRow(r, QueueMonitorView.RunSource.ACTIVITY)))
                .sorted(Comparator.comparing(QueueMonitorView.ActivityRow::startedAt, Comparator.reverseOrder()))
                .limit(ACTIVITY_LIMIT)
                .collect(Collectors.toList());

        eventsView.update(poolSize, activeWorkers, pendingRows, activityRows);
        popEventToasts(byId, recentRuns);
        baselineEstablished = true;
    }

    private QueueMonitorView.ActivityRow toActivityRow(TaskRunRecord r, QueueMonitorView.RunSource source) {
        String name = r.getTaskName() != null && !r.getTaskName().isBlank() ? r.getTaskName() : "(unknown task)";
        boolean errored = r.getStatus() == TaskRunRecord.Status.FAILED;
        // Direction (inbound/outbound) isn't on TaskRunRecord itself — it's
        // per-task config, so it's looked up from the live task snapshot.
        // Falls back to null for a since-deleted task (row still renders,
        // just without a directional icon — see QueueMonitorView's renderer).
        ScheduledTask task = latestById.get(r.getTaskId());
        ScheduledTask.TransferDirection direction = task != null ? task.getTransferDirection() : null;
        return new QueueMonitorView.ActivityRow(r.getId(), source, r.getTaskId(), name, 0,
                r.getStartedAt(), r.getEndedAt(), errored, r.getReason(), r.getTaskType(), direction);
    }

    private void refreshStatsTab() {
        try {
            int rangeHours = statsPanel.getSelectedRangeHours();
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime windowStart = now.minusHours(rangeHours);
            LocalDateTime priorWindowStart = now.minusHours((long) rangeHours * 2);

            // One query covering both the current window and the equal-length
            // period immediately before it (for the trend deltas), split
            // client-side — cheaper than two round trips, and both windows
            // need the same status/task filters (none) so there's nothing to
            // gain from querying them separately. STATS_SAMPLE_LIMIT no longer
            // caps this: it's now range-bound instead of count-bound, so a 7d
            // view isn't silently truncated to whatever the last 500 rows
            // happen to cover across every task in the system.
            List<TaskRunRecord> both = scheduler.getRunHistoryService()
                    .queryRuns(null, null, priorWindowStart, null, STATS_QUERY_LIMIT);

            List<TaskRunRecord> current = new java.util.ArrayList<>();
            List<TaskRunRecord> prior = new java.util.ArrayList<>();
            for (TaskRunRecord r : both) {
                if (r.getStartedAt() == null) continue;
                if (!r.getStartedAt().isBefore(windowStart)) current.add(r);
                else prior.add(r);
            }
            statsPanel.update(current, prior);
        } catch (Exception ignored) {
            // Run-history DB briefly locked by a write — just skip this tick, next refresh will catch up.
        }
    }

    /**
     * Pops a small toast for every run-history row not already seen — i.e.
     * every task run that just completed (watcher-triggered or scheduled)
     * since the last refresh tick — with a short summary: task name,
     * success/failure, and timing.
     *
     * <p>On the very first call ({@code baselineEstablished} false), rows
     * are recorded as seen but nothing is popped — otherwise opening the
     * Event Monitor on an app that's already been running a while (or has
     * pre-existing run history from before a restart) would instantly dump
     * up to {@link #ACTIVITY_LIMIT} toasts.
     */
    private void popEventToasts(Map<String, ScheduledTask> byId, List<TaskRunRecord> recentRuns) {
        if (recentRuns.isEmpty()) return;
        ToastManager toasts = baselineEstablished ? ensureEventToasts() : null;

        // Feed is newest-first; walk it oldest-to-newest so toasts for a
        // burst of events appear (and stack) in the order they happened.
        for (int i = recentRuns.size() - 1; i >= 0; i--) {
            TaskRunRecord r = recentRuns.get(i);
            if (!seenRunIds.add(r.getId())) continue; // already popped this one
            if (toasts != null) {
                boolean errored = r.getStatus() == TaskRunRecord.Status.FAILED;
                String taskName = r.getTaskName() != null && !r.getTaskName().isBlank()
                        ? r.getTaskName() : taskName(byId, r.getTaskId());
                String title = (errored ? "\u26A0 " : "\u2713 ") + taskName;
                StringBuilder body = new StringBuilder();
                if (r.getStartedAt() != null) body.append("Started ").append(r.getStartedAt().format(TIME_FMT));
                if (r.getEndedAt() != null) body.append(body.length() > 0 ? " · " : "")
                        .append("Finished ").append(r.getEndedAt().format(TIME_FMT));
                if (r.getReason() != null && !r.getReason().isBlank()) {
                    body.append(" — ").append(r.getReason());
                } else if (!errored) {
                    body.append(" — completed successfully");
                }
                toasts.showToast(title, body.toString(),
                        errored ? AppTheme.EARTH_RUST : AppTheme.EARTH_MOSS,
                        errored ? "\u26A0" : "\u2713");
            }
        }

        // Keep the seen-set from growing forever across a long session —
        // bound it a little above ACTIVITY_LIMIT so entries still in the
        // visible feed are never re-popped after trimming.
        while (seenRunIds.size() > ACTIVITY_LIMIT * 2) {
            java.util.Iterator<Long> it = seenRunIds.iterator();
            it.next();
            it.remove();
        }
    }

    /** Lazily binds the toast popups to whichever window currently hosts
     *  this panel (the standalone Event Monitor window). Returns null if
     *  the panel isn't in a window yet (e.g. mid-construction). */
    private ToastManager ensureEventToasts() {
        if (eventToasts == null) {
            Window w = SwingUtilities.getWindowAncestor(this);
            if (w != null) eventToasts = new ToastManager(w);
        }
        return eventToasts;
    }

    private static String taskName(Map<String, ScheduledTask> byId, String taskId) {
        ScheduledTask t = byId.get(taskId);
        return t != null ? t.getName() : "(deleted task " + taskId + ")";
    }

    private static String scheduleType(Map<String, ScheduledTask> byId, String taskId) {
        ScheduledTask t = byId.get(taskId);
        return t != null ? t.getScheduleType().name() : "—";
    }
}
