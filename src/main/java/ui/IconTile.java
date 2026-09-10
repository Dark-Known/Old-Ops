package ui;

import javax.swing.*;
import java.awt.*;

/**
 * A rounded square (36×36, arc=10) with a centered icon and a soft tinted background.
 * Used for task-type, credential-type, and log-severity icons throughout the redesigned UI.
 */
public class IconTile extends JLabel {

    private final Color bgColor;

    public IconTile(Icon icon, Color fgColor, Color bgColor) {
        super(icon);
        this.bgColor = bgColor;
        
        setHorizontalAlignment(CENTER);
        setVerticalAlignment(CENTER);
        setOpaque(true);
        setBackground(bgColor);
        setForeground(fgColor);
        
        // Fixed 36x36 size
        setPreferredSize(new Dimension(36, 36));
        setMinimumSize(new Dimension(36, 36));
        setMaximumSize(new Dimension(36, 36));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw rounded background
        int arc = 10;
        g2.setColor(bgColor);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

        // Draw icon centered
        super.paintComponent(g);
    }
}
