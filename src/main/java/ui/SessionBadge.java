package ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the small SFTP-session badge in the baseline strip says. Kept free of Swing so the wording can be tested alone.
 *
 * <p>While sessions are open it shows how many ("SFTP ● 2 open"); otherwise it shows how many sessions the last run used,
 * read from that run's "Transfer summary" log line ("SFTP · last run: 3 sessions"); if there is neither it says none are open.
 */
final class SessionBadge {

    enum Kind {LIVE, LAST, IDLE}

    record Look(String text, Kind kind, String tooltip) {
    }

    private static final Pattern SUMMARY = Pattern.compile(
            "Transfer summary: (\\d+) file\\(s\\), .+? total, (\\d+) batch\\(es\\), (\\d+) session\\(s\\)");

    private SessionBadge() {
    }

    /**
     * Sessions and batches of the newest "Transfer summary" line in {@code logText}, as {sessions, batches}; null if there is none.
     */
    static int[] lastRun(String logText) {
        if (logText == null || logText.isEmpty()) return null;
        Matcher m = SUMMARY.matcher(logText);
        int[] found = null;
        while (m.find()) {
            try {
                found = new int[]{Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2))};
            } catch (NumberFormatException ignored) { /* absurdly long number: keep the previous match */ }
        }
        return found;
    }

    /**
     * @param openNow         SFTP sessions open for the selected task right now
     * @param lastRunSessions sessions the last run used, or -1 when unknown
     * @param lastRunBatches  batches the last run was split into, or -1 when unknown
     */
    static Look of(int openNow, int lastRunSessions, int lastRunBatches) {
        String last = lastRunSessions >= 0
                ? "Last run used " + plural(lastRunSessions, "session") + (lastRunBatches >= 0 ? " for " + plural(lastRunBatches, "batch") : "") + "."
                : "No run summary in the log yet.";
        if (openNow > 0) {
            return new Look("SFTP ● " + openNow + " open", Kind.LIVE,
                    plural(openNow, "SFTP session") + " open for this task right now. " + last);
        }
        if (lastRunSessions >= 0) {
            return new Look("SFTP · last run: " + plural(lastRunSessions, "session"), Kind.LAST,
                    "No SFTP session is open for this task right now. " + last);
        }
        return new Look("SFTP · none open", Kind.IDLE, "No SFTP session is open for this task right now. " + last);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : (word.endsWith("ch") ? "es" : "s"));
    }
}
