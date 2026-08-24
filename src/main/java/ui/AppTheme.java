package ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.prefs.Preferences;

/**
 * Installs a modern, flat look and feel (FlatLaf — see libs-repo/README.md
 * for how it's vendored) across the whole application in place of the old
 * plain {@code UIManager.getSystemLookAndFeelClassName()} call, which is
 * what gave every screen its dated, inconsistent, OS-default appearance.
 *
 * <p>This one class is what upgrades every existing panel — Task Manager,
 * Credentials, Logs, Settings, every dialog — all at once: buttons, text
 * fields, tabs, tables, scrollbars, combo boxes, checkboxes etc. all pick
 * up the new rounded, properly-spaced, properly-contrasted styling
 * automatically, with zero changes needed in each panel's own code.
 *
 * <p>Call {@link #install()} once, before any Swing component is created
 * (i.e. the very first line of {@code main()}).
 */
public final class AppTheme {

    private static final String PREF_KEY_DARK = "dark_mode";
    private static final Preferences PREFS = Preferences.userNodeForPackage(SettingsPanel.class);

    /** The app's accent color — warm terracotta, used for the header gradient, focus rings,
     *  selected nav item, and FlatLaf's own accent-driven components (selected tabs, progress
     *  bars, checked checkboxes...) so native Swing chrome matches the custom UI. */
    public static final Color ACCENT = new Color(0xC1652F);          // terracotta
    public static final Color ACCENT_DARK = new Color(0x6B4226);     // umber — header gradient start
    public static final Color ACCENT_SECONDARY = new Color(0x8A9A5B); // olive/moss — header gradient end
    private static final String ACCENT_HEX = "#C1652F";

    /** Earthy semantic button colors — used in place of the old primary-color palette
     *  (bright green/blue/red/orange) across TaskManagerPanel, CredentialManagerPanel, etc. */
    public static final Color EARTH_MOSS   = new Color(0x5B7B4F); // creation / positive actions (New Task, Add Credential)
    public static final Color EARTH_SIENNA = new Color(0xA0522D); // primary actions (Run Now, Save)
    public static final Color EARTH_RUST   = new Color(0xA54A3F); // destructive actions (Delete)
    public static final Color EARTH_OCHRE  = new Color(0xC68642); // secondary emphasis (Restart)
    public static final Color EARTH_CLAY   = new Color(0xBF6952); // tertiary emphasis (View Logs)
    public static final Color EARTH_TEAL   = new Color(0x4A6D5C); // alt action (Latest Logs, deep sage-teal)

    private AppTheme() {}

    /** Installs the theme according to the saved light/dark preference (light by default). */
    public static void install() {
        // Must happen BEFORE setLookAndFeel — this is how FlatLaf itself is customized at the
        // properties-file level (its "@variable" system), as opposed to the plain UIManager.put()
        // overrides below, which only take effect AFTER the LaF's own defaults are installed.
        FlatLaf.setGlobalExtraDefaults(Collections.singletonMap("@accentColor", ACCENT_HEX));

        try {
            UIManager.setLookAndFeel(isDark() ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (Exception ex) {
            // Should never happen (both classes are always available once vendored), but if it
            // somehow does, fall back to the plain OS-default LaF rather than crashing the app.
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        }

        applyStyleOverrides();
    }

    /**
     * Wraps a component (typically a table's JScrollPane) with a small bold
     * label above it instead of a boxed {@link javax.swing.border.TitledBorder}.
     * A TitledBorder draws its own sharp-cornered rectangle, which overrides —
     * and clashes with — the rounded ScrollPane.arc styling set in
     * {@link #applyStyleOverrides()}, so this is the preferred way to caption
     * a table without losing its soft corners.
     */
    public static JPanel titledSection(String title, JComponent content) {
        JPanel section = new JPanel(new BorderLayout(0, 6));
        section.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        section.add(label, BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    public static boolean isDark() {
        return PREFS.getBoolean(PREF_KEY_DARK, false);
    }

    /** Flips light/dark and live-refreshes every currently open window — no restart required. */
    public static void toggle() {
        PREFS.putBoolean(PREF_KEY_DARK, !isDark());
        try {
            UIManager.setLookAndFeel(isDark() ? new FlatDarkLaf() : new FlatLightLaf());
            applyStyleOverrides();
            FlatLaf.updateUI(); // repaints/re-lays-out every open window with the new defaults
        } catch (Exception ignored) { /* keep the previous theme rather than half-apply a broken one */ }
    }

    /**
     * Global style tokens applied on top of the installed FlatLaf theme — must run AFTER
     * {@code setLookAndFeel}, since that call replaces the whole UIManager defaults table these
     * write into. Every key here is a real, documented FlatLaf UIManager key (verified directly
     * against FlatLaf's own FlatLaf.properties defaults).
     */
    private static void applyStyleOverrides() {
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 5);

        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 8);
        UIManager.put("ScrollBar.showButtons", false);

        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);

        // Round the outer frame every JScrollPane draws — including the ones every
        // panel wraps its JTable in (Task Manager, Run History, Notifications,
        // Credentials, etc.) — so tables get the same soft corners as buttons and
        // text fields instead of a hard rectangular box. ScrollPane.Table.arc
        // overrides the general ScrollPane.arc specifically for table scroll panes.
        UIManager.put("ScrollPane.arc", 10);
        UIManager.put("ScrollPane.Table.arc", 10);

        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("Table.rowHeight", 26);

        UIManager.put("Component.focusWidth", 1);

        Font sans = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        UIManager.put("defaultFont", sans);
    }
}
