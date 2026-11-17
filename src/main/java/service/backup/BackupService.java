package service.backup;

import model.Credential;
import model.ScheduledTask;
import service.TransferBatching;
import service.TransferPaths;
import service.transfer.ByteFormat;
import service.transfer.LocalFileOps;
import service.transfer.RemoteFileStat;
import service.transfer.CredentialLookup;
import service.transfer.RemoteDirs;
import service.transfer.SizedCommand;
import service.transfer.TransferCommand;
import service.transfer.TransferEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Retention-based archiving: files older than the retention window are moved, by calendar day, from
 * the source to the destination (either side local or remote, not both).
 *
 * <p>Knows nothing about SQLite, WinSCP or the scheduler. It asks for a credential by name, lists and
 * runs remote work through a {@link TransferEngine}, and delegates "run these commands in size-capped
 * batches" to a {@link BatchRunner}, which the transfer service shares with ordinary file transfers.
 */
public final class BackupService {

    /** Runs sized commands against one remote session per size-capped batch. Returns whether every batch succeeded. */
    @FunctionalInterface
    public interface BatchRunner {
        boolean run(Credential target, String password, List<SizedCommand> commands,
                    Consumer<String> logLine, String taskId) throws Exception;
    }

    /** The two batching limits, read on every run so a settings change applies without a restart. */
    public interface BatchSettings {
        long batchMaxBytes();
        int batchIntervalSeconds();
    }

    private final CredentialLookup credentials;
    private final TransferEngine engine;
    private final RemoteDirs remoteDirs;
    private final BatchRunner batchRunner;
    private final BatchSettings settings;

    public BackupService(CredentialLookup credentials, TransferEngine engine, RemoteDirs remoteDirs, BatchRunner batchRunner,
                         BatchSettings settings) {
        this.credentials = credentials;
        this.engine = engine;
        this.remoteDirs = remoteDirs;
        this.batchRunner = batchRunner;
        this.settings = settings;
    }

    // ─── Backup (retention-based archiving; local or remote, either side) ────
    //
    // "D" = today. backupRetentionDays files are KEPT in place at the source:
    // D, D-1, ..., D-(retentionDays-1). Everything older is a backup
    // candidate. Unlike previous versions, a run archives EVERY eligible file
    // in one go — there is no more day-bucket/"batch days" limit. A large
    // backlog is instead worked through using the same SIZE-based batching as
    // file transfers (settings.batchMaxBytes()), pausing
    // settings.batchIntervalSeconds() between batches, so a
    // huge one-time backlog doesn't hammer the link or block for a long
    // unbroken stretch.
    //
    // Either side (source or destination) may be LOCAL or REMOTE:
    //   - LOCAL  side: backupSource/DestinationUsername is blank; the path is
    //     a plain filesystem folder.
    //   - REMOTE side: backupSource/DestinationUsername is set, and the path
    //     is resolved over SFTP using that username's stored credential (the
    //     same creds_<username>.xml mechanism File Transfer tasks use).
    // Both sides being remote at once is not supported (would require a
    // double SFTP hop) and is rejected with a clear error.

    /** A backup-eligible file: its full identity/location, calendar day, and size in bytes. */
    private static final class BackupCandidate {
        final String name;
        final LocalDate day;
        final long size;
        final Path localPath;      // set when source is LOCAL
        final String remotePath;   // set when source is REMOTE (full remote path)
        BackupCandidate(String name, LocalDate day, long size, Path localPath, String remotePath) {
            this.name = name; this.day = day; this.size = size;
            this.localPath = localPath; this.remotePath = remotePath;
        }
    }

