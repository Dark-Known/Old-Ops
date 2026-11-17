package service.exec;

import model.RemoteFileMetadata;
import model.ScheduledTask;
import service.TransferService;
import service.WatcherSkipException;

import java.time.LocalDateTime;
import java.util.List;

/** Runs {@link ScheduledTask.TaskType#FILE_TRANSFER} tasks (SFTP/local, scheduled or watcher-driven). */
public final class FileTransferExecutor implements TaskExecutor {

    /** Told which files a scan found, once the scan is done and before they are transferred. */
    @FunctionalInterface
    public interface DetectionRecorder {
        void record(ScheduledTask task, List<RemoteFileMetadata> files, LocalDateTime scanStartedAt);
    }

    private final TransferService transfers;
    private final DetectionRecorder detections;

    public FileTransferExecutor(TransferService transfers, DetectionRecorder detections) {
        this.transfers = transfers;
        this.detections = detections;
    }

    @Override
    public ScheduledTask.TaskType type() {
        return ScheduledTask.TaskType.FILE_TRANSFER;
    }

    @Override
    public TaskOutcome execute(RunContext ctx) throws Exception {
        ScheduledTask task = ctx.task();
        try {
            // Real wall-clock scan time, captured just before the scan/listing begins inside
            // executeTransfer, rather than inside the callback (which only fires once the scan
            // is already done and files are known) — otherwise the "detection" event would
            // always show a 0-duration scan no matter how long the listing actually took.
            final LocalDateTime scanStart = LocalDateTime.now();
            transfers.setDetectionCallback(files -> detections.record(task, files, scanStart));
            boolean success = transfers.executeTransfer(task, ctx.log(), ctx.changedFileNames());
            return success ? TaskOutcome.ok() : TaskOutcome.failure(null);
        } catch (WatcherSkipException e) {
            ctx.log().accept("[INFO] Inbound watcher skipped transfer: " + e.getMessage());
            return TaskOutcome.skip(e.getMessage());
        } finally {
            transfers.clearDetectionCallback();
        }
    }
}
