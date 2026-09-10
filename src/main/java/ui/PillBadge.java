package ui;

import javax.swing.*;
import java.awt.*;

/**
 * A small fully-rounded "pill" label — e.g. a status badge like "Running" or
 * "Failed" — with a solid or soft-tinted background. Swing has no built-in
 * chip/pill component, so this overrides paintComponent to draw one directly
 * rather than faking it with borders, which never renders a true rounded-pill
 * shape at small sizes.
 *
 * <p>Shared by every panel that shows a status (Tasks, Credentials, ...) so a
 * given status always looks the same regardless of which screen it's on —
 * see the {@code PILL_*} color pairs in {@link AppTheme}.
 */
public class PillBadge extends JLabel {

    private Color pillBackground;

    public PillBadge(String text, Color foreground, Color background) {
        super(text);
        setForeground(foreground);
        this.pillBackground = background;
        setOpaque(false); // we paint our own background, not the default rectangular one
        setHorizontalAlignment(SwingConstants.CENTER);
        setFont(getFont().deriveFont(Font.BOLD, 11f));
        setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
    }

    public void update(String text, Color foreground, Color background) {
        setText(text);
        setForeground(foreground);
        this.pillBackground = background;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(pillBackground);
        int arc = getHeight(); // fully rounded ends
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        // Guarantee a minimum pill width so single-word short statuses (e.g. "OK")
        // don't render as a near-circle.
        return new Dimension(Math.max(d.width, 64), d.height);
    }
}
