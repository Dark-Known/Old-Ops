package ui;

import model.ScheduledTask;
import service.queue.TaskWorkerPool;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.List;
import java.util.Map;

/**
 * Small strip showing what every worker thread is doing right now — one row
 * per busy thread, a status dot, and a fixed direction glyph (\u2193 inbound
 * / \u2191 outbound), with idle threads shown dimmed below.
 *
 * <p>Mostly not animated: an earlier version pulsed every dot and slid a
 * small arrow along a track to suggest motion, but a typical file transfer
 * finishes in milliseconds to low seconds — far faster than any animation
 * frame could usefully convey — so most of that motion was visual noise
 * unrelated to real progress. A row only starts pulsing once its task has
 * genuinely been running longer than {@link #LONG_RUNNING_THRESHOLD_MS} —
 * that's the one case where "still going" is itself useful information the
 * static dot can't convey (is it stuck, or just a big transfer?). Everything
 * else renders the current {@link #refresh} snapshot plainly.
 * {@link ui.EventMonitorPanel} already calls that roughly once a second;
 * that's precisely as "live" as this needs to look outside of the
 * long-running pulse, which runs its own lightweight timer (only while at
 * least one row qualifies) so short-lived rows never pay for a repaint loop
 * they don't use.
 */
public class WorkerActivityStrip extends JPanel {

    private static final int ROW_HEIGHT = 20;
    // A transfer running longer than this is the one case worth calling out
    // with motion — anything shorter finishes before an animation frame
    // would even register. 5s comfortably separates "normal" transfers from
    // ones actually worth flagging as long-running.
    private static final long LONG_RUNNING_THRESHOLD_MS = 5_000;
    private static final int PULSE_TICK_MS = 400;

    private List<Row> rows = List.of();
    private int idleCount = 0;
    private String modeLabel = null;

    private final javax.swing.Timer pulseTimer;
    private int pulsePhase = 0;

    private record Row(String taskName, ScheduledTask.TransferDirection direction, java.time.LocalDateTime startedAt) {
        boolean isLongRunning() {
            return startedAt != null
                    && java.time.Duration.between(startedAt, java.time.LocalDateTime.now()).toMillis() > LONG_RUNNING_THRESHOLD_MS;
        }
    }

    public WorkerActivityStrip() {
        setOpaque(false);
        setBorder(new EmptyBorder(4, 8, 4, 8));
        // Only ticks while at least one row is actually long-running (started/stopped
        // in layoutAndRepaint) — short-lived rows never pay for this at all.
        pulseTimer = new javax.swing.Timer(PULSE_TICK_MS, e -> {
            pulsePhase++;
            repaint();
        });
    }

    /** Stops the pulse timer — call before discarding an instance so it doesn't keep ticking. */
    public void dispose() {
        pulseTimer.stop();
    }

    /**
     * @param inFlight     the pool's current per-thread snapshot
     * @param tasksById    live task definitions, for name/direction lookup
     * @param totalWorkers configured pool size (idle rows fill the remainder)
     */
    public void refresh(List<TaskWorkerPool.InFlightTask> inFlight, Map<String, ScheduledTask> tasksById, int totalWorkers) {
        java.util.List<Row> next = new java.util.ArrayList<>();
        for (TaskWorkerPool.InFlightTask t : inFlight) {
            ScheduledTask task = tasksById.get(t.taskId());
            String name = task != null ? task.getName() : "(task)";
            ScheduledTask.TransferDirection dir = task != null ? task.getTransferDirection() : null;
            next.add(new Row(name, dir, t.startedAt()));
        }
        this.rows = next;
        this.idleCount = Math.max(0, totalWorkers - next.size());
        this.modeLabel = null;
        layoutAndRepaint(totalWorkers);
    }

