package ui;

import javax.swing.*;
import java.awt.*;

/**
 * Sidebar nav button — paints a soft gradient fill in the same style as
 * {@link GradientButton} when selected, and stays fully transparent
 * (showing the sidebar's own background) when not. Unselected items don't
 * get a gradient because a gradient on every idle nav row would be busy;
 * this keeps the gradient motif reserved for "this is where you are".
 */
public class GradientToggleButton extends JToggleButton {

    public GradientToggleButton(String text, Icon icon) {
        super(text, icon);
        setContentAreaFilled(false);
        setOpaque(false);
        setFocusPainted(false);
        setBorderPainted(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (isSelected()) {
            Color base = getBackground();
            if (base != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color top = shade(base, 0.5f);
                Color bottom = shade(base, -0.08f);
                int arc = arcFor();
                g2.setPaint(new GradientPaint(0, 0, top, getWidth(), getHeight(), bottom));
                g2.fillRoundRect(0, 0, Math.max(getWidth() - 1, 0), Math.max(getHeight() - 1, 0), arc, arc);
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }

    private static int arcFor() {
        Object v = UIManager.get("Component.arc");
        if (v instanceof Integer) return (Integer) v;
        return 8;
    }

    private static Color shade(Color c, float amount) {
        int r = clamp(c.getRed()   + Math.round(255 * amount));
        int g = clamp(c.getGreen() + Math.round(255 * amount));
        int b = clamp(c.getBlue()  + Math.round(255 * amount));
        return new Color(r, g, b, c.getAlpha());
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
