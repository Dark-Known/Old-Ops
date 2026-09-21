package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.CommandQueueService;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Replaces {@code StatusStrip} (the earlier always-present, full-width
 * header row) with a fundamentally different pattern: a small corner pill
 * — a dot, a bell, a count — that costs no layout space when nothing needs
 * attention, and opens a non-modal popup panel on click instead of a bar
 * baked permanently into {@link MainWindow}'s layout.
 *
 * <p>Everything the strip computed is preserved (severity from
 * {@link TaskRunRecord.FailureCategory}, 24h success-rate trend, real
 * shared-credential impact for AUTH failures, session-scoped snooze,
 * worker/next-run/healthy-count footer, Retry/View log actions) — only
 * where/how it's shown changed. See the individual method docs carried over
 * from that class for the reasoning behind each computed value.
 */
public class ActionCenterButton extends JPanel {

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

    // Session-only: group key -> when the snooze expires. Never persisted —
    // "I'm handling it" shouldn't survive a crash/relaunch and hide a real problem.
    private final Map<String, Instant> snoozedUntil = new HashMap<>();

    private final JLabel dot = new JLabel();
    private final JLabel countLabel = new JLabel();

    private JDialog popup;
    private final javax.swing.Timer pollTimer;
    private final java.util.function.Consumer<TaskRunRecord> runListener;

    public ActionCenterButton(XmlStorageService storage, TaskSchedulerService scheduler, WatchStatusMonitor watchStatusMonitor) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.watchStatusMonitor = watchStatusMonitor;

        setLayout(new FlowLayout(FlowLayout.CENTER, 6, 2));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                new RoundedLineBorder(),
                new EmptyBorder(4, 10, 4, 8)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        dot.setFont(dot.getFont().deriveFont(Font.PLAIN, 10f));
        dot.setText("\u25CF");
        add(dot);

