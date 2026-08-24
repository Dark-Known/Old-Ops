package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

public class NotificationPanel extends JPanel {

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private DefaultTableModel failedTableModel;
    private JTable failedTable;
    private DefaultTableModel skippedTableModel;
    private JTable skippedTable;
    private JTextArea detailsArea;

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler) {
        this.storage = storage;
        this.scheduler = scheduler;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        refresh();
    }

    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 8));

        JLabel title = new JLabel("Notifications & Failure Recovery");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        JLabel description = new JLabel("Monitor failed tasks, view recent failure details, and restart recovery attempts.");
        description.setFont(description.getFont().deriveFont(Font.PLAIN, 12f));

        header.add(title, BorderLayout.NORTH);
        header.add(description, BorderLayout.SOUTH);
        return header;
    }

    private Component buildBody() {
        JPanel body = new JPanel(new BorderLayout(10, 10));

        String[] columns = {"Task Name", "Status", "Last Result", "Retries Left", "Last Started"};
        failedTableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        skippedTableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        failedTable = new JTable(failedTableModel);
        failedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        failedTable.setRowHeight(26);
        failedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (failedTable.getSelectedRow() >= 0) skippedTable.clearSelection();
                updateDetailsForSelection();
            }
        });

        skippedTable = new JTable(skippedTableModel);
        skippedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skippedTable.setRowHeight(26);
        skippedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (skippedTable.getSelectedRow() >= 0) failedTable.clearSelection();
                updateDetailsForSelection();
            }
        });

        JScrollPane failedScroll = new JScrollPane(failedTable);
        failedScroll.setPreferredSize(new Dimension(800, 180));

        JScrollPane skippedScroll = new JScrollPane(skippedTable);
        skippedScroll.setPreferredSize(new Dimension(800, 140));

        // A label-above-table caption instead of a boxed TitledBorder, so these
        // tables keep the app-wide rounded ScrollPane corners (see AppTheme).
        JPanel tablesPanel = new JPanel();
        tablesPanel.setLayout(new BoxLayout(tablesPanel, BoxLayout.Y_AXIS));
        tablesPanel.add(AppTheme.titledSection("Failure / Stale Running Tasks", failedScroll));
        tablesPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        tablesPanel.add(AppTheme.titledSection("Skipped Tasks", skippedScroll));

        detailsArea = new JTextArea();
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsArea.setBorder(BorderFactory.createTitledBorder("Task Details"));

        JPanel lowerPanel = new JPanel(new BorderLayout(8, 8));
        lowerPanel.add(new JScrollPane(detailsArea), BorderLayout.CENTER);
        lowerPanel.add(buildActionsPanel(), BorderLayout.SOUTH);

        body.add(tablesPanel, BorderLayout.CENTER);
        body.add(lowerPanel, BorderLayout.SOUTH);
        return body;
    }

    private Component buildActionsPanel() {
        JButton btnRefresh = new JButton("Refresh");
        JButton btnRestart = new JButton("Restart Selected");
        JButton btnRestartAll = new JButton("Restart All Failed");

        btnRefresh.addActionListener(e -> refresh());
        btnRestart.addActionListener(e -> restartSelected());
        btnRestartAll.addActionListener(e -> restartAllFailed());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(btnRefresh);
        actions.add(btnRestart);
        actions.add(btnRestartAll);
        return actions;
    }

    private void refresh() {
        failedTableModel.setRowCount(0);
        skippedTableModel.setRowCount(0);
        List<ScheduledTask> tasks = storage.loadTasks();
        for (ScheduledTask task : tasks) {
            if (task.getStatus() == ScheduledTask.TaskStatus.FAILED
                    || task.getStatus() == ScheduledTask.TaskStatus.RETRYING
                    || task.getStatus() == ScheduledTask.TaskStatus.RUNNING) {
                failedTableModel.addRow(new Object[] {
                        task.getName(),
                        task.getStatus().name(),
                        task.getLastRunResult() != null ? task.getLastRunResult() : "",
                        task.getRetryCount(),
                        task.getLastStartedAt() != null ? task.getLastStartedAt().toString() : ""
                });
            }
            if ("SKIPPED".equals(task.getLastRunResult())) {
                skippedTableModel.addRow(new Object[] {
                        task.getName(),
                        task.getStatus() != null ? task.getStatus().name() : "PENDING",
                        task.getLastRunResult(),
                        task.getRetryCount(),
                        task.getLastStartedAt() != null ? task.getLastStartedAt().toString() : ""
                });
            }
        }
        detailsArea.setText("Select a task to view detailed information.");
    }

    private void updateDetailsForSelection() {
        int row = failedTable.getSelectedRow();
        JTable source = failedTable;
        if (row < 0) {
            row = skippedTable.getSelectedRow();
            source = skippedTable;
        }
        if (row < 0) {
            detailsArea.setText("Select a task to view detailed information.");
            return;
        }

        String name = (String) source.getValueAt(row, 0);
        ScheduledTask task = storage.loadTasks().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst().orElse(null);
        if (task == null) {
            detailsArea.setText("Task details not found.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task.getName()).append("\n");
        sb.append("Status: ").append(task.getStatus().name()).append("\n");
        sb.append("Retries left: ").append(task.getRetryCount()).append("\n");
        sb.append("Last started: ")
                .append(task.getLastStartedAt() != null ? task.getLastStartedAt().toString() : "Never").append("\n");
        sb.append("Last run: ")
                .append(task.getLastRunAt() != null ? task.getLastRunAt().toString() : "Never").append("\n");
        sb.append("Result: ").append(task.getLastRunResult() != null ? task.getLastRunResult() : "None").append("\n\n");
        sb.append("Use the Restart buttons to requeue failed or stale-running tasks.\n");
        if (task.getStatus() == ScheduledTask.TaskStatus.RUNNING) {
            sb.append("This task appears to be active or stale. Use Restart Selected to recover it.\n");
        }
        detailsArea.setText(sb.toString());
    }

    private void restartSelected() {
        int row = failedTable.getSelectedRow();
        JTable source = failedTable;
        if (row < 0) {
            row = skippedTable.getSelectedRow();
            source = skippedTable;
        }
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a task first.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String name = (String) source.getValueAt(row, 0);
        ScheduledTask task = storage.loadTasks().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst().orElse(null);
        if (task == null) {
            JOptionPane.showMessageDialog(this, "Task data not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        task.setStatus(ScheduledTask.TaskStatus.PENDING);
        storage.saveTask(task);
        try { scheduler.cancelTask(task.getId()); } catch (Exception ignored) {}
        scheduler.runNow(task.getId());
        refresh();
    }

    private void restartAllFailed() {
        List<ScheduledTask> failed = storage.loadTasks();
        int count = 0;
        for (ScheduledTask task : failed) {
            if (task.getStatus() == ScheduledTask.TaskStatus.FAILED
                    || task.getStatus() == ScheduledTask.TaskStatus.RETRYING
                    || task.getStatus() == ScheduledTask.TaskStatus.RUNNING) {
                task.setStatus(ScheduledTask.TaskStatus.PENDING);
                task.setLastStartedAt(null);
                storage.saveTask(task);
                try { scheduler.cancelTask(task.getId()); } catch (Exception ignored) {}
                scheduler.runNow(task.getId());
                count++;
            }
        }
        JOptionPane.showMessageDialog(this,
                count + " failed task(s) restarted.",
                "Restarted", JOptionPane.INFORMATION_MESSAGE);
        refresh();
    }
}
