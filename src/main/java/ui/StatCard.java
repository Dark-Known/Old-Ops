package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * A small "label over big number" summary tile — e.g. "Running · 2" — used in
 * the stat strip above a panel's main list/table so the person sees an
 * at-a-glance summary before scanning individual rows.
 *
 * <p>The number is mutable via {@link #setValue(String)} so a single set of
 * {@code StatCard}s can be created once in {@code buildUI()} and updated in
 * place on every {@code refresh()}, rather than being rebuilt from scratch.
 */
public class StatCard extends JPanel {

    private final JLabel valueLabel;

    public StatCard(String label, String initialValue, Color valueColor) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.surface2());
        setBorder(new EmptyBorder(10, 14, 10, 14));
        setOpaque(true);

        JLabel labelLabel = new JLabel(label);
        labelLabel.setFont(labelLabel.getFont().deriveFont(Font.PLAIN, 11f));
        labelLabel.setForeground(Color.GRAY);
        labelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        valueLabel = new JLabel(initialValue);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 19f));
        if (valueColor != null) valueLabel.setForeground(valueColor);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(labelLabel);
        add(Box.createVerticalStrut(2));
        add(valueLabel);
    }

    public void setValue(String value) {
        valueLabel.setText(value);
    }

    /** Rounded background to match {@link PillBadge}'s soft-card look elsewhere. */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(getBackground());
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g2.dispose();
        // Don't call super.paintComponent with isOpaque(true) — it would flat-fill a
        // square behind our rounded fill above. Paint children directly instead.
        setOpaque(false);
        super.paintComponent(g);
        setOpaque(true);
    }
}
