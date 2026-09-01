package ui;

import model.TaskRunRecord;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Statistics view for the Event Monitor — sourced from
 * {@link service.RunHistoryService}'s shared SQLite run log, which both the
 * GUI and the headless Daemon write to. Unlike the live queue tabs (which
 * only ever see one process each), this data is process-agnostic by
 * construction, so it's the most reliable place for aggregate numbers.
 */
public class StatisticsPanel extends JPanel {

    private static final Color SUCCESS = new Color(0x5C7A45);
    private static final Color FAILED  = new Color(0x9C4A32);
    private static final Color SKIPPED = new Color(0xAD7C33);
    private static final int BUCKET_COUNT = 24;
    private static final int BUCKET_MINUTES = 5; // 24 x 5min = last 2 hours

    private JLabel valTotal, valSuccessRate, valAvgDuration, valFailures;
    private ActivityChart chart;

    public StatisticsPanel() {
        setLayout(new BorderLayout(10, 10));
        add(buildCards(), BorderLayout.NORTH);

        JPanel chartWrap = new JPanel(new BorderLayout(4, 4));
        JLabel chartTitle = new JLabel("Runs over the last 2 hours (5-minute buckets)");
        chartTitle.setFont(chartTitle.getFont().deriveFont(Font.BOLD, 12f));
        chartWrap.add(chartTitle, BorderLayout.NORTH);
        chart = new ActivityChart();
        chart.setPreferredSize(new Dimension(100, 160));
        chartWrap.add(chart, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 2));
        legend.add(legendChip("Success", SUCCESS));
        legend.add(legendChip("Failed", FAILED));
        legend.add(legendChip("Skipped", SKIPPED));
        chartWrap.add(legend, BorderLayout.SOUTH);

        add(chartWrap, BorderLayout.CENTER);
    }

    private JComponent buildCards() {
        JPanel row = new JPanel(new GridLayout(1, 4, 10, 0));
        valTotal = new JLabel("—");
        valSuccessRate = new JLabel("—");
        valAvgDuration = new JLabel("—");
        valFailures = new JLabel("—");

        row.add(statCard("Total Runs", valTotal, new Color(0x596F6F)));
        row.add(statCard("Success Rate", valSuccessRate, SUCCESS));
        row.add(statCard("Avg Duration", valAvgDuration, new Color(0x8A7A66)));
        row.add(statCard("Failures", valFailures, FAILED));
        return row;
    }

    private JPanel statCard(String label, JLabel valueLabel, Color accent) {
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

        JPanel border = new JPanel(new BorderLayout());
        border.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(withAlpha(accent, 90), 1, true),
                BorderFactory.createEmptyBorder()));
        border.add(card, BorderLayout.CENTER);
        return border;
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

    /** Recomputes every stat and the chart from a fresh batch of run records (any order). */
    public void update(List<TaskRunRecord> runs) {
        int total = runs.size();
        int success = 0, failed = 0, skipped = 0;
        long durationSum = 0;
        int durationCount = 0;

        for (TaskRunRecord r : runs) {
            switch (r.getStatus()) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
            if (r.getStatus() != TaskRunRecord.Status.SKIPPED && r.getDurationMs() > 0) {
                durationSum += r.getDurationMs();
                durationCount++;
            }
        }

        valTotal.setText(String.valueOf(total));
        valSuccessRate.setText(total == 0 ? "—" : String.format("%.0f%%", 100.0 * success / total));
        valAvgDuration.setText(durationCount == 0 ? "—" : formatMs(durationSum / durationCount));
        valFailures.setText(String.valueOf(failed));

        chart.setData(bucketize(runs));
    }

    private int[][] bucketize(List<TaskRunRecord> runs) {
        int[][] buckets = new int[BUCKET_COUNT][3]; // [success, failed, skipped]
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes((long) BUCKET_COUNT * BUCKET_MINUTES);

        for (TaskRunRecord r : runs) {
            LocalDateTime started = r.getStartedAt();
            if (started == null || started.isBefore(windowStart) || started.isAfter(now)) continue;
            long minutesAgo = ChronoUnit.MINUTES.between(started, now);
            int bucketFromEnd = (int) (minutesAgo / BUCKET_MINUTES);
            int idx = BUCKET_COUNT - 1 - bucketFromEnd;
            if (idx < 0 || idx >= BUCKET_COUNT) continue;
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
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /** Small hand-painted stacked bar chart — no external charting library needed. */
    private static final class ActivityChart extends JComponent {
        private int[][] data = new int[BUCKET_COUNT][3];

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
