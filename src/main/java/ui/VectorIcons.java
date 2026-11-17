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

    /** A sun (rays + circle) — used for the "switch to light mode" toggle state. */
    static Icon sun(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                int cx = x + size / 2, cy = y + size / 2;
                int r = (int) (size * 0.22);
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
                g2.setStroke(new BasicStroke(size * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double rayInner = size * 0.34, rayOuter = size * 0.47;
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI / 4 * i;
                    int x1 = cx + (int) (Math.cos(ang) * rayInner);
                    int y1 = cy + (int) (Math.sin(ang) * rayInner);
                    int x2 = cx + (int) (Math.cos(ang) * rayOuter);
                    int y2 = cy + (int) (Math.sin(ang) * rayOuter);
                    g2.drawLine(x1, y1, x2, y2);
                }
                g2.dispose();
            }
        };
    }

    /** A crescent moon — used for the "switch to dark mode" toggle state. */
    static Icon moon(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                float d = size * 0.72f;
                float ox = x + size * 0.16f;
                float oy = y + (size - d) / 2f;
                java.awt.geom.Area moon = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(ox, oy, d, d));
                float cutD = d * 0.88f;
                float cutOx = ox + d * 0.38f;
                float cutOy = oy - d * 0.08f;
                moon.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(cutOx, cutOy, cutD, cutD)));
                g2.fill(moon);
                g2.dispose();
            }
        };
    }

    /** A checklist — Tasks nav icon: a rounded rectangle outline with two checkmark rows. */
    static Icon checklist(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float pad = size * 0.12f;
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(x + pad, y + pad,
                        size - pad * 2, size - pad * 2, size * 0.22f, size * 0.22f));
                float rowY1 = y + size * 0.38f, rowY2 = y + size * 0.65f;
                float boxX = x + size * 0.24f;
                drawCheckRow(g2, boxX, rowY1, size);
                drawCheckRow(g2, boxX, rowY2, size);
                g2.dispose();
            }
            private void drawCheckRow(Graphics2D g2, float boxX, float rowY, int size) {
                g2.drawLine((int) boxX, (int) rowY, (int) (boxX + size * 0.09f), (int) (rowY + size * 0.09f));
                g2.drawLine((int) (boxX + size * 0.09f), (int) (rowY + size * 0.09f),
                        (int) (boxX + size * 0.28f), (int) (rowY - size * 0.12f));
            }
        };
    }

    /** A key — Credentials nav icon: a ring with a small notched shaft. */
    static Icon key(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float ringD = size * 0.42f;
                float ringX = x + size * 0.08f, ringY = y + size * 0.08f;
                g2.draw(new java.awt.geom.Ellipse2D.Float(ringX, ringY, ringD, ringD));
                float shaftStartX = ringX + ringD * 0.78f, shaftStartY = ringY + ringD * 0.78f;
                float shaftEndX = x + size * 0.92f, shaftEndY = y + size * 0.92f;
                g2.draw(new java.awt.geom.Line2D.Float(shaftStartX, shaftStartY, shaftEndX, shaftEndY));
                float tooth1X = shaftEndX - size * 0.16f, tooth1Y = shaftEndY - size * 0.16f;
                g2.draw(new java.awt.geom.Line2D.Float(tooth1X, tooth1Y, tooth1X + size * 0.14f, tooth1Y - size * 0.02f));
                g2.dispose();
            }
        };
    }

    /** A document with lines — Logs nav icon. */
    static Icon document(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float pad = size * 0.16f;
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(x + pad, y + pad * 0.6f,
                        size - pad * 2, size - pad * 1.2f, size * 0.14f, size * 0.14f));
                for (int i = 0; i < 3; i++) {
                    float lineY = y + size * (0.4f + i * 0.18f);
                    g2.draw(new java.awt.geom.Line2D.Float(x + pad * 1.6f, lineY, x + size - pad * 1.6f, lineY));
                }
                g2.dispose();
            }
        };
    }

    /** Three sliders with knobs — Settings nav icon. */
    static Icon sliders(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float[] knobFrac = { 0.65f, 0.35f, 0.55f };
                for (int i = 0; i < 3; i++) {
                    float lineY = y + size * (0.22f + i * 0.3f);
                    g2.draw(new java.awt.geom.Line2D.Float(x + size * 0.1f, lineY, x + size * 0.9f, lineY));
                    float knobX = x + size * knobFrac[i];
                    g2.fill(new java.awt.geom.Ellipse2D.Float(knobX - size * 0.08f, lineY - size * 0.08f,
                            size * 0.16f, size * 0.16f));
                }
                g2.dispose();
            }
        };
    }

    /** An ECG-style pulse/activity line — used for the Event Monitor launcher. */
    static Icon pulse(Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = begin(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float midY = y + size * 0.5f;
                java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
                p.moveTo(x + size * 0.02f, midY);
                p.lineTo(x + size * 0.24f, midY);
                p.lineTo(x + size * 0.36f, y + size * 0.18f);
                p.lineTo(x + size * 0.50f, y + size * 0.86f);
                p.lineTo(x + size * 0.62f, midY);
                p.lineTo(x + size * 0.98f, midY);
                g2.draw(p);
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
