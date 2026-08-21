package ui;

import model.TaskRunRecord;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows small transient "toast" popups near the bottom-right of the main
 * window for every task run — success, failure, or skip — regardless of
 * which tab is currently active. Toasts stack when several arrive close
 * together and auto-dismiss after a few seconds.
 *
 * <p>Must only be driven from the EDT — callers (e.g. a
 * {@code RunHistoryService} run listener, which fires on a background
 * thread) should wrap calls to {@link #showToast} in
 * {@link SwingUtilities#invokeLater}.
 */
public class ToastManager {

    private static final int TOAST_WIDTH = 300;
    private static final int MARGIN = 20;
    private static final int GAP = 8;
    private static final int DISMISS_MS = 4500;

    private final Window owner;
    private final List<JWindow> active = new ArrayList<>();

    public ToastManager(Window owner) {
        this.owner = owner;
    }

    /** Displays a toast summarizing one completed run. Must be called on the EDT. */
    public void showToast(TaskRunRecord r) {
        Color accent;
        String icon;
        switch (r.getStatus()) {
            case SUCCESS: accent = new Color(0x2E7D32); icon = "\u2713"; break; // ✓
            case FAILED:  accent = new Color(0xC62828); icon = "\u2717"; break; // ✗
            default:      accent = new Color(0xF9A825); icon = "\u23ED"; break; // ⏭ (skipped)
        }

        JWindow toast = new JWindow(owner);
        toast.setType(Window.Type.POPUP);
        toast.setAlwaysOnTop(true);

        JPanel content = new JPanel(new BorderLayout(8, 4));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 2),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JLabel title = new JLabel(icon + "  " + r.getTaskName() + " — " + r.getStatus());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(accent);

        String reasonText = r.getReason() != null && !r.getReason().isEmpty() ? r.getReason() : "(no details)";
        JLabel body = new JLabel("<html><div style='width:" + (TOAST_WIDTH - 40) + "px'>"
                + escape(reasonText) + "</div></html>");
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 11f));
        body.setForeground(new Color(0x424242));

        content.add(title, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);

        // Click a toast to dismiss it immediately.
        content.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                dismiss(toast);
            }
        });

        toast.getContentPane().add(content);
        toast.setSize(TOAST_WIDTH, toast.getPreferredSize().height);

        active.add(toast);
        repositionAll();
        toast.setVisible(true);

        Timer dismissTimer = new Timer(DISMISS_MS, e -> dismiss(toast));
        dismissTimer.setRepeats(false);
        dismissTimer.start();
    }

    private void dismiss(JWindow toast) {
        if (!active.remove(toast)) return;
        toast.dispose();
        repositionAll();
    }

    /** Stacks all currently-active toasts bottom-up near the owner window's bottom-right corner. */
    private void repositionAll() {
        Rectangle ownerBounds = owner.getBounds();
        int baseY = ownerBounds.y + ownerBounds.height - MARGIN;
        for (int i = active.size() - 1; i >= 0; i--) {
            JWindow w = active.get(i);
            baseY -= w.getHeight();
            w.setLocation(ownerBounds.x + ownerBounds.width - w.getWidth() - MARGIN, baseY);
            baseY -= GAP;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
