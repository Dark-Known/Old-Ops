package ui.monitor;

import model.EventKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The Event Monitor's persisted view preferences. One shared instance is
 * handed to the feed, the detail pane and the panel so the Options menu, the
 * toolbar and the layout all agree; changing anything notifies listeners and
 * writes it straight to {@link Preferences}, so the monitor reopens the way
 * the operator left it.
 */
public final class MonitorPrefs {

    public enum ToastMode {
        PROBLEMS("Failures & warnings only"), ALL("Every completed run"), OFF("Off");
        private final String label;
        ToastMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Time-range choices; a null window means "everything still on record". */
    public record Range(String label, Duration window) {}

    public static final List<Range> RANGES = List.of(
            new Range("Last 15 minutes", Duration.ofMinutes(15)),
            new Range("Last hour", Duration.ofHours(1)),
            new Range("Last 6 hours", Duration.ofHours(6)),
            new Range("Last 24 hours", Duration.ofHours(24)),
            new Range("Last 7 days", Duration.ofDays(7)),
            new Range("All recorded", null));

    public record Level(String label, EventKind.Severity min) {}

    public static final List<Level> LEVELS = List.of(
            new Level("Normal", EventKind.Severity.INFO),
            new Level("Warnings & errors", EventKind.Severity.WARN),
            new Level("Errors only", EventKind.Severity.ERROR),
            new Level("Everything (incl. debug)", EventKind.Severity.DEBUG));

    private static final Preferences P = Preferences.userNodeForPackage(MonitorPrefs.class).node("eventMonitor");

    private final List<Runnable> listeners = new ArrayList<>();

    private boolean groupRuns      = P.getBoolean("groupRuns", true);
    private boolean collapseRepeats = P.getBoolean("collapseRepeats", true);
    private boolean highlightNew   = P.getBoolean("highlightNew", true);
    private boolean showDetail     = P.getBoolean("showDetail", true);
    private boolean showQueue      = P.getBoolean("showQueue", true);
    private ToastMode toastMode    = parseToast(P.get("toastMode", ToastMode.PROBLEMS.name()));
    private int rangeIndex         = clamp(P.getInt("range", 3), RANGES.size());
    private int levelIndex         = clamp(P.getInt("level", 0), LEVELS.size());

    public void addListener(Runnable r)    { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed()                 { for (Runnable r : new ArrayList<>(listeners)) r.run(); }

    public boolean groupRuns()       { return groupRuns; }
    public boolean collapseRepeats() { return collapseRepeats; }
    public boolean highlightNew()    { return highlightNew; }
    public boolean showDetail()      { return showDetail; }
    public boolean showQueue()       { return showQueue; }
    public ToastMode toastMode()     { return toastMode; }
    public Range range()             { return RANGES.get(rangeIndex); }
    public Level level()             { return LEVELS.get(levelIndex); }
    public int rangeIndex()          { return rangeIndex; }
    public int levelIndex()          { return levelIndex; }

    public void setGroupRuns(boolean v)       { if (v != groupRuns) { groupRuns = v; P.putBoolean("groupRuns", v); changed(); } }
    public void setCollapseRepeats(boolean v) { if (v != collapseRepeats) { collapseRepeats = v; P.putBoolean("collapseRepeats", v); changed(); } }
    public void setHighlightNew(boolean v)    { if (v != highlightNew) { highlightNew = v; P.putBoolean("highlightNew", v); changed(); } }
    public void setShowDetail(boolean v)      { if (v != showDetail) { showDetail = v; P.putBoolean("showDetail", v); changed(); } }
    public void setShowQueue(boolean v)       { if (v != showQueue) { showQueue = v; P.putBoolean("showQueue", v); changed(); } }
    public void setToastMode(ToastMode m)     { if (m != toastMode) { toastMode = m; P.put("toastMode", m.name()); changed(); } }
    public void setRangeIndex(int i)          { i = clamp(i, RANGES.size()); if (i != rangeIndex) { rangeIndex = i; P.putInt("range", i); changed(); } }
    public void setLevelIndex(int i)          { i = clamp(i, LEVELS.size()); if (i != levelIndex) { levelIndex = i; P.putInt("level", i); changed(); } }

    private static int clamp(int i, int size) { return Math.max(0, Math.min(size - 1, i)); }

    private static ToastMode parseToast(String s) {
        try { return ToastMode.valueOf(s); } catch (IllegalArgumentException e) { return ToastMode.PROBLEMS; }
    }
}
