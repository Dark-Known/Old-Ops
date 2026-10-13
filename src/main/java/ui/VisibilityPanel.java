package ui;

import service.ActivityVisibility;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings card: how long background work / a frozen window must last before it shows up in the Event Monitor. */
final class VisibilityPanel extends JPanel {
    private final JSpinner slowJob = spinner(ActivityVisibility.DEFAULT_SLOW_JOB_MS);
    private final JSpinner uiStall = spinner(ActivityVisibility.DEFAULT_UI_STALL_MS);

    VisibilityPanel() {
        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(4, 4, 4, 4));
        setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 8);
        c.anchor = GridBagConstraints.WEST;
        row(0, c, "Background work slower than", "Routine background jobs (screen loads, feed refreshes) appear in the Event Monitor once they run this long. Actions you trigger yourself are always shown.", slowJob);
        row(1, c, "UI stall longer than", "When the window freezes for at least this long, a \"UI stall\" event with the blocking code appears.", uiStall);
    }

    private static JSpinner spinner(int v) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(v, ActivityVisibility.MIN_MS, ActivityVisibility.MAX_MS, 50));
        sp.setPreferredSize(new Dimension(96, sp.getPreferredSize().height));
        return sp;
    }

    private void row(int y, GridBagConstraints c, String title, String hint, JSpinner sp) {
        c.gridy = y; c.gridx = 0;
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
        JLabel h = new JLabel("<html><div style='width:360px'>" + hint + "</div></html>");
        h.setFont(h.getFont().deriveFont(Font.PLAIN, 11f));
        h.setForeground(new Color(0x888888));
        left.add(t); left.add(h);
        add(left, c);
        c.gridx = 1; add(sp, c);
        c.gridx = 2; add(new JLabel("ms"), c);
    }

    void load() {
        slowJob.setValue((int) ActivityVisibility.slowJobMs());
        uiStall.setValue((int) ActivityVisibility.uiStallMs());
    }

    Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ActivityVisibility.KEY_SLOW_JOB_MS, String.valueOf(slowJob.getValue()));
        m.put(ActivityVisibility.KEY_UI_STALL_MS, String.valueOf(uiStall.getValue()));
        return m;
    }
}
