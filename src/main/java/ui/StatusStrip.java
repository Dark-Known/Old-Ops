package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;

/**
 * Slim, always-visible, full-width health bar — v3. One line, "is everything
 * OK and how bad", with everything else moved out to {@link NotificationPanel}
 * (opened on click) rather than expanding in place:
 * <ul>
 *   <li><b>v1</b> was a static strip with no detail at all.</li>
 *   <li><b>v2</b> added an inline expandable detail panel (per-group rows,
 *       retry/snooze actions, a 4-stat grid) directly under the bar — useful,
 *       but it meant the bar's own height was never predictable, and the
 *       detail duplicated what the (separate, plain-table) NotificationPanel
 *       already showed.</li>
 *   <li><b>v3</b> (this class) goes back to a fixed-height single line —
 *       dot, one summary sentence, and a couple of compact glance-stats — and
 *       treats "what exactly is wrong and what do I do about it" as
 *       exclusively the health feed's job. Grouping/severity/credential-impact
 *       logic that used to live here moved to {@link IssueGrouping} so both
 *       classes compute it identically; snooze state stays here (session-only,
 *       see below) since this bar is the long-lived instance across however
 *       many times the feed dialog gets opened and closed.</li>
 * </ul>
 *
 * <p><b>Snooze</b> is session-scoped (in-memory, resets on app restart —
 * intentionally not persisted, since "quiet down, I'm already handling this"
 * shouldn't survive a crash/relaunch and hide a real problem). Snoozing
 * removes a group from the bell badge and from the feed's main list; it
 * never changes this bar's summary line or dot color, which always reflect
 * the true count.
 */
public class StatusStrip extends JPanel {

    private static final int LOOKBACK_HOURS = 24;
    private static final int QUERY_LIMIT = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("EEE HH:mm");

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private final WatchStatusMonitor watchStatusMonitor;

    // Session-only: group key -> when the snooze expires. Never written to disk;
    // see class javadoc for why that's deliberate. Owned here (not in
    // NotificationPanel) because a fresh NotificationPanel instance is created
    // every time the feed dialog is opened, and this state needs to survive
    // across opens/closes.
    private final Map<String, Instant> snoozedUntil = new HashMap<>();

    private final JLabel dot = new JLabel("\u25CF");
    private final JLabel summary = new JLabel();
    private final JLabel workersChip = glanceChip();
    private final JLabel nextRunChip = glanceChip();
    private final JLabel bellBadge = new JLabel();

    private final javax.swing.Timer pollTimer;
    private final java.util.function.Consumer<TaskRunRecord> runListener;

    public StatusStrip(XmlStorageService storage, TaskSchedulerService scheduler, WatchStatusMonitor watchStatusMonitor) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.watchStatusMonitor = watchStatusMonitor;

        setLayout(new BorderLayout(10, 0));
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.isDark() ? new Color(0x2B2E3E) : new Color(0xE7E7F0)),
                new EmptyBorder(6, 14, 6, 14)));
        setPreferredSize(new Dimension(100, 32));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter openOnClick = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { openFeed(); }
        };
        addMouseListener(openOnClick);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        dot.setFont(dot.getFont().deriveFont(Font.PLAIN, 12f));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 12.5f));
        left.add(dot);
        left.add(summary);
        add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        right.add(workersChip);
        right.add(nextRunChip);

        bellBadge.setFont(bellBadge.getFont().deriveFont(Font.BOLD, 10f));
        bellBadge.setOpaque(true);
        bellBadge.setForeground(Color.WHITE);
        bellBadge.setBackground(new Color(0xE53935));
        bellBadge.setBorder(new EmptyBorder(2, 7, 2, 7));
        right.add(bellBadge);
        add(right, BorderLayout.EAST);

        // Every child needs the same click-opens-feed behavior and the same
        // "not part of the layout flow, just visual" opaque=false — apply
        // the listener to the panels/labels too so a click anywhere on the
        // bar (not just the exact pixel the JPanel background shows through)
        // opens the feed.
        for (Component c : new Component[]{left, right, dot, summary, workersChip, nextRunChip, bellBadge}) {
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            c.addMouseListener(openOnClick);
        }

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

    private static JLabel glanceChip() {
        JLabel l = new JLabel();
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        l.setForeground(AppTheme.NEUTRAL_FG);
        return l;
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
    // Snooze — session state, shared with whichever NotificationPanel
    // instance is currently open (see openFeed()).
    // ------------------------------------------------------------------

    /** Snoozes a failure group (by its {@link IssueGrouping#groupByLikelyCause} key) for 2 hours. */
    void snooze(String groupKey) {
        snoozedUntil.put(groupKey, Instant.now().plusSeconds(2 * 3600));
        refresh();
    }

    /** Cancels an active snooze early. */
    void unsnooze(String groupKey) {
        snoozedUntil.remove(groupKey);
        refresh();
    }

    boolean isSnoozed(String groupKey) {
        purgeExpiredSnoozes();
        return snoozedUntil.containsKey(groupKey);
    }

    private void purgeExpiredSnoozes() {
        Instant now = Instant.now();
        snoozedUntil.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }

    // ------------------------------------------------------------------
    // Data refresh
    // ------------------------------------------------------------------

    /** Re-reads failures, trend, and worker/schedule glance-stats, then repaints. Cheap — call freely. */
    public void refresh() {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(LOOKBACK_HOURS);
        List<TaskRunRecord> failures;
        try {
            var history = scheduler.getRunHistoryService();
            failures = history.queryRuns(null, TaskRunRecord.Status.FAILED, since, null, QUERY_LIMIT);
        } catch (Exception e) {
            return; // DB briefly locked by a write — next poll tick will catch up
        }

        purgeExpiredSnoozes();
        Map<String, List<TaskRunRecord>> allGroups = IssueGrouping.groupByLikelyCause(failures);
        int activeCount = 0;
        for (var e : allGroups.entrySet()) {
            if (!snoozedUntil.containsKey(e.getKey())) activeCount += e.getValue().size();
        }
        int badgeCount = activeCount + (watchStatusMonitor != null ? watchStatusMonitor.getEventCount() : 0);
        bellBadge.setVisible(badgeCount > 0);
        bellBadge.setText(badgeCount > 99 ? "99+" : String.valueOf(badgeCount));

        int active = scheduler.getActiveWorkerCount();
        int total = scheduler.getWorkerPoolSize();
        workersChip.setText(active + "/" + total + " workers");
        nextRunChip.setText("Next " + describeNextScheduledCompact());

        int distinctFailingTasks = (int) failures.stream().map(TaskRunRecord::getTaskId).distinct().count();
        boolean anyBlocking = failures.stream().anyMatch(IssueGrouping::isBlocking);
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
    }

    /** Best-effort "what's coming up next" — cheapest useful signal to balance a bar that's otherwise all-bad-news. */
    private String describeNextScheduledCompact() {
        ScheduledTask next = findNextScheduled();
        if (next == null || next.getScheduledAt() == null) return "\u2014";
        return next.getScheduledAt().format(TIME_FMT);
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
    // Opening the feed
    // ------------------------------------------------------------------

    private void openFeed() {
        Window ownerWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(ownerWindow, "Health Feed", Dialog.ModalityType.MODELESS);
        NotificationPanel panel = new NotificationPanel(storage, scheduler, watchStatusMonitor, this);
        dialog.getContentPane().add(panel);
        dialog.setSize(760, 640);
        dialog.setLocationRelativeTo(ownerWindow);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { refresh(); }
        });
        dialog.setVisible(true);
    }
}
