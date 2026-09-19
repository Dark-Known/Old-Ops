package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.CommandQueueService;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Full-width, always-visible header strip answering "is everything OK, and
 * what do I do about it" — v2. Supersedes the original {@link StatusStrip}
 * pass (kept the same class name/wiring in {@link MainWindow}, this is an
 * in-place rewrite) by surfacing more of what the person actually needs to
 * triage, using only data this app genuinely has:
 * <ul>
 *   <li><b>Severity</b>, derived from {@link TaskRunRecord.FailureCategory}
 *       (AUTH/PERMISSION/DISK_SPACE are treated as blocking; NETWORK/TIMEOUT/
 *       CONFIG/ORPHANED/UNKNOWN as transient-leaning) — not shown in v1.</li>
 *   <li><b>A trend</b>: 24h success rate vs. the prior 24h window, so a
 *       failure reads as "getting worse" or "one-off blip" instead of a bare
 *       count.</li>
 *   <li><b>Real shared-cause impact</b> for AUTH failures specifically: this
 *       app already links tasks to credentials by id
 *       ({@code sourceCredentialId}/{@code targetCredentialId}), so "N other
 *       tasks use this same credential" is a genuine, computed fact — not
 *       invented. (Earlier mockups floated an "affects N files" style stat;
 *       there's no such field anywhere in the model, so it's deliberately
 *       left out rather than fabricated.)</li>
 *   <li><b>Snooze</b>: session-scoped (in-memory, resets on app restart —
 *       intentionally not persisted, since "quiet down, I'm already handling
 *       this" shouldn't survive a crash/relaunch and hide a real problem).
 *       Snoozing removes a group from the bell badge and pushes it to the
 *       bottom of the expanded list, dimmed; it never changes the header
 *       summary line or dot color, which always reflect the true count.</li>
 *   <li>The existing v1 essentials stay: worker-busy count, next scheduled
 *       run, per-group Retry/View log actions, and the modeless failure
 *       recovery dialog for anything needing deeper triage.</li>
 * </ul>
 */
public class StatusStrip extends JPanel {

    private static final int LOOKBACK_HOURS = 24;
    private static final int QUERY_LIMIT = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("EEE HH:mm");
    private static final Set<TaskRunRecord.FailureCategory> BLOCKING_CATEGORIES = EnumSet.of(
            TaskRunRecord.FailureCategory.AUTH,
            TaskRunRecord.FailureCategory.PERMISSION,
            TaskRunRecord.FailureCategory.DISK_SPACE);

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private final WatchStatusMonitor watchStatusMonitor;

    // Session-only: group key -> when the snooze expires. Never written to disk;
    // see class javadoc for why that's deliberate.
    private final Map<String, Instant> snoozedUntil = new HashMap<>();

    private final JPanel headerRow = new JPanel(new BorderLayout(10, 0));
    private final JLabel dot = new JLabel("\u25CF");
    private final JLabel summary = new JLabel();
    private final JLabel trendLabel = new JLabel();
    private final JLabel bellBadge = new JLabel();
    private final JLabel chevron = new JLabel("\u25BE");

    private final JPanel statsRow = new JPanel(new GridLayout(1, 4));
    private final JLabel statWorkers = statValue();
    private final JLabel statLastSuccess = statValue();
    private final JLabel statNextRun = statValue();
    private final JLabel statOpenIssues = statValue();

    private final JPanel detailPanel = new JPanel();
    private boolean expanded = false;

    private final javax.swing.Timer pollTimer;
    private final java.util.function.Consumer<TaskRunRecord> runListener;

    public StatusStrip(XmlStorageService storage, TaskSchedulerService scheduler, WatchStatusMonitor watchStatusMonitor) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.watchStatusMonitor = watchStatusMonitor;

        setLayout(new BorderLayout());
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.isDark() ? new Color(0x2B2E3E) : new Color(0xE7E7F0)),
                new EmptyBorder(0, 0, 0, 0)));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        buildHeaderRow();
        buildStatsRow();
        top.add(headerRow);
        top.add(statsRow);
        add(top, BorderLayout.NORTH);

        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setBorder(new EmptyBorder(0, 14, 8, 14));
        detailPanel.setOpaque(false);
        detailPanel.setVisible(false);
        add(detailPanel, BorderLayout.CENTER);

        refresh();
        pollTimer = new javax.swing.Timer(15_000, e -> refresh());
        pollTimer.start();
        if (scheduler != null && scheduler.getRunHistoryService() != null) {
            runListener = rec -> {
                if (rec.getStatus() == TaskRunRecord.Status.FAILED || rec.getStatus() == TaskRunRecord.Status.SUCCESS) {
                    SwingUtilities.invokeLater(this::refresh);
                }
            };
            scheduler.getRunHistoryService().addRunListener(runListener);
        } else {
            runListener = null;
        }
    }

    /** Stops the internal poll timer and detaches the run listener — call before discarding
     *  a StatusStrip instance (e.g. on a UI rebuild), so rebuilds don't stack duplicates. */
    public void dispose() {
        pollTimer.stop();
        if (runListener != null && scheduler != null && scheduler.getRunHistoryService() != null) {
            scheduler.getRunHistoryService().removeRunListener(runListener);
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void buildHeaderRow() {
        headerRow.setOpaque(false);
        headerRow.setBorder(new EmptyBorder(8, 14, 6, 14));
        headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleLine.setOpaque(false);
        dot.setFont(dot.getFont().deriveFont(Font.PLAIN, 12f));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 12.5f));
        titleLine.add(dot);
        titleLine.add(summary);
        left.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        trendLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        trendLabel.setFont(trendLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        trendLabel.setForeground(AppTheme.NEUTRAL_FG);
        trendLabel.setBorder(new EmptyBorder(1, 20, 0, 0));
        left.add(titleLine);
        left.add(trendLabel);
        headerRow.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        bellBadge.setFont(bellBadge.getFont().deriveFont(Font.BOLD, 10f));
        bellBadge.setOpaque(true);
        bellBadge.setForeground(Color.WHITE);
        bellBadge.setBackground(new Color(0xE53935));
        bellBadge.setBorder(new EmptyBorder(1, 6, 1, 6));
        right.add(bellBadge);

        chevron.setFont(chevron.getFont().deriveFont(Font.PLAIN, 11f));
        chevron.setForeground(AppTheme.NEUTRAL_FG);
        right.add(chevron);

        headerRow.add(right, BorderLayout.EAST);

        java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleExpanded(); }
        };
        headerRow.addMouseListener(toggle);
        left.addMouseListener(toggle);
        titleLine.addMouseListener(toggle);
    }

    private void buildStatsRow() {
        statsRow.setOpaque(false);
        statsRow.setBorder(new EmptyBorder(2, 14, 8, 14));
        statsRow.add(statCell("Workers busy", statWorkers));
        statsRow.add(statCell("Last success", statLastSuccess));
        statsRow.add(statCell("Next run", statNextRun));
        statsRow.add(statCell("Open issues", statOpenIssues));
    }

    private static JLabel statValue() {
        JLabel l = new JLabel();
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }

    private static JPanel statCell(String caption, JLabel valueLabel) {
        JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        JLabel cap = new JLabel(caption);
        cap.setFont(cap.getFont().deriveFont(Font.PLAIN, 10.5f));
        cap.setForeground(AppTheme.NEUTRAL_FG);
        cap.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        cell.add(cap);
        cell.add(valueLabel);
        return cell;
    }

    private void toggleExpanded() {
        expanded = !expanded;
        detailPanel.setVisible(expanded);
        chevron.setText(expanded ? "\u25B4" : "\u25BE");
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------
    // Data refresh
    // ------------------------------------------------------------------

    /** Re-reads failures, success-rate trend, credential impact, and worker activity, then repaints. Cheap — call freely. */
    public void refresh() {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(LOOKBACK_HOURS);
        List<TaskRunRecord> failures;
        double rateNow, ratePrev;
        LocalDateTime lastSuccessAt = null;
        try {
            var history = scheduler.getRunHistoryService();
            failures = history.queryRuns(null, TaskRunRecord.Status.FAILED, since, null, QUERY_LIMIT);
            rateNow = successRate(history, since, now);
            ratePrev = successRate(history, now.minusHours(2L * LOOKBACK_HOURS), since);
            List<TaskRunRecord> lastSuccess = history.queryRuns(null, TaskRunRecord.Status.SUCCESS, 1);
            if (!lastSuccess.isEmpty()) lastSuccessAt = lastSuccess.get(0).getEndedAt();
        } catch (Exception e) {
            return; // DB briefly locked by a write — next poll tick will catch up
        }

        purgeExpiredSnoozes();
        Map<String, List<TaskRunRecord>> allGroups = groupByLikelyCause(failures);
        Map<String, List<TaskRunRecord>> activeGroups = new LinkedHashMap<>();
        Map<String, List<TaskRunRecord>> snoozedGroups = new LinkedHashMap<>();
        for (var e : allGroups.entrySet()) {
            (snoozedUntil.containsKey(e.getKey()) ? snoozedGroups : activeGroups).put(e.getKey(), e.getValue());
        }

        int badgeCount = activeGroups.values().stream().mapToInt(List::size).sum();
        if (watchStatusMonitor != null) badgeCount += watchStatusMonitor.getEventCount();
        bellBadge.setVisible(badgeCount > 0);
        bellBadge.setText(badgeCount > 99 ? "99+" : String.valueOf(badgeCount));

        int active = scheduler.getActiveWorkerCount();
        int total = scheduler.getWorkerPoolSize();
        statWorkers.setText(active + " / " + total);
        statLastSuccess.setText(lastSuccessAt == null ? "\u2014" : humanizeAgo(lastSuccessAt, now));
        statNextRun.setText(describeNextScheduledCompact());
        int distinctFailingTasks = (int) failures.stream().map(TaskRunRecord::getTaskId).distinct().count();
        statOpenIssues.setText(allGroups.isEmpty() ? "0" : allGroups.size() + " group" + (allGroups.size() == 1 ? "" : "s"));

        boolean anyBlocking = failures.stream().anyMatch(StatusStrip::isBlocking);
        if (failures.isEmpty()) {
            dot.setForeground(AppTheme.SUCCESS_FG);
            summary.setText("All clear \u2014 no failures in the last " + LOOKBACK_HOURS + "h");
            setBackground(AppTheme.SUCCESS_BG);
        } else {
            dot.setForeground(anyBlocking ? AppTheme.FAILED_FG : AppTheme.RUNNING_FG);
            setBackground(anyBlocking ? AppTheme.FAILED_BG : AppTheme.SKIPPED_BG);
            summary.setText(failures.size() + " failure" + (failures.size() == 1 ? "" : "s")
                    + " across " + distinctFailingTasks + " task" + (distinctFailingTasks == 1 ? "" : "s")
                    + (anyBlocking ? " \u2014 action needed" : " \u2014 all retryable"));
        }
        trendLabel.setText(formatTrend(rateNow, ratePrev));

        rebuildDetail(activeGroups, snoozedGroups);
    }

    private static double successRate(service.RunHistoryService history, LocalDateTime from, LocalDateTime to) {
        int ok = history.queryRuns(null, TaskRunRecord.Status.SUCCESS, from, to, QUERY_LIMIT).size();
        int bad = history.queryRuns(null, TaskRunRecord.Status.FAILED, from, to, QUERY_LIMIT).size();
        int totalRuns = ok + bad;
        return totalRuns == 0 ? -1 : (100.0 * ok / totalRuns);
    }

    private static String formatTrend(double rateNow, double ratePrev) {
        if (rateNow < 0) return "No runs in the last " + LOOKBACK_HOURS + "h yet.";
        String nowStr = String.format(Locale.US, "%.0f%% success (last %dh)", rateNow, LOOKBACK_HOURS);
        if (ratePrev < 0) return nowStr;
        double delta = rateNow - ratePrev;
        if (Math.abs(delta) < 0.5) return nowStr + " \u2014 steady vs. the previous " + LOOKBACK_HOURS + "h";
        String arrow = delta > 0 ? "\u2191" : "\u2193";
        return nowStr + "  " + arrow + " " + String.format(Locale.US, "%.0f", Math.abs(delta)) + "pt vs. previous " + LOOKBACK_HOURS + "h";
    }

    private static boolean isBlocking(TaskRunRecord r) {
        return r.getFailureCategory() != null && BLOCKING_CATEGORIES.contains(r.getFailureCategory());
    }

    /**
     * Groups failures by the most specific shared cause available: same
     * task name + same failure category first (repeated failures of one
     * task), falling back to just failure category (several different
     * tasks failing the same way — e.g. all AUTH, suggesting one shared
     * broken credential). Sorted blocking-first, then by occurrence count,
     * so the thing most worth acting on is always at the top.
     */
    private static Map<String, List<TaskRunRecord>> groupByLikelyCause(List<TaskRunRecord> failures) {
        Map<String, List<TaskRunRecord>> raw = new LinkedHashMap<>();
        for (TaskRunRecord r : failures) {
            String category = r.getFailureCategory() != null ? r.getFailureCategory().name() : "UNKNOWN";
            String key = r.getTaskName() + " (" + category + ")";
            raw.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<String, List<TaskRunRecord>> sorted = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted((a, b) -> {
                    boolean ablock = isBlocking(a.getValue().get(0));
                    boolean bblock = isBlocking(b.getValue().get(0));
                    if (ablock != bblock) return ablock ? -1 : 1;
                    return Integer.compare(b.getValue().size(), a.getValue().size());
                })
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private void purgeExpiredSnoozes() {
        Instant now = Instant.now();
        snoozedUntil.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }

    // ------------------------------------------------------------------
    // Detail panel
    // ------------------------------------------------------------------

    private void rebuildDetail(Map<String, List<TaskRunRecord>> activeGroups, Map<String, List<TaskRunRecord>> snoozedGroups) {
        detailPanel.removeAll();

        if (activeGroups.isEmpty() && snoozedGroups.isEmpty()) {
            JLabel none = new JLabel("Nothing needs attention right now.");
            none.setFont(none.getFont().deriveFont(Font.PLAIN, 12f));
            none.setForeground(AppTheme.NEUTRAL_FG);
            none.setBorder(new EmptyBorder(6, 0, 6, 0));
            detailPanel.add(none);
        } else {
            for (var e : activeGroups.entrySet()) {
                detailPanel.add(buildGroupRow(e.getKey(), e.getValue(), false));
                detailPanel.add(Box.createVerticalStrut(1));
            }
            if (!snoozedGroups.isEmpty()) {
                JLabel snoozedHeader = new JLabel(snoozedGroups.size() + " snoozed \u2014 still monitoring in the background");
                snoozedHeader.setFont(snoozedHeader.getFont().deriveFont(Font.ITALIC, 11f));
                snoozedHeader.setForeground(AppTheme.NEUTRAL_FG);
                snoozedHeader.setBorder(new EmptyBorder(6, 0, 2, 0));
                snoozedHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
                detailPanel.add(snoozedHeader);
                for (var e : snoozedGroups.entrySet()) {
                    detailPanel.add(buildGroupRow(e.getKey(), e.getValue(), true));
                }
            }
        }

        if (watchStatusMonitor != null && watchStatusMonitor.getEventCount() > 0) {
            detailPanel.add(buildWatcherRow());
        }

        int healthyCount = countHealthyTasks(activeGroups, snoozedGroups);
        detailPanel.add(buildFooterRow(healthyCount));
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private int countHealthyTasks(Map<String, List<TaskRunRecord>> activeGroups, Map<String, List<TaskRunRecord>> snoozedGroups) {
        try {
            Set<String> failingTaskNames = new HashSet<>();
            activeGroups.values().forEach(list -> list.forEach(r -> failingTaskNames.add(r.getTaskName())));
            snoozedGroups.values().forEach(list -> list.forEach(r -> failingTaskNames.add(r.getTaskName())));
            long enabledTotal = storage.loadTasks().stream()
                    .filter(t -> t.getStatus() != ScheduledTask.TaskStatus.DISABLED)
                    .count();
            return (int) Math.max(0, enabledTotal - failingTaskNames.size());
        } catch (Exception e) {
            return 0;
        }
    }

    private JPanel buildGroupRow(String groupKey, List<TaskRunRecord> records, boolean snoozed) {
        TaskRunRecord example = records.get(0);
        boolean blocking = isBlocking(example);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(7, 0, 7, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (snoozed) row.setEnabled(false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        String category = example.getFailureCategory() != null ? example.getFailureCategory().name() : "UNKNOWN";
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel severityChip = new JLabel(blocking ? "CRITICAL" : "WARNING");
        severityChip.setOpaque(true);
        severityChip.setFont(severityChip.getFont().deriveFont(Font.BOLD, 9.5f));
        severityChip.setForeground(Color.WHITE);
        severityChip.setBackground(blocking ? new Color(0xC62828) : new Color(0xE68A00));
        severityChip.setBorder(new EmptyBorder(1, 6, 1, 6));
        titleLine.add(severityChip);

        JLabel title = new JLabel(example.getTaskName() + "  \u00d7" + records.size() + "   \u2014   " + category);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12.5f));
        if (snoozed) title.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(title);

        left.add(titleLine);

        String action = example.getSuggestedAction() != null
                ? example.getSuggestedAction() : "No specific suggestion available.";
        JLabel actionLabel = new JLabel("<html><div style='width:420px'>" + escape(action) + "</div></html>");
        actionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionLabel.setFont(actionLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        actionLabel.setForeground(AppTheme.NEUTRAL_FG);
        left.add(actionLabel);

        String impact = describeCredentialImpact(example);
        if (impact != null) {
            JLabel impactLabel = new JLabel(impact);
            impactLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            impactLabel.setFont(impactLabel.getFont().deriveFont(Font.ITALIC, 11f));
            impactLabel.setForeground(blocking ? new Color(0xC62828) : AppTheme.NEUTRAL_FG);
            left.add(impactLabel);
        }

        row.add(left, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);

        if (snoozed) {
            JButton unsnooze = new JButton("Un-snooze");
            unsnooze.setFont(unsnooze.getFont().deriveFont(11.5f));
            unsnooze.addActionListener(ev -> { snoozedUntil.remove(groupKey); refresh(); });
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

            JButton snooze = new JButton("Snooze 2h");
            snooze.setFont(snooze.getFont().deriveFont(11.5f));
            snooze.setToolTipText("Stop re-alerting on this group for 2 hours — it keeps running normally, this only quiets the badge");
            snooze.addActionListener(ev -> {
                snoozedUntil.put(groupKey, Instant.now().plusSeconds(2 * 3600));
                refresh();
            });
            actions.add(snooze);
        }

        row.add(actions, BorderLayout.EAST);
        return row;
    }

    /**
     * For AUTH failures specifically, looks up the failing task's credential
     * id(s) and counts other enabled tasks that reference the same
     * credential — a real, computed "this affects more than just this one
     * task" signal, not an invented file/item count.
     */
    private String describeCredentialImpact(TaskRunRecord example) {
        if (example.getFailureCategory() != TaskRunRecord.FailureCategory.AUTH || example.getTaskId() == null) return null;
        try {
            List<ScheduledTask> all = storage.loadTasks();
            ScheduledTask failing = all.stream().filter(t -> t.getId().equals(example.getTaskId())).findFirst().orElse(null);
            if (failing == null) return null;
            Set<String> credIds = new HashSet<>();
            if (failing.getSourceCredentialId() != null) credIds.add(failing.getSourceCredentialId());
            if (failing.getTargetCredentialId() != null) credIds.add(failing.getTargetCredentialId());
            if (credIds.isEmpty()) return null;
            long othersSharingCredential = all.stream()
                    .filter(t -> !t.getId().equals(failing.getId()))
                    .filter(t -> t.getStatus() != ScheduledTask.TaskStatus.DISABLED)
                    .filter(t -> credIds.contains(t.getSourceCredentialId()) || credIds.contains(t.getTargetCredentialId()))
                    .count();
            if (othersSharingCredential == 0) return null;
            return "Also used by " + othersSharingCredential + " other enabled task"
                    + (othersSharingCredential == 1 ? "" : "s") + " \u2014 fixing this credential likely fixes those too.";
        } catch (Exception e) {
            return null;
        }
    }

    private JPanel buildWatcherRow() {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(7, 0, 7, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel("Watcher push \u2192 polling fallback \u00d7"
                + watchStatusMonitor.getEventCount());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        row.add(label, BorderLayout.CENTER);

        JButton details = new JButton("Details");
        details.setFont(details.getFont().deriveFont(11.5f));
        details.addActionListener(ev -> openManageDialog(1));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(details);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private JPanel buildFooterRow(int healthyCount) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 2, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);

        JLabel nextUp = new JLabel(describeNextScheduledFull());
        nextUp.setFont(nextUp.getFont().deriveFont(Font.PLAIN, 11.5f));
        nextUp.setForeground(AppTheme.NEUTRAL_FG);
        left.add(nextUp);

        if (healthyCount > 0) {
            JLabel healthy = new JLabel(healthyCount + " other task" + (healthyCount == 1 ? "" : "s") + " healthy");
            healthy.setFont(healthy.getFont().deriveFont(Font.PLAIN, 11.5f));
            healthy.setForeground(AppTheme.SUCCESS_FG);
            left.add(healthy);
        }
        row.add(left, BorderLayout.WEST);

        JButton viewAll = new JButton("View all \u2192");
        viewAll.setFont(viewAll.getFont().deriveFont(11.5f));
        viewAll.addActionListener(ev -> openManageDialog(0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(viewAll);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    /** Best-effort "what's coming up next" — cheapest useful signal to balance a strip that's otherwise all-bad-news. */
    private String describeNextScheduledCompact() {
        ScheduledTask next = findNextScheduled();
        if (next == null || next.getScheduledAt() == null) return "\u2014";
        return next.getScheduledAt().format(TIME_FMT);
    }

    private String describeNextScheduledFull() {
        ScheduledTask next = findNextScheduled();
        if (next == null || next.getScheduledAt() == null) return "No upcoming scheduled runs.";
        return "Next scheduled: " + next.getName() + " \u00b7 " + next.getScheduledAt().format(TIME_FMT);
    }

    private ScheduledTask findNextScheduled() {
        try {
            ScheduledTask next = null;
            LocalDateTime soonest = null;
            for (ScheduledTask t : storage.loadTasks()) {
                if (t.getStatus() == ScheduledTask.TaskStatus.DISABLED) continue;
                LocalDateTime candidate = t.getScheduledAt();
                if (candidate == null) continue;
                if (soonest == null || candidate.isBefore(soonest)) {
                    soonest = candidate;
                    next = t;
                }
            }
            return next;
        } catch (Exception e) {
            return null;
        }
    }

    private static String humanizeAgo(LocalDateTime when, LocalDateTime now) {
        long minutes = java.time.Duration.between(when, now).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void retryTask(String taskId, String taskName) {
        if (taskId == null) return;
        storage.loadTasks().stream().filter(t -> t.getId().equals(taskId)).findFirst().ifPresent(t -> {
            t.setStatus(ScheduledTask.TaskStatus.PENDING);
            storage.saveTask(t);
        });
        CommandQueueService.enqueue(storage.getDataDir(), taskId, CommandQueueService.Action.CANCEL, "gui");
        CommandQueueService.enqueue(storage.getDataDir(), taskId, CommandQueueService.Action.REFRESH, "gui");
        CommandQueueService.enqueue(storage.getDataDir(), taskId, CommandQueueService.Action.RUN_NOW, "gui");
        if (scheduler != null) scheduler.refresh();
        JOptionPane.showMessageDialog(this, "\"" + taskName + "\" queued for immediate retry.");
        refresh();
    }

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

    private void openManageDialog(int initialTab) {
        Window ownerWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(ownerWindow, "Failure Recovery", Dialog.ModalityType.MODELESS);
        NotificationPanel panel = new NotificationPanel(storage, scheduler, watchStatusMonitor);
        panel.selectTab(initialTab);
        dialog.getContentPane().add(panel);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(ownerWindow);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { refresh(); }
        });
        dialog.setVisible(true);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
