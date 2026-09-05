package ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * (source/destination folder, file names, byte totals, batch/session/thread
 * counts) is already emitted, in a fixed and consistently-worded shape, by
 * {@code TransferService#logTransferPaths} and the {@code "[INFO] Transfer
 * summary: ..."} line every transfer path (watcher-triggered or not) now emits.
 * If that shape ever changes, only this one class needs updating.
 */
final class RunLogSummarizer {

    private RunLogSummarizer() {}

    /** Structured view of one FILE_TRANSFER run, or {@code null} if the log
     *  text doesn't look like a file-transfer run at all (e.g. captured before
     *  the transfer even started, or from a non-transfer task type). */
    record FileTransferSummary(
            String direction,           // "OUTBOUND" / "INBOUND", or null if not found
            String sourcePath,
            String destPath,
            List<String> fileNames,     // best-effort; empty for non-watcher batched transfers (not logged per-file)
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
    private static final Pattern QUEUED     = Pattern.compile("^\\[INFO\\] Queued (?:outbound|inbound): (.+?) \u2192 (.+)$");
    private static final Pattern SUMMARY    = Pattern.compile(
            "^\\[INFO\\] Transfer summary: (\\d+) file\\(s\\), (.+?) total, (\\d+) batch\\(es\\), "
                    + "(\\d+) session\\(s\\), (\\d+) worker thread\\(s\\)\\.$");

    static FileTransferSummary parse(String detailsText) {
        if (detailsText == null || detailsText.isBlank()) return null;

        String direction = null, sourcePath = null, destPath = null, totalBytesFormatted = null;
        int fileCount = 0, batchCount = 0, sessionCount = 0, workerThreads = 0;
        Set<String> fileNames = new LinkedHashSet<>();

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
            } else if ((m = QUEUED.matcher(line)).matches()) {
                String from = m.group(1);
                int cut = Math.max(from.lastIndexOf('/'), from.lastIndexOf('\\'));
                fileNames.add(cut >= 0 ? from.substring(cut + 1) : from);
            } else if ((m = SUMMARY.matcher(line)).matches()) {
                fileCount = Integer.parseInt(m.group(1));
                totalBytesFormatted = m.group(2).trim();
                batchCount = Integer.parseInt(m.group(3));
                sessionCount = Integer.parseInt(m.group(4));
                workerThreads = Integer.parseInt(m.group(5));
            }
        }

        if (direction == null && sourcePath == null && destPath == null) return null;
        if (fileCount == 0 && !fileNames.isEmpty()) fileCount = fileNames.size();

        return new FileTransferSummary(direction, sourcePath, destPath, new ArrayList<>(fileNames),
                fileCount, totalBytesFormatted, batchCount, sessionCount, workerThreads);
    }
}
