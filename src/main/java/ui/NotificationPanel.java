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
    private final WatchStatusMonitor watchStatusMonitor; // nullable-safe
    private JTabbedPane tabs;
    private DefaultTableModel failedTableModel;
    private JTable failedTable;
    private DefaultTableModel skippedTableModel;
    private JTable skippedTable;
    private DefaultTableModel watchFallbackTableModel;
    private JTable watchFallbackTable;
    private JTextArea detailsArea;

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler) {
        this(storage, scheduler, null);
    }

    public NotificationPanel(XmlStorageService storage, TaskSchedulerService scheduler, WatchStatusMonitor watchStatusMonitor) {
        this.storage = storage;
        this.scheduler = scheduler;
        this.watchStatusMonitor = watchStatusMonitor;
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

        JLabel description = new JLabel("Failed/skipped tasks and watcher push-to-polling fallbacks, kept on separate tabs.");
        description.setFont(description.getFont().deriveFont(Font.PLAIN, 12f));

        header.add(title, BorderLayout.NORTH);
        header.add(description, BorderLayout.SOUTH);
        return header;
    }

    /** Two independent tabs — "Tasks" (failed/stale/skipped scheduled tasks,
     *  with restart actions) and "Watcher" (push-to-polling fallback history,
     *  informational only) — deliberately kept apart rather than stacked in
     *  one view, since they're different kinds of thing an operator cares
     *  about for different reasons and at different urgency. */
    private Component buildBody() {
        tabs = new JTabbedPane();
        tabs.addTab("Tasks", buildTasksTab());
        tabs.addTab("Watcher", buildWatcherTab());
        return tabs;
    }

    private Component buildTasksTab() {
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
        failedScroll.setPreferredSize(new Dimension(800, 220));
        failedScroll.setBorder(BorderFactory.createTitledBorder("Failure / Stale Running Tasks"));

        JScrollPane skippedScroll = new JScrollPane(skippedTable);
        skippedScroll.setPreferredSize(new Dimension(800, 180));
        skippedScroll.setBorder(BorderFactory.createTitledBorder("Skipped Tasks"));

        JPanel tablesPanel = new JPanel();
        tablesPanel.setLayout(new BoxLayout(tablesPanel, BoxLayout.Y_AXIS));
        tablesPanel.add(failedScroll);
        tablesPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        tablesPanel.add(skippedScroll);

        detailsArea = new JTextArea();
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsArea.setBorder(BorderFactory.createTitledBorder("Task Details"));

        JPanel lowerPanel = new JPanel(new BorderLayout(8, 8));
        lowerPanel.add(new JScrollPane(detailsArea), BorderLayout.CENTER);
        lowerPanel.add(buildTaskActionsPanel(), BorderLayout.SOUTH);

        body.add(tablesPanel, BorderLayout.CENTER);
        body.add(lowerPanel, BorderLayout.SOUTH);
        return body;
    }

    private Component buildWatcherTab() {
        JPanel body = new JPanel(new BorderLayout(10, 10));

        String[] watchColumns = {"Task Name", "Was", "Now", "Detail", "When"};
        watchFallbackTableModel = new DefaultTableModel(watchColumns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        watchFallbackTable = new JTable(watchFallbackTableModel);
        watchFallbackTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        watchFallbackTable.setRowHeight(26);
        // No details-pane wiring for this table — the Detail column already
        // holds the full explanation (e.g. "remote host has no inotifywait"),
        // so there's nothing further to drill into like the Tasks tab has.

        JScrollPane watchFallbackScroll = new JScrollPane(watchFallbackTable);
        watchFallbackScroll.setBorder(BorderFactory.createTitledBorder(
                "Watcher Push \u2192 Polling Fallbacks (live watch/push stopped working, or was confirmed unsupported)"));

        JLabel note = new JLabel(watchStatusMonitor == null
                ? "Watcher fallback monitoring is not available in this context."
                : "Purely informational — restarting a task from the Tasks tab does not affect this history.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        body.add(watchFallbackScroll, BorderLayout.CENTER);
        body.add(note, BorderLayout.NORTH);
        body.add(buildWatcherActionsPanel(), BorderLayout.SOUTH);
        return body;
    }

    private Component buildTaskActionsPanel() {
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

    private Component buildWatcherActionsPanel() {
        JButton btnRefresh = new JButton("Refresh");
        JButton btnClear = new JButton("Clear History");

        btnRefresh.addActionListener(e -> refresh());
        btnClear.addActionListener(e -> {
            if (watchStatusMonitor != null) watchStatusMonitor.clearEvents();
            refresh();
        });
        btnClear.setEnabled(watchStatusMonitor != null);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(btnRefresh);
        actions.add(btnClear);
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

        watchFallbackTableModel.setRowCount(0);
        if (watchStatusMonitor != null) {
            for (WatchStatusMonitor.Event ev : watchStatusMonitor.getRecentEvents()) {
                watchFallbackTableModel.addRow(new Object[] {
                        ev.taskName(),
                        prettyMode(ev.fromMode()),
                        prettyMode(ev.toMode()),
                        ev.detail(),
                        ev.at().toString()
                });
            }
        }

        detailsArea.setText("Select a task to view detailed information.");

        if (tabs != null) {
            int taskCount = failedTableModel.getRowCount() + skippedTableModel.getRowCount();
            tabs.setTitleAt(0, taskCount > 0 ? "Tasks (" + taskCount + ")" : "Tasks");
            int watchCount = watchFallbackTableModel.getRowCount();
            tabs.setTitleAt(1, watchCount > 0 ? "Watcher (" + watchCount + ")" : "Watcher");
        }
    }

    /** Switches to the "Tasks" (0) or "Watcher" (1) tab — used by
     *  {@link NotificationBell} so clicking a specific item in the dropdown
     *  lands on the tab that item actually belongs to, instead of always
     *  opening to whichever tab happens to be first. */
    public void selectTab(int index) {
        if (tabs != null && index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        }
    }

    private static String prettyMode(String rawWatchModeName) {
        return switch (rawWatchModeName) {
            case "NATIVE_WATCH" -> "Live (native watch)";
            case "REMOTE_PUSH" -> "Live (remote push)";
            case "POLLING_ONLY_UNSUPPORTED" -> "Polling only (unsupported)";
            case "POLLING_ONLY" -> "Polling only";
            default -> rawWatchModeName;
        };
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
