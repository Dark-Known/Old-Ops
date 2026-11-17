package ui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A small, fully-rounded (pill-shaped) label — "Running", "Failed",
 * "Success", etc. — with a soft tinted background. Swing has no native
 * chip/pill component; {@code JLabel} only paints a rectangular background
 * even with {@code setOpaque(true)}, so this overrides {@code paintComponent}
 * to draw the rounded shape itself before the superclass paints the text.
 *
 * <p>Colors are always passed in as an explicit (fg, bg) pair — see the
 * status tokens on {@code ui.AppTheme} (e.g. {@code RUNNING_FG}/{@code RUNNING_BG})
 * — rather than derived from a single color, since the background is
 * deliberately always pale regardless of light/dark mode (a "highlighter"
 * look) while the foreground must always stay dark enough to read on it.
 */
public class PillBadge extends JLabel {

    private Color bg;

    public PillBadge(String text, Color fg, Color bg) {
        super(text);
        this.bg = bg;
        setForeground(fg);
        setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
        setHorizontalAlignment(CENTER);
        setBorder(new EmptyBorder(2, 9, 2, 9));
        setOpaque(false); // we paint our own (rounded) background instead of JLabel's rectangular one
    }

    /** Neutral variant — plain muted text, no pill background at all (for
     *  "—" / not-applicable cells where a colored chip would be noise). */
    public static PillBadge neutral(String text, Color fg) {
        PillBadge b = new PillBadge(text, fg, null);
        return b;
    }

    public void setColors(Color fg, Color bg) {
        setForeground(fg);
        this.bg = bg;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (bg != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
            g2.dispose();
        }
        super.paintComponent(g);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        // Ensure the pill is never narrower than it is tall (a perfect circle
        // for very short text would look wrong) — a small minimum width floor.
        return new Dimension(Math.max(d.width, d.height + 12), d.height);
    }
}
