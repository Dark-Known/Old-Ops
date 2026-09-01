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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Live dashboard for the event-driven scheduler — see
 * {@link service.queue.TaskEventQueue} / {@link service.queue.TaskWorkerPool}.
 *
 * Three tabs, all refreshed on a 1s timer:
 *  - <b>GUI Process</b> — this JVM's own scheduler, observed directly
 *    through {@link TaskSchedulerService}'s live accessors.
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
 * Purely observational: nothing here mutates scheduler state.
 */
public class EventMonitorPanel extends JPanel {

    // How stale a Daemon status file can be before we call it "offline".
    // The exporter writes every 2s (see TaskSchedulerService#enableStatusExport);
    // anything past ~4x that interval means the process most likely died
    // mid-tick rather than just being between writes.
    private static final long DAEMON_STALE_MS = 8_000L;
    private static final int ACTIVITY_LIMIT = 100;
    private static final int STATS_SAMPLE_LIMIT = 500;
    private static final int REFRESH_MS = 1000;

    private final TaskSchedulerService scheduler;
    private final Path daemonStatusFile;

    private QueueMonitorView guiView;
    private QueueMonitorView daemonView;
    private StatisticsPanel statsPanel;

    private Timer refreshTimer;

    public EventMonitorPanel(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        this.daemonStatusFile = scheduler.getStorage().getDataDir().toPath().resolve("scheduler-status-daemon.dat");

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        guiView = new QueueMonitorView();
        daemonView = new QueueMonitorView();
        statsPanel = new StatisticsPanel();

        JTabbedPane tabs = new JTabbedPane();
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

        refreshGuiTab(byId);
        refreshDaemonTab(byId);
        refreshStatsTab();
    }

    private void refreshGuiTab(Map<String, ScheduledTask> byId) {
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

    private void refreshDaemonTab(Map<String, ScheduledTask> byId) {
        SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.read(daemonStatusFile);
        if (snap == null) {
            daemonView.showUnavailable("Daemon status unavailable — the background daemon may not be running.");
            return;
        }
        if (!snap.isFresh(DAEMON_STALE_MS)) {
            daemonView.showUnavailable("Daemon appears offline (last update "
                    + java.time.Duration.between(snap.getWrittenAt(), LocalDateTime.now()).getSeconds() + "s ago).");
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
