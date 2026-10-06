package ui.monitor;

import model.EventKind;
import ui.AppTheme;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Colors, number/time formatting and tiny helpers shared by the monitor's components. */
public final class MonitorStyle {

    private MonitorStyle() {}

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_HMS = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss");

    private static final Color INFO_FG  = new Color(0x5E5A52), INFO_BG  = new Color(0xECEAE4);
    private static final Color DEBUG_FG = new Color(0x8A8378), DEBUG_BG = new Color(0xF2F0EC);

    /** {foreground, background} for a status pill. */
    public static Color[] pill(FeedRow.Outcome o) {
        return switch (o) {
            case SUCCEEDED -> new Color[]{AppTheme.SUCCESS_FG, AppTheme.SUCCESS_BG};
            case FAILED, ERROR -> new Color[]{AppTheme.FAILED_FG, AppTheme.FAILED_BG};
            case RUNNING -> new Color[]{AppTheme.RUNNING_FG, AppTheme.RUNNING_BG};
            case SKIPPED, WARNING -> new Color[]{AppTheme.SKIPPED_FG, AppTheme.SKIPPED_BG};
            case INFO -> new Color[]{INFO_FG, INFO_BG};
            case DEBUG -> new Color[]{DEBUG_FG, DEBUG_BG};
        };
    }

    /** Colored edge shown at the left of rows that need attention; null for calm rows. */
    public static Color edge(FeedRow.Outcome o) {
        return switch (o) {
            case FAILED, ERROR -> new Color(0xC0504A);
            case WARNING, SKIPPED -> new Color(0xD9A441);
            case RUNNING -> new Color(0xE0A458);
            default -> null;
        };
    }

    public static Color categoryColor(EventKind.Category c) {
        return switch (c) {
            case RUN -> new Color(0xB5704A);
            case WATCH -> new Color(0x4A8A6D);
            case QUEUE -> new Color(0x8A8378);
            case SYSTEM -> new Color(0x6E6FA8);
        };
    }

    /**
     * Makes a status colour readable on the current surface. The app's status tokens (dark green,
     * dark amber...) are designed for pale chips and light backgrounds; as plain text or a small dot
     * on a dark surface they nearly vanish, so in dark mode they are lightened while keeping their hue.
     */
    public static Color onSurface(Color c) {
        if (c == null || !AppTheme.isDark()) return c;
        return new Color(lift(c.getRed()), lift(c.getGreen()), lift(c.getBlue()));
    }

    private static int lift(int v) {
        return Math.min(255, (int) Math.round(v + (255 - v) * 0.55));
    }

    public static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(0x8A8378);
    }

    public static Color fg() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : Color.BLACK;
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ── Formatting ──────────────────────────────────────────────────

    /** "12:03:11" for today, "Oct 4  23:58:02" for earlier days. */
    public static String time(LocalDateTime t) {
        if (t == null) return "\u2014";
        return t.toLocalDate().equals(LocalDate.now()) ? t.format(HMS) : t.format(DAY_HMS);
    }

    public static String fullTime(LocalDateTime t) {
        return t == null ? "\u2014" : t.format(FULL);
    }

    /** 850 -> "850 ms", 4200 -> "4.2 s", 72_000 -> "1m 12s", 7_500_000 -> "2h 5m"; negative -> "". */
    public static String duration(long ms) {
        if (ms < 0) return "";
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 10) return String.format("%.1f s", ms / 1000.0);
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    public static String duration(Duration d) {
        return duration(d.toMillis());
    }

    public static String triggerLabel(String t) {
        if (t == null) return "";
        return switch (t) {
            case "SCHEDULE" -> "Schedule";
            case "WATCHER" -> "Watcher";
            case "MANUAL" -> "Manual";
            case "RETRY" -> "Retry";
            default -> t;
        };
    }

    public static String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String directionGlyph(model.ScheduledTask.TransferDirection d) {
        if (d == model.ScheduledTask.TransferDirection.INBOUND) return "\u2193 ";
        if (d == model.ScheduledTask.TransferDirection.OUTBOUND) return "\u2191 ";
        return "";
    }
}
