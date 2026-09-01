package ui;

import service.TaskSchedulerService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A standalone window dedicated to live-monitoring the event-driven
 * scheduler's queue and worker pool (see {@link EventMonitorPanel}).
 *
 * Deliberately a separate top-level window rather than another sidebar tab
 * in {@link MainWindow}: it's meant to be left open on the side — on a
 * second monitor, or just floating over the main app — while you work in
 * the Task Manager, so you can watch events actually fire in something
 * close to real time instead of switching tabs to check.
 *
 * Styled to match the main application shell: the same gradient header
 * ({@link GradientPanel} + {@link AppTheme} accent colors) and status-chip
 * pattern used in {@link MainWindow}'s header, so this reads as part of the
 * same app rather than a bolted-on debug tool.
 *
 * Non-modal and reusable: {@link #open} keeps at most one instance alive
 * per app and just brings it to front on repeat calls, so triggering it
 * again from a button doesn't spawn duplicate windows.
 */
public class EventMonitorWindow extends JFrame {

    private static EventMonitorWindow openInstance;

    private final TaskSchedulerService scheduler;
    private final EventMonitorPanel panel;
    private JLabel workerChip;
    private Timer chipTimer;

    private EventMonitorWindow(TaskSchedulerService scheduler) {
        super("Event Monitor — Task Scheduler");
        this.scheduler = scheduler;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(900, 620);
        setMinimumSize(new Dimension(620, 420));
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildHeader(), BorderLayout.NORTH);

        panel = new EventMonitorPanel(scheduler);
        root.add(panel, BorderLayout.CENTER);
        setContentPane(root);

        chipTimer = new Timer(1000, e -> refreshChip());
        refreshChip();
        chipTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.stopRefreshing();
                chipTimer.stop();
                if (openInstance == EventMonitorWindow.this) {
                    openInstance = null;
                }
            }
        });
    }

    private JComponent buildHeader() {
        GradientPanel header = new GradientPanel(new BorderLayout(), AppTheme.ACCENT_DARK, AppTheme.ACCENT_SECONDARY);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel title = new JLabel("Event Monitor", VectorIcons.pulse(Color.WHITE, 20), SwingConstants.LEFT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        title.setForeground(Color.WHITE);
        title.setIconTextGap(10);

        JLabel subTitle = new JLabel("Live view of the scheduler's event queue and worker pool");
        subTitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subTitle.setForeground(new Color(0xE3E1FB));

        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleStack.add(title);
        titleStack.add(Box.createVerticalStrut(3));
        titleStack.add(subTitle);

        workerChip = statusChip("Workers: —", new Color(0x9CB380));
        JPanel badges = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        badges.setOpaque(false);
        badges.add(workerChip);

        header.add(titleStack, BorderLayout.WEST);
        header.add(badges, BorderLayout.EAST);
        return header;
    }

    private void refreshChip() {
        if (scheduler == null || workerChip == null) return;
        int size = scheduler.getWorkerPoolSize();
        int active = scheduler.getActiveWorkerCount();
        Color dot = active == 0 ? new Color(0x9CB380) : new Color(0xE0A458);
        restyleChip(workerChip, "Workers: " + active + " / " + size + " busy", dot);
    }

    // ── Chip helpers — mirrors MainWindow's header status-pill pattern so
    // this window's chrome matches the rest of the app. ─────────────────

    private JLabel statusChip(String text, Color dotColor) {
        JLabel chip = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 30));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setForeground(Color.WHITE);
        chip.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        chip.setBorder(new EmptyBorder(5, 12, 5, 12));
        restyleChip(chip, text, dotColor);
        return chip;
    }

    private void restyleChip(JLabel chip, String text, Color dotColor) {
        String hex = String.format("#%02X%02X%02X", dotColor.getRed(), dotColor.getGreen(), dotColor.getBlue());
        chip.setText("<html><span style='color:" + hex + "'>\u25CF</span>&nbsp;&nbsp;" + text + "</html>");
    }

    /**
     * Opens the Event Monitor window, or brings the already-open one to
     * front if one exists. Safe to call repeatedly (e.g. from a toolbar
     * button) without accumulating duplicate windows.
     */
    public static void open(TaskSchedulerService scheduler, Component relativeTo) {
        if (openInstance != null) {
            openInstance.setState(Frame.NORMAL);
            openInstance.toFront();
            openInstance.requestFocus();
            return;
        }
        openInstance = new EventMonitorWindow(scheduler);
        openInstance.setLocationRelativeTo(relativeTo);
        openInstance.setVisible(true);
    }
}
