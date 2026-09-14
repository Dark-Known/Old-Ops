package ui;

import model.ScheduledTask;
import model.TaskRunRecord;
import service.RunHistoryService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Detail popup shown when the operator clicks a row in the Event Monitor's
 * "Events" table ({@link QueueMonitorView}) — a borderless {@link JWindow},
 * same "click outside to dismiss" pattern other lightweight popups in this
 * app use.
 *
 * <p>Looks up the matching {@link TaskRunRecord} by its exact database row
 * id (now carried directly on {@link QueueMonitorView.ActivityRow#runId()})
 * to get the full captured run log, then renders one of two shapes
 * depending on the task's type:
 * <ul>
 *   <li><b>FILE_TRANSFER</b> — parsed via {@link RunLogSummarizer}: files
 *       transferred, source/destination folder, total bytes, batch count,
 *       SFTP/WinSCP session count, worker threads consumed.</li>
 *   <li>Everything else (mail routing, backup, etc. — tasks whose job is
 *       essentially moving information between systems rather than files)
 *       — a plain summary: what happened, why it failed if it failed, and
 *       whether that failure is the kind that surfaces in the Notifications
 *       panel.</li>
 * </ul>
 */
final class ActivityEventPopup {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ActivityEventPopup() {}

    static void show(Component invoker, Point screenLocation, QueueMonitorView.ActivityRow row,
                      Map<String, ScheduledTask> byId, RunHistoryService runHistoryService) {
        JWindow popup = new JWindow(SwingUtilities.getWindowAncestor(invoker));
        popup.setType(Window.Type.POPUP);
        popup.setAlwaysOnTop(true);
        popup.setFocusableWindowState(true);

        boolean errored = row.errored();
        Color accent = errored ? new Color(0xC0392B) : new Color(0x2E7D32);

        ScheduledTask task = byId != null ? byId.get(row.taskId()) : null;
        TaskRunRecord run = runHistoryService == null ? null
                : row.source() == QueueMonitorView.RunSource.ACTIVITY
                        ? runHistoryService.getActivityEventById(row.runId())
                        : runHistoryService.getRunById(row.runId());

        // A detection/started activity row is still logged against a
        // FILE_TRANSFER task, so task.getTaskType() alone can't tell these
        // apart from a real completed transfer — without this check, a
        // detection row's own file-listing text (name/size per line, no
        // "Transfer summary: ..." line) was being fed through the same
        // RunLogSummarizer used for real completions, so both ended up
        // rendering the exact same shape of detail (files/bytes/batches/
        // sessions/threads) even though a detection event has no batches,
        // sessions, or worker threads to report — it's a scan, not a
        // transfer. See appendDetectionDetail below for what it shows
        // instead: scan duration up front, then the files found.
        boolean isDetectionRow = row.errorMessage() != null && row.errorMessage().startsWith("Detected ");
        boolean isStartedRow = row.errorMessage() != null && row.errorMessage().startsWith("Started \u2014");

        boolean isFileTransfer = !isDetectionRow && !isStartedRow
                && task != null && task.getTaskType() == ScheduledTask.TaskType.FILE_TRANSFER;
        RunLogSummarizer.FileTransferSummary transferSummary =
                isFileTransfer && run != null ? RunLogSummarizer.parse(run.getDetails()) : null;

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x757575), 1),
                new EmptyBorder(10, 12, 10, 12)));
        content.setPreferredSize(new Dimension(transferSummary != null ? 420 : 360, transferSummary != null ? 320 : 210));

        JLabel title = new JLabel(row.taskName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JLabel statusBadge = new JLabel(errored ? " \u26A0 Failed " : " \u2713 Succeeded ");
        statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 11f));
        statusBadge.setOpaque(true);
        statusBadge.setBackground(accent);
        statusBadge.setForeground(Color.WHITE);
        statusBadge.setBorder(new EmptyBorder(2, 6, 2, 6));

        Duration d = (row.startedAt() != null && row.finishedAt() != null)
                ? Duration.between(row.startedAt(), row.finishedAt()) : Duration.ZERO;

        StringBuilder sb = new StringBuilder();
        if (row.attempt() > 0) sb.append("Retry attempt: ").append(row.attempt()).append('\n');
        sb.append("Started:  ").append(row.startedAt() != null ? row.startedAt().format(TIME_FMT) : "—").append('\n');
        sb.append("Finished: ").append(row.finishedAt() != null ? row.finishedAt().format(TIME_FMT) : "—").append('\n');
        sb.append("Duration: ").append(QueueMonitorView.formatDuration(d)).append('\n');

        if (transferSummary != null) {
            appendFileTransferDetail(sb, transferSummary);
        } else if (isDetectionRow) {
            appendDetectionDetail(sb, run);
        } else {
            appendGenericDetail(sb, task, run, row, errored);
        }

        JTextArea detailArea = new JTextArea(sb.toString());
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(detailArea.getFont().deriveFont(Font.PLAIN, 11f));
        detailArea.setBackground(new Color(0xF5F5F5));
        detailArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> popup.dispose());

        JPanel header = new JPanel(new BorderLayout(4, 2));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(statusBadge);
        if (isFileTransfer) {
            JLabel typeTag = new JLabel("  File Transfer");
            typeTag.setFont(typeTag.getFont().deriveFont(Font.PLAIN, 11f));
            typeTag.setForeground(Color.GRAY);
            badgeRow.add(typeTag);
        } else if (task != null) {
            JLabel typeTag = new JLabel("  " + task.getTaskType().name());
            typeTag.setFont(typeTag.getFont().deriveFont(Font.PLAIN, 11f));
            typeTag.setForeground(Color.GRAY);
            badgeRow.add(typeTag);
        }
        header.add(badgeRow, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footer.setOpaque(false);
        footer.add(closeBtn);

        content.add(header, BorderLayout.NORTH);
        content.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);

        popup.getContentPane().add(content);
        popup.pack();

        Point loc = clampToScreen(screenLocation, popup.getSize(), invoker);
        popup.setLocation(loc);

        popup.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { popup.dispose(); }
        });
        content.registerKeyboardAction(e -> popup.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        popup.setVisible(true);
        popup.requestFocus();
    }

    /**
     * Detail for a detection/scan activity row — deliberately a different
     * shape than {@link #appendFileTransferDetail}: a detection has no
     * batches, WinSCP/SFTP sessions, or worker threads to report (that's
     * what the *eventual completion* row for the same run will show once
     * the transfer itself runs), just what the scan found and how long the
     * scan itself took. The "Started/Finished/Duration" lines already
     * printed above this (see the caller) are the scan's own timing —
     * {@link service.TaskSchedulerService#recordDetectionEvent} stamps the
     * real scan start time now rather than a synthetic zero-duration
     * timestamp, so that duration line is meaningful here.
     */
    private static void appendDetectionDetail(StringBuilder sb, TaskRunRecord run) {
        sb.append('\n').append("File scan \u2014 no transfer has run yet for these files.\n");
        if (run == null || run.getDetails() == null) return;

        List<String> files = new java.util.ArrayList<>();
        for (String line : run.getDetails().split("\n")) {
            String l = line.trim();
            if (!l.startsWith("[INFO]") || !l.contains("| size=")) continue;
            String rest = l.substring("[INFO]".length()).trim();
            int bar = rest.indexOf('|');
            String name = bar > 0 ? rest.substring(0, bar).trim() : rest;
            String sizePart = rest.substring(rest.indexOf("size=") + 5).trim();
            long sizeBytes;
            try { sizeBytes = Long.parseLong(sizePart); } catch (NumberFormatException nfe) { sizeBytes = -1; }
            files.add(name + (sizeBytes >= 0 ? "  \u2014  " + RunLogSummarizer.formatBytes(sizeBytes) : ""));
        }
        if (files.isEmpty()) return;

        sb.append('\n').append("File(s) found:\n");
        int shown = Math.min(files.size(), 15);
        for (int i = 0; i < shown; i++) {
            sb.append("  \u2022 ").append(files.get(i)).append('\n');
        }
        if (files.size() > shown) sb.append("  \u2026 and ").append(files.size() - shown).append(" more\n");
    }

    private static void appendFileTransferDetail(StringBuilder sb, RunLogSummarizer.FileTransferSummary s) {
        sb.append('\n');
        if (s.direction() != null) sb.append("Direction: ").append(s.direction()).append('\n');
        if (s.sourcePath() != null) sb.append("Source folder:      ").append(s.sourcePath()).append('\n');
        if (s.destPath() != null) sb.append("Destination folder: ").append(s.destPath()).append('\n');
        sb.append('\n');
        sb.append("Files transferred: ").append(s.fileCount()).append('\n');
        if (s.totalBytesFormatted() != null) sb.append("Total bytes:        ").append(s.totalBytesFormatted()).append('\n');
        sb.append("Batches:            ").append(s.batchCount() > 0 ? s.batchCount() : 1).append('\n');
        sb.append("WinSCP/SFTP sessions: ").append(s.sessionCount() > 0 ? s.sessionCount() : 1).append('\n');
        sb.append("Worker threads used: ").append(s.workerThreads() > 0 ? s.workerThreads() : 1).append('\n');

        List<RunLogSummarizer.FileEntry> files = s.files();
        if (!files.isEmpty()) {
            sb.append('\n').append("File(s) detected/transferred:\n");
            int shown = Math.min(files.size(), 15);
            for (int i = 0; i < shown; i++) {
                RunLogSummarizer.FileEntry f = files.get(i);
                sb.append("  \u2022 ").append(f.name());
                if (f.sizeFormatted() != null) sb.append("  —  ").append(f.sizeFormatted());
                sb.append('\n');
            }
            if (files.size() > shown) sb.append("  \u2026 and ").append(files.size() - shown).append(" more\n");
        }
    }

    private static void appendGenericDetail(StringBuilder sb, ScheduledTask task, TaskRunRecord run,
                                             QueueMonitorView.ActivityRow row, boolean errored) {
        sb.append('\n');
        if (run != null && run.getReason() != null && !run.getReason().isBlank()) {
            sb.append(errored ? "Failure reason:\n" : "Summary:\n").append(run.getReason()).append('\n');
        } else if (errored && row.errorMessage() != null && !row.errorMessage().isBlank()) {
            sb.append("Failure reason:\n").append(row.errorMessage()).append('\n');
        } else if (!errored) {
            sb.append("Completed successfully.\n");
        }

        if (errored) {
            sb.append('\n');
            if (task != null && task.getRetryCount() > 0) {
                sb.append("This failure schedules a retry and marks the task RETRYING — surfaced "
                        + "in the Notifications panel until it either succeeds or exhausts its retries.\n");
            } else {
                sb.append("This failure marks the task FAILED — surfaced in the Notifications panel "
                        + "until it's re-run or edited.\n");
            }
        }
    }

    private static Point clampToScreen(Point desired, Dimension size, Component invoker) {
        GraphicsConfiguration gc = invoker.getGraphicsConfiguration();
        Rectangle bounds = gc != null ? gc.getBounds() : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        int x = Math.min(desired.x, bounds.x + bounds.width - size.width);
        int y = Math.min(desired.y, bounds.y + bounds.height - size.height);
        return new Point(Math.max(bounds.x, x), Math.max(bounds.y, y));
    }
}
