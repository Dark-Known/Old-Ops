package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.TaskSchedulerService;
import service.queue.SchedulerStatusSnapshot;
import service.queue.TaskDueEvent;
import service.queue.TaskWorkerPool.ActivityEntry;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Live dashboard for the event-driven scheduler — see
 * {@link service.queue.TaskEventQueue} / {@link service.queue.TaskWorkerPool}.
 *
 * Only one scheduler is ever meant to be active at a time — the Daemon is
 * primary when it's alive, the GUI only schedules on standby (see
 * {@code ui.MainWindow}) — so the header line and the two tabs' titles are
 * relabeled every refresh to say which one that currently is, rather than
 * presenting both as always-live:
 *  - <b>GUI Process</b> — this JVM's own scheduler, observed directly
 *    through {@link TaskSchedulerService}'s live accessors. Shows a
 *    "standby" placeholder while the Daemon is the one actually scheduling.
 *  - <b>Daemon Process</b> — the headless background daemon, a separate
 *    JVM. It can't be reached in-process, so this tab reads back the
 *    snapshot file the Daemon periodically exports (see
 *    {@link SchedulerStatusSnapshot}) to the shared data directory. Shows
 *    an "offline" placeholder if that file is missing or stale.
 *  - <b>Statistics</b> — aggregate numbers pulled from the shared
 *    run-history database, which both processes write to, so these totals
 *    reflect activity from either process regardless of which one is
 *    running right now.
 *
 * All three tabs, plus the header line, refresh on a 1s timer.
 * Purely observational: nothing here mutates scheduler state (beyond
 * TaskSchedulerService's own standby→active promotion, which is driven by
 * MainWindow, not by this panel).
 */
public class EventMonitorPanel extends JPanel {

    // How stale a Daemon status file can be before we call it "offline".
    // The exporter writes every 2s (see TaskSchedulerService#enableStatusExport);
    // anything past ~4x that interval means the process most likely died
    // mid-tick rather than just being between writes.
    private static final long DAEMON_STALE_MS = SchedulerStatusSnapshot.DEFAULT_STALE_MS;
    private static final int ACTIVITY_LIMIT = 100;
    private static final int STATS_SAMPLE_LIMIT = 500;
    private static final int REFRESH_MS = 1000;

    private final TaskSchedulerService scheduler;
    private final Path daemonStatusFile;

    private JLabel activeSchedulerLabel;
    private JTabbedPane tabs;
    private QueueMonitorView guiView;
    private QueueMonitorView daemonView;
    private StatisticsPanel statsPanel;

    private Timer refreshTimer;

