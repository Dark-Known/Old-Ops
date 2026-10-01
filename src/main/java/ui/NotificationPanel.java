package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.CommandQueueService;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;

/**
 * The Health Feed — "what needs my attention, in the order it happened" —
 * v2. Replaces the old two-tab, plain-{@link javax.swing.JTable} layout (a
 * "Tasks" tab and a separate "Watcher" tab, each its own table + its own
 * action buttons) with one scrollable, chronologically-sorted feed mixing
 * all three kinds of thing this app can flag:
 * <ul>
 *   <li>Failure groups (see {@link IssueGrouping}) — same grouping/severity/
 *       credential-impact this panel used to only show inside StatusStrip's
 *       old inline detail panel, now living here instead.</li>
 *   <li>Skipped tasks — informational, with a Restart action.</li>
 *   <li>Watcher push\u2192polling fallback events (see {@link WatchStatusMonitor}) —
 *       informational only, same as before.</li>
 * </ul>
 * Named "Health Feed" (not "Activity Feed") deliberately — {@link EventMonitorPanel}
 * already has its own, unrelated "activity feed" (a live worker-activity log);
 * reusing that name here for a completely different view would make both
 * ambiguous. This one pairs with {@link StatusStrip}, the "health bar" that
 * opens it.
 *
 * <p>Sorted newest-first by whatever timestamp each kind of entry actually has,
 * so a watcher fallback that happened five minutes ago outranks a failure
 * group whose most recent occurrence was an hour ago — one list, one sense
 * of "what's the latest thing I should know about", instead of splitting
 * attention across separate tabs with no shared ordering.
 *
 * <p>Snoozed failure groups are hidden from the main feed and shown, dimmed,
 * under a "Show snoozed" toggle instead — snooze state itself is owned by
 * whichever {@link StatusStrip} instance opened this panel (see the 4-arg
 * constructor), since a fresh NotificationPanel is created every time the
 * feed dialog is opened and closed, but the snooze should survive across
 * those opens.
 */
public class NotificationPanel extends JPanel {

    private static final int LOOKBACK_HOURS = 24;
    private static final int QUERY_LIMIT = 500;

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private final WatchStatusMonitor watchStatusMonitor; // nullable-safe
    private final StatusStrip healthBar; // nullable — see snooze()/isSnoozed()/unsnooze() below

    // Only used when healthBar is null (the 2-/3-arg legacy constructors, kept
    // only so a couple of unwired older experiments — ActionCenterButton,
    // NotificationBell — still compile; never exercised by the real app,
    // which always goes through StatusStrip.openFeed()'s 4-arg constructor).
    private final Map<String, java.time.Instant> localSnoozedUntil = new HashMap<>();

