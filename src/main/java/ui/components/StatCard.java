package ui.components;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * A small "big number + label" card — Total / Running / Failed / Success
 * rate, etc. — for the summary strip above a task/credential list. Value and
 * label can be updated in place via {@link #setValue(String)} so the same
 * card instances are reused across refreshes rather than rebuilt.
 */
public class StatCard extends JPanel {

    private final JLabel valueLabel;
    private final JLabel captionLabel;

    public StatCard(String caption, String initialValue, Color accent) {
        setLayout(new BorderLayout(0, 2));
        setBorder(new CompoundBorder(
                new LineBorder(new Color(0, 0, 0, 30), 1, true),
                new EmptyBorder(8, 14, 8, 14)));
        setOpaque(false);

        valueLabel = new JLabel(initialValue);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 20f));
        if (accent != null) valueLabel.setForeground(accent);

        captionLabel = new JLabel(caption);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.PLAIN, 11f));
        captionLabel.setForeground(new Color(0x8A8378));

        add(valueLabel, BorderLayout.NORTH);
        add(captionLabel, BorderLayout.SOUTH);
    }

    public void setValue(String value) {
        valueLabel.setText(value);
    }
}
