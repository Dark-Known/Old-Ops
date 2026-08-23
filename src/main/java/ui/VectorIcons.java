package ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Small vector-drawn icons used in place of emoji/dingbat characters (✅ ❌
 * ⏳ 📁 etc). Those depend on the JRE/OS having an emoji-capable font
 * installed — many Linux desktops and some Windows/JRE combinations don't
 * ship one, so the glyph falls back to an empty "tofu" box. Painting the
 * shapes ourselves with {@link Graphics2D} renders identically on every
 * platform, with zero font dependency.
 */
final class VectorIcons {

    private VectorIcons() {}

    /** A circular green checkmark. */
    static Icon check(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.fillOval(x, y, size, size);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = x + size / 2, cy = y + size / 2;
                g2.drawLine((int) (x + size * 0.28), cy, (int) (x + size * 0.44), (int) (y + size * 0.64));
                g2.drawLine((int) (x + size * 0.44), (int) (y + size * 0.64), (int) (x + size * 0.74), (int) (y + size * 0.32));
                g2.dispose();
            }
        };
    }

    /** A circular red cross. */
    static Icon cross(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.fillOval(x, y, size, size);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pad = (int) (size * 0.3);
                g2.drawLine(x + pad, y + pad, x + size - pad, y + size - pad);
                g2.drawLine(x + size - pad, y + pad, x + pad, y + size - pad);
                g2.dispose();
            }
        };
    }

    /** A simple hourglass, used while a check is in progress. */
    static Icon hourglass(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pad = (int) (size * 0.18);
                int top = y + pad, bottom = y + size - pad, midY = y + size / 2;
                int left = x + pad, right = x + size - pad, midX = x + size / 2;
                g2.drawLine(left, top, right, top);
                g2.drawLine(left, bottom, right, bottom);
                g2.drawLine(left, top, midX, midY);
                g2.drawLine(right, top, midX, midY);
                g2.drawLine(left, bottom, midX, midY);
                g2.drawLine(right, bottom, midX, midY);
                g2.dispose();
            }
        };
    }

    private static Graphics2D begin(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g2;
    }
}