        JLabel bellText = new JLabel("Alerts");
        bellText.setFont(bellText.getFont().deriveFont(Font.PLAIN, 11.5f));
        bellText.setForeground(AppTheme.NEUTRAL_FG);
        add(bellText);

        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 10.5f));
        countLabel.setOpaque(true);
        countLabel.setForeground(Color.WHITE);
        countLabel.setBackground(new Color(0xE53935));
        countLabel.setBorder(new EmptyBorder(1, 6, 1, 6));
        add(countLabel);

        MouseAdapter toggle = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { togglePopup(); }
        };
        addMouseListener(toggle);
        bellText.addMouseListener(toggle);
        countLabel.addMouseListener(toggle);

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

    /** Stops the internal poll timer, detaches the run listener, and closes any open popup —
     *  call before discarding an instance (e.g. on a UI rebuild), so rebuilds don't stack duplicates. */
    public void dispose() {
        pollTimer.stop();
        if (runListener != null && scheduler != null && scheduler.getRunHistoryService() != null) {
            scheduler.getRunHistoryService().removeRunListener(runListener);
        }
        closePopup();
    }

    /** Thin rounded border matching the pill's own corner radius; no extra dependency. */
    private static class RoundedLineBorder extends javax.swing.border.AbstractBorder {
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(AppTheme.isDark() ? new Color(0x3A3D4D) : new Color(0xD9D9E3));
            g2.drawRoundRect(x, y, w - 1, h - 1, 14, 14);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
    }

    // ------------------------------------------------------------------
    // Popup lifecycle
    // ------------------------------------------------------------------

    private void togglePopup() {
        if (popup != null && popup.isVisible()) {
            closePopup();
        } else {
            openPopup();
        }
    }

    private void closePopup() {
        if (popup != null) {
            popup.dispose();
            popup = null;
        }
    }

    private void openPopup() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        popup = new JDialog(owner, Dialog.ModalityType.MODELESS);
        popup.setUndecorated(true);
        popup.setFocusableWindowState(true);

        JPanel content = buildPopupContent();
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AppTheme.isDark() ? new Color(0x3A3D4D) : new Color(0xD9D9E3), 1, true),
                new EmptyBorder(0, 0, 0, 0)));
        popup.setContentPane(content);
        popup.pack();
        popup.setSize(Math.max(320, popup.getWidth()), popup.getHeight());

        Point loc = getLocationOnScreen();
        int x = loc.x + getWidth() - popup.getWidth();
        int y = loc.y + getHeight() + 6;
        popup.setLocation(Math.max(0, x), y);

        // Standard Swing "light dismiss" pattern: close as soon as the popup
        // (or anything inside it) stops being the focused window, so a click
        // anywhere outside — including back in the main window — closes it,
        // without needing a document-wide mouse listener.
        popup.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (popup != null && !popup.isActive()) closePopup();
                });
            }
        });
        popup.getRootPane().registerKeyboardAction(
                e -> closePopup(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        popup.setVisible(true);
        popup.toFront();
    }

    // ------------------------------------------------------------------
    // Data + popup content
    // ------------------------------------------------------------------

    /** Re-reads failures/trend/impact and updates the pill's badge; if the popup is open, rebuilds it live. Cheap — call freely. */
    public void refresh() {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        Snapshot snap = loadSnapshot();
        if (snap == null) return;

        int badgeCount = snap.activeGroups.values().stream().mapToInt(List::size).sum();
        if (watchStatusMonitor != null) badgeCount += watchStatusMonitor.getEventCount();
        countLabel.setVisible(badgeCount > 0);
        countLabel.setText(badgeCount > 99 ? "99+" : String.valueOf(badgeCount));
        dot.setForeground(snap.failures.isEmpty() ? AppTheme.SUCCESS_FG
                : (snap.anyBlocking ? AppTheme.FAILED_FG : AppTheme.RUNNING_FG));

        if (popup != null && popup.isVisible()) {
            popup.setContentPane(buildPopupContent());
            popup.revalidate();
            popup.repaint();
        }
    }

    private static class Snapshot {
        List<TaskRunRecord> failures;
        Map<String, List<TaskRunRecord>> activeGroups;
        Map<String, List<TaskRunRecord>> snoozedGroups;
        boolean anyBlocking;
        double rateNow, ratePrev;
        LocalDateTime lastSuccessAt;
    }

    private Snapshot loadSnapshot() {
        Snapshot s = new Snapshot();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(LOOKBACK_HOURS);
        try {
            var history = scheduler.getRunHistoryService();
            s.failures = history.queryRuns(null, TaskRunRecord.Status.FAILED, since, null, QUERY_LIMIT);
            s.rateNow = successRate(history, since, now);
            s.ratePrev = successRate(history, now.minusHours(2L * LOOKBACK_HOURS), since);
            List<TaskRunRecord> lastSuccess = history.queryRuns(null, TaskRunRecord.Status.SUCCESS, 1);
            s.lastSuccessAt = lastSuccess.isEmpty() ? null : lastSuccess.get(0).getEndedAt();
        } catch (Exception e) {
            return null; // DB briefly locked by a write — next poll tick will catch up
        }
        purgeExpiredSnoozes();
        Map<String, List<TaskRunRecord>> allGroups = groupByLikelyCause(s.failures);
        s.activeGroups = new LinkedHashMap<>();
        s.snoozedGroups = new LinkedHashMap<>();
        for (var e : allGroups.entrySet()) {
            (snoozedUntil.containsKey(e.getKey()) ? s.snoozedGroups : s.activeGroups).put(e.getKey(), e.getValue());
        }
        s.anyBlocking = s.failures.stream().anyMatch(ActionCenterButton::isBlocking);
        return s;
    }

    private JPanel buildPopupContent() {
        Snapshot snap = loadSnapshot();

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(AppTheme.isDark() ? new Color(0x22232E) : Color.WHITE);
        root.setPreferredSize(new Dimension(320, root.getPreferredSize().height));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(10, 14, 8, 10));
        JLabel title = new JLabel(snap == null || snap.failures.isEmpty() ? "All clear" : "Attention needed");
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 13f));
        header.add(title, BorderLayout.WEST);
        JButton close = new JButton("\u2715");
        close.setFont(close.getFont().deriveFont(11f));
        close.setMargin(new Insets(0, 4, 0, 4));
        close.addActionListener(e -> closePopup());
        header.add(close, BorderLayout.EAST);
        root.add(header);

        if (snap != null) {
            JLabel trend = new JLabel(formatTrend(snap.rateNow, snap.ratePrev));
            trend.setFont(trend.getFont().deriveFont(Font.PLAIN, 11f));
            trend.setForeground(AppTheme.NEUTRAL_FG);
            trend.setBorder(new EmptyBorder(0, 14, 8, 14));
            trend.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(trend);
        }

        root.add(new JSeparator());

        // Rows
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setBorder(new EmptyBorder(2, 14, 4, 14));

        if (snap == null || (snap.activeGroups.isEmpty() && snap.snoozedGroups.isEmpty())) {
            JLabel none = new JLabel("Nothing needs attention right now.");
            none.setFont(none.getFont().deriveFont(Font.PLAIN, 12f));
            none.setForeground(AppTheme.NEUTRAL_FG);
            none.setBorder(new EmptyBorder(8, 0, 8, 0));
            rows.add(none);
        } else {
            for (var e : snap.activeGroups.entrySet()) {
                rows.add(buildRow(e.getKey(), e.getValue(), false));
            }
            if (!snap.snoozedGroups.isEmpty()) {
                JLabel snoozedHeader = new JLabel(snap.snoozedGroups.size() + " snoozed");
                snoozedHeader.setFont(snoozedHeader.getFont().deriveFont(Font.ITALIC, 10.5f));
                snoozedHeader.setForeground(AppTheme.NEUTRAL_FG);
                snoozedHeader.setBorder(new EmptyBorder(6, 0, 2, 0));
                snoozedHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
                rows.add(snoozedHeader);
                for (var e : snap.snoozedGroups.entrySet()) {
                    rows.add(buildRow(e.getKey(), e.getValue(), true));
                }
            }
        }
        root.add(rows);

        if (watchStatusMonitor != null && watchStatusMonitor.getEventCount() > 0) {
            root.add(new JSeparator());
            root.add(buildWatcherRow());
        }

        root.add(new JSeparator());
        root.add(buildFooter(snap));

        return root;
    }

    private JPanel buildRow(String groupKey, List<TaskRunRecord> records, boolean snoozed) {
        TaskRunRecord example = records.get(0);
        boolean blocking = isBlocking(example);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(7, 0, 7, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleLine.setOpaque(false);
        titleLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel groupDot = new JLabel("\u25CF");
        groupDot.setFont(groupDot.getFont().deriveFont(9f));
        groupDot.setForeground(snoozed ? AppTheme.NEUTRAL_FG : (blocking ? AppTheme.FAILED_FG : AppTheme.RUNNING_FG));
        titleLine.add(groupDot);
        JLabel name = new JLabel(example.getTaskName());
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 12.5f));
        if (snoozed) name.setForeground(AppTheme.NEUTRAL_FG);
        titleLine.add(name);
        left.add(titleLine);

        String category = example.getFailureCategory() != null ? example.getFailureCategory().name().toLowerCase(Locale.US) : "unknown";
        String meta = category + " failed " + records.size() + (records.size() == 1 ? " time" : " times");
        String impact = describeCredentialImpact(example);
        if (impact != null) meta += " \u2014 " + impact;
        JLabel metaLabel = new JLabel("<html><div style='width:210px'>" + escape(meta) + "</div></html>");
        metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 11f));
        metaLabel.setForeground(AppTheme.NEUTRAL_FG);
        left.add(metaLabel);

        row.add(left, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        if (snoozed) {
            JButton unsnooze = new JButton("Un-snooze");
            unsnooze.setFont(unsnooze.getFont().deriveFont(11f));
            unsnooze.setMargin(new Insets(1, 6, 1, 6));
            unsnooze.addActionListener(ev -> { snoozedUntil.remove(groupKey); refresh(); });
            actions.add(unsnooze);
        } else {
            JButton view = new JButton("Log");
            view.setFont(view.getFont().deriveFont(11f));
            view.setMargin(new Insets(1, 6, 1, 6));
            view.addActionListener(ev -> showRunDetail(example));
            actions.add(view);

            JButton retry = new JButton(blocking ? "Fix" : "Retry");
            retry.setFont(retry.getFont().deriveFont(11f));
            retry.setMargin(new Insets(1, 6, 1, 6));
            retry.addActionListener(ev -> retryTask(example.getTaskId(), example.getTaskName()));
            actions.add(retry);
        }
        row.add(actions, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(row, BorderLayout.CENTER);
        wrap.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                AppTheme.isDark() ? new Color(0x2B2E3E) : new Color(0xEDEDF2)));
        return wrap;
    }

    private JPanel buildWatcherRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(7, 14, 7, 14));
        JLabel label = new JLabel("Watcher push \u2192 polling fallback \u00d7" + watchStatusMonitor.getEventCount());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11.5f));
        row.add(label, BorderLayout.CENTER);
        JButton details = new JButton("Details");
        details.setFont(details.getFont().deriveFont(11f));
        details.addActionListener(ev -> openManageDialog(1));
        row.add(details, BorderLayout.EAST);
        return row;
    }

    private JPanel buildFooter(Snapshot snap) {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(8, 14, 10, 10));

        int healthy = snap == null ? 0 : countHealthyTasks(snap);
        int active = scheduler != null ? scheduler.getActiveWorkerCount() : 0;
        int total = scheduler != null ? scheduler.getWorkerPoolSize() : 0;
        String left = active + "/" + total + " workers \u00b7 " + describeNextScheduledCompact()
                + (healthy > 0 ? " \u00b7 " + healthy + " healthy" : "");
        JLabel leftLabel = new JLabel(left);
        leftLabel.setFont(leftLabel.getFont().deriveFont(Font.PLAIN, 10.5f));
        leftLabel.setForeground(AppTheme.NEUTRAL_FG);
        footer.add(leftLabel, BorderLayout.WEST);

        JButton viewAll = new JButton("View all \u2192");
        viewAll.setFont(viewAll.getFont().deriveFont(11f));
        viewAll.setMargin(new Insets(1, 6, 1, 6));
        viewAll.addActionListener(ev -> openManageDialog(0));
        footer.add(viewAll, BorderLayout.EAST);
        return footer;
    }

    private int countHealthyTasks(Snapshot snap) {
        try {
            Set<String> failingTaskNames = new HashSet<>();
            snap.activeGroups.values().forEach(list -> list.forEach(r -> failingTaskNames.add(r.getTaskName())));
            snap.snoozedGroups.values().forEach(list -> list.forEach(r -> failingTaskNames.add(r.getTaskName())));
            long enabledTotal = storage.loadTasks().stream()
                    .filter(t -> t.getStatus() != ScheduledTask.TaskStatus.DISABLED)
                    .count();
            return (int) Math.max(0, enabledTotal - failingTaskNames.size());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Computation helpers (same logic previously in StatusStrip)
    // ------------------------------------------------------------------

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
        if (Math.abs(delta) < 0.5) return nowStr + " \u2014 steady";
        String arrow = delta > 0 ? "\u2191" : "\u2193";
        return nowStr + "  " + arrow + " " + String.format(Locale.US, "%.0f", Math.abs(delta)) + "pt vs. previous " + LOOKBACK_HOURS + "h";
    }

    private static boolean isBlocking(TaskRunRecord r) {
        return r.getFailureCategory() != null && BLOCKING_CATEGORIES.contains(r.getFailureCategory());
    }

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

    /** For AUTH failures, counts other enabled tasks that share the same credential id — real, computed, not invented. */
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
            return "also blocks " + othersSharingCredential + " other task" + (othersSharingCredential == 1 ? "" : "s");
        } catch (Exception e) {
            return null;
        }
    }

    private String describeNextScheduledCompact() {
        ScheduledTask next = findNextScheduled();
        if (next == null || next.getScheduledAt() == null) return "no upcoming runs";
        return "next " + next.getScheduledAt().format(TIME_FMT);
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
        closePopup();
        Window ownerWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(ownerWindow, "Failure Recovery", Dialog.ModalityType.MODELESS);
        NotificationPanel panel = new NotificationPanel(storage, scheduler, watchStatusMonitor);
        panel.selectTab(initialTab);
        dialog.getContentPane().add(panel);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(ownerWindow);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { refresh(); }
        });
        dialog.setVisible(true);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
