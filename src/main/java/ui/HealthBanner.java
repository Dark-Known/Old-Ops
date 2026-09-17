package ui;

import model.TaskRunRecord;
import service.TaskSchedulerService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One always-visible line summarizing application health — this is the
 * "simple UI" half of surfacing failures: instead of checking the Logs tab,
 * Statistics tab, Event Monitor, and the daemon badge separately to piece
 * together "is everything OK", there's one line that's either green ("All
 * clear") or tells you exactly how many things need attention and why,
 * with the full grouped detail one click away rather than requiring a tab
 * switch.
 *
 * <p>"Grouped by likely root cause" means: five failures across five tasks
 * that all classified as {@code AUTH} against the same credential username
 * are shown as one entry ("5 failures — likely credential 'svc-backup'"),
 * not five unrelated-looking rows. See {@link model.TaskRunRecord.FailureCategory}
 * and {@link service.FailureClassifier} for where the category/suggested
 * action on each row comes from.
 *
 * <p>Refreshed two ways: a periodic query (covers Daemon-side failures,
 * which reach this only via the shared database) and an immediate push via
 * {@link service.RunHistoryService#addRunListener} for same-process
 * failures, same event-bus pattern used elsewhere in this app.
 */
public class HealthBanner extends JPanel {

    private static final int LOOKBACK_HOURS = 24;
    private static final int QUERY_LIMIT = 500;

    private final TaskSchedulerService scheduler;
    private final JLabel icon = new JLabel();
    private final JLabel text = new JLabel();
    private List<TaskRunRecord> currentFailures = List.of();

    public HealthBanner(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        setLayout(new BorderLayout(8, 0));
        setBorder(new EmptyBorder(6, 14, 6, 14));
        setOpaque(true);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 13f));
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 12.5f));
        left.add(icon);
        left.add(text);
        add(left, BorderLayout.WEST);

        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { showDetail(); }
        });

        refresh();
        javax.swing.Timer poll = new javax.swing.Timer(15_000, e -> refresh());
        poll.start();
        if (scheduler != null && scheduler.getRunHistoryService() != null) {
            scheduler.getRunHistoryService().addRunListener(rec -> {
                if (rec.getStatus() == TaskRunRecord.Status.FAILED) {
                    SwingUtilities.invokeLater(this::refresh);
                }
            });
        }
    }

    private void refresh() {
        if (scheduler == null || scheduler.getRunHistoryService() == null) return;
        LocalDateTime since = LocalDateTime.now().minusHours(LOOKBACK_HOURS);
        List<TaskRunRecord> failures;
        try {
            failures = scheduler.getRunHistoryService()
                    .queryRuns(null, TaskRunRecord.Status.FAILED, since, null, QUERY_LIMIT);
        } catch (Exception e) {
            return; // DB briefly locked by a write — next poll tick will catch up
        }
        this.currentFailures = failures;

        if (failures.isEmpty()) {
            icon.setText("\u25CF");
            icon.setForeground(AppTheme.SUCCESS_FG);
            text.setText("All clear \u2014 no failures in the last " + LOOKBACK_HOURS + "h");
            setBackground(AppTheme.SUCCESS_BG);
        } else {
            Map<String, Integer> groups = groupByLikelyCause(failures);
            String topGroup = groups.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);

            boolean allRetryable = failures.stream().allMatch(TaskRunRecord::isRetryable);
            icon.setText("\u25CF");
            icon.setForeground(allRetryable ? AppTheme.RUNNING_FG : AppTheme.FAILED_FG);
            setBackground(allRetryable ? AppTheme.SKIPPED_BG : AppTheme.FAILED_BG);

            StringBuilder sb = new StringBuilder();
            sb.append(failures.size()).append(" failure").append(failures.size() == 1 ? "" : "s")
                    .append(" in the last ").append(LOOKBACK_HOURS).append("h");
            if (topGroup != null && groups.size() < failures.size()) {
                sb.append(" \u2014 ").append(groups.get(topGroup)).append(" likely ").append(topGroup);
            }
            sb.append("  (click for detail)");
            text.setText(sb.toString());
        }
    }

    /**
     * Groups failures by the most specific shared cause available: same
     * task name + same failure category first (repeated failures of one
     * task), falling back to just failure category (several different
     * tasks failing the same way — e.g. all AUTH, suggesting one shared
     * broken credential even though this app doesn't yet track which
     * credential backs which failure explicitly).
     */
    private static Map<String, Integer> groupByLikelyCause(List<TaskRunRecord> failures) {
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (TaskRunRecord r : failures) {
            String category = r.getFailureCategory() != null ? r.getFailureCategory().name() : "UNKNOWN";
            String key = r.getTaskName() + " (" + category + ")";
            groups.merge(key, 1, Integer::sum);
        }
        return groups;
    }

    private void showDetail() {
        if (currentFailures.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No failures in the last " + LOOKBACK_HOURS + " hours.",
                    "System health", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Map<String, Integer> groups = groupByLikelyCause(currentFailures);
        Map<String, TaskRunRecord> exampleByGroup = new LinkedHashMap<>();
        for (TaskRunRecord r : currentFailures) {
            String category = r.getFailureCategory() != null ? r.getFailureCategory().name() : "UNKNOWN";
            exampleByGroup.putIfAbsent(r.getTaskName() + " (" + category + ")", r);
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            TaskRunRecord example = exampleByGroup.get(e.getKey());
            JPanel row = new JPanel(new BorderLayout(4, 2));
            row.setBorder(new EmptyBorder(6, 4, 6, 4));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(480, 70));

            JLabel title = new JLabel(e.getKey() + "  \u00d7" + e.getValue());
            title.setFont(title.getFont().deriveFont(Font.BOLD, 12.5f));
            row.add(title, BorderLayout.NORTH);

            String action = example != null && example.getSuggestedAction() != null
                    ? example.getSuggestedAction() : "No specific suggestion \u2014 open Logs for the full detail.";
            JLabel actionLabel = new JLabel("<html><div style='width:400px'>" + action + "</div></html>");
            actionLabel.setFont(actionLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
            row.add(actionLabel, BorderLayout.CENTER);

            panel.add(row);
            panel.add(new JSeparator());
        }

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(480, Math.min(400, groups.size() * 80 + 20)));
        scroll.setBorder(null);

        JOptionPane.showMessageDialog(this, scroll,
                currentFailures.size() + " failure(s) in the last " + LOOKBACK_HOURS + "h",
                JOptionPane.PLAIN_MESSAGE);
    }
}
