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
 * ({@link GradientPanel} + {@link AppTheme} accent colors) used in
 * {@link MainWindow}, so this reads as part of the same app rather than a
 * bolted-on debug tool. All live status (scheduler, workers, queue) lives in
 * the panel's own summary bar rather than a header chip.
 *
 * Non-modal and reusable: {@link #open} keeps at most one instance alive
 * per app and just brings it to front on repeat calls, so triggering it
 * again from a button doesn't spawn duplicate windows.
 */
public class EventMonitorWindow extends JFrame {

    private static EventMonitorWindow openInstance;

    private final EventMonitorPanel panel;

    private EventMonitorWindow(TaskSchedulerService scheduler) {
        super("Event Monitor \u2014 Task Scheduler");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1240, 740);
        setMinimumSize(new Dimension(760, 460));
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildHeader(), BorderLayout.NORTH);

        panel = new EventMonitorPanel(scheduler);
        root.add(panel, BorderLayout.CENTER);
        setContentPane(root);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.stopRefreshing();
                if (openInstance == EventMonitorWindow.this) {
                    openInstance = null;
                }
            }
        });
    }

    private JComponent buildHeader() {
        GradientPanel header = new GradientPanel(new BorderLayout(), AppTheme.ACCENT_DARK, AppTheme.ACCENT_SECONDARY);
        header.setBorder(new EmptyBorder(12, 20, 12, 20));

        JLabel title = new JLabel("Event Monitor", VectorIcons.pulse(Color.WHITE, 20), SwingConstants.LEFT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        title.setForeground(Color.WHITE);
        title.setIconTextGap(10);

        JLabel subTitle = new JLabel("Every run, watcher change and setting change \u2014 live, grouped, and searchable");
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

        JLabel keys = new JLabel("Ctrl+F search   \u00b7   Ctrl+P pause   \u00b7   \u2190 \u2192 collapse / expand");
        keys.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        keys.setForeground(new Color(0xE3E1FB));

        header.add(titleStack, BorderLayout.WEST);
        header.add(keys, BorderLayout.EAST);
        return header;
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
