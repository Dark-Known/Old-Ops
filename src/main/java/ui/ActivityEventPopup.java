package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * Small, lightweight popup (a borderless {@link JWindow}, same "click
 * outside to dismiss" pattern {@link WatcherInfoPopup} and
 * {@link NotificationBell}'s dropdown use) that shows a short summary of a
 * single event from the Event Monitor's "Recent Activity" table — shown from
 * {@link QueueMonitorView} when the operator clicks directly on an activity
 * row, via {@link EventMonitorPanel}.
 *
 * <p>Deliberately small and read-only: this is a quick "what actually
 * happened here" glance right where you clicked, not the full run-log dialog
 * the Logs panel opens on double-click (see {@code RunHistoryPanel#showDetails}).
 */
final class ActivityEventPopup {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ActivityEventPopup() {}

    static void show(Component invoker, Point screenLocation, QueueMonitorView.ActivityRow row) {
        JWindow popup = new JWindow(SwingUtilities.getWindowAncestor(invoker));
        popup.setType(Window.Type.POPUP);
        popup.setAlwaysOnTop(true);
        popup.setFocusableWindowState(true);

        boolean errored = row.errored();
        Color accent = errored ? new Color(0xC0392B) : new Color(0x2E7D32);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x757575), 1),
                new EmptyBorder(10, 12, 10, 12)));
        content.setPreferredSize(new Dimension(340, 190));

        JLabel title = new JLabel(row.taskName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JLabel statusBadge = new JLabel(errored ? " \u26A0 Failed " : " \u2713 Succeeded ");
        statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 11f));
        statusBadge.setOpaque(true);
        statusBadge.setBackground(accent);
        statusBadge.setForeground(Color.WHITE);
        statusBadge.setBorder(new EmptyBorder(2, 6, 2, 6));

        Duration d = (row.startedAt() != null && row.finishedAt() != null)
                ? Duration.between(row.startedAt(), row.finishedAt()) : Duration.ZERO;

        StringBuilder sb = new StringBuilder();
        if (row.attempt() > 0) sb.append("Retry attempt: ").append(row.attempt()).append('\n');
        sb.append("Started:  ").append(row.startedAt() != null ? row.startedAt().format(TIME_FMT) : "—").append('\n');
        sb.append("Finished: ").append(row.finishedAt() != null ? row.finishedAt().format(TIME_FMT) : "—").append('\n');
        sb.append("Duration: ").append(QueueMonitorView.formatDuration(d)).append('\n');
        if (errored) {
            sb.append('\n').append("Error:\n").append(row.errorMessage() != null ? row.errorMessage() : "(no error message captured)");
        }

        JTextArea detailArea = new JTextArea(sb.toString());
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(detailArea.getFont().deriveFont(Font.PLAIN, 11f));
        detailArea.setBackground(new Color(0xF5F5F5));
        detailArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> popup.dispose());

        JPanel header = new JPanel(new BorderLayout(4, 2));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(statusBadge);
        header.add(badgeRow, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footer.setOpaque(false);
        footer.add(closeBtn);

        content.add(header, BorderLayout.NORTH);
        content.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);

        popup.getContentPane().add(content);
        popup.pack();

        // Keep it on-screen even if the click was near a screen edge.
        Point loc = clampToScreen(screenLocation, popup.getSize(), invoker);
        popup.setLocation(loc);

        popup.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { popup.dispose(); }
        });
        content.registerKeyboardAction(e -> popup.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        popup.setVisible(true);
        popup.requestFocus();
    }

    private static Point clampToScreen(Point desired, Dimension size, Component invoker) {
        GraphicsConfiguration gc = invoker.getGraphicsConfiguration();
        Rectangle bounds = gc != null ? gc.getBounds() : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        int x = Math.min(desired.x, bounds.x + bounds.width - size.width);
        int y = Math.min(desired.y, bounds.y + bounds.height - size.height);
        return new Point(Math.max(bounds.x, x), Math.max(bounds.y, y));
    }
}
