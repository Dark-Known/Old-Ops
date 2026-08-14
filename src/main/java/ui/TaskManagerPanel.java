package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.TransferService;
import service.XmlStorageService;
import model.ScheduledTask.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskManagerPanel extends JPanel {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Status colours ────────────────────────────────────────────────────────
    private static final Color COLOR_RUNNING  = new Color(0xE3F2FD); // light blue
    private static final Color COLOR_FAILED   = new Color(0xFFEBEE); // light red
    private static final Color COLOR_SUCCESS  = new Color(0xE8F5E9); // light green
    private static final Color COLOR_DISABLED = new Color(0xF5F5F5); // light grey
    private static final Color COLOR_SKIPPED  = new Color(0xFFF8E1); // light amber

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private DefaultTableModel tableModel;
    private JTable table;
    private JTextArea logArea;
    private JLabel lblSelectedTask;

    // ── Watcher fingerprint status bar ────────────────────────────────────────
    // Surfaces the epoch + size baseline for the selected task.
    // Refreshed on selection change AND after every log line (which fires after
    // a run completes and the service may have persisted a new baseline).
    private JLabel lblWatcherFingerprint;
    private JPanel watcherBar; // kept as a field so refresh() can reach it directly

    // taskId -> accumulated in-memory log
    private final Map<String, StringBuilder> taskLogs = new HashMap<>();
    private final List<String> taskIds = new ArrayList<>();

    public TaskManagerPanel(XmlStorageService storage, TaskSchedulerService scheduler) {
        this.storage   = storage;
        this.scheduler = scheduler;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();

        // Register log callback — called by the scheduler on every emitted log line
        scheduler.setLogCallback((taskId, line) ->
            SwingUtilities.invokeLater(() -> appendLog(taskId, line)));
    }

    private void buildUI() {
        // ── Toolbar ──────────────────────────────────────────────────────────
        JButton btnNew        = new JButton("New Task");
        JButton btnEdit       = new JButton("Edit");
        JButton btnDelete     = new JButton("Delete");
        JButton btnRunNow     = new JButton("Run Now");
        JButton btnRestart    = new JButton("Restart Task");
        JButton btnEnable     = new JButton("Enable/Disable");
        JButton btnRefresh    = new JButton("Refresh");
        JButton btnViewLogs   = new JButton("View Logs");
        JButton btnLatestLogs = new JButton("Latest Logs");

        styleBtn(btnNew,        new Color(0x2E7D32));
        styleBtn(btnRunNow,     new Color(0x1565C0));
        styleBtn(btnRestart,    new Color(0xF57C00));
        styleBtn(btnDelete,     new Color(0xC62828));
        styleBtn(btnViewLogs,   new Color(0xFF6F00));
        styleBtn(btnLatestLogs, new Color(0x00796B));

        btnNew.addActionListener(e        -> newTask());
        btnEdit.addActionListener(e       -> editTask());
        btnDelete.addActionListener(e     -> deleteTask());
        btnRunNow.addActionListener(e     -> runNow());
        btnRestart.addActionListener(e    -> restartTask());
        btnEnable.addActionListener(e     -> toggleEnable());
        btnRefresh.addActionListener(e    -> refresh());
        btnViewLogs.addActionListener(e   -> showLogForSelected());
        btnLatestLogs.addActionListener(e -> showLatestLogsForSelected());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.add(btnNew);    toolbar.add(btnEdit);    toolbar.add(btnDelete);
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        toolbar.add(btnRunNow); toolbar.add(btnRestart); toolbar.add(btnEnable);
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(btnRefresh);
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        toolbar.add(btnViewLogs); toolbar.add(btnLatestLogs);

        JPanel legend = buildLegend();

        JPanel headerPanel = new JPanel(new BorderLayout(0, 4));
        headerPanel.add(toolbar, BorderLayout.NORTH);
        headerPanel.add(legend,  BorderLayout.SOUTH);
        add(headerPanel, BorderLayout.NORTH);

        // ── Table ─────────────────────────────────────────────────────────────
        String[] cols = {
            "Name", "Type", "Direction", "Mode", "Status",
            "Schedule", "Last Run", "Next Run", "Last Result"
        };
        tableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row)) {
                    String status = (String) tableModel.getValueAt(row, 4); // col 4 = Status
                    String result = (String) tableModel.getValueAt(row, 8); // col 8 = Last Result
                    String mode   = (String) tableModel.getValueAt(row, 3); // col 3 = Mode (LOCAL)
                    c.setBackground(rowColor(status, result, mode));
                }
                return c;
            }
        };

        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(180); // Name
        table.getColumnModel().getColumn(1).setPreferredWidth(110); // Type
        table.getColumnModel().getColumn(2).setPreferredWidth(80);  // Direction
        table.getColumnModel().getColumn(3).setPreferredWidth(80);  // Mode
        table.getColumnModel().getColumn(4).setPreferredWidth(80);  // Status
        table.getColumnModel().getColumn(5).setPreferredWidth(115); // Schedule
        table.getColumnModel().getColumn(6).setPreferredWidth(115); // Last Run
        table.getColumnModel().getColumn(7).setPreferredWidth(115); // Next Run
        table.getColumnModel().getColumn(8).setPreferredWidth(85);  // Last Result

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showLogForSelected();
                updateWatcherFingerprintBar();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(900, 220));

        // ── Log panel ─────────────────────────────────────────────────────────
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0x1E1E1E));
        logArea.setForeground(new Color(0xD4D4D4));
        logArea.setCaretColor(Color.WHITE);
        JScrollPane logScroll = new JScrollPane(logArea);

        lblSelectedTask = new JLabel("Select a task to view its execution log");
        lblSelectedTask.setFont(lblSelectedTask.getFont().deriveFont(Font.BOLD));
        lblSelectedTask.setBorder(new EmptyBorder(4, 2, 4, 0));

        JButton btnClearLog = new JButton("Clear Log");
        btnClearLog.addActionListener(e -> {
            logArea.setText("");
            String id = getSelectedTaskId();
            if (id != null) taskLogs.remove(id);
        });

        JButton btnExportLog    = new JButton("Export");
        JButton btnViewArchives = new JButton("Archives");
        btnExportLog.addActionListener(e    -> exportLogsForSelected());
        btnViewArchives.addActionListener(e -> viewLogArchives());

        JPanel logButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        logButtonPanel.add(btnViewArchives);
        logButtonPanel.add(btnExportLog);
        logButtonPanel.add(btnClearLog);

        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.add(lblSelectedTask, BorderLayout.WEST);
        logHeader.add(logButtonPanel,  BorderLayout.EAST);

        // ── Watcher fingerprint bar ───────────────────────────────────────────
        // Thin amber-tinted strip shown below the log header whenever the selected
        // task is an INBOUND FILE_TRANSFER with the watcher enabled.
        // Surfaces three states: no baseline, baseline with size, legacy (no size).
        // Colour coding mirrors TaskDialog's refreshWatcherStatusLabel:
        //   grey  = no baseline  |  green = healthy  |  amber = legacy/needs reset
        lblWatcherFingerprint = new JLabel();
        lblWatcherFingerprint.setFont(lblWatcherFingerprint.getFont().deriveFont(Font.PLAIN, 11f));

        watcherBar = new JPanel(new BorderLayout());
        watcherBar.setBackground(new Color(0xFFFDE7));
        watcherBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(0xFFCC02)),
            new EmptyBorder(3, 6, 3, 6)));
        watcherBar.add(lblWatcherFingerprint, BorderLayout.WEST);
        watcherBar.setVisible(false);

        // Small "Reset Baseline" link inside the bar so ops can reset without
        // opening the Edit dialog when they spot an amber / stale baseline.
        JButton btnBarReset = new JButton("Reset Baseline");
        btnBarReset.setFont(btnBarReset.getFont().deriveFont(Font.PLAIN, 11f));
        btnBarReset.setForeground(new Color(0xE65100));
        btnBarReset.setBorderPainted(false);
        btnBarReset.setContentAreaFilled(false);
        btnBarReset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnBarReset.setToolTipText(
            "Clears the stored epoch and size so the next watcher run treats everything as new.");
        btnBarReset.addActionListener(e -> resetBaselineForSelected());
        watcherBar.add(btnBarReset, BorderLayout.EAST);

        // Stack: logHeader → watcherBar → logScroll
        JPanel logPanel = new JPanel(new BorderLayout(4, 0));
        logPanel.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel logTop = new JPanel(new BorderLayout(0, 2));
        logTop.add(logHeader, BorderLayout.NORTH);
        logTop.add(watcherBar, BorderLayout.SOUTH);

        logPanel.add(logTop,      BorderLayout.NORTH);
        logPanel.add(logScroll,   BorderLayout.CENTER);

        logScroll.setPreferredSize(new Dimension(900, 200));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, logPanel);
        split.setResizeWeight(0.45);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        refresh();
    }

    // ── Watcher fingerprint bar ────────────────────────────────────────────────

    /**
     * Refreshes the watcher fingerprint bar for the currently selected task.
     *
     * Visible only when:
     *   taskType == FILE_TRANSFER AND direction == INBOUND AND watcherEnabled.
     *
     * Three states (matching TaskDialog and TaskManagerPanel original implementation):
     *
     *   epoch == 0 (no baseline):
     *     Grey — "Watcher: no baseline stored — first run will always transfer."
     *
     *   epoch > 0, size >= 0 (healthy):
     *     Green — "Watcher baseline: last seen YYYY-MM-DD HH:mm:ss | size: N bytes"
     *
     *   epoch > 0, size == -1 (legacy — size not tracked):
     *     Amber — prompts the operator to open Edit → Reset Baseline.
     *
     * This is reloaded from storage on every call so it reflects the latest epoch
     * persisted by TransferService after each successful watcher run.
     */
    private void updateWatcherFingerprintBar() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= taskIds.size()) {
            watcherBar.setVisible(false);
            return;
        }

        String taskId = taskIds.get(row);
        ScheduledTask task = storage.loadTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElse(null);

        if (task == null
                || task.getTaskType() != TaskType.FILE_TRANSFER
                || !task.isWatcherEnabled()) {  // removed direction check — show for both directions
            watcherBar.setVisible(false);
            return;
        }

        long epoch = task.getLastKnownRemoteFileEpoch();
        long size  = task.getLastKnownRemoteFileSize();

        String html;
        if (epoch <= 0) {
            html = "<html><b style='color:#757575'>Watcher:</b> "
                    + "<i style='color:#757575'>no baseline stored - first run will always transfer.</i></html>";
        } else {
            String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(epoch));
            if (size < 0) {
                html = String.format(
                        "<html><b style='color:#E65100'>Watcher baseline:</b> "
                                + "<i style='color:#E65100'>last seen %s &nbsp;|&nbsp; size: not tracked "
                                + "(reset baseline to fix)</i></html>", dateStr);
            } else {
                html = String.format(
                        "<html><b style='color:#2E7D32'>Watcher baseline:</b> "
                                + "<i style='color:#2E7D32'>last seen %s &nbsp;|&nbsp; size: %,d bytes</i></html>",
                        dateStr, size);
            }
        }

        lblWatcherFingerprint.setText(html);
        watcherBar.setVisible(true);
        watcherBar.revalidate();
        watcherBar.repaint();
    }

    /**
     * Quick baseline reset directly from the fingerprint bar's "Reset Baseline" button.
     * Clears epoch and size on the task, persists it, then refreshes the bar.
     * Avoids making the operator open the Edit dialog just to clear a stale baseline.
     */
    private void resetBaselineForSelected() {
        String id = getSelectedTaskId();
        if (id == null) return;

        storage.loadTasks().stream()
            .filter(t -> t.getId().equals(id))
            .findFirst()
            .ifPresent(task -> {
                int ok = JOptionPane.showConfirmDialog(this,
                    "Reset watcher baseline for \"" + task.getName() + "\"?\n\n"
                    + "The next watcher run will treat all files in the source directory as new "
                    + "and transfer everything it finds.",
                    "Reset Watcher Baseline", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
                if (ok != JOptionPane.YES_OPTION) return;

                task.setLastKnownRemoteFileEpoch(0L);
                task.setLastKnownRemoteFileSize(-1L);
                storage.saveTask(task);
                updateWatcherFingerprintBar();
                JOptionPane.showMessageDialog(this,
                    "Baseline cleared. The next watcher run will start fresh.",
                    "Baseline Reset", JOptionPane.INFORMATION_MESSAGE);
            });
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private JPanel buildLegend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        p.setOpaque(false);
        p.add(legendChip(COLOR_RUNNING,  "Running"));
        p.add(legendChip(COLOR_SUCCESS,  "Success"));
        p.add(legendChip(COLOR_SKIPPED,  "Skipped (no new file)"));
        p.add(legendChip(COLOR_FAILED,   "Failed"));
        p.add(legendChip(COLOR_DISABLED, "Disabled"));
        return p;
    }

    private JPanel legendChip(Color color, String label) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);
        JLabel swatch = new JLabel("  ");
        swatch.setOpaque(true);
        swatch.setBackground(color);
        swatch.setBorder(BorderFactory.createLineBorder(new Color(0xBBBBBB)));
        JLabel text = new JLabel(label);
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
        chip.add(swatch);
        chip.add(text);
        return chip;
    }

    // ── Row colour logic ──────────────────────────────────────────────────────

    /**
     * Returns the background colour for a table row.
     */
    private Color rowColor(String status, String lastResult, String mode) {
        if (status == null) return Color.WHITE;
        switch (status) {
            case "RUNNING":  return COLOR_RUNNING;
            case "FAILED":   return COLOR_FAILED;
            case "DISABLED": return COLOR_DISABLED;
            case "SUCCESS":
                if (lastResult != null && lastResult.contains("SKIPPED")) return COLOR_SKIPPED;
                return COLOR_SUCCESS;
            default:
                if (lastResult != null && lastResult.contains("SKIPPED")) return COLOR_SKIPPED;
                return Color.WHITE;
        }
    }

    // ── Refresh / table population ────────────────────────────────────────────

    public void refresh() {
        int selectedRow = table.getSelectedRow();
        String selectedId = (selectedRow >= 0 && selectedRow < taskIds.size())
            ? taskIds.get(selectedRow) : null;

        taskIds.clear();
        tableModel.setRowCount(0);

        List<ScheduledTask> tasks = storage.loadTasks();

        for (ScheduledTask t : tasks) {
            String schedDesc  = buildScheduleDescription(t);
            String lastResult = t.getLastRunResult() != null ? t.getLastRunResult() : "";

            String displayResult = lastResult;
            if ("SKIPPED".equals(lastResult)) displayResult = "SKIPPED";

            String directionCell = t.getTaskType() == TaskType.FILE_TRANSFER
                ? t.getTransferDirection().name() : "";

            String modeCell = t.getTaskType() == TaskType.FILE_TRANSFER
                ? (t.getTransferMode() != null ? t.getTransferMode().name() : "")
                : "";

            tableModel.addRow(new Object[]{
                t.getName(),
                t.getTaskType().name().replace("_", " "),
                directionCell,
                modeCell,
                t.getStatus().name(),
                schedDesc,
                t.getLastRunAt() != null ? t.getLastRunAt().format(DT) : "Never",
                calculateNextRun(t),
                displayResult
            });
            taskIds.add(t.getId());
        }

        // Re-select previously selected row
        if (selectedId != null) {
            for (int i = 0; i < tasks.size(); i++) {
                if (selectedId.equals(tasks.get(i).getId())) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }

        updateWatcherFingerprintBar();
    }

    // ── Schedule description / next-run calculation ───────────────────────────

    private String buildScheduleDescription(ScheduledTask t) {
        switch (t.getScheduleType()) {
            case RUN_NOW:          return "Run Now";
            case ONCE:             return "Once @ " + (t.getScheduledAt() != null ? t.getScheduledAt().format(DT) : "?");
            case DAILY:            return "Daily @ " + (t.getCronExpression() != null ? t.getCronExpression() : "?");
            case WEEKLY:           return "Weekly " + (t.getCronExpression() != null ? t.getCronExpression() : "?");
            case INTERVAL_MINUTES: return "Every " + t.getIntervalMinutes() + " min";
            case INTERVAL_SECONDS: return "Every " + t.getIntervalSeconds() + " sec";
            default:               return "?";
        }
    }

    private String calculateNextRun(ScheduledTask t) {
        if (t.getStatus() == TaskStatus.DISABLED) return "Disabled";
        LocalDateTime now = LocalDateTime.now();
        switch (t.getScheduleType()) {
            case RUN_NOW:
                return "On demand";
            case ONCE:
                if (t.getScheduledAt() == null) return "Invalid";
                if (t.getScheduledAt().isBefore(now)) return "Overdue";
                return t.getScheduledAt().format(DT);
            case DAILY:
                if (t.getCronExpression() == null) return "Invalid";
                try {
                    LocalTime target = LocalTime.parse(t.getCronExpression(),
                        DateTimeFormatter.ofPattern("HH:mm"));
                    LocalDateTime next = now.withHour(target.getHour())
                        .withMinute(target.getMinute()).withSecond(0);
                    if (!next.isAfter(now)) next = next.plusDays(1);
                    return next.format(DT);
                } catch (Exception e) { return "Invalid"; }
            case WEEKLY:
                if (t.getCronExpression() == null) return "Invalid";
                try {
                    String[] parts = t.getCronExpression().split(" ");
                    if (parts.length < 2) return "Invalid";
                    LocalTime target = LocalTime.parse(parts[1],
                        DateTimeFormatter.ofPattern("HH:mm"));
                    DayOfWeek targetDay = null;
                    for (DayOfWeek dw : DayOfWeek.values()) {
                        if (dw.name().startsWith(parts[0].toUpperCase()
                                .substring(0, Math.min(3, parts[0].length())))) {
                            targetDay = dw;
                            break;
                        }
                    }
                    if (targetDay == null) return "Invalid";
                    LocalDateTime next = now.with(TemporalAdjusters.next(targetDay))
                        .withHour(target.getHour()).withMinute(target.getMinute()).withSecond(0);
                    if (now.getDayOfWeek() == targetDay) {
                        LocalDateTime today = now.withHour(target.getHour())
                            .withMinute(target.getMinute()).withSecond(0);
                        if (today.isAfter(now)) next = today;
                    }
                    return next.format(DT);
                } catch (Exception e) { return "Invalid"; }
            case INTERVAL_MINUTES:
                if (t.getIntervalMinutes() <= 0) return "Invalid";
                return (t.getLastRunAt() != null
                    ? t.getLastRunAt().plusMinutes(t.getIntervalMinutes()) : now).format(DT);
            case INTERVAL_SECONDS:
                if (t.getIntervalSeconds() <= 0) return "Invalid";
                return (t.getLastRunAt() != null
                    ? t.getLastRunAt().plusSeconds(t.getIntervalSeconds()) : now).format(DT);
            default: return "Unknown";
        }
    }

    // ── Task actions ──────────────────────────────────────────────────────────

    private void newTask() {
        try {
            TaskDialog dlg = new TaskDialog(
                (Frame) SwingUtilities.getWindowAncestor(this), storage, null);
            dlg.setVisible(true);
            if (dlg.getResult() != null) refresh();
        } catch (Throwable ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to open New Task dialog:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private void editTask() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to edit."); return; }
        String id = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task to edit."); return; }
        storage.loadTasks().stream().filter(t -> t.getId().equals(id)).findFirst()
            .ifPresentOrElse(t -> {
                try {
                    TaskDialog dlg = new TaskDialog(
                        (Frame) SwingUtilities.getWindowAncestor(this), storage, t);
                    dlg.setVisible(true);
                    if (dlg.getResult() != null) refresh();
                } catch (Throwable ex) {
                    JOptionPane.showMessageDialog(this,
                        "Failed to open Edit Task dialog:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                    ex.printStackTrace();
                }
            }, () -> JOptionPane.showMessageDialog(this,
                "Selected task could not be found.", "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void deleteTask() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to delete."); return; }
        String name = (String) tableModel.getValueAt(row, 0);
        String id   = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task to delete."); return; }

        int ok = JOptionPane.showConfirmDialog(this,
            "Delete task \"" + name + "\"?",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            storage.deleteTask(id);
            try { scheduler.cancelTask(id); } catch (Exception ignored) {}
            try { scheduler.refresh();      } catch (Exception ignored) {}
            taskLogs.remove(id);
            refresh();
        }
    }

    private void runNow() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to run."); return; }
        String id   = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task to run."); return; }
        String name = (String) tableModel.getValueAt(row, 0);

        int ok = JOptionPane.showConfirmDialog(this,
            "Run task \"" + name + "\" immediately?",
            "Confirm Run Now", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            scheduler.runNow(id);
            JOptionPane.showMessageDialog(this,
                "Task \"" + name + "\" queued for immediate execution.");
            refresh();
        }
    }

    private void toggleEnable() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task."); return; }
        String id = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task."); return; }

        storage.loadTasks().stream().filter(t -> t.getId().equals(id)).findFirst().ifPresent(t -> {
            t.setStatus(t.getStatus() == TaskStatus.DISABLED
                ? TaskStatus.PENDING : TaskStatus.DISABLED);
            storage.saveTask(t);
            try { if (t.getStatus() == TaskStatus.DISABLED) scheduler.cancelTask(t.getId()); }
            catch (Exception ignored) {}
            try { scheduler.refresh(); } catch (Exception ignored) {}
            refresh();
        });
    }

    private void restartTask() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to restart."); return; }
        String id = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task to restart."); return; }
        String name = (String) tableModel.getValueAt(row, 0);

        ScheduledTask task = storage.loadTasks().stream()
            .filter(t -> t.getId().equals(id)).findFirst().orElse(null);
        if (task == null) { JOptionPane.showMessageDialog(this, "Selected task could not be found."); return; }

        int ok = JOptionPane.showConfirmDialog(this,
            "Restart task \"" + name + "\" now?",
            "Confirm Restart", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        task.setStatus(TaskStatus.PENDING);
        storage.saveTask(task);
        try { scheduler.cancelTask(task.getId()); scheduler.refresh(); } catch (Exception ignored) {}
        scheduler.runNow(task.getId());
        JOptionPane.showMessageDialog(this, "Task \"" + name + "\" has been restarted.");
        refresh();
    }

    // ── Log display ───────────────────────────────────────────────────────────

    private void showLogForSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            lblSelectedTask.setText("Select a task to view its execution log");
            logArea.setText("");
            return;
        }
        String name = (String) tableModel.getValueAt(row, 0);
        String id   = getSelectedTaskId();
        if (id == null) {
            lblSelectedTask.setText("Select a task to view its execution log");
            logArea.setText("");
            return;
        }

        String lastResult = (String) tableModel.getValueAt(row, 8);
        if (lastResult != null && lastResult.contains("SKIPPED")) {
            lblSelectedTask.setText("Execution log: " + name
                + "  ⏭ Last run was skipped (no new file detected)");
            lblSelectedTask.setForeground(new Color(0xE65100));
        } else {
            lblSelectedTask.setText("Execution log: " + name);
            lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));
        }

        List<String> logs = scheduler.getLogService().getTaskLogs(id);
        if (logs.isEmpty()) {
            StringBuilder sb = taskLogs.getOrDefault(id, new StringBuilder());
            logArea.setText(sb.length() > 0
                ? sb.toString()
                : "No log entries yet. Run the task to see output here.");
        } else {
            logArea.setText(String.join("\n", logs));
        }
        logArea.setCaretPosition(
            Math.min(logArea.getText().length(), logArea.getDocument().getLength()));
    }

    private void showLatestLogsForSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            lblSelectedTask.setText("Select a task to view logs");
            logArea.setText(""); return;
        }
        String name = (String) tableModel.getValueAt(row, 0);
        String id   = getSelectedTaskId();
        if (id == null) {
            lblSelectedTask.setText("Select a task to view logs");
            logArea.setText(""); return;
        }
        lblSelectedTask.setText("Latest 50 lines - Execution log: " + name);
        lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));

        List<String> logs = scheduler.getLogService().getTaskLogsLastN(id, 50);
        if (logs.isEmpty()) {
            StringBuilder sb = taskLogs.getOrDefault(id, new StringBuilder());
            logArea.setText(sb.length() > 0 ? sb.toString()
                : "No log entries yet. Run the task to see output here.");
        } else {
            logArea.setText(String.join("\n", logs));
        }
        logArea.setCaretPosition(
            Math.min(logArea.getText().length(), logArea.getDocument().getLength()));
    }

    private void appendLog(String taskId, String line) {
        taskLogs.computeIfAbsent(taskId, k -> new StringBuilder()).append(line).append("\n");

        int row = table.getSelectedRow();
        if (row >= 0 && taskId.equals(getSelectedTaskId())) {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getText().length());
        }

        // Only refresh table on terminal lines — not every log line
        // This prevents UI thrash that breaks the Refresh button and next-run calculation
        if (line.contains("=== Task") && line.contains("finished")) {
            Timer timer = new Timer(600, e -> {
                refresh();
                updateWatcherFingerprintBar();
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    // ── Export / archives ─────────────────────────────────────────────────────

    private void exportLogsForSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }
        String name = (String) tableModel.getValueAt(row, 0);
        String id   = getSelectedTaskId();
        if (id == null) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(name + "_logs.txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = fc.getSelectedFile();
            try (java.io.FileWriter fw = new java.io.FileWriter(selected)) {
                for (String logLine : scheduler.getLogService().getTaskLogs(id))
                    fw.write(logLine + "\n");
                JOptionPane.showMessageDialog(this,
                    "Logs exported to:\n" + selected.getAbsolutePath(),
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to export logs: " + e.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void viewLogArchives() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }
        String name = (String) tableModel.getValueAt(row, 0);
        String id   = getSelectedTaskId();
        if (id == null) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }

        List<java.io.File> archives = scheduler.getLogService().getTaskLogArchives(id);
        if (archives.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No archived logs found for this task.",
                "No Archives", JOptionPane.INFORMATION_MESSAGE); return;
        }

        String[] archiveNames = archives.stream().map(java.io.File::getName).toArray(String[]::new);
        String selected = (String) JOptionPane.showInputDialog(this,
            "Select an archived log file to view:", "View Log Archives",
            JOptionPane.QUESTION_MESSAGE, null, archiveNames,
            archiveNames.length > 0 ? archiveNames[0] : null);

        if (selected != null) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(
                    archives.stream().filter(f -> f.getName().equals(selected))
                        .findFirst().orElseThrow().toPath());
                logArea.setText(String.join("\n", lines));
                lblSelectedTask.setText("Archive: " + selected + " - " + name);
                lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to read archive: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getSelectedTaskId() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= taskIds.size()) return null;
        return taskIds.get(row);
    }

    private void styleBtn(JButton b, Color bg) {
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBorderPainted(false);
    }
}