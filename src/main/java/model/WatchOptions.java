package model;

/**
 * What a "Watch a folder" task reports: files that appear and/or files whose size or modified time changes.
 * Stored on the task as text ({@code new=1;changed=1}). The file name rules, the "settled" wait and the
 * "no new file for N minutes" alert come from the task's File Selection.
 */
public record WatchOptions(boolean onNew, boolean onChanged) {

    public static final WatchOptions DEFAULT = new WatchOptions(true, true);

    public static WatchOptions parse(String text) {
        if (text == null || text.isBlank()) return DEFAULT;
        boolean n = true, c = true;
        for (String part : text.split(";")) {
            String p = part.trim();
            if (p.startsWith("new=")) n = p.endsWith("1");
            else if (p.startsWith("changed=")) c = p.endsWith("1");
        }
        return new WatchOptions(n, c);
    }

    public String toText() { return "new=" + (onNew ? 1 : 0) + ";changed=" + (onChanged ? 1 : 0); }
}
