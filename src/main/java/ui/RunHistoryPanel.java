package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.RunHistoryService;
import service.XmlStorageService;
import export.XlsxWriter;
import export.PdfTableWriter;
import export.HtmlReportWriter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Date;
import java.util.ArrayList;
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

    private static final Color COLOR_FAILED  = new Color(0xF5E0DC); // pale rust
    private static final Color COLOR_SUCCESS = new Color(0xEAF0E3); // pale moss
    private static final Color COLOR_SKIPPED = new Color(0xFBF3E3); // pale wheat
    private static final String HEX_FAILED  = "FFEBEE";
    private static final String HEX_SUCCESS = "E8F5E9";
    private static final String HEX_SKIPPED = "FFF8E1";
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
        JPanel bar = new JPanel(new BorderLayout(0, 4));

        JLabel banner = new JLabel(
            "<html><b>Task Run Logs</b> — every recorded run (success, failure, or skip) for every task.<br>"
            + "<span style='color:gray'>Filter by task or status below, then export or archive as needed.</span></html>");
        banner.setBorder(new EmptyBorder(0, 0, 4, 0));
        bar.add(banner, BorderLayout.NORTH);

        JPanel filters = new JPanel();
        filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));
        bar.add(filters, BorderLayout.CENTER);

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

        JButton btnExportExcel = new JButton("Export Excel");
        btnExportExcel.setToolTipText("Export the rows currently shown (with the same status coloring) to a .xlsx file");
        btnExportExcel.addActionListener(e -> exportExcel());
        row1.add(btnExportExcel);

        JButton btnExportPdf = new JButton("Export PDF");
        btnExportPdf.setToolTipText("Export the rows currently shown (with the same status coloring) to a .pdf file");
        btnExportPdf.addActionListener(e -> exportPdf());
        row1.add(btnExportPdf);

        JButton btnExportHtml = new JButton("Export HTML");
        btnExportHtml.setToolTipText("Export the rows currently shown to a self-contained, offline-viewable .html report "
                + "with a collapsible task/run tree, search, and charts");
        btnExportHtml.addActionListener(e -> exportHtml());
        row1.add(btnExportHtml);

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
        spFromDate.setEditor(new JSpinner.DateEditor(spFromDate, "yyyy-MM-dd HH:mm"));
        spFromDate.setValue(toDateTime(LocalDate.now().minusDays(7), LocalTime.MIDNIGHT));
        spFromDate.setEnabled(false);
        ChangeListener dateChangeListener = e -> { if (!suppressFilterEvents && cbUseDateFilter.isSelected()) refresh(); };
        spFromDate.addChangeListener(dateChangeListener);
        row2.add(spFromDate);

        row2.add(new JLabel("To:"));
        spToDate = new JSpinner(new SpinnerDateModel());
        spToDate.setEditor(new JSpinner.DateEditor(spToDate, "yyyy-MM-dd HH:mm"));
        spToDate.setValue(toDateTime(LocalDate.now(), LocalTime.of(23, 59)));
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

        filters.add(row1);
        filters.add(row2);
        return bar;
    }

    private static Date toDateTime(LocalDate d, LocalTime t) {
        return Date.from(LocalDateTime.of(d, t).atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Reads both the date AND time of day the user set on the spinner —
     * previously this only had a date picker, so "From 09:00 to 17:00"
     * type ranges weren't expressible; a "From" filter always meant
     * midnight and "To" always meant end-of-day regardless of what the
     * user actually wanted. Now the spinner's own time-of-day is used
     * as-is; {@code endOfDay} still pads seconds up to :59 so a "To" value
     * of e.g. 17:30 is inclusive of that whole minute.
     */
    private static LocalDateTime dateSpinnerToLocalDateTime(JSpinner spinner, boolean endOfDay) {
        Date d = (Date) spinner.getValue();
        LocalDateTime ldt = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        return endOfDay ? ldt.withSecond(59) : ldt.withSecond(0);
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
                    Color special = null;
                    switch (r.getStatus()) {
                        case SUCCESS: special = COLOR_SUCCESS; break;
                        case FAILED:  special = COLOR_FAILED;  break;
                        case SKIPPED: special = COLOR_SKIPPED; break;
                    }
                    if (special != null) {
                        // Pale status tints stay pale regardless of app theme, so force dark
                        // text on them explicitly — the default (theme-following) foreground
                        // goes light-on-light and disappears in dark mode otherwise.
                        c.setBackground(special);
                        c.setForeground(new Color(0x2B2116));
                    } else {
                        c.setBackground(table.getBackground());
                        c.setForeground(table.getForeground());
                    }
                } else if (!isSelected) {
                    c.setBackground(table.getBackground());
                    c.setForeground(table.getForeground());
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

    private static String hexForStatus(TaskRunRecord.Status status) {
        switch (status) {
            case SUCCESS: return HEX_SUCCESS;
            case FAILED:  return HEX_FAILED;
            case SKIPPED: return HEX_SKIPPED;
            default:      return null;
        }
    }

    /** Common headers/values for both export formats — one row per currently-displayed record, in the same order shown on screen. */
    private static final String[] EXPORT_HEADERS = {"Task", "Type", "Status", "Started", "Ended", "Duration", "Reason", "Details"};

    private List<String[]> buildExportRows() {
        List<String[]> out = new ArrayList<>();
        for (TaskRunRecord r : currentRows) {
            out.add(new String[]{
                    r.getTaskName(),
                    r.getTaskType() != null ? r.getTaskType() : "",
                    r.getStatus().name(),
                    r.getStartedAt().format(DT_FMT),
                    r.getEndedAt().format(DT_FMT),
                    formatDuration(r.getDurationMs()),
                    r.getReason() != null ? r.getReason() : "",
                    r.getDetails() != null ? r.getDetails() : ""
            });
        }
        return out;
    }

    private List<String> buildExportFills() {
        List<String> out = new ArrayList<>();
        for (TaskRunRecord r : currentRows) out.add(hexForStatus(r.getStatus()));
        return out;
    }

    /**
     * Runs {@code exportTask} (the actual file write) on a background
     * thread so exporting a few thousand rows doesn't freeze the UI, then
     * shows a success/error dialog back on the EDT. Shared by both the
     * Excel and PDF export buttons.
     */
    private void runExport(String suggestedFileName, String description, String extension,
            ExportTask exportTask) {
        if (currentRows == null || currentRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No rows to export — adjust the filters above first.",
                    "Nothing to export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export " + description);
        chooser.setSelectedFile(new File(suggestedFileName));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(description, extension));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith("." + extension)) {
            target = new File(target.getParentFile(), target.getName() + "." + extension);
        }
        final File finalTarget = target;

        List<String[]> rows = buildExportRows();
        List<String> fills = buildExportFills();

        new SwingWorker<Void, Void>() {
            Exception failure;
            @Override protected Void doInBackground() {
                try {
                    exportTask.run(finalTarget, rows, fills);
                } catch (Exception ex) {
                    failure = ex;
                }
                return null;
            }
            @Override protected void done() {
                if (failure != null) {
                    JOptionPane.showMessageDialog(RunHistoryPanel.this,
                            "Export failed: " + failure.getMessage(),
                            "Export error", JOptionPane.ERROR_MESSAGE);
                } else {
                    int open = JOptionPane.showConfirmDialog(RunHistoryPanel.this,
                            "Exported " + rows.size() + " row(s) to:\n" + finalTarget.getAbsolutePath()
                                    + "\n\nOpen the containing folder now?",
                            "Export complete", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (open == JOptionPane.YES_OPTION) {
                        try {
                            Desktop.getDesktop().open(finalTarget.getParentFile());
                        } catch (Exception ignored) {
                            // best-effort — not fatal if no desktop file manager is available
                        }
                    }
                }
            }
        }.execute();
    }

    private void exportExcel() {
        int[] widths = {22, 16, 12, 20, 20, 12, 40, 60};
        runExport("task_logs.xlsx", "Excel Workbook (*.xlsx)", "xlsx",
                (file, rows, fills) -> XlsxWriter.write(file, EXPORT_HEADERS, widths, rows, fills));
    }

    private void exportPdf() {
        // Points, sums to within the Letter-landscape usable width (792 - 2*30 margin = 732).
        float[] widths = {95f, 65f, 55f, 85f, 85f, 55f, 150f, 142f};
        runExport("task_logs.pdf", "PDF Document (*.pdf)", "pdf",
                (file, rows, fills) -> PdfTableWriter.write(file, "Task Run Logs — exported " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        EXPORT_HEADERS, widths, rows, fills));
    }

    private void exportHtml() {
        runExport("task_logs.html", "HTML Report (*.html)", "html",
                (file, rows, fills) -> HtmlReportWriter.write(file, "Task Run Logs — exported " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        EXPORT_HEADERS, rows));
    }

    @FunctionalInterface
    private interface ExportTask {
        void run(File file, List<String[]> rows, List<String> rowFillHex) throws Exception;
    }
}
