package ui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A small (36×36 by default) rounded square with a centered glyph and a soft
 * tinted background — the icon tile shown at the start of each row in the
 * card-style {@code JList} row renderers (task type, credential type, log
 * severity). Deliberately a plain glyph/letter/emoji-capable label rather
 * than requiring real icon assets, since this app has none vendored — a
 * single bold character (e.g. "\u2B06" for outbound, "F" for file transfer)
 * reads perfectly well at this size and needs no asset pipeline.
 */
public class IconTile extends JComponent {

    private final String glyph;
    private final Color fg;
    private final Color bg;
    private final int size;
    private final int arc;

    public IconTile(String glyph, Color fg, Color bg) {
        this(glyph, fg, bg, 36, 10);
    }

    public IconTile(String glyph, Color fg, Color bg, int size, int arc) {
        this.glyph = glyph;
        this.fg = fg;
        this.bg = bg;
        this.size = size;
        this.arc = arc;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(bg);
        g2.fill(new RoundRectangle2D.Float(0, 0, size, size, arc, arc));

        g2.setColor(fg);
        g2.setFont(getFont().deriveFont(Font.BOLD, size * 0.42f));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (size - fm.stringWidth(glyph)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(glyph, tx, ty);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(size, size); }
}