    /** Runs one backup pass for {@code task}. Returns false when anything failed (errors are logged). */
    public boolean run(ScheduledTask task, Consumer<String> logLine) {
        String sourcePathStr = task.getBackupSourcePath();
        String destPathStr   = task.getBackupDestinationPath();

        if (sourcePathStr == null || sourcePathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup source path is not configured.");
            return false;
        }
        if (destPathStr == null || destPathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup destination path is not configured.");
            return false;
        }

        boolean sourceRemote = task.getBackupSourceUsername() != null && !task.getBackupSourceUsername().trim().isEmpty();
        boolean destRemote   = task.getBackupDestinationUsername() != null && !task.getBackupDestinationUsername().trim().isEmpty();

        if (sourceRemote && destRemote) {
            logLine.accept("[ERROR] Backup source and destination cannot both be remote "
                    + "(remote\u2192remote backup is not supported). Make one side local.");
            return false;
        }

        Credential sourceCred = sourceRemote ? credentials.find(task.getBackupSourceUsername(), "source", logLine) : null;
        if (sourceRemote && sourceCred == null) return false;
        Credential destCred = destRemote ? credentials.find(task.getBackupDestinationUsername(), "destination", logLine) : null;
        if (destRemote && destCred == null) return false;

        int retentionDays = task.getBackupRetentionDays();
        if (retentionDays < 1) retentionDays = 1;

        LocalDate today = LocalDate.now();
        // Oldest day that is still KEPT in place, e.g. retentionDays=3 → keep D, D-1, D-2.
        LocalDate keepFromDay = today.minusDays(retentionDays - 1);

        logLine.accept("[INFO] Backup source      : " + sourcePathStr + (sourceRemote ? " (REMOTE, " + sourceCred.getHost() + ")" : " (local)"));
        logLine.accept("[INFO] Backup destination : " + destPathStr + (destRemote ? " (REMOTE, " + destCred.getHost() + ")" : " (local)"));
        logLine.accept("[INFO] Today (D)          : " + today);
        logLine.accept("[INFO] Retention          : keep " + retentionDays
                + " day(s) in place (" + keepFromDay + " .. " + today + ")");
        logLine.accept("[INFO] Batching           : entire backlog runs in one go, split into size-based "
                + "batches (~" + ByteFormat.humanReadable(settings.batchMaxBytes()) + " each, "
                + settings.batchIntervalSeconds() + "s between batches).");

        // ── Enumerate backup candidates from the source (local or remote) ─────
        List<BackupCandidate> candidates;
        try {
            candidates = sourceRemote
                    ? enumerateRemoteBackupCandidates(sourceCred, sourcePathStr, keepFromDay, logLine)
                    : enumerateLocalBackupCandidates(sourcePathStr, keepFromDay, logLine);
        } catch (Exception ex) {
            logLine.accept("[ERROR] Failed to list backup source: " + ex.getMessage());
            return false;
        }
        if (candidates == null) return false; // error already logged
        if (candidates.isEmpty()) {
            logLine.accept("[INFO] Nothing to back up — all files fall within the "
                    + retentionDays + "-day retention window.");
            return true;
        }

        long totalBytes = candidates.stream().mapToLong(c -> c.size).sum();
        java.util.Set<LocalDate> days = candidates.stream().map(c -> c.day).collect(Collectors.toCollection(java.util.TreeSet::new));
        logLine.accept("[INFO] " + candidates.size() + " file(s), " + ByteFormat.humanReadable(totalBytes)
                + " total, spanning " + days.size() + " day(s) older than the retention window.");

        // ── Move/transfer, in size-based batches ───────────────────────────────
        boolean ok;
        if (!sourceRemote && !destRemote) {
            ok = runLocalToLocalBackup(candidates, destPathStr, logLine);
        } else if (sourceRemote) {
            ok = runRemoteToLocalBackup(task.getId(), sourceCred, candidates, destPathStr, logLine);
        } else {
            ok = runLocalToRemoteBackup(task.getId(), destCred, candidates, destPathStr, logLine);
        }

        logLine.accept((ok ? "[SUCCESS] " : "[WARNING] ") + "Backup run complete.");
        return ok;
    }

    /** Local source: lists regular files older than the retention cutoff, grouped by calendar day. */
    private List<BackupCandidate> enumerateLocalBackupCandidates(
            String sourcePathStr, LocalDate keepFromDay, Consumer<String> logLine) {
        File sourceDir = new File(sourcePathStr);
        if (!sourceDir.isDirectory()) {
            logLine.accept("[ERROR] Backup source folder does not exist: " + sourcePathStr);
            return null;
        }
        File[] rawFiles = sourceDir.listFiles(File::isFile);
        List<BackupCandidate> out = new ArrayList<>();
        if (rawFiles == null) return out;
        for (File f : rawFiles) {
            LocalDate day = Instant.ofEpochMilli(f.lastModified())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (!day.isBefore(keepFromDay)) continue; // within retention window — leave in place
            out.add(new BackupCandidate(f.getName(), day, f.length(), f.toPath(), null));
        }
        return out;
    }