    private JLabel subtitle;
    private JPanel feedList;
    private JToggleButton showSnoozedToggle;

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler) {
        this(storage, scheduler, null, null);
    }

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler, WatchStatusMonitor watchStatusMonitor) {
        this(storage, scheduler, watchStatusMonitor, null);
    }

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler,
                              WatchStatusMonitor watchStatusMonitor, StatusStrip healthBar) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.watchStatusMonitor = watchStatusMonitor;
        this.healthBar = healthBar;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        refresh();
    }

    /** No-op — kept only so older, currently-unwired experiments that predate
     *  the unified feed (a single view has nothing to select between) still
     *  compile without modification. */
    public void selectTab(int index) { /* intentionally does nothing */ }

    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));

        JLabel title = new JLabel("Health Feed");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        subtitle = new JLabel(" ");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        subtitle.setForeground(AppTheme.NEUTRAL_FG);

        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private Component buildBody() {
        JPanel body = new JPanel(new BorderLayout(8, 8));

        feedList = new JPanel();
        feedList.setLayout(new BoxLayout(feedList, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(feedList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        body.add(scroll, BorderLayout.CENTER);
        body.add(buildToolbar(), BorderLayout.SOUTH);
        return body;
    }

    private Component buildToolbar() {
        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> {
            // Logged here, not inside refresh() itself — refresh() also runs on
            // dialog open, the "show snoozed" toggle, and after snooze/retry
            // actions, and logging every one of those would flood the feed with
            // noise nobody asked for. Only an explicit click is "touched" it.
            logActivity("Health feed refreshed", "Manually refreshed from the Health Feed dialog.");
            refresh();
        });

        showSnoozedToggle = new JToggleButton("Show snoozed");
        showSnoozedToggle.addActionListener(e -> refresh());

        JButton btnRestartAll = new JButton("Restart All Failed");
        btnRestartAll.addActionListener(e -> restartAllFailed());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(btnRefresh);
        actions.add(showSnoozedToggle);
        actions.add(btnRestartAll);
        return actions;
    }

    // ------------------------------------------------------------------
    // Snooze — delegates to the owning StatusStrip when there is one (the
    // real app always provides one; see class javadoc).
    // ------------------------------------------------------------------

    private boolean isSnoozed(String groupKey) {
        if (healthBar != null) return healthBar.isSnoozed(groupKey);
        purgeExpiredLocalSnoozes();
        return localSnoozedUntil.containsKey(groupKey);
    }

    /**
     * Records an application-activity note into the Event Monitor's feed —
     * same "SETTINGS"/"CREDENTIALS"/"TASKS" pseudo-task-id pattern used by
     * TaskManagerPanel/CredentialManagerPanel/TaskDialog, using "HEALTH" for
     * anything about this health feed itself. No-op if there's no scheduler
     * (defensive — the real app always provides one) or run history.
     */
    private void logActivity(String title, String detail) {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            scheduler.getRunHistoryService().recordActivityEvent("HEALTH", title, null, detail, detail, now, now);
        } catch (Exception ignored) {
            // Best-effort — a failure to log this shouldn't block the actual action.
        }
    }

    /** Same as {@link #logActivity} but marks the event FAILED, logged under
     *  "TASKS" rather than "HEALTH" since a retry failure is about the task
     *  being retried, not about the health feed itself. */
    private void logActivityFailed(String title, String detail) {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            scheduler.getRunHistoryService().recordActivityEvent("TASKS", title, null,
                    model.TaskRunRecord.Status.FAILED, detail, detail, now, now);
        } catch (Exception ignored) {
            // Best-effort — a failure to log this shouldn't block the actual action.
        }
    }

    private void snooze(String groupKey) {
        if (healthBar != null) healthBar.snooze(groupKey);
        else localSnoozedUntil.put(groupKey, java.time.Instant.now().plusSeconds(2 * 3600));
        logActivity("Failure group snoozed", "Snoozed \"" + groupKey + "\" for 2 hours.");
        refresh();
    }

    private void unsnooze(String groupKey) {
        if (healthBar != null) healthBar.unsnooze(groupKey);
        else localSnoozedUntil.remove(groupKey);
        logActivity("Failure group un-snoozed", "Un-snoozed \"" + groupKey + "\".");
        refresh();
    }

    private void purgeExpiredLocalSnoozes() {
        java.time.Instant now = java.time.Instant.now();
        localSnoozedUntil.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }

    // ------------------------------------------------------------------
    // Feed assembly
    // ------------------------------------------------------------------

    /** One row in the merged feed — just enough to sort everything by time and render it. */
    private record FeedItem(LocalDateTime timestamp, boolean snoozed, JPanel card) {}

    private void refresh() {
        feedList.removeAll();

        List<FeedItem> items = new ArrayList<>();
        int activeIssueCount = 0;
        int watcherCount = 0;

        if (scheduler != null && scheduler.getRunHistoryService() != null) {
            try {
                var history = scheduler.getRunHistoryService();
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime since = now.minusHours(LOOKBACK_HOURS);
                List<TaskRunRecord> failures = history.queryRuns(null, TaskRunRecord.Status.FAILED, since, null, QUERY_LIMIT);
                Map<String, List<TaskRunRecord>> groups = IssueGrouping.groupByLikelyCause(failures);
                for (var e : groups.entrySet()) {
                    boolean snoozed = isSnoozed(e.getKey());
                    if (!snoozed) activeIssueCount += e.getValue().size();
                    LocalDateTime ts = IssueGrouping.mostRecent(e.getValue());
                    items.add(new FeedItem(ts != null ? ts : LocalDateTime.MIN, snoozed,
                            buildFailureCard(e.getKey(), e.getValue(), snoozed)));
                }
            } catch (Exception ignored) {
                // DB briefly locked by a write — next refresh will catch up
            }
        }

        for (ScheduledTask task : storage.loadTasks()) {
            if ("SKIPPED".equals(task.getLastRunResult())) {
                LocalDateTime ts = task.getLastStartedAt() != null ? task.getLastStartedAt() : task.getLastRunAt();
                items.add(new FeedItem(ts != null ? ts : LocalDateTime.MIN, false, buildSkippedCard(task)));
            }
        }

        if (watchStatusMonitor != null) {
            List<WatchStatusMonitor.Event> events = watchStatusMonitor.getRecentEvents();
            watcherCount = events.size();
            for (WatchStatusMonitor.Event ev : events) {
                items.add(new FeedItem(ev.at() != null ? ev.at() : LocalDateTime.MIN, false, buildWatcherCard(ev)));
            }
        }

        boolean showSnoozed = showSnoozedToggle != null && showSnoozedToggle.isSelected();
        items.sort((a, b) -> {
            if (a.snoozed() != b.snoozed()) return a.snoozed() ? 1 : -1; // active first, always
            return b.timestamp().compareTo(a.timestamp()); // newest first within each group
        });

        int shown = 0;
        for (FeedItem item : items) {
            if (item.snoozed() && !showSnoozed) continue;
            feedList.add(item.card());
            feedList.add(Box.createVerticalStrut(1));
            shown++;
        }
        if (shown == 0) {
            feedList.add(emptyStateCard());
        }

        long snoozedCount = items.stream().filter(FeedItem::snoozed).count();
        if (showSnoozedToggle != null) {
            showSnoozedToggle.setText(snoozedCount > 0 ? "Show snoozed (" + snoozedCount + ")" : "Show snoozed");
            showSnoozedToggle.setEnabled(snoozedCount > 0);
        }

        subtitle.setText(activeIssueCount + " active issue" + (activeIssueCount == 1 ? "" : "s")
                + " \u00b7 " + watcherCount + " watcher event" + (watcherCount == 1 ? "" : "s")
                + (snoozedCount > 0 ? " \u00b7 " + snoozedCount + " snoozed" : ""));

        feedList.revalidate();
        feedList.repaint();
    }

    private JPanel emptyStateCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(24, 8, 24, 8));
        JLabel label = new JLabel("Nothing needs attention right now.", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
        label.setForeground(AppTheme.NEUTRAL_FG);
        card.add(label, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------
    // Card builders — one per kind of feed entry. Each is a left-accent-bar
    // + content row, matching the same visual language (severity chip, small
    // muted timestamp) across all three kinds so the feed reads as one thing
    // rather than three bolted-together widgets.
    // ------------------------------------------------------------------

    private JPanel accentCard(Color accent, boolean dimmed) {
        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, dimmed ? accent.darker() : accent),
                new EmptyBorder(9, 10, 9, 10)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBackground(AppTheme.isDark() ? new Color(0x24273A) : new Color(0xFAFAFC));
        card.setOpaque(true);
        return card;
    }

    private static JLabel chip(String text, Color bg) {
        JLabel l = new JLabel(text);
        l.setOpaque(true);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 9.5f));
        l.setForeground(Color.WHITE);
        l.setBackground(bg);
        l.setBorder(new EmptyBorder(1, 6, 1, 6));
        return l;
    }

    private static String timeAgo(LocalDateTime when) {
        if (when == null || when == LocalDateTime.MIN) return "";
        long minutes = java.time.Duration.between(when, LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private JPanel buildFailureCard(String groupKey, List<TaskRunRecord> records, boolean snoozed) {
        TaskRunRecord example = records.get(0);
        boolean blocking = IssueGrouping.isBlocking(example);
        Color accent = blocking ? new Color(0xC62828) : new Color(0xE68A00);

        JPanel card = accentCard(accent, snoozed);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        String category = example.getFailureCategory() != null ? example.getFailureCategory().name() : "UNKNOWN";
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLine.add(chip(blocking ? "CRITICAL" : "WARNING", accent));
        JLabel title = new JLabel(example.getTaskName() + "  \u00d7" + records.size() + "   \u2014   " + category);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12.5f));
        if (snoozed) title.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(title);
        JLabel time = new JLabel(timeAgo(IssueGrouping.mostRecent(records)));
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 10.5f));
        time.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(time);
        left.add(titleLine);

        String action = example.getSuggestedAction() != null ? example.getSuggestedAction() : "No specific suggestion available.";
        JLabel actionLabel = new JLabel("<html><div style='width:420px'>" + escape(action) + "</div></html>");
        actionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionLabel.setFont(actionLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        actionLabel.setForeground(AppTheme.NEUTRAL_FG);
        left.add(actionLabel);

        String impact = IssueGrouping.describeCredentialImpact(storage, example);
        if (impact != null) {
            JLabel impactLabel = new JLabel(impact);
            impactLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            impactLabel.setFont(impactLabel.getFont().deriveFont(Font.ITALIC, 11f));
            impactLabel.setForeground(blocking ? new Color(0xC62828) : AppTheme.NEUTRAL_FG);
            left.add(impactLabel);
        }
        card.add(left, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        if (snoozed) {
            JButton unsnooze = new JButton("Un-snooze");
            unsnooze.setFont(unsnooze.getFont().deriveFont(11.5f));
            unsnooze.addActionListener(ev -> unsnooze(groupKey));
            actions.add(unsnooze);
        } else {
            JButton viewLog = new JButton("View log");
            viewLog.setFont(viewLog.getFont().deriveFont(11.5f));
            viewLog.addActionListener(ev -> showRunDetail(example));
            actions.add(viewLog);

            JButton retry = new JButton(blocking ? "Fix & retry" : "Retry");
            retry.setFont(retry.getFont().deriveFont(11.5f));
            retry.setToolTipText(blocking
                    ? "This isn't usually transient (e.g. a bad credential) — fix the cause, then retry"
                    : "Re-run this task now");
            retry.addActionListener(ev -> retryTask(example.getTaskId(), example.getTaskName()));
            actions.add(retry);

            JButton snoozeBtn = new JButton("Snooze 2h");
            snoozeBtn.setFont(snoozeBtn.getFont().deriveFont(11.5f));
            snoozeBtn.setToolTipText("Stop re-alerting on this group for 2 hours — it keeps running normally, this only quiets the badge");
            snoozeBtn.addActionListener(ev -> snooze(groupKey));
            actions.add(snoozeBtn);
        }
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private JPanel buildSkippedCard(ScheduledTask task) {
        JPanel card = accentCard(new Color(0x757575), false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.add(chip("SKIPPED", new Color(0x757575)));
        JLabel title = new JLabel(task.getName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12.5f));
        titleLine.add(title);
        LocalDateTime ts = task.getLastStartedAt() != null ? task.getLastStartedAt() : task.getLastRunAt();
        JLabel time = new JLabel(timeAgo(ts));
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 10.5f));
        time.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(time);
        left.add(titleLine);

        JLabel detail = new JLabel("Last run was skipped.");
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11.5f));
        detail.setForeground(AppTheme.NEUTRAL_FG);
        left.add(detail);
        card.add(left, BorderLayout.CENTER);

        JButton restart = new JButton("Restart");
        restart.setFont(restart.getFont().deriveFont(11.5f));
        restart.addActionListener(ev -> restartTask(task));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(restart);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private JPanel buildWatcherCard(WatchStatusMonitor.Event ev) {
        JPanel card = accentCard(new Color(0x1565C0), false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.add(chip("WATCHER", new Color(0x1565C0)));
        JLabel title = new JLabel(ev.taskName() + "  \u2014  " + prettyMode(ev.fromMode()) + " \u2192 " + prettyMode(ev.toMode()));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12.5f));
        titleLine.add(title);
        JLabel time = new JLabel(timeAgo(ev.at()));
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 10.5f));
        time.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(time);
        left.add(titleLine);

        if (ev.detail() != null && !ev.detail().isEmpty()) {
            JLabel detail = new JLabel("<html><div style='width:420px'>" + escape(ev.detail()) + "</div></html>");
            detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11.5f));
            detail.setForeground(AppTheme.NEUTRAL_FG);
            left.add(detail);
        }
        card.add(left, BorderLayout.CENTER);
        return card;
    }

    private static String prettyMode(String rawWatchModeName) {
        if (rawWatchModeName == null) return "?";
        return switch (rawWatchModeName) {
            case "NATIVE_WATCH" -> "Live (native)";
            case "REMOTE_PUSH" -> "Live (push)";
            case "POLLING_ONLY_UNSUPPORTED" -> "Polling (unsupported)";
            case "POLLING_ONLY" -> "Polling";
            default -> rawWatchModeName;
        };
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void showRunDetail(TaskRunRecord record) {
        JTextArea area = new JTextArea(record.getDetails() != null ? record.getDetails() : record.getReason());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(560, 320));
        JOptionPane.showMessageDialog(this, scroll, record.getTaskName() + " \u2014 run detail", JOptionPane.PLAIN_MESSAGE);
    }

    private void retryTask(String taskId, String taskName) {
        if (taskId == null) return;
        // restartTask() returns whether the status change actually persisted —
        // previously unchecked here, so a failed save still claimed the task was
        // "queued for immediate retry" and still asked the daemon to run it.
        boolean[] ok = {false};
        storage.loadTasks().stream().filter(t -> t.getId().equals(taskId)).findFirst()
                .ifPresentOrElse(t -> ok[0] = restartTask(t),
                        () -> logActivityFailed("Task restart failed",
                                "Could not retry task \"" + taskName + "\" — task no longer exists."));
        if (!ok[0]) {
            JOptionPane.showMessageDialog(this,
                    "Could not retry \"" + taskName + "\" — the status change did not persist. See the "
                            + "Event Monitor for details.",
                    "Retry Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (scheduler != null) scheduler.refresh();
        JOptionPane.showMessageDialog(this, "\"" + taskName + "\" queued for immediate retry.");
        refresh();
    }

    /** Returns whether the status change actually persisted — see retryTask(). */
    private boolean restartTask(ScheduledTask task) {
        ScheduledTask.TaskStatus oldStatus = task.getStatus();
        task.setStatus(ScheduledTask.TaskStatus.PENDING);
        task.setLastStartedAt(null);
        if (!storage.saveTask(task)) {
            String detail = storage.isConnected()
                    ? storage.getLastTaskSaveError() : storage.getConnectionError();
            logActivityFailed("Task restart failed",
                    "Could not retry task \"" + task.getName() + "\""
                            + (storage.isConnected() ? " — database connected, but this write failed"
                                    : " — database not connected")
                            + (detail != null ? ": " + detail : "."));
            return false;
        }
        logActivity("Task restarted", "Task \"" + task.getName() + "\" status: " + oldStatus + " \u2192 "
                + ScheduledTask.TaskStatus.PENDING + "; retried from the Health Feed.");
        // Ask the daemon/service to actually do this — this window never
        // runs its own scheduler/worker pool (see MainWindow's thin-client
        // migration and service.CommandQueueService).
        CommandQueueService.enqueue(storage.getDataDir(), task.getId(), CommandQueueService.Action.CANCEL, "gui");
        CommandQueueService.enqueue(storage.getDataDir(), task.getId(), CommandQueueService.Action.RUN_NOW, "gui");
        refresh();
        return true;
    }

    private void restartAllFailed() {
        // restartTask() now reports success/failure per task (see its own
        // comment) — this bulk version previously counted every attempt as a
        // success regardless of whether the save actually persisted, so a
        // partial failure here would still claim "N restarted" with nothing
        // to indicate some of those N never actually happened.
        int succeeded = 0, failed = 0;
        for (ScheduledTask task : storage.loadTasks()) {
            if (task.getStatus() == ScheduledTask.TaskStatus.FAILED
                    || task.getStatus() == ScheduledTask.TaskStatus.RETRYING
                    || task.getStatus() == ScheduledTask.TaskStatus.RUNNING) {
                if (restartTask(task)) succeeded++; else failed++;
            }
        }
        String msg = succeeded + " failed task(s) restarted" + (failed > 0 ? ", " + failed + " could not be"
                + " restarted (see Event Monitor for details)" : "") + ".";
        JOptionPane.showMessageDialog(this, msg, failed > 0 ? "Partially Restarted" : "Restarted",
                failed > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        refresh();
    }
}
