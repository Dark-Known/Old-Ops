package ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Single source of truth for the app's visual language: an "earthy modern"
 * palette (terracotta, clay, olive, warm cream) plus a few reusable Swing
 * helpers (rounded borders, pill badges, section headers).
 *
 * Call {@link #install()} once, before any Swing component is created
 * (first line of main()), to switch the whole app from the default OS
 * chrome to Nimbus and repaint every standard control — buttons, tabs,
 * tables, scrollbars, combo boxes, text fields — with this palette. No
 * extra dependency is required: Nimbus ships in the JDK.
 */
public final class Theme {

    private Theme() {}

    // ── Core palette ─────────────────────────────────────────────────────
    // Kept deliberately small: one warm neutral scale for structure, one
    // accent (terracotta) reserved for the primary action and the active
    // nav item, and muted semantic colors for status text (no rainbow
    // pills) so the app reads as calm and simple rather than busy.
    public static final Color BG               = new Color(0xF2ECE2); // app background
    public static final Color SURFACE          = new Color(0xFFFDF9); // cards / tables / inputs
    public static final Color SURFACE_ALT      = new Color(0xEFE7D8); // stripes, hover rows
    public static final Color BORDER           = new Color(0xE1D6C1); // hairline borders
    public static final Color DIVIDER          = new Color(0xEAE1CE);

    public static final Color TEXT             = new Color(0x33271C); // near-black warm brown
    public static final Color TEXT_MUTED       = new Color(0x8A7A66); // secondary text
    public static final Color TEXT_DISABLED    = new Color(0xBBAE97);
    public static final Color TEXT_ON_DARK     = new Color(0xF4EEE3); // text on the sidebar

    public static final Color PRIMARY          = new Color(0xB0602F); // terracotta — the one accent color
    public static final Color PRIMARY_DARK     = new Color(0x8A4A26);
    public static final Color PRIMARY_LIGHT    = new Color(0xE7C39E);
    public static final Color PRIMARY_TINT_BG  = new Color(0xF1E1CD);

    // Sidebar navigation — flat, single tone (no gradient).
    public static final Color SIDEBAR_BG       = new Color(0x3B2E22);
    public static final Color SIDEBAR_BG_HOVER = new Color(0x4A3A2B);
    public static final Color SIDEBAR_SELECTED = new Color(0x5A4534);
    public static final Color SIDEBAR_TEXT     = new Color(0xD9CBB6);
    public static final Color SIDEBAR_TEXT_SEL = new Color(0xF4EEE3);

    public static final Color ACCENT           = PRIMARY;
    public static final Color ACCENT_DARK      = PRIMARY_DARK;

    // Status colors: muted, used as small dots/text rather than filled
    // pill backgrounds, so a busy task list doesn't look like confetti.
    public static final Color SUCCESS          = new Color(0x5C7A45);
    public static final Color SUCCESS_BG       = new Color(0xE9EEDF);
    public static final Color WARNING          = new Color(0xAD7C33);
    public static final Color WARNING_BG       = new Color(0xF3E9D2);
    public static final Color DANGER           = new Color(0x9C4A32);
    public static final Color DANGER_BG        = new Color(0xF2E0D7);
    public static final Color INFO             = new Color(0x596F6F);
    public static final Color INFO_BG          = new Color(0xE3E9E7);

    // Dark surfaces (log/console areas) kept warm rather than blue-black.
    public static final Color CONSOLE_BG       = new Color(0x2A2118);
    public static final Color CONSOLE_TEXT     = new Color(0xE9DCC4);
    public static final Color CONSOLE_MUTED    = new Color(0xA6957C);

    public static final Font FONT_BASE   = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font FONT_TITLE  = new Font(Font.SANS_SERIF, Font.BOLD, 19);
    public static final Font FONT_HEADER = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    public static final Font FONT_SMALL  = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static final Font FONT_MONO   = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    public static final int RADIUS = 10;

    /** Installs Nimbus + the earthy palette as the app-wide look and feel. */
    public static void install() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // Fall back silently to whatever the platform default is.
        }

        UIManager.put("nimbusBase", PRIMARY_DARK);
        UIManager.put("nimbusBlueGrey", new Color(0xC7B79C));
        UIManager.put("control", BG);
        UIManager.put("info", new Color(0xFFF7E7));
        UIManager.put("nimbusLightBackground", SURFACE);
        UIManager.put("text", TEXT);
        UIManager.put("nimbusFocus", PRIMARY_LIGHT);
        UIManager.put("nimbusSelectionBackground", PRIMARY);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusDisabledText", TEXT_DISABLED);
        UIManager.put("nimbusSelection", PRIMARY);
        UIManager.put("nimbusOrange", WARNING);
        UIManager.put("nimbusRed", DANGER);
        UIManager.put("nimbusGreen", SUCCESS);
        UIManager.put("nimbusAlertYellow", WARNING);
        UIManager.put("nimbusBorder", BORDER);
        UIManager.put("nimbusInfoBlue", INFO);
        UIManager.put("defaultFont", FONT_BASE);
        UIManager.put("TitledBorder.titleColor", TEXT);
        UIManager.put("Table.alternateRowColor", SURFACE_ALT);
        UIManager.put("SplitPane.background", BG);
    }

    /** A flat, rounded border with a hairline in {@link #BORDER}. */
    public static Border roundedLine(int radius) {
        return roundedLine(BORDER, radius);
    }

    public static Border roundedLine(Color color, int radius) {
        return new Border() {
            @Override public Insets getBorderInsets(Component c) { return new Insets(8, 10, 8, 10); }
            @Override public boolean isBorderOpaque() { return false; }
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, radius, radius));
                g2.dispose();
            }
        };
    }

    /** Padding-only border, useful alongside a panel that paints its own rounded background. */
    public static Border padding(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    /** A small rounded "pill" label used for status badges (e.g. "Active", "Failed"). */
    public static JLabel pill(String text, Color fg, Color bg) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight()));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setForeground(fg);
        label.setFont(FONT_SMALL.deriveFont(Font.BOLD));
        label.setBorder(new EmptyBorder(3, 10, 3, 10));
        label.setOpaque(false);
        return label;
    }

    /** A panel that paints a flat rounded card background (for grouping content). */
    public static JPanel card() {
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), RADIUS, RADIUS));
                g2.setColor(BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(14, 16, 14, 16));
        return panel;
    }

    /** A bold small-caps-style section label, used above grouped form fields. */
    public static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(FONT_SMALL.deriveFont(Font.BOLD, 11f));
        label.setForeground(TEXT_MUTED);
        return label;
    }

    /**
     * A flat sidebar navigation button: label only, no icon square, a thin
     * left accent bar when selected, and a subtle hover state. Several of
     * these stacked in a vertical box make up the app's left nav.
     */
    public static JToggleButton navButton(String text) {
        JToggleButton btn = new JToggleButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Color bg = isSelected() ? SIDEBAR_SELECTED : (getModel().isRollover() ? SIDEBAR_BG_HOVER : SIDEBAR_BG);
                g2.setColor(bg);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (isSelected()) {
                    g2.setColor(PRIMARY);
                    g2.fillRect(0, 0, 3, getHeight());
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(FONT_BASE.deriveFont(13f));
        btn.setForeground(SIDEBAR_TEXT);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setBorder(new EmptyBorder(11, 20, 11, 16));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        btn.addChangeListener(e -> btn.setForeground(btn.isSelected() ? SIDEBAR_TEXT_SEL : SIDEBAR_TEXT));
        return btn;
    }
}
