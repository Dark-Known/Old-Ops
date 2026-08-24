package ui;

import javax.swing.*;
import java.awt.*;

/** A {@link JPanel} that paints a smooth diagonal two-color gradient instead of a flat fill. */
public class GradientPanel extends JPanel {

    private final Color from;
    private final Color to;

    public GradientPanel(LayoutManager layout, Color from, Color to) {
        super(layout);
        this.from = from;
        this.to = to;
        setOpaque(false); // we paint the full background ourselves in paintComponent
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setPaint(new GradientPaint(0, 0, from, getWidth(), getHeight(), to));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
