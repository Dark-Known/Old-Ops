package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.RunHistoryService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * "Logs" tab — browses the SQLite run-history database (see
 * {@link RunHistoryService}) instead of raw text logs. Each row is one task
 * run, showing at a glance whether it succeeded, failed, or was skipped and
 * why — with the full captured log for that single run available on
 * double-click for when the short reason isn't enough.
 *
 * <p>All filters (task, status, date range, row limit) apply automatically
 * as soon as they're changed — there's no separate "Apply" step, though a
 * manual Refresh button is kept for re-pulling the latest rows without
 * changing any filter. The panel also refreshes itself live whenever a new
 * run is recorded anywhere in the app (see {@link #onRunRecorded}), so it
 * doesn't go stale while you're sitting on this tab.
 */
public class RunHistoryPanel extends JPanel {

    private static final Color COLOR_FAILED  = new Color(0xFFEBEE);
    private static final Color COLOR_SUCCESS = new Color(0xE8F5E9);
    private static final Color COLOR_SKIPPED = new Color(0xFFF8E1);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final XmlStorageService storage;
    private final RunHistoryService runHistoryService;

    private JComboBox<String> cbTaskFilter;
    private JComboBox<String> cbStatusFilter;
    private JCheckBox cbUseDateFilter;
    private JSpinner spFromDate;
    private JSpinner spToDate;
    private JSpinner spLimit;
    private DefaultTableModel tableModel;
    private JTable table;
    private List<TaskRunRecord> currentRows;

    // Guards against the filter-repopulation in refresh() (removeAllItems /
    // addItem on the task combo) re-triggering itself via the very
    // ActionListener that's supposed to auto-apply filters on user changes.
    private boolean suppressFilterEvents = false;

    public RunHistoryPanel(XmlStorageService storage, RunHistoryService runHistoryService) {
        this.storage = storage;
        this.runHistoryService = runHistoryService;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildFilterBar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);

        refresh();
    }

    private JComponent buildFilterBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.add(new JLabel("Task:"));
        cbTaskFilter = new JComboBox<>();
        cbTaskFilter.addItem("All tasks");
        cbTaskFilter.addActionListener(e -> { if (!suppressFilterEvents) refresh(); });
        row1.add(cbTaskFilter);

        row1.add(new JLabel("Status:"));
        cbStatusFilter = new JComboBox<>(new String[]{"All", "SUCCESS", "FAILED", "SKIPPED"});
        cbStatusFilter.addActionListener(e -> { if (!suppressFilterEvents) refresh(); });
        row1.add(cbStatusFilter);

        row1.add(new JLabel("Show last:"));
        spLimit = new JSpinner(new SpinnerNumberModel(200, 10, 5000, 50));
        ((JSpinner.DefaultEditor) spLimit.getEditor()).getTextField().setColumns(5);
        spLimit.addChangeListener(e -> { if (!suppressFilterEvents) refresh(); });
        row1.add(spLimit);

        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.setToolTipText("Filters apply automatically — this just re-pulls the latest rows without changing them.");
        btnRefresh.addActionListener(e -> refresh());
        row1.add(btnRefresh);

        JLabel hint = new JLabel("Double-click a row for full run details");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(new Color(0x757575));
        row1.add(hint);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cbUseDateFilter = new JCheckBox("Filter by date range:");
        cbUseDateFilter.addActionListener(e -> {
            boolean on = cbUseDateFilter.isSelected();
            spFromDate.setEnabled(on);
            spToDate.setEnabled(on);
            if (!suppressFilterEvents) refresh();
        });
        row2.add(cbUseDateFilter);

        row2.add(new JLabel("From:"));
        spFromDate = new JSpinner(new SpinnerDateModel());
        spFromDate.setEditor(new JSpinner.DateEditor(spFromDate, "yyyy-MM-dd"));
        spFromDate.setValue(toDate(LocalDate.now().minusDays(7)));
        spFromDate.setEnabled(false);
        ChangeListener dateChangeListener = e -> { if (!suppressFilterEvents && cbUseDateFilter.isSelected()) refresh(); };
        spFromDate.addChangeListener(dateChangeListener);
        row2.add(spFromDate);

        row2.add(new JLabel("To:"));
        spToDate = new JSpinner(new SpinnerDateModel());
        spToDate.setEditor(new JSpinner.DateEditor(spToDate, "yyyy-MM-dd"));
        spToDate.setValue(toDate(LocalDate.now()));
        spToDate.setEnabled(false);
        spToDate.addChangeListener(dateChangeListener);
        row2.add(spToDate);

        JButton btnClearDates = new JButton("Clear dates");
        btnClearDates.addActionListener(e -> {
            cbUseDateFilter.setSelected(false);
            spFromDate.setEnabled(false);
            spToDate.setEnabled(false);
            refresh();
        });
        row2.add(btnClearDates);

        bar.add(row1);
        bar.add(row2);
        return bar;
    }

    private static Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDateTime dateSpinnerToLocalDateTime(JSpinner spinner, boolean endOfDay) {
        Date d = (Date) spinner.getValue();
        LocalDate ld = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return endOfDay ? LocalDateTime.of(ld, LocalTime.of(23, 59, 59)) : LocalDateTime.of(ld, LocalTime.MIDNIGHT);
    }

    private JComponent buildTable() {
        String[] columns = {"Task", "Type", "Status", "Started", "Duration", "Reason"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(140);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(420);

        DefaultTableCellRenderer rowColorRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected && row < currentRows.size()) {
                    int modelRow = table.convertRowIndexToModel(row);
                    TaskRunRecord r = currentRows.get(modelRow);
                    switch (r.getStatus()) {
                        case SUCCESS: c.setBackground(COLOR_SUCCESS); break;
                        case FAILED:  c.setBackground(COLOR_FAILED);  break;
                        case SKIPPED: c.setBackground(COLOR_SKIPPED); break;
                    }
                } else if (!isSelected) {
                    c.setBackground(Color.WHITE);
                }
                return c;
            }
        };
        for (int i = 0; i < columns.length; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(rowColorRenderer);
        }

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow < 0) return;
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    showDetails(currentRows.get(modelRow));
                }
            }
        });

        return new JScrollPane(table);
    }

    /**
     * Repopulates the task filter dropdown and reloads the table from the
     * database using the current filter values. Safe to call from
     * {@link #onRunRecorded} — repopulating the task combo is guarded so it
     * doesn't recursively re-trigger this same method via its own listener.
     */
    public void refresh() {
        suppressFilterEvents = true;
        try {
            String previouslySelectedTask = (String) cbTaskFilter.getSelectedItem();
            cbTaskFilter.removeAllItems();
            cbTaskFilter.addItem("All tasks");
            for (ScheduledTask t : storage.loadTasks()) {
                cbTaskFilter.addItem(t.getName());
            }
            if (previouslySelectedTask != null) {
                cbTaskFilter.setSelectedItem(previouslySelectedTask);
            }
        } finally {
            suppressFilterEvents = false;
        }

        String taskFilterName = (String) cbTaskFilter.getSelectedItem();
        String statusFilterStr = (String) cbStatusFilter.getSelectedItem();
        int limit = (Integer) spLimit.getValue();

        String taskId = null;
        if (taskFilterName != null && !"All tasks".equals(taskFilterName)) {
            taskId = storage.loadTasks().stream()
                    .filter(t -> t.getName().equals(taskFilterName))
                    .map(ScheduledTask::getId)
                    .findFirst().orElse(null);
        }
        TaskRunRecord.Status status = (statusFilterStr != null && !"All".equals(statusFilterStr))
                ? TaskRunRecord.Status.valueOf(statusFilterStr) : null;

        LocalDateTime from = null;
        LocalDateTime to = null;
        if (cbUseDateFilter.isSelected()) {
            from = dateSpinnerToLocalDateTime(spFromDate, false);
            to = dateSpinnerToLocalDateTime(spToDate, true);
        }

        currentRows = runHistoryService.queryRuns(taskId, status, from, to, limit);

        tableModel.setRowCount(0);
        for (TaskRunRecord r : currentRows) {
            tableModel.addRow(new Object[]{
                    r.getTaskName(),
                    r.getTaskType(),
                    r.getStatus().name(),
                    r.getStartedAt().format(DT_FMT),
                    formatDuration(r.getDurationMs()),
                    r.getReason() != null ? r.getReason() : ""
            });
        }
    }

    /**
     * Called (via {@link SwingUtilities#invokeLater}, from whatever thread
     * recorded the run) whenever a new run is recorded anywhere in the app,
     * so this tab reflects new runs immediately without the user needing to
     * switch tabs or click Refresh.
     */
    public void onRunRecorded(TaskRunRecord record) {
        refresh();
    }

    private void showDetails(TaskRunRecord r) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new Color(0x1E1E1E));
        area.setForeground(new Color(0xD4D4D4));

        StringBuilder sb = new StringBuilder();
        sb.append("Task:     ").append(r.getTaskName()).append(" (").append(r.getTaskType()).append(")\n");
        sb.append("Status:   ").append(r.getStatus()).append('\n');
        sb.append("Started:  ").append(r.getStartedAt().format(DT_FMT)).append('\n');
        sb.append("Ended:    ").append(r.getEndedAt().format(DT_FMT)).append('\n');
        sb.append("Duration: ").append(formatDuration(r.getDurationMs())).append('\n');
        sb.append("Reason:   ").append(r.getReason() != null ? r.getReason() : "-").append('\n');
        sb.append("\n--- Full run log ---\n");
        sb.append(r.getDetails() != null && !r.getDetails().isEmpty() ? r.getDetails() : "(no detail lines captured)");
        area.setText(sb.toString());
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(700, 450));

        JOptionPane.showMessageDialog(this, scroll,
                "Run details — " + r.getTaskName() + " @ " + r.getStartedAt().format(DT_FMT),
                JOptionPane.PLAIN_MESSAGE);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return s + "s";
    }
}
