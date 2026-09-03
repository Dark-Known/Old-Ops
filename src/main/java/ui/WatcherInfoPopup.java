package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.XmlStorageService;
import service.queue.SchedulerStatusSnapshot;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * Small, lightweight popup (a borderless {@link JWindow}, same "click
 * outside to dismiss" pattern {@link NotificationBell}'s dropdown uses) that
 * shows a single watcher-enabled task's current push/polling status in
 * detail, plus a manual "Reconnect" action — shown from {@link TaskManagerPanel}
 * when the operator clicks directly on a watcher-enabled row.
 *
 * <p>Reads status the same way {@link TaskManagerPanel}'s fingerprint bar
 * does: prefers the headless Daemon's exported snapshot when it's alive and
 * fresh, falls back to the in-process scheduler otherwise — so what this
 * popup shows matches reality regardless of which process is actually
 * running the task.
 */
final class WatcherInfoPopup {

    private WatcherInfoPopup() {}

    static void show(Component invoker, Point screenLocation, ScheduledTask task,
                      XmlStorageService storage, TaskSchedulerService scheduler) {

        JWindow popup = new JWindow(SwingUtilities.getWindowAncestor(invoker));
        popup.setType(Window.Type.POPUP);
        popup.setAlwaysOnTop(true);
        popup.setFocusableWindowState(true);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x757575), 1),
                new EmptyBorder(10, 12, 10, 12)));
        content.setPreferredSize(new Dimension(360, 210));

        JLabel title = new JLabel(task.getName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JLabel modeBadge = new JLabel();
        modeBadge.setFont(modeBadge.getFont().deriveFont(Font.BOLD, 11f));
        modeBadge.setOpaque(true);
        modeBadge.setBorder(new EmptyBorder(2, 6, 2, 6));

        JLabel sourceLabel = new JLabel();
        sourceLabel.setFont(sourceLabel.getFont().deriveFont(Font.PLAIN, 10f));
        sourceLabel.setForeground(new Color(0x9E9E9E));

        JTextArea detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(detailArea.getFont().deriveFont(Font.PLAIN, 11f));
        detailArea.setBackground(new Color(0xF5F5F5));
        detailArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        JLabel checkedAtLabel = new JLabel();
        checkedAtLabel.setFont(checkedAtLabel.getFont().deriveFont(Font.PLAIN, 10f));
        checkedAtLabel.setForeground(new Color(0x9E9E9E));

        JButton reconnectBtn = new JButton("Reconnect");
        JButton closeBtn = new JButton("Close");

        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            Result r = resolve(storage, scheduler, task);
            modeBadge.setText(" " + r.label + " ");
            modeBadge.setBackground(r.color);
            modeBadge.setForeground(Color.WHITE);
            sourceLabel.setText(r.fromDaemon ? "reported by Daemon" : "reported by this process");
            detailArea.setText(r.detail);
            checkedAtLabel.setText("Checked " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
            boolean applicable = r.mode.equals("NATIVE_WATCH") || r.mode.equals("REMOTE_PUSH")
                    || r.mode.equals("POLLING_ONLY") || r.mode.equals("POLLING_ONLY_UNSUPPORTED");
            reconnectBtn.setEnabled(applicable);
        };
        refreshRef[0].run();

        reconnectBtn.addActionListener(e -> {
            reconnectBtn.setEnabled(false);
            reconnectBtn.setText("Reconnecting...");
            scheduler.reconnectWatch(task.getId());
            // The actual reconnect (filesystem registration or SSH connect) may
            // still be in flight when reconnectWatch() returns — give it a
            // moment before re-reading status, same 2s cadence the fingerprint
            // bar's own refresh timer uses elsewhere in this panel.
            Timer delay = new Timer(2000, e2 -> {
                refreshRef[0].run();
                reconnectBtn.setText("Reconnect");
            });
            delay.setRepeats(false);
            delay.start();
        });
        closeBtn.addActionListener(e -> popup.dispose());

        JPanel header = new JPanel(new BorderLayout(4, 2));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(modeBadge);
        header.add(badgeRow, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(checkedAtLabel, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(reconnectBtn);
        buttons.add(closeBtn);
        footer.add(buttons, BorderLayout.EAST);

        content.add(header, BorderLayout.NORTH);
        content.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.setOpaque(false);
        south.add(sourceLabel, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        popup.getContentPane().add(content);
        popup.pack();
        popup.setLocation(screenLocation);

        // Dismiss like a normal popup: click anywhere else, or hit Escape.
        popup.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { popup.dispose(); }
        });
        content.registerKeyboardAction(e -> popup.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        popup.setVisible(true);
        popup.requestFocus();
    }

    private static Result resolve(XmlStorageService storage, TaskSchedulerService scheduler, ScheduledTask task) {
        Path daemonFile = storage.getDataDir().toPath().resolve("scheduler-status-daemon.dat");
        if (SchedulerStatusSnapshot.isAlive(daemonFile, SchedulerStatusSnapshot.DEFAULT_STALE_MS)) {
            SchedulerStatusSnapshot snap = SchedulerStatusSnapshot.read(daemonFile);
            if (snap != null) {
                for (SchedulerStatusSnapshot.WatchEntry w : snap.getWatchEntries()) {
                    if (w.taskId().equals(task.getId())) {
                        return build(w.mode(), w.detail(), true);
                    }
                }
            }
        }
        TaskSchedulerService.WatchStatus s = scheduler.getWatchStatus(task);
        return build(s.mode().name(), s.detail(), false);
    }

    private static Result build(String mode, String detail, boolean fromDaemon) {
        String label;
        Color color;
        switch (mode) {
            case "NATIVE_WATCH" -> { label = "\u26A1 Live \u2014 native watch"; color = new Color(0x2E7D32); }
            case "REMOTE_PUSH" -> { label = "\u26A1 Live \u2014 remote push"; color = new Color(0x2E7D32); }
            case "POLLING_ONLY_UNSUPPORTED" -> { label = "Polling only \u2014 unsupported"; color = new Color(0xE65100); }
            case "POLLING_ONLY" -> { label = "Polling only"; color = new Color(0x757575); }
            default -> { label = "Not applicable"; color = new Color(0x9E9E9E); }
        }
        return new Result(mode, label, color, detail != null ? detail : "", fromDaemon);
    }

    private record Result(String mode, String label, Color color, String detail, boolean fromDaemon) {}
}
