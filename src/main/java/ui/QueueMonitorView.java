package ui;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Reusable "pending events + recent activity" table pair for one process's
 * scheduler state. Deliberately data-source agnostic (see {@link PendingRow}
 * / {@link ActivityRow}) so the same view can be fed either from the
 * in-process {@code TaskSchedulerService} (the GUI's own scheduler) or from
 * a {@code SchedulerStatusSnapshot} read back from another process's status
 * file (the headless Daemon) — see {@link EventMonitorPanel}.
 */
public class QueueMonitorView extends JPanel {

    public record PendingRow(String taskName, String scheduleType, int attempt, LocalDateTime dueAt) {}

    public record ActivityRow(String taskName, int attempt, LocalDateTime startedAt,
                               LocalDateTime finishedAt, boolean errored, String errorMessage) {}

    private static final Color COLOR_ERROR   = new Color(0xF5E0DC); // pale rust
    private static final Color COLOR_OK      = new Color(0xEAF0E3); // pale moss
    private static final Color COLOR_OVERDUE = new Color(0xFBF3E3); // pale wheat
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private static final String CARD_CONTENT = "content";
    private static final String CARD_OFFLINE = "offline";

    private JLabel lblWorkerStatus;
    private JLabel lblQueueStatus;
    private JProgressBar workerBar;
    private DefaultTableModel pendingModel;
    private JTable pendingTable;
    private DefaultTableModel activityModel;
    private JTable activityTable;
    private JLabel offlineLabel;

    // Most-recently-rendered activity rows, in the same order as
    // activityModel's rows — lets the click handler map a clicked table row
    // straight back to its full ActivityRow (error message included, which
    // the table itself only shows truncated). See setActivityRowClickListener.
    private List<ActivityRow> currentActivity = List.of();
    private BiConsumer<ActivityRow, Point> activityClickListener;

    public QueueMonitorView() {
        setLayout(new BorderLayout(8, 8));
        add(buildSummaryBar(), BorderLayout.NORTH);

        cardHost.add(buildContent(), CARD_CONTENT);
        cardHost.add(buildOfflineCard(), CARD_OFFLINE);
        add(cardHost, BorderLayout.CENTER);
    }

    private JComponent buildSummaryBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        lblWorkerStatus = new JLabel("Workers: —");
        lblWorkerStatus.setFont(lblWorkerStatus.getFont().deriveFont(Font.BOLD));
        workerBar = new JProgressBar(0, 1);
        workerBar.setPreferredSize(new Dimension(140, 14));
        lblQueueStatus = new JLabel("Pending events: —");
        lblQueueStatus.setFont(lblQueueStatus.getFont().deriveFont(Font.BOLD));

        bar.add(lblWorkerStatus);
        bar.add(workerBar);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(lblQueueStatus);
        return bar;
    }

    private JComponent buildContent() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildPendingSection(), buildActivitySection());
        split.setResizeWeight(0.5);
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
        panel.add(sectionHeader("Recent Activity — newest first"), BorderLayout.NORTH);

        activityModel = new DefaultTableModel(new Object[]{"Task", "Attempt", "Started", "Duration", "Outcome"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        activityTable = new JTable(activityModel);
        activityTable.setRowHeight(24);
        activityTable.setFillsViewportHeight(true);
        activityTable.getTableHeader().setReorderingAllowed(false);
        activityTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                             boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    Object outcome = table.getValueAt(row, 4);
                    boolean errored = outcome != null && outcome.toString().startsWith("ERROR");
                    c.setBackground(errored ? COLOR_ERROR : COLOR_OK);
                }
                return c;
            }
        });
        panel.add(new JScrollPane(activityTable), BorderLayout.CENTER);

        // Click a row to see a small summary popup for that one event (task,
        // timing, full outcome/error) — same idea as the Logs panel's
        // double-click-for-details, but a single click here since this is
        // already a lightweight "just tell me what happened" glance rather
        // than a big detail dialog. See setActivityRowClickListener.
        activityTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (activityClickListener == null) return;
                int viewRow = activityTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                int modelRow = activityTable.convertRowIndexToModel(viewRow);
                if (modelRow < 0 || modelRow >= currentActivity.size()) return;
                activityClickListener.accept(currentActivity.get(modelRow), e.getLocationOnScreen());
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
        lblWorkerStatus.setText("Workers: —");
        lblQueueStatus.setText("Pending events: —");
        workerBar.setValue(0);
    }

    /** Pushes a fresh snapshot into the tables. */
    public void update(int poolSize, int activeWorkers, List<PendingRow> pending, List<ActivityRow> activity) {
        cards.show(cardHost, CARD_CONTENT);

        lblWorkerStatus.setText("Workers: " + activeWorkers + " / " + poolSize + " busy");
        workerBar.setMaximum(Math.max(1, poolSize));
        workerBar.setValue(Math.min(activeWorkers, Math.max(1, poolSize)));
        lblQueueStatus.setText("Pending events: " + pending.size());

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

        int aSel = activityTable.getSelectedRow();
        activityModel.setRowCount(0);
        currentActivity = activity;
        for (ActivityRow row : activity) {
            Duration d = Duration.between(row.startedAt(), row.finishedAt());
            String outcome = row.errored() ? "ERROR: " + shorten(row.errorMessage(), 60) : "OK";
            activityModel.addRow(new Object[]{
                    row.taskName(),
                    row.attempt() > 0 ? "retry " + row.attempt() : "—",
                    row.startedAt().format(TIME_FMT),
                    formatDuration(d),
                    outcome
            });
        }
        if (aSel >= 0 && aSel < activityModel.getRowCount()) activityTable.setRowSelectionInterval(aSel, aSel);
    }

    /**
     * Registers a callback fired when the operator clicks a row in the
     * Recent Activity table — passed the full {@link ActivityRow} (including
     * the untruncated error message) and the click's screen location, so the
     * caller can anchor a small detail popup right there. See
     * {@link EventMonitorPanel} for the popup itself.
     */
    public void setActivityRowClickListener(BiConsumer<ActivityRow, Point> listener) {
        this.activityClickListener = listener;
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
