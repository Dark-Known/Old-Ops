package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Reusable "pending events + event stream" pair for one process's scheduler
 * state. Deliberately data-source agnostic (see {@link PendingRow} /
 * {@link ActivityRow}) so the same view can be fed either from the
 * in-process {@code TaskSchedulerService} (the GUI's own scheduler) or from
 * a {@code SchedulerStatusSnapshot} read back from another process's status
 * file (the headless Daemon) — see {@link EventMonitorPanel}.
 *
 * <p>Pending events stay a plain {@code JTable} — a small, structured,
 * rarely-more-than-a-handful list, not a stream. The Events section (Phase 4
 * of the row-card redesign) is a genuinely different UX problem: a live feed
 * where new items arrive continuously and old ones age out, so it's a
 * compact single-line {@code JList} (density over the two-line card look
 * used elsewhere) with a severity dot instead of a full-row tint, and a
 * brief highlight on newly-arrived rows — the one place in this redesign
 * where a little motion earns its keep, since it's how the user notices
 * something just happened without staring at the list waiting for it.
 */
public class QueueMonitorView extends JPanel {

    public record PendingRow(String taskName, String scheduleType, int attempt, LocalDateTime dueAt) {}

    public record ActivityRow(String taskId, String taskName, int attempt, LocalDateTime startedAt,
                               LocalDateTime finishedAt, boolean errored, String errorMessage) {}

    private static final Color COLOR_OVERDUE = new Color(0xFBF3E3); // pale wheat
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int HIGHLIGHT_MILLIS = 1400;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private static final String CARD_CONTENT = "content";
    private static final String CARD_OFFLINE = "offline";

    private DefaultTableModel pendingModel;
    private JTable pendingTable;
    private DefaultListModel<ActivityRow> activityListModel;
    private JList<ActivityRow> activityList;
    private JLabel offlineLabel;
    private BiConsumer<ActivityRow, Point> activityClickListener;

    // Keys (taskId|startedAt) seen in the previous update() call — anything
    // new this time gets a brief highlight. Keys currently mid-highlight, so
    // the renderer knows to paint them differently until their Timer clears
    // them back out.
    private Set<String> previousKeys = new HashSet<>();
    private final Set<String> highlightedKeys = new HashSet<>();

    public QueueMonitorView() {
        setLayout(new BorderLayout(8, 8));
        cardHost.add(buildContent(), CARD_CONTENT);
        cardHost.add(buildOfflineCard(), CARD_OFFLINE);
        add(cardHost, BorderLayout.CENTER);
    }

    private JComponent buildContent() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildPendingSection(), buildActivitySection());
        split.setResizeWeight(0.4);
        split.setBorder(null);
        split.setContinuousLayout(true);
        return split;
    }

    private JComponent buildOfflineCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        offlineLabel = new JLabel("No data");
        offlineLabel.setFont(offlineLabel.getFont().deriveFont(Font.PLAIN, 14f));
        offlineLabel.setForeground(UIManager.getColor("Label.disabledForeground") != null
                ? UIManager.getColor("Label.disabledForeground") : Color.GRAY);
        panel.add(offlineLabel);
        return panel;
    }

    private JComponent buildPendingSection() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(sectionHeader("Pending Events — soonest due first"), BorderLayout.NORTH);

        pendingModel = new DefaultTableModel(new Object[]{"Task", "Schedule", "Attempt", "Due At", "In"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        pendingTable = new JTable(pendingModel);
        pendingTable.setRowHeight(24);
        pendingTable.setFillsViewportHeight(true);
        pendingTable.getTableHeader().setReorderingAllowed(false);
        pendingTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                             boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    Object inCol = table.getValueAt(row, 4);
                    boolean overdue = inCol != null && inCol.toString().startsWith("overdue");
                    c.setBackground(overdue ? COLOR_OVERDUE : Color.WHITE);
                }
                return c;
            }
        });
        panel.add(new JScrollPane(pendingTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildActivitySection() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(sectionHeader("Events — newest first (click a row for details)"), BorderLayout.NORTH);

        activityListModel = new DefaultListModel<>();
        activityList = new JList<>(activityListModel);
        activityList.setCellRenderer(new EventRowRenderer());
        activityList.setFixedCellHeight(24);
        panel.add(new JScrollPane(activityList), BorderLayout.CENTER);

        // Click a row to see a small summary popup for that one event (task,
        // timing, full outcome/error) — same idea as the Logs panel's
        // double-click-for-details, but a single click here since this is
        // already a lightweight "just tell me what happened" glance rather
        // than a big detail dialog. See setActivityRowClickListener.
        activityList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (activityClickListener == null) return;
                int index = activityList.locationToIndex(e.getPoint());
                if (index < 0 || !activityList.getCellBounds(index, index).contains(e.getPoint())) return;
                activityClickListener.accept(activityListModel.get(index), e.getLocationOnScreen());
            }
        });
        return panel;
    }

    private JLabel sectionHeader(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        return l;
    }

    /** Shows the offline/unavailable placeholder instead of the tables. */
    public void showUnavailable(String message) {
        offlineLabel.setText(message);
        cards.show(cardHost, CARD_OFFLINE);
    }

    private static String keyOf(ActivityRow row) {
        return row.taskId() + "|" + row.startedAt();
    }

    /** Pushes a fresh snapshot into the tables. {@code poolSize}/{@code activeWorkers} are
     *  currently unused by this view (the Active Scheduler table they used to feed was
     *  removed — the panel/tab title now conveys which scheduler is active instead — kept
     *  as parameters so callers don't need to change) but reserved in case a future compact
     *  "N/M workers busy" indicator is added back in a less redundant form. */
    public void update(int poolSize, int activeWorkers, List<PendingRow> pending, List<ActivityRow> activity) {
        cards.show(cardHost, CARD_CONTENT);

        int pSel = pendingTable.getSelectedRow();
        pendingModel.setRowCount(0);
        LocalDateTime now = LocalDateTime.now();
        for (PendingRow row : pending) {
            pendingModel.addRow(new Object[]{
                    row.taskName(),
                    row.scheduleType(),
                    row.attempt() > 0 ? "retry " + row.attempt() : "—",
                    row.dueAt().format(TIME_FMT),
                    formatCountdown(row.dueAt(), now)
            });
        }
        if (pSel >= 0 && pSel < pendingModel.getRowCount()) pendingTable.setRowSelectionInterval(pSel, pSel);

        // New-row detection for the brief highlight — compare this update's
        // keys against the previous update's, before overwriting the model.
        Set<String> newKeys = new HashSet<>();
        for (ActivityRow row : activity) {
            String key = keyOf(row);
            if (!previousKeys.contains(key)) newKeys.add(key);
        }
        previousKeys = new HashSet<>();
        for (ActivityRow row : activity) previousKeys.add(keyOf(row));

        ActivityRow selected = activityList.getSelectedValue();
        activityListModel.clear();
        for (ActivityRow row : activity) activityListModel.addElement(row);
        if (selected != null) {
            String selKey = keyOf(selected);
            for (int i = 0; i < activityListModel.size(); i++) {
                if (keyOf(activityListModel.get(i)).equals(selKey)) {
                    activityList.setSelectedIndex(i);
                    break;
                }
            }
        }

        // Flash newly-arrived rows, then let them settle back to normal — a
        // one-shot delayed switch-off rather than a continuously-repainting
        // smooth fade, since that's much simpler to get right and still
        // reads clearly as "something just happened here."
        for (String key : newKeys) {
            highlightedKeys.add(key);
            Timer t = new Timer(HIGHLIGHT_MILLIS, e -> {
                highlightedKeys.remove(key);
                activityList.repaint();
            });
            t.setRepeats(false);
            t.start();
        }
        if (!newKeys.isEmpty()) activityList.repaint();
    }

    /**
     * Registers a callback fired when the operator clicks a row in the
     * Events list — passed the full {@link ActivityRow} (including the
     * untruncated error message) and the click's screen location, so the
     * caller can anchor a small detail popup right there. See
     * {@link EventMonitorPanel} for the popup itself.
     */
    public void setActivityRowClickListener(BiConsumer<ActivityRow, Point> listener) {
        this.activityClickListener = listener;
    }

    /**
     * One compact line per event: a severity dot, task name, outcome, and
     * timing — density over the two-line card look used elsewhere, since
     * this is a live stream meant to be scanned quickly, not browsed.
     */
    private class EventRowRenderer extends JPanel implements ListCellRenderer<ActivityRow> {
        private final JLabel dot = new JLabel("\u25CF");
        private final JLabel text = new JLabel();
        private final JLabel time = new JLabel();

        EventRowRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(2, 8, 2, 8));
            dot.setFont(dot.getFont().deriveFont(10f));
            text.setFont(text.getFont().deriveFont(Font.PLAIN, 12f));
            time.setFont(time.getFont().deriveFont(Font.PLAIN, 11f));
            time.setForeground(new Color(0x8A8378));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(dot);
            left.add(text);
            add(left, BorderLayout.CENTER);
            add(time, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ActivityRow> list, ActivityRow row,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            String key = keyOf(row);
            boolean highlighted = highlightedKeys.contains(key);

            Duration d = Duration.between(row.startedAt(), row.finishedAt());
            String outcome = row.errored() ? "Failed: " + shorten(row.errorMessage(), 70)
                    : (row.errorMessage() != null && !row.errorMessage().isBlank()
                            ? shorten(row.errorMessage(), 70) : "Succeeded");

            text.setText(row.taskName() + (row.attempt() > 0 ? " (retry " + row.attempt() + ")" : "")
                    + "  \u2014  " + outcome);
            time.setText(row.startedAt().format(TIME_FMT) + "  \u00b7  " + formatDuration(d));

            Color dotColor = row.errored() ? AppTheme.FAILED_FG : AppTheme.SUCCESS_FG;
            dot.setForeground(dotColor);

            Color bg;
            if (isSelected) {
                bg = list.getSelectionBackground();
                // Same fix as the task/credential row renderers: force
                // high-contrast text against the selection highlight instead
                // of leaving the dot/text/time colors fixed regardless of
                // what's now behind them.
                dot.setForeground(list.getSelectionForeground());
                text.setForeground(list.getSelectionForeground());
                time.setForeground(list.getSelectionForeground());
            } else if (highlighted) {
                bg = row.errored() ? AppTheme.FAILED_BG : AppTheme.SUCCESS_BG;
                text.setForeground(UIManager.getColor("List.foreground"));
                time.setForeground(new Color(0x8A8378));
            } else {
                bg = index % 2 == 0 ? list.getBackground() : AppTheme.surface2();
                text.setForeground(UIManager.getColor("List.foreground"));
                time.setForeground(new Color(0x8A8378));
            }
            setBackground(bg);
            setOpaque(true);
            return this;
        }
    }

    static String formatCountdown(LocalDateTime dueAt, LocalDateTime now) {
        Duration d = Duration.between(now, dueAt);
        if (d.isNegative()) return "overdue " + formatDuration(d.abs());
        return "in " + formatDuration(d);
    }

    static String formatDuration(Duration d) {
        long totalSeconds = Math.max(0, d.getSeconds());
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
