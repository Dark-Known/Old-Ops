package ui.monitor;

import ui.AppTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A pill-shaped choice for a segmented filter row ("All · Runs · Watchers …").
 *
 * <p>Exists because the stock toggle button gives no visible difference
 * between "on" and "off" in this theme — operators could not tell which
 * categories were being shown. Here the chosen chip is filled with the accent
 * colour and the rest are plain outlines, so the active filter is readable at
 * a glance. A small count can be shown after the label.
 */
final class FilterChip extends JComponent {

    /** Receives a click; {@code multi} is true when Ctrl or Shift was held. */
    interface Listener { void clicked(FilterChip chip, boolean multi); }

    private final String label;
    private int count = -1;
    private boolean selected;
    private boolean hover;

    FilterChip(String label, String tooltip, Listener listener) {
        this.label = label;
        setToolTipText(tooltip);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(false);
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) listener.clicked(FilterChip.this, e.isControlDown() || e.isShiftDown());
            }
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
        });
    }

    void setSelected(boolean s) {
        if (s != selected) { selected = s; repaint(); }
    }

    boolean isSelected() { return selected; }

    /** Shows {@code n} after the label; a negative value hides the count. */
    void setCount(int n) {
        if (n != count) { count = n; revalidate(); repaint(); }
    }

    String label() { return label; }

    private String text() {
        return count > 0 ? label + "  " + count : label;
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont().deriveFont(Font.BOLD, 12f));
        return new Dimension(fm.stringWidth(text()) + 20, 26);
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 1;
        boolean dark = AppTheme.isDark();

        Color fill, border, fg;
        if (selected) {
            fill = dark ? new Color(0xC1652F) : AppTheme.ACCENT_DARK;
            border = fill;
            fg = Color.WHITE;
        } else {
            fill = hover ? (dark ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 20)) : new Color(0, 0, 0, 0);
            border = dark ? new Color(255, 255, 255, 70) : new Color(0, 0, 0, 55);
            fg = MonitorStyle.fg();
        }
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, w, h, h, h);
        g2.setColor(border);
        g2.drawRoundRect(0, 0, w, h, h, h);

        g2.setFont(getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, 12f));
        g2.setColor(fg);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text(), (getWidth() - fm.stringWidth(text())) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