    public EventMonitorPanel(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        this.daemonStatusFile = scheduler.getStorage().getDataDir().toPath().resolve("scheduler-status-daemon.dat");

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        activeSchedulerLabel = new JLabel("Active scheduler: checking...");
        activeSchedulerLabel.setFont(activeSchedulerLabel.getFont().deriveFont(Font.BOLD));
        activeSchedulerLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 2));
        add(activeSchedulerLabel, BorderLayout.NORTH);

        guiView = new QueueMonitorView();
        daemonView = new QueueMonitorView();
        statsPanel = new StatisticsPanel();

        tabs = new JTabbedPane();
        tabs.addTab("GUI Process", VectorIcons.pulse(new Color(0x5C7A45), 14), wrap(guiView));
        tabs.addTab("Daemon Process", VectorIcons.pulse(new Color(0x596F6F), 14), wrap(daemonView));
        tabs.addTab("Statistics", VectorIcons.sliders(new Color(0x8A7A66), 14), wrap(statsPanel));
        add(tabs, BorderLayout.CENTER);

        refresh();
        refreshTimer = new Timer(REFRESH_MS, e -> refresh());
        refreshTimer.start();
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

        boolean daemonFresh = SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS);
        boolean guiActive = scheduler.isStarted();

        refreshHeader(guiActive, daemonFresh);
        refreshGuiTab(byId, guiActive);
        refreshDaemonTab(byId, daemonFresh);
        refreshStatsTab();
    }

    /**
     * Only one scheduler is ever meant to be firing tasks at a time — the
     * Daemon takes priority, and the GUI only runs its own scheduler when
     * the Daemon isn't alive (see ui.MainWindow). This summary line, plus
     * the "(Active)"/"(Standby)"/"(Offline)" tab suffixes below, make that
     * hand-off visible here too instead of implying both are always live.
     */
    private void refreshHeader(boolean guiActive, boolean daemonFresh) {
        if (guiActive) {
            activeSchedulerLabel.setText("Active scheduler: GUI (this window)");
        } else if (daemonFresh) {
            activeSchedulerLabel.setText("Active scheduler: Daemon (background process)");
        } else {
            activeSchedulerLabel.setText("Active scheduler: none detected — tasks are not being scheduled");
        }
        tabs.setTitleAt(0, "GUI Process" + (guiActive ? " (Active)" : " (Standby)"));
        tabs.setTitleAt(1, "Daemon Process" + (daemonFresh ? " (Active)" : " (Offline)"));
    }

    private void refreshGuiTab(Map<String, ScheduledTask> byId, boolean guiActive) {
        if (!guiActive) {
            guiView.showUnavailable("GUI scheduler is on standby — the Daemon is currently handling scheduling.");
            return;
        }

        List<TaskDueEvent> pending = scheduler.getPendingEvents();
        List<ActivityEntry> activity = scheduler.getRecentActivity(ACTIVITY_LIMIT);

        List<QueueMonitorView.PendingRow> pendingRows = pending.stream()
                .map(e -> new QueueMonitorView.PendingRow(taskName(byId, e.getTaskId()),
                        scheduleType(byId, e.getTaskId()), e.getAttempt(), e.getDueAt()))
                .collect(Collectors.toList());
        List<QueueMonitorView.ActivityRow> activityRows = activity.stream()
                .map(a -> new QueueMonitorView.ActivityRow(taskName(byId, a.getTaskId()), a.getAttempt(),
                        a.getStartedAt(), a.getFinishedAt(), a.isErrored(), a.getErrorMessage()))
                .collect(Collectors.toList());

        guiView.update(scheduler.getWorkerPoolSize(), scheduler.getActiveWorkerCount(), pendingRows, activityRows);
    }

    private void refreshDaemonTab(Map<String, ScheduledTask> byId, boolean daemonFresh) {
        SchedulerStatusSnapshot snap = daemonFresh ? SchedulerStatusSnapshot.read(daemonStatusFile) : null;
        if (snap == null) {
            daemonView.showUnavailable(scheduler.isStarted()
                    ? "Daemon not running — the GUI scheduler is currently handling scheduling."
                    : "Daemon status unavailable — the background daemon may not be running.");
            return;
        }

        List<QueueMonitorView.PendingRow> pendingRows = snap.getPending().stream()
                .map(e -> new QueueMonitorView.PendingRow(taskName(byId, e.taskId()),
                        scheduleType(byId, e.taskId()), e.attempt(), e.dueAt()))
                .collect(Collectors.toList());
        List<QueueMonitorView.ActivityRow> activityRows = snap.getActivity().stream()
                .map(a -> new QueueMonitorView.ActivityRow(taskName(byId, a.taskId()), a.attempt(),
                        a.startedAt(), a.finishedAt(), a.errored(), a.errorMessage()))
                .collect(Collectors.toList());

        daemonView.update(snap.getPoolSize(), snap.getActiveWorkers(), pendingRows, activityRows);
    }

    private void refreshStatsTab() {
        try {
            List<TaskRunRecord> runs = scheduler.getRunHistoryService().getRecentRuns(STATS_SAMPLE_LIMIT);
            statsPanel.update(runs);
        } catch (Exception ignored) {
            // Run-history DB briefly locked by a write — just skip this tick, next refresh will catch up.
        }
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
