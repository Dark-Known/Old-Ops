package service.watch;

import service.TaskSchedulerService.WatchMode;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The text of the watcher bar under the task list: what the task's baseline is and how the watch is
 * triggered. Pure string building; the panel decides where the facts come from and paints the result.
 */
public final class WatcherBarText {

    private static final String GREEN = "#2E7D32", AMBER = "#E65100", GREY = "#757575";

    private WatcherBarText() { }

    /**
     * The whole bar as HTML.
     *
     * @param epoch       last seen remote modification time, or {@code <= 0} when there is no baseline yet
     * @param size        last seen size in bytes, or {@code < 0} when the size was not tracked (legacy)
     * @param modeTagHtml what {@link #modeTag} returned, appended at the end
     */
    public static String bar(long epoch, long size, String modeTagHtml) {
        if (epoch <= 0) {
            return "<html><b style='color:" + GREY + "'>Watcher:</b> "
                    + "<i style='color:" + GREY + "'>no baseline stored - first run will always transfer.</i>"
                    + modeTagHtml + "</html>";
        }
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epoch));
        if (size < 0) {
            return String.format("<html><b style='color:" + AMBER + "'>Watcher baseline:</b> "
                    + "<i style='color:" + AMBER + "'>last seen %s &nbsp;|&nbsp; size: not tracked "
                    + "(reset baseline to fix)</i>%s</html>", date, modeTagHtml);
        }
        return String.format("<html><b style='color:" + GREEN + "'>Watcher baseline:</b> "
                + "<i style='color:" + GREEN + "'>last seen %s &nbsp;|&nbsp; size: %,d bytes</i>%s</html>",
                date, size, modeTagHtml);
    }

    /**
     * A trailing HTML fragment (no surrounding html tags) saying how the watch is triggered: an instant
     * OS-level directory watch, an instant remote push, or polling. Empty for modes that have nothing to say.
     *
     * @param fromDaemon true when the mode comes from the daemon's exported status, which adds a "daemon" marker
     */
    public static String modeTag(WatchMode mode, boolean fromDaemon) {
        String label, color;
        switch (mode) {
            case NATIVE_WATCH -> { label = "⚡ Live (native watch)"; color = GREEN; }
            case REMOTE_PUSH -> { label = "⚡ Live (remote push)"; color = GREEN; }
            case POLLING_ONLY_UNSUPPORTED -> { label = "Polling only — remote push unavailable on this host"; color = AMBER; }
            case POLLING_ONLY -> { label = "Polling only"; color = GREY; }
            default -> { return ""; }
        }
        if (fromDaemon) label += " • daemon";
        return "&nbsp;&nbsp;&nbsp;<b style='color:" + color + "'>" + label + "</b>";
    }

    /** Parses a mode name exported by another process; an unknown name (a newer version) means "nothing to say". */
    public static WatchMode parseMode(String name) {
        try {
            return WatchMode.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return WatchMode.NOT_APPLICABLE;
        }
    }
}
