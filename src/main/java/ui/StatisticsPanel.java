package ui;

import model.TaskRunRecord;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Statistics view for the Event Monitor — sourced from
 * {@link service.RunHistoryService}'s shared SQLite run log, which both the
 * GUI and the headless Daemon write to. Unlike the live queue tabs (which
 * only ever see one process each), this data is process-agnostic by
 * construction, so it's the most reliable place for aggregate numbers.
 *
 * <p>Redesign over the original fixed-"last 2 hours" version: a selectable
 * time range (1h/2h/24h/7d) with trend deltas against the equal-length prior
 * period, duration split out by task type (a single mixed average across a
 * 2-second mail check and a 10-minute backup was never a meaningful number),
 * and a top-failing-tasks list — the original showed *how many* runs failed
 * but never *which* tasks were failing.
 *
 * <p>{@link #update} takes the runs for the selected window AND the
 * equal-length window immediately before it (for the trend deltas) — the
 * caller ({@link EventMonitorPanel}) is responsible for querying both from
 * {@link service.RunHistoryService#queryRuns} using a {@code from} bound
 * matching {@link #getSelectedRangeHours()}, since only it holds a reference
 * to the service.
 */
public class StatisticsPanel extends JPanel {

    private static final Color SUCCESS = new Color(0x5C7A45);
    private static final Color FAILED  = new Color(0x9C4A32);
    private static final Color SKIPPED = new Color(0xAD7C33);
    private static final Color NEUTRAL_ACCENT = new Color(0x596F6F);
    private static final Color DURATION_ACCENT = new Color(0x8A7A66);

    /** Range options shown as chips, in hours. */
    private static final int[] RANGE_HOURS = { 1, 2, 24, 24 * 7 };
    private static final String[] RANGE_LABELS = { "1h", "2h", "24h", "7d" };
    private static final int DEFAULT_RANGE_INDEX = 1; // 2h, matching the old fixed window

    private int selectedRangeHours = RANGE_HOURS[DEFAULT_RANGE_INDEX];
    private IntConsumer rangeChangeListener;

    private JLabel valTotal, valSuccessRate, valMedianDuration, valFailures;
    private JLabel deltaTotal, deltaSuccessRate, deltaFailures, subP95Duration;
    private ActivityChart chart;
    private JLabel chartTitle;
    private JPanel durByTypePanel;
    private JPanel topFailingPanel;
    private final JToggleButton[] rangeButtons = new JToggleButton[RANGE_HOURS.length];

    public StatisticsPanel() {
        setLayout(new BorderLayout(10, 10));

        JPanel header = new JPanel(new BorderLayout());
        header.add(buildRangeChips(), BorderLayout.NORTH);
        header.add(buildCards(), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        JPanel chartWrap = new JPanel(new BorderLayout(4, 4));
        chartTitle = new JLabel();
        chartTitle.setFont(chartTitle.getFont().deriveFont(Font.BOLD, 12f));
        chartWrap.add(chartTitle, BorderLayout.NORTH);
        chart = new ActivityChart();
        chart.setPreferredSize(new Dimension(100, 150));
        chartWrap.add(chart, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 2));
        legend.add(legendChip("Success", SUCCESS));
        legend.add(legendChip("Failed", FAILED));
        legend.add(legendChip("Skipped", SKIPPED));
        chartWrap.add(legend, BorderLayout.SOUTH);

        JPanel breakdownRow = new JPanel(new GridLayout(1, 2, 10, 0));
        durByTypePanel = breakdownCard();
        topFailingPanel = breakdownCard();
        breakdownRow.add(wrapBreakdownCard(durByTypePanel, "Duration by task type"));
        breakdownRow.add(wrapBreakdownCard(topFailingPanel, "Top failing tasks"));

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(chartWrap, BorderLayout.NORTH);
        center.add(breakdownRow, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        updateChartTitle();
    }

    /** Registers a listener fired whenever the person picks a different range chip — the caller re-queries and calls {@link #update} again. */
    public void setRangeChangeListener(IntConsumer listener) {
        this.rangeChangeListener = listener;
    }

    public int getSelectedRangeHours() {
        return selectedRangeHours;
    }

    private JComponent buildRangeChips() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < RANGE_HOURS.length; i++) {
            int hours = RANGE_HOURS[i];
            JToggleButton btn = new JToggleButton(RANGE_LABELS[i]);
            btn.setSelected(i == DEFAULT_RANGE_INDEX);
            btn.setFocusPainted(false);
            btn.setMargin(new Insets(2, 10, 2, 10));
            btn.addActionListener(e -> {
                selectedRangeHours = hours;
                updateChartTitle();
                if (rangeChangeListener != null) rangeChangeListener.accept(hours);
            });
            group.add(btn);
            rangeButtons[i] = btn;
            row.add(btn);
        }
        return row;
    }

    private void updateChartTitle() {
        String rangeLabel = RANGE_LABELS[indexOfRange(selectedRangeHours)];
        int bucketMinutes = bucketMinutesFor(selectedRangeHours);
        String bucketLabel = bucketMinutes < 60 ? bucketMinutes + "-minute"
                : bucketMinutes < 24 * 60 ? (bucketMinutes / 60) + "-hour"
                : (bucketMinutes / (24 * 60)) + "-day";
        chartTitle.setText("Runs over the last " + rangeLabel + " (" + bucketLabel + " buckets)");
    }

    private static int indexOfRange(int hours) {
        for (int i = 0; i < RANGE_HOURS.length; i++) if (RANGE_HOURS[i] == hours) return i;
        return DEFAULT_RANGE_INDEX;
    }

    /** Keeps the visible chart at roughly 12-28 bars regardless of which range is selected. */
    private static int bucketMinutesFor(int rangeHours) {
        if (rangeHours <= 1) return 5;           // 12 buckets
        if (rangeHours <= 2) return 5;           // 24 buckets
        if (rangeHours <= 24) return 60;         // 24 buckets
        return 6 * 60;                            // 7d -> 28 buckets of 6h
    }

    private JComponent buildCards() {
        JPanel row = new JPanel(new GridLayout(1, 4, 10, 0));
        valTotal = new JLabel("—");
        valSuccessRate = new JLabel("—");
        valMedianDuration = new JLabel("—");
        valFailures = new JLabel("—");
        deltaTotal = deltaLabel();
        deltaSuccessRate = deltaLabel();
        subP95Duration = deltaLabel();
        deltaFailures = deltaLabel();

        row.add(statCard("Total Runs", valTotal, deltaTotal, NEUTRAL_ACCENT));
        row.add(statCard("Success Rate", valSuccessRate, deltaSuccessRate, SUCCESS));
        row.add(statCard("Median Duration", valMedianDuration, subP95Duration, DURATION_ACCENT));
        row.add(statCard("Failures", valFailures, deltaFailures, FAILED));
        return row;
    }

    private JLabel deltaLabel() {
        JLabel l = new JLabel(" ");
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JPanel statCard(String label, JLabel valueLabel, JLabel subLabel, Color accent) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(10, 14, 10, 14));
        card.setBackground(UIManager.getColor("Panel.background"));
        card.setOpaque(true);

        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 22f));
        valueLabel.setForeground(accent);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel captionLabel = new JLabel(label);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        captionLabel.setForeground(UIManager.getColor("Label.disabledForeground") != null
                ? UIManager.getColor("Label.disabledForeground") : Color.GRAY);
        captionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(valueLabel);
        card.add(Box.createVerticalStrut(2));
        card.add(captionLabel);
        card.add(Box.createVerticalStrut(2));
        card.add(subLabel);

        JPanel border = new JPanel(new BorderLayout());
        border.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(withAlpha(accent, 90), 1, true),
                BorderFactory.createEmptyBorder()));
        border.add(card, BorderLayout.CENTER);
        return border;
    }

    private JPanel breakdownCard() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private JComponent wrapBreakdownCard(JPanel contentPanel, String titleText) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setBorder(new EmptyBorder(10, 12, 10, 12));
        wrapper.setBackground(UIManager.getColor("Panel.background"));
        wrapper.setOpaque(true);
        JLabel title = new JLabel(titleText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        wrapper.add(title, BorderLayout.NORTH);
        wrapper.add(contentPanel, BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent legendChip(String text, Color color) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel dot = new JLabel("\u25CF");
        dot.setForeground(color);
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(11.5f));
        p.add(dot);
        p.add(lbl);
        return p;
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /**
     * Recomputes every stat, the chart, and both breakdown lists.
     *
     * @param runs      runs within the currently selected range (any order)
     * @param priorRuns runs in the equal-length period immediately before
     *                  {@code runs}' window — used only for the trend deltas
     *                  on the summary cards; pass an empty list to suppress
     *                  deltas (they'll just read blank)
     */
    public void update(List<TaskRunRecord> runs, List<TaskRunRecord> priorRuns) {
        int total = runs.size();
        int success = 0, failed = 0, skipped = 0;
        List<Long> durations = new ArrayList<>();

        for (TaskRunRecord r : runs) {
            switch (r.getStatus()) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
            if (r.getStatus() != TaskRunRecord.Status.SKIPPED && r.getDurationMs() > 0) {
                durations.add(r.getDurationMs());
            }
        }

        valTotal.setText(String.valueOf(total));
        double successRate = total == 0 ? -1 : 100.0 * success / total;
        valSuccessRate.setText(total == 0 ? "—" : String.format("%.0f%%", successRate));
        valMedianDuration.setText(durations.isEmpty() ? "—" : formatMs(percentile(durations, 0.50)));
        subP95Duration.setText(durations.isEmpty() ? " " : "P95: " + formatMs(percentile(durations, 0.95)));
        valFailures.setText(String.valueOf(failed));

        String vsSuffix = " vs prior " + RANGE_LABELS[indexOfRange(selectedRangeHours)];
        applyDelta(deltaTotal, total, priorRuns.size(), false, vsSuffix);

        int priorSuccess = (int) priorRuns.stream().filter(r -> r.getStatus() == TaskRunRecord.Status.SUCCESS).count();
        double priorRate = priorRuns.isEmpty() ? -1 : 100.0 * priorSuccess / priorRuns.size();
        applyDeltaPoints(deltaSuccessRate, successRate, priorRate, vsSuffix);

        int priorFailed = (int) priorRuns.stream().filter(r -> r.getStatus() == TaskRunRecord.Status.FAILED).count();
        applyDelta(deltaFailures, failed, priorFailed, true, vsSuffix);

        chart.setData(bucketize(runs, selectedRangeHours));
        updateDurationByType(runs);
        updateTopFailing(runs);
    }

    /** Green up-arrow is "good" for a count unless {@code higherIsBad} (e.g. failure counts), where it flips to red. */
    private void applyDelta(JLabel label, int current, int prior, boolean higherIsBad, String suffix) {
        if (prior == 0 && current == 0) { label.setText(" "); return; }
        int diff = current - prior;
        String arrow = diff > 0 ? "\u2191" : diff < 0 ? "\u2193" : "\u2192";
        boolean good = diff == 0 || (higherIsBad ? diff < 0 : diff > 0);
        label.setForeground(diff == 0 ? Color.GRAY : (good ? SUCCESS : FAILED));
        label.setText(arrow + " " + Math.abs(diff) + suffix);
    }

    private void applyDeltaPoints(JLabel label, double current, double prior, String suffix) {
        if (current < 0 || prior < 0) { label.setText(" "); return; }
        double diff = current - prior;
        if (Math.abs(diff) < 0.5) { label.setForeground(Color.GRAY); label.setText("\u2192 flat" + suffix); return; }
        String arrow = diff > 0 ? "\u2191" : "\u2193";
        label.setForeground(diff > 0 ? SUCCESS : FAILED);
        label.setText(arrow + " " + Math.round(Math.abs(diff)) + "pt" + suffix);
    }

    private static long percentile(List<Long> values, double p) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /** One row of median/P95 per task type — the original panel had no per-type split at all, just one mixed average across every task. */
    private void updateDurationByType(List<TaskRunRecord> runs) {
        Map<String, List<Long>> byType = new LinkedHashMap<>();
        for (TaskRunRecord r : runs) {
            if (r.getStatus() == TaskRunRecord.Status.SKIPPED || r.getDurationMs() <= 0) continue;
            String type = r.getTaskType() != null ? r.getTaskType() : "UNKNOWN";
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(r.getDurationMs());
        }

        durByTypePanel.removeAll();
        if (byType.isEmpty()) {
            durByTypePanel.add(emptyRow("No completed runs in this window"));
        } else {
            List<String> types = new ArrayList<>(byType.keySet());
            types.sort(Comparator.naturalOrder());
            for (String type : types) {
                List<Long> durs = byType.get(type);
                String text = type + "  —  " + formatMs(percentile(durs, 0.50))
                        + " median, " + formatMs(percentile(durs, 0.95)) + " P95";
                durByTypePanel.add(breakdownRow(text, null));
            }
        }
        durByTypePanel.revalidate();
        durByTypePanel.repaint();
    }

    /** Which tasks are failing, not just how many runs failed overall — the gap in the original panel. */
    private void updateTopFailing(List<TaskRunRecord> runs) {
        Map<String, Integer> failCounts = new LinkedHashMap<>();
        for (TaskRunRecord r : runs) {
            if (r.getStatus() != TaskRunRecord.Status.FAILED) continue;
            String name = r.getTaskName() != null && !r.getTaskName().isBlank() ? r.getTaskName() : "(unknown task)";
            failCounts.merge(name, 1, Integer::sum);
        }

        topFailingPanel.removeAll();
        if (failCounts.isEmpty()) {
            topFailingPanel.add(emptyRow("No failures in this window"));
        } else {
            failCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> topFailingPanel.add(breakdownRow(e.getKey(), e.getValue() + " failed")));
        }
        topFailingPanel.revalidate();
        topFailingPanel.repaint();
    }

    private JComponent emptyRow(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.ITALIC, 11.5f));
        l.setForeground(Color.GRAY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JComponent breakdownRow(String left, String rightBadge) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(new BottomHairlineBorder());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel leftLabel = new JLabel(left);
        leftLabel.setFont(leftLabel.getFont().deriveFont(12.5f));
        row.add(leftLabel, BorderLayout.WEST);

        if (rightBadge != null) {
            JLabel badge = new JLabel(rightBadge);
            badge.setOpaque(true);
            badge.setBackground(new Color(0xFBEAEA));
            badge.setForeground(FAILED);
            badge.setFont(badge.getFont().deriveFont(Font.PLAIN, 11f));
            badge.setBorder(new EmptyBorder(2, 8, 2, 8));
            row.add(badge, BorderLayout.EAST);
        }
        return row;
    }

    /** Thin bottom hairline between breakdown rows without pulling in a full LineBorder per row. */
    private static final class BottomHairlineBorder extends MatteBorder {
        BottomHairlineBorder() { super(0, 0, 1, 0, new Color(0, 0, 0, 20)); }
        @Override public Insets getBorderInsets(Component c) { return new Insets(4, 0, 4, 0); }
    }

    private int[][] bucketize(List<TaskRunRecord> runs, int rangeHours) {
        int bucketMinutes = bucketMinutesFor(rangeHours);
        int bucketCount = Math.max(1, (int) Math.ceil((rangeHours * 60.0) / bucketMinutes));
        int[][] buckets = new int[bucketCount][3]; // [success, failed, skipped]
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes((long) bucketCount * bucketMinutes);

        for (TaskRunRecord r : runs) {
            LocalDateTime started = r.getStartedAt();
            if (started == null || started.isBefore(windowStart) || started.isAfter(now)) continue;
            long minutesAgo = ChronoUnit.MINUTES.between(started, now);
            int bucketFromEnd = (int) (minutesAgo / bucketMinutes);
            int idx = bucketCount - 1 - bucketFromEnd;
            if (idx < 0 || idx >= bucketCount) continue;
            switch (r.getStatus()) {
                case SUCCESS -> buckets[idx][0]++;
                case FAILED -> buckets[idx][1]++;
                case SKIPPED -> buckets[idx][2]++;
            }
        }
        return buckets;
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        Duration d = Duration.ofMillis(ms);
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /** Small hand-painted stacked bar chart — no external charting library needed. */
    private static final class ActivityChart extends JComponent {
        private int[][] data = new int[24][3];

        void setData(int[][] data) {
            this.data = data;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int padBottom = 4, padTop = 6;
            int usableH = h - padBottom - padTop;

            int max = 1;
            for (int[] b : data) max = Math.max(max, b[0] + b[1] + b[2]);

            double barWidth = (double) w / data.length;
            for (int i = 0; i < data.length; i++) {
                int[] b = data[i];
                int total = b[0] + b[1] + b[2];
                double x = i * barWidth;
                double barW = Math.max(1, barWidth - 2);
                double yCursor = h - padBottom;

                if (total == 0) {
                    // Faint baseline tick so an empty window still reads as a timeline, not a blank box.
                    g2.setColor(new Color(0, 0, 0, 18));
                    g2.fill(new RoundRectangle2D.Double(x + 1, h - padBottom - 1, barW, 1, 1, 1));
                    continue;
                }

                int[] counts = { b[0], b[1], b[2] };
                Color[] colors = { SUCCESS, FAILED, SKIPPED };
                for (int s = 0; s < 3; s++) {
                    if (counts[s] == 0) continue;
                    double segH = usableH * ((double) counts[s] / max);
                    g2.setColor(colors[s]);
                    g2.fill(new RoundRectangle2D.Double(x + 1, yCursor - segH, barW, segH, 2, 2));
                    yCursor -= segH;
                }
            }
            g2.dispose();
        }
    }
}