    /** Remote source: lists remote files (over SFTP) older than the retention cutoff, grouped by calendar day. */
    private List<BackupCandidate> enumerateRemoteBackupCandidates(
            Credential sourceCred, String sourcePathStr, LocalDate keepFromDay, Consumer<String> logLine) throws Exception {
        String remotePath = TransferPaths.normalizeRemotePath(sourcePathStr);
        String dirPath = TransferPaths.remoteListingDirFor(remotePath);
        java.util.Map<String, RemoteFileStat> stats =
                engine.listFiles(sourceCred, sourceCred.getPassword(), dirPath, logLine);
        List<BackupCandidate> out = new ArrayList<>();
        for (java.util.Map.Entry<String, RemoteFileStat> e : stats.entrySet()) {
            String fullPath = e.getKey();
            RemoteFileStat stat = e.getValue();
            LocalDate day = Instant.ofEpochMilli(stat.epochMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (!day.isBefore(keepFromDay)) continue;
            String fname = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            out.add(new BackupCandidate(fname, day, stat.size, null, fullPath));
        }
        return out;
    }

    /** Local→local backup: moves files into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runLocalToLocalBackup(List<BackupCandidate> candidates, String destPathStr, Consumer<String> logLine) {
        File destRoot = new File(destPathStr);
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            logLine.accept("[ERROR] Cannot create backup destination folder: " + destPathStr);
            return false;
        }
        List<List<BackupCandidate>> batches = TransferBatching.chunkBySize(candidates, c -> c.size, settings.batchMaxBytes());
        int intervalSeconds = settings.batchIntervalSeconds();
        if (batches.size() > 1) {
            logLine.accept("[INFO] Splitting into " + batches.size() + " batch(es).");
        }
        boolean allOk = true;
        int moved = 0;
        for (int i = 0; i < batches.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logLine.accept("[INFO] Backup cancelled.");
                return false;
            }
            List<BackupCandidate> batch = batches.get(i);
            if (batches.size() > 1) {
                logLine.accept("[INFO] Batch " + (i + 1) + "/" + batches.size() + " (" + batch.size() + " file(s))");
            }
            for (BackupCandidate c : batch) {
                File dayDestDir = new File(destRoot, c.day.toString());
                if (!dayDestDir.exists() && !dayDestDir.mkdirs()) {
                    logLine.accept("[ERROR] Cannot create backup folder for " + c.day + ": " + dayDestDir);
                    allOk = false;
                    continue;
                }
                boolean ok = LocalFileOps.moveReplacing(c.localPath, dayDestDir.toPath().resolve(c.name), logLine);
                allOk &= ok;
                if (ok) moved++;
            }
            if (i < batches.size() - 1 && intervalSeconds > 0) LocalFileOps.pauseBetweenBatches(intervalSeconds, logLine);
        }
        logLine.accept("[INFO] " + moved + " file(s) archived.");
        return allOk;
    }

    /** Remote source → local destination: pulls files with `get -delete` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runRemoteToLocalBackup(String taskId, Credential sourceCred, List<BackupCandidate> candidates,
            String destPathStr, Consumer<String> logLine) {
        File destRoot = new File(destPathStr);
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            logLine.accept("[ERROR] Cannot create backup destination folder: " + destPathStr);
            return false;
        }
        List<SizedCommand> commands = new ArrayList<>();
        java.util.Map<String, LocalDate> dayByRemotePath = new java.util.HashMap<>();
        for (BackupCandidate c : candidates) {
            File dayDestDir = new File(destRoot, c.day.toString());
            if (!dayDestDir.exists() && !dayDestDir.mkdirs()) {
                logLine.accept("[ERROR] Cannot create backup folder for " + c.day + ": " + dayDestDir);
                continue;
            }
            String destPath = TransferPaths.normalizeLocalPath(new File(dayDestDir, c.name).getAbsolutePath());
            commands.add(new SizedCommand(new TransferCommand.Download(c.remotePath, destPath, true), c.size));
        }
        try {
            boolean ok = batchRunner.run(sourceCred, sourceCred.getPassword(), commands, logLine, taskId);
            // Originals are removed by WinSCP itself ("get -delete"), file by file, in the same session as the download.
            if (ok) logLine.accept("[INFO] Removed " + candidates.size() + " original file(s) from the remote source in the same session.");
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Remote\u2192local backup failed: " + ex.getMessage());
            return false;
        }
    }

    /** Local source → remote destination: pushes files with `put` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runLocalToRemoteBackup(String taskId, Credential destCred, List<BackupCandidate> candidates,
            String destPathStr, Consumer<String> logLine) {
        String remoteRoot = TransferPaths.normalizeRemotePath(destPathStr);
        String remoteRootDir = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";

        // One mkdir per distinct day, de-duplicated, run best-effort first (a day
        // folder that already exists from a previous run is not an error).
        java.util.LinkedHashSet<String> dayDirs = new java.util.LinkedHashSet<>();
        for (BackupCandidate c : candidates) dayDirs.add(remoteRootDir + c.day);

        try {
            boolean mkdirOk = remoteDirs.ensure(destCred, dayDirs, logLine);
            if (!mkdirOk) {
                logLine.accept("[WARN] Some remote day folders may not have been created (they may already exist) — continuing.");
            }
            List<SizedCommand> putCommands = candidates.stream().map(c -> new SizedCommand(
                    new TransferCommand.Upload(TransferPaths.normalizeLocalPath(c.localPath.toAbsolutePath().toString()),
                            remoteRootDir + c.day + "/" + c.name), c.size))
                    .collect(Collectors.toList());
            boolean ok = batchRunner.run(destCred, destCred.getPassword(), putCommands, logLine, taskId);
            if (ok) {
                for (BackupCandidate c : candidates) {
                    try {
                        Files.delete(c.localPath);
                    } catch (IOException ex) {
                        logLine.accept("[WARN] Uploaded but could not remove local original " + c.name + ": " + ex.getMessage());
                    }
                }
            }
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Local\u2192remote backup failed: " + ex.getMessage());
            return false;
        }
    }

}