    /**
     * Cross-process fallback for when the Daemon (not this GUI process) is
     * the one actually executing tasks — see this class's doc comment. The
     * GUI's own {@code TaskSchedulerService} sits on standby whenever a
     * Daemon is detected alive (see {@code MainWindow}'s startup check), so
     * its worker pool never starts and {@link #refresh} above would always
     * show every thread idle — not because nothing's happening, but because
     * the GUI process genuinely can't see the Daemon's separate in-memory
     * worker pool. The Daemon exports just an aggregate busy/idle count to
     * the shared status file (no per-task detail crosses process
     * boundaries — see {@code SchedulerStatusExporter}), so that's all this
     * can show: busy count as unlabeled "Daemon task" rows rather than real
     * task names/directions.
     */
    public void refreshFromDaemonCounts(int totalWorkers, int activeWorkers) {
        java.util.List<Row> next = new java.util.ArrayList<>();
        for (int i = 0; i < activeWorkers; i++) {
            // No per-task startedAt crosses the process boundary (see class doc),
            // so these rows can never qualify as long-running — they just render
            // static, same as before.
            next.add(new Row("Daemon task", null, null));
        }
        this.rows = next;
        this.idleCount = Math.max(0, totalWorkers - activeWorkers);
        this.modeLabel = "Daemon is executing these \u2014 task detail isn't available across processes";
        layoutAndRepaint(totalWorkers);
    }

    private void layoutAndRepaint(int totalWorkers) {
        int idleShown = Math.min(idleCount, 2);
        int extraRows = (modeLabel != null ? 1 : 0);
        setPreferredSize(new Dimension(100, ROW_HEIGHT * Math.max(1, rows.size() + idleShown
                + (idleCount > idleShown ? 1 : 0) + extraRows)));
        revalidate();

        boolean anyLongRunning = rows.stream().anyMatch(Row::isLongRunning);
        if (anyLongRunning && !pulseTimer.isRunning()) {
            pulseTimer.start();
        } else if (!anyLongRunning && pulseTimer.isRunning()) {
            pulseTimer.stop();
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(getFont().deriveFont(11.5f));

        Color fg = UIManager.getColor("Label.foreground");
        if (fg == null) fg = Color.DARK_GRAY;

        int y = 2;
        for (Row r : rows) {
            Color dotColor = AppTheme.SUCCESS_FG != null ? AppTheme.SUCCESS_FG : new Color(0x2E7D32);
            boolean longRunning = r.isLongRunning();
            if (longRunning) {
                // Simple sine pulse on alpha — the one case where motion earns its keep:
                // signals "still going" for a transfer that's well past typical duration.
                double phase = (pulsePhase % 10) / 10.0;
                int alpha = 140 + (int) Math.round(115 * Math.abs(Math.sin(phase * Math.PI)));
                g2.setColor(new Color(dotColor.getRed(), dotColor.getGreen(), dotColor.getBlue(), alpha));
            } else {
                g2.setColor(dotColor);
            }
            g2.fill(new Ellipse2D.Double(6, y + 6, 8, 8));

            g2.setColor(fg);
            String glyph = r.direction() == ScheduledTask.TransferDirection.INBOUND ? "\u2193"
                    : r.direction() == ScheduledTask.TransferDirection.OUTBOUND ? "\u2191" : " ";
            g2.drawString(glyph, 22, y + 14);
            String label = clip(r.taskName(), longRunning ? 30 : 40);
            g2.drawString(label, 34, y + 14);
            if (longRunning) {
                long elapsedSec = java.time.Duration.between(r.startedAt(), java.time.LocalDateTime.now()).getSeconds();
                g2.setFont(getFont().deriveFont(Font.ITALIC, 10.5f));
                g2.drawString(elapsedSec + "s", getWidth() - 34, y + 14);
                g2.setFont(getFont().deriveFont(11.5f));
            }

            y += ROW_HEIGHT;
        }

        int idleShown = Math.min(idleCount, 2);
        for (int i = 0; i < idleShown; i++) {
            g2.setColor(new Color(0, 0, 0, 60));
            g2.fill(new Ellipse2D.Double(6, y + 6, 8, 8));
            g2.setColor(new Color(0, 0, 0, 110));
            g2.drawString("idle", 22, y + 14);
            y += ROW_HEIGHT;
        }
        if (idleCount > idleShown) {
            g2.setColor(new Color(0, 0, 0, 110));
            g2.drawString("+" + (idleCount - idleShown) + " more idle", 22, y + 14);
            y += ROW_HEIGHT;
        }

        if (modeLabel != null) {
            g2.setColor(new Color(0, 0, 0, 130));
            g2.setFont(getFont().deriveFont(Font.ITALIC, 10.5f));
            g2.drawString(modeLabel, 6, y + 12);
        }

        g2.dispose();
    }

    private static String clip(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "\u2026";
    }
}
