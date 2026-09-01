package ui;

import javax.swing.*;
import java.awt.*;

/**
 * A colored action button that paints a soft vertical gradient (lighter at
 * top, darker at bottom, with hover/press states) instead of a flat fill —
 * the same visual language as the header's gradient banner, applied to
 * every primary/secondary action button so the "gradient" look isn't
 * confined to just the header.
 *
 * <p>Usage mirrors a plain {@link JButton}: construct with text, then set
 * a base color via {@link #setBackground(Color)} — the gradient's light/dark
 * ends are derived from that one color, so existing "styleBtn(btn, color)"
 * helpers across the panels only need their button's class changed, not
 * their color-picking logic.
 */
public class GradientButton extends JButton {

    public GradientButton(String text) {
        super(text);
        setContentAreaFilled(false); // let paintComponent draw the fill; FlatButtonUI paints text/icon on top
        setOpaque(false);
        setFocusPainted(false);
        setBorderPainted(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Color base = getBackground();
        if (base != null && isEnabled()) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean pressed = getModel().isArmed() && getModel().isPressed();
            boolean hover = getModel().isRollover();
            float topShade = pressed ? -0.14f : hover ? 0.16f : 0.09f;
            float bottomShade = pressed ? -0.06f : hover ? -0.02f : -0.11f;

            int arc = arcFor(this);
            g2.setPaint(new GradientPaint(0, 0, shade(base, topShade), 0, getHeight(), shade(base, bottomShade)));
            g2.fillRoundRect(0, 0, Math.max(getWidth() - 1, 0), Math.max(getHeight() - 1, 0), arc, arc);
            g2.dispose();
        }
        super.paintComponent(g);
    }

    private static int arcFor(Component c) {
        Object v = UIManager.get("Button.arc");
        if (v instanceof Integer) return (Integer) v;
        return 10;
    }

    private static Color shade(Color c, float amount) {
        int r = clamp(c.getRed()   + Math.round(255 * amount));
        int g = clamp(c.getGreen() + Math.round(255 * amount));
        int b = clamp(c.getBlue()  + Math.round(255 * amount));
        return new Color(r, g, b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
