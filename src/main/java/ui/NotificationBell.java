package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.XmlStorageService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Small bell-icon button (like a social-media notification bell) that
 * replaces the old always-visible "Notifications" tab. Lives in the header,
 * so it's visible no matter which tab is active. Shows a red badge with the
 * count of currently failed/retrying tasks; clicking it drops down a short
 * list plus a "Manage failures..." action that opens the same
 * restart/recovery tooling the old tab had (now on demand, in a dialog,
 * instead of permanently occupying a tab).
 */
public class NotificationBell extends JButton {

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    private int failedCount = 0;

    // Facebook's header bell: a plain outline icon sitting directly on the
    // bar (no permanent background), with a soft circular highlight that
    // only appears on hover, and a solid red count badge overlapping the
    // top-right corner. Reproduced here with a semi-transparent white
    // hover disc (Facebook uses a light gray one on its white bar; white
    // is the equivalent on this app's dark navy header) instead of an
    // always-on colored chip.
    private static final Color HOVER_HIGHLIGHT = new Color(255, 255, 255, 38);
    private boolean hovering = false;

    public NotificationBell(XmlStorageService storage, TaskSchedulerService scheduler) {
        this.storage = storage;
        this.scheduler = scheduler;
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setFont(getFont().deriveFont(Font.PLAIN, 18f));
        setText("\uD83D\uDD14"); // 🔔
        setForeground(Color.WHITE); // plain white icon reads clearly on the navy header, Facebook-style
        setToolTipText("Failed tasks");
        setMargin(new Insets(4, 4, 4, 4));
        addActionListener(e -> showDropdown());
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { hovering = true; repaint(); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { hovering = false; repaint(); }
        });
        refreshCount();
    }

    /** Re-reads the current failed/retrying task count from storage and repaints the badge. Cheap — call freely. */
    public void refreshCount() {
        int count = 0;
        for (ScheduledTask t : storage.loadTasks()) {
            if (t.getStatus() == ScheduledTask.TaskStatus.FAILED
                    || t.getStatus() == ScheduledTask.TaskStatus.RETRYING) {
                count++;
            }
        }
        this.failedCount = count;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(34, 34);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Soft circular highlight on hover only — like Facebook's bell.
        if (hovering) {
            int size = Math.min(getWidth(), getHeight());
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            g2.setColor(HOVER_HIGHLIGHT);
            g2.fillOval(x, y, size, size);
        }
        g2.dispose();

        super.paintComponent(g);

        if (failedCount <= 0) return;
        Graphics2D g3 = (Graphics2D) g.create();
        g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        String label = failedCount > 99 ? "99+" : String.valueOf(failedCount);
        Font badgeFont = getFont().deriveFont(Font.BOLD, 10f);
        FontMetrics fm = g3.getFontMetrics(badgeFont);
        int textWidth = fm.stringWidth(label);
        int diameter = Math.max(16, textWidth + 8);
        int bx = getWidth() - diameter - 1;
        int by = 1;
        g3.setColor(new Color(0xE53935));
        g3.fillOval(bx, by, diameter, diameter);
        g3.setColor(Color.WHITE);
        g3.setFont(badgeFont);
        g3.drawString(label, bx + (diameter - textWidth) / 2, by + diameter - 5);
        g3.dispose();
    }

    private void showDropdown() {
        refreshCount();
        List<ScheduledTask> tasks = storage.loadTasks();
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        boolean any = false;
        int shown = 0;
        for (ScheduledTask t : tasks) {
            if (t.getStatus() == ScheduledTask.TaskStatus.FAILED
                    || t.getStatus() == ScheduledTask.TaskStatus.RETRYING) {
                any = true;
                shown++;
                if (shown > 8) continue; // keep the dropdown short; "Manage failures..." shows the rest
                JMenuItem item = new JMenuItem("<html><b>" + escape(t.getName()) + "</b><br>"
                        + "<span style='color:gray;font-size:90%'>" + t.getStatus()
                        + (t.getLastRunAt() != null ? " · " + t.getLastRunAt() : "") + "</span></html>");
                item.addActionListener(e -> openManageDialog());
                popup.add(item);
            }
        }
        if (!any) {
            JMenuItem none = new JMenuItem("No failed tasks");
            none.setEnabled(false);
            popup.add(none);
        } else if (shown > 8) {
            JMenuItem more = new JMenuItem("+ " + (shown - 8) + " more...");
            more.setEnabled(false);
            popup.add(more);
        }
        popup.addSeparator();
        JMenuItem manage = new JMenuItem("Manage failures...");
        manage.addActionListener(e -> openManageDialog());
        popup.add(manage);

        popup.show(this, 0, getHeight());
    }

    private void openManageDialog() {
        Window ownerWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(ownerWindow, "Failure Recovery", Dialog.ModalityType.MODELESS);
        NotificationPanel panel = new NotificationPanel(storage, scheduler);
        dialog.getContentPane().add(panel);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(ownerWindow);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { refreshCount(); }
        });
        dialog.setVisible(true);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
