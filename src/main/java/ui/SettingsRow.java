package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * A reusable settings row component: label + description (two-line) + control.
 * Provides consistent styling for all setting rows in SettingsPanel.
 * Used to replace repeated GridBagLayout boilerplate.
 */
public class SettingsRow extends JPanel {

    public SettingsRow(String label, String description, JComponent control) {
        setLayout(new BorderLayout(12, 4));
        setBorder(new EmptyBorder(12, 0, 12, 0));
        setOpaque(false);

        // Left section: label + description
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        JLabel lblLabel = new JLabel(label);
        lblLabel.setFont(lblLabel.getFont().deriveFont(Font.BOLD, 13f));
        lblLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(lblLabel);

        JLabel lblDesc = new JLabel(description);
        lblDesc.setFont(lblDesc.getFont().deriveFont(Font.PLAIN, 11f));
        lblDesc.setForeground(new Color(0x888888));
        lblDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(lblDesc);

        add(left, BorderLayout.CENTER);

        // Right section: control
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(control);
        add(right, BorderLayout.EAST);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension pref = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, pref.height);
    }
}
