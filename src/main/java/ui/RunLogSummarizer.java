package ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the free-text run log captured in {@link model.TaskRunRecord#getDetails()}
 * — the exact lines {@code service.TransferService} emitted for one run — into
 * structured fields for the Event Monitor's per-event detail popup
 * ({@link ActivityEventPopup}).
 *
 * <p>Deliberately reads the log text rather than requiring new database columns
 * or a new stats-plumbing path through the scheduler: every field surfaced here
 * (source/destination folder, file names + sizes, byte totals, batch/session/
 * thread counts) is already emitted, in a fixed and consistently-worded shape,
 * by {@code TransferService#logTransferPaths}, the per-file "Watcher found ..."
 * detection listing, and the {@code "[INFO] Transfer summary: ..."} line every
 * transfer path (watcher-triggered or not) now emits. If that shape ever
 * changes, only this one class needs updating.
 */
final class RunLogSummarizer {

    private RunLogSummarizer() {}

    /** One file this run touched — the name it was detected/queued under, and
     *  its size formatted the same way the rest of the app formats sizes
     *  (e.g. "4.2 MB"), or {@code null} if a size was never captured for it. */
    record FileEntry(String name, String sizeFormatted) {}

    /** Structured view of one FILE_TRANSFER run, or {@code null} if the log
     *  text doesn't look like a file-transfer run at all (e.g. captured before
     *  the transfer even started, or from a non-transfer task type). */
    record FileTransferSummary(
            String direction,           // "OUTBOUND" / "INBOUND", or null if not found
            String sourcePath,
            String destPath,
            List<FileEntry> files,      // best-effort; empty for non-watcher batched transfers (not logged per-file)
            int fileCount,
            String totalBytesFormatted, // e.g. "4.2 MB"; null if not found
            int batchCount,
            int sessionCount,
            int workerThreads) {}

    private static final Pattern DIRECTION  = Pattern.compile("^\\[INFO\\] Transfer direction : (\\w+)$");
    private static final Pattern LOCAL_SRC  = Pattern.compile("^\\[INFO\\] Local source path\\s*: (.+)$");
    private static final Pattern REMOTE_TGT = Pattern.compile("^\\[INFO\\] Remote target path\\s*: (.+)$");
    private static final Pattern REMOTE_SRC = Pattern.compile("^\\[INFO\\] Remote/source path\\s*: (.+)$");
    private static final Pattern LOCAL_DST  = Pattern.compile("^\\[INFO\\] Local destination\\s*: (.+)$");
    // "Watcher found N new/updated file(s):" detection listing — the actual
    // moment a file was detected, before any transfer attempt; raw byte size.
    private static final Pattern DETECTED    = Pattern.compile(
            "^\\[INFO\\]\\s{2,}(.+?) \\| lastModified=(.+?) \\| size=(\\d+)$");
    // "Queued outbound/inbound: local → remote (123ms, 4.2 MB)" — logged when
    // the transfer actually starts moving that file; size already formatted.
    private static final Pattern QUEUED     = Pattern.compile(
            "^\\[INFO\\] Queued (?:outbound|inbound): (.+?) \u2192 (.+?)(?: \\(\\d+ms, (.+?)\\))?$");
    private static final Pattern SUMMARY    = Pattern.compile(
            "^\\[INFO\\] Transfer summary: (\\d+) file\\(s\\), (.+?) total, (\\d+) batch\\(es\\), "
                    + "(\\d+) session\\(s\\), (\\d+) worker thread\\(s\\)\\.$");

    static FileTransferSummary parse(String detailsText) {
        if (detailsText == null || detailsText.isBlank()) return null;

        String direction = null, sourcePath = null, destPath = null, totalBytesFormatted = null;
        int fileCount = 0, batchCount = 0, sessionCount = 0, workerThreads = 0;
        Set<String> orderedNames = new LinkedHashSet<>();
        // Raw byte size from the detection listing, keyed by file name — the
        // most precise size available; used unless only the (already-
        // formatted) Queued-line size is available for a file.
        Map<String, Long> rawSizeByName = new LinkedHashMap<>();
        Map<String, String> formattedSizeByName = new LinkedHashMap<>();

        for (String raw : detailsText.split("\\R")) {
            String line = raw.strip();
            Matcher m;
            if ((m = DIRECTION.matcher(line)).matches()) {
                direction = m.group(1);
            } else if ((m = LOCAL_SRC.matcher(line)).matches()) {
                sourcePath = m.group(1);
            } else if ((m = REMOTE_TGT.matcher(line)).matches()) {
                destPath = m.group(1);
            } else if ((m = REMOTE_SRC.matcher(line)).matches()) {
                sourcePath = m.group(1);
            } else if ((m = LOCAL_DST.matcher(line)).matches()) {
                destPath = m.group(1);
            } else if ((m = DETECTED.matcher(line)).matches()) {
                try {
                    rawSizeByName.put(m.group(1), Long.parseLong(m.group(3)));
                } catch (NumberFormatException ignored) { /* not actually a size line — skip */ }
            } else if ((m = QUEUED.matcher(line)).matches()) {
                String from = m.group(1);
                int cut = Math.max(from.lastIndexOf('/'), from.lastIndexOf('\\'));
                String name = cut >= 0 ? from.substring(cut + 1) : from;
                orderedNames.add(name);
                if (m.group(3) != null) formattedSizeByName.put(name, m.group(3).trim());
            } else if ((m = SUMMARY.matcher(line)).matches()) {
                fileCount = Integer.parseInt(m.group(1));
                totalBytesFormatted = m.group(2).trim();
                batchCount = Integer.parseInt(m.group(3));
                sessionCount = Integer.parseInt(m.group(4));
                workerThreads = Integer.parseInt(m.group(5));
            }
        }

        // Detection listing may name files the Queued lines never reached
        // (e.g. the run failed partway through) — include those too, after
        // the ones actually queued, so nothing detected silently disappears.
        for (String detectedName : rawSizeByName.keySet()) orderedNames.add(detectedName);

        List<FileEntry> files = new ArrayList<>();
        for (String name : orderedNames) {
            String size;
            if (rawSizeByName.containsKey(name)) {
                size = formatBytes(rawSizeByName.get(name));
            } else {
                size = formattedSizeByName.get(name);
            }
            files.add(new FileEntry(name, size));
        }

        if (direction == null && sourcePath == null && destPath == null && files.isEmpty()) return null;
        if (fileCount == 0 && !files.isEmpty()) fileCount = files.size();

        return new FileTransferSummary(direction, sourcePath, destPath, files,
                fileCount, totalBytesFormatted, batchCount, sessionCount, workerThreads);
    }

    /** Matches the app's own byte-formatting convention closely enough for
     *  display purposes (binary/1024-based units, one decimal place). */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }
}
