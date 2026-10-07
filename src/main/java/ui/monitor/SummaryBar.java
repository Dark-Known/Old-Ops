package ui.monitor;

import ui.AppTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single slim row of at-a-glance numbers across the top of the monitor:
 * which scheduler is active, how busy the workers are, how much is running
 * and queued, and whether anything needs attention. Replaces the old header
 * chip + worker strip + per-tab titles, which each said a fragment of this.
 */
public class SummaryBar extends JPanel {

    private final Map<String, Chip> chips = new LinkedHashMap<>();

    public SummaryBar() {
        setLayout(new WrapLayout(FlowLayout.LEFT, 8, 4));
        setBorder(new EmptyBorder(0, 0, 4, 0));
        setOpaque(false);
    }

    /** Creates or updates a chip. {@code accent} colours the leading dot; null for none. */
    public void set(String id, String label, String value, Color accent, String tooltip) {
        Chip c = chips.get(id);
        if (c == null) { c = new Chip(); chips.put(id, c); add(c); }
        c.update(label, value, accent, tooltip);
    }

    /** Makes a chip clickable (hand cursor + callback). */
    public void onClick(String id, Runnable r) {
        Chip c = chips.get(id);
        if (c != null) c.setClick(r);
    }

    private static final class Chip extends JPanel {
        private final JLabel label = new JLabel();
        private final JLabel value = new JLabel();
        private Color accent;
        private Runnable click;

        Chip() {
            setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(4, 12, 4, 12));
            label.setForeground(MonitorStyle.muted());
            label.setFont(label.getFont().deriveFont(11.5f));
            value.setFont(value.getFont().deriveFont(Font.BOLD, 12f));
            add(label);
            add(value);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (click != null) click.run(); }
            });
        }

        void update(String l, String v, Color a, String tip) {
            label.setText(l);
            value.setText(v);
            accent = a;
            setToolTipText(tip);
            // Leave room for the dot only when there is one.
            setBorder(new EmptyBorder(4, a != null ? 22 : 12, 4, 12));
            repaint();
        }

        void setClick(Runnable r) {
            click = r;
            setCursor(Cursor.getPredefinedCursor(r != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean dark = AppTheme.isDark();
            g2.setColor(dark ? new Color(255, 255, 255, 18) : new Color(0, 0, 0, 13));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            if (accent != null) {
                g2.setColor(MonitorStyle.onSurface(accent));
                g2.fillOval(10, getHeight() / 2 - 4, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
