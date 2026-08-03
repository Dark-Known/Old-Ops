package service;

import model.Credential;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.RemoteFileMetadataServiceFactory.ManagedMetadataService;
import util.MailFetchMode;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Executes file transfers via WinSCP's scripting interface (winscp.com).
 *
 * <h2>Watcher integration — both directions</h2>
 * When {@code task.isWatcherEnabled() == true} AND
 * {@code task.getTransferMode() == LATEST_ONLY}, the watcher path fires
 * regardless of transfer direction:
 *
 * <ul>
 *   <li><b>OUTBOUND LATEST_ONLY</b> — {@link LocalFileMetadataService} lists
 *       the <em>local</em> source directory and returns only files whose
 *       lastModified is newer than the stored epoch.  Those files are then
 *       pushed to the remote with WinSCP {@code put}.</li>
 *   <li><b>INBOUND LATEST_ONLY</b> — {@link SftpRemoteFileMetadataService}
 *       (or {@link LocalFileMetadataService} for local→local) lists the
 *       <em>remote</em> source directory and returns only new files.  Those
 *       files are pulled with WinSCP {@code get} (or {@link Files#copy} for
 *       local→local).</li>
 * </ul>
 *
 * <h2>Local→local mode (non-watcher)</h2>
 * When the task is INBOUND and the target host is local (blank / localhost /
 * 127.0.0.1 / local hostname), {@link #executeTransfer} bypasses WinSCP
 * entirely and routes directly to {@link #executeLocalCopy}.  All three
 * transfer modes are supported:
 * <ul>
 *   <li>ENTIRE_FOLDER — copies every regular file in the source directory.</li>
 *   <li>LATEST_ONLY   — copies only the file(s) with the newest lastModified
 *       timestamp.  (The watcher variant uses the stored baseline epoch; the
 *       non-watcher variant resolves the latest file at run-time.)</li>
 *   <li>SPECIFIC_FILE — copies the single file identified by
 *       {@code task.getTargetPath()}.</li>
 * </ul>
 *
 * In both cases an empty result throws {@link WatcherSkipException} so the
 * scheduler marks the run SKIPPED rather than FAILED.  On success the epoch
 * of the newest transferred file is persisted as the new baseline.
 */
public class TransferService {

    private static final String[] WINSCP_PATHS = {
            "C:\\Program Files (x86)\\WinSCP\\WinSCP.com",
            "C:\\Program Files\\WinSCP\\WinSCP.com",
            "winscp.com"
    };

    private final XmlStorageService storage;
    private final RemoteFileMetadataServiceFactory metadataServiceFactory;
    private String winScpPath;

    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public TransferService(XmlStorageService storage) {
        this.storage                = storage;
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath             = detectWinScp();
    }

    public void   setWinScpPath(String path) { this.winScpPath = path; }
    public String getWinScpPath()            { return winScpPath; }

    private String detectWinScp() {
        for (String p : WINSCP_PATHS) {
            if (new File(p).exists()) return p;
        }
        return WINSCP_PATHS[0];
    }

    // ─── Auto-detect source system ───────────────────────────────────────────

    public static String getLocalHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return System.getenv("COMPUTERNAME"); }
    }

    public static String getLocalUsername() {
        return System.getProperty("user.name", System.getenv("USERNAME"));
    }

    // ─── Main entry point ────────────────────────────────────────────────────

    public boolean executeTransfer(ScheduledTask task, Consumer<String> logLine)
            throws WatcherSkipException {

        if (task.getTransferDirection() == TransferDirection.LOCAL_TO_LOCAL) {
            logTransferPaths(task, null, logLine);
            if (task.isWatcherEnabled() && task.getTransferMode() == TransferMode.LATEST_ONLY) {
                return executeWatcherTransfer(task, null, logLine);
            }
            return executeLocalToLocalNonWatcher(task, logLine);
        }

        Credential target = resolveTargetCredential(task, logLine);
        logTransferPaths(task, target, logLine);

        // ── Watcher path: fires for BOTH directions when LATEST_ONLY + watcher enabled
        if (task.isWatcherEnabled()
                && task.getTransferMode() == TransferMode.LATEST_ONLY) {
            return executeWatcherTransfer(task, target, logLine);
        }

        // ── FIX A: Local→local non-watcher path ──────────────────────────────
        // When target credential is null (or resolves to the local machine),
        // the task is a local→local copy — route to executeLocalCopy instead
        // of falling through to WinSCP (which would crash with a null credential).
        if (isLocalToLocalTask(task, target)) {
            return executeLocalCopyNonWatcher(task, logLine);
        }

        // ── Non-watcher remote path ───────────────────────────────────────────
        if (target == null) {
            logLine.accept("[ERROR] No target credential resolved and task is not local→local.");
            return false;
        }

        File scriptFile = null;
        try {
            scriptFile = buildWinScpScript(target, target.getPassword(), task, logLine);
            logLine.accept("[INFO] WinSCP script prepared. Starting transfer...");
            return runWinScpScript(scriptFile, logLine, task.getId());
        } catch (Exception e) {
            logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
            return false;
        } finally {
            if (scriptFile != null) scriptFile.delete();
        }
    }

    // ─── Local→local detection ────────────────────────────────────────────────

    /**
     * Returns {@code true} when this task should use local filesystem copy
     * rather than WinSCP — i.e. credential is null OR the credential's host
     * resolves to the local machine.
     */
    private boolean isLocalToLocalTask(ScheduledTask task, Credential target) {
        if (task.getTransferDirection() == TransferDirection.LOCAL_TO_LOCAL) return true;
        if (task.getTransferDirection() != TransferDirection.INBOUND) return false;
        if (target == null) return true;
        String host = target.getHost();
        if (host == null || host.isEmpty()) return true;
        String localHost = getLocalHostname();
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || (localHost != null && localHost.equalsIgnoreCase(host));
    }

    // ─── Local→local copy — non-watcher entry point ──────────────────────────

    /**
     * Handles all three transfer modes for INBOUND local→local when the watcher
     * is NOT enabled (or mode is not LATEST_ONLY).
     *
     * <p>INBOUND path semantics:
     * <ul>
     *   <li>{@code task.getTargetPath()} — the watch/source folder (files come from here)</li>
     *   <li>{@code task.getSourcePath()} — the local destination folder</li>
     * </ul>
     */
    private boolean executeLocalCopyNonWatcher(ScheduledTask task, Consumer<String> logLine) {
        Path srcDir  = Paths.get(task.getTargetPath());   // watch folder
        Path destDir = Paths.get(task.getSourcePath());   // local destination

        logLine.accept("[INFO] Local→local copy (non-watcher)");
        logLine.accept("[INFO]   Source      : " + srcDir);
        logLine.accept("[INFO]   Destination : " + destDir);
        logLine.accept("[INFO]   Mode        : " + task.getTransferMode());

        if (!Files.isDirectory(srcDir)) {
            logLine.accept("[ERROR] Source directory does not exist: " + srcDir);
            return false;
        }

        try {
            Files.createDirectories(destDir);
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot create destination directory: " + ex.getMessage());
            return false;
        }

        TransferMode mode = task.getTransferMode() != null
                ? task.getTransferMode() : TransferMode.ENTIRE_FOLDER;

        switch (mode) {

            case SPECIFIC_FILE: {
                // targetPath is the full path to the specific file
                Path src  = Paths.get(task.getTargetPath());
                Path dest = destDir.resolve(src.getFileName());
                return copySingleFile(src, dest, logLine);
            }

            case LATEST_ONLY: {
                // Resolve the newest file at run-time (no stored baseline used here)
                List<Path> latest = resolveLatestLocalFiles(srcDir, logLine);
                if (latest.isEmpty()) return false;
                boolean ok = true;
                for (Path src : latest) {
                    ok &= copySingleFile(src, destDir.resolve(src.getFileName()), logLine);
                }
                return ok;
            }

            case ENTIRE_FOLDER:
            default: {
                return copyEntireFolder(srcDir, destDir, logLine);
            }
        }
    }

    private boolean executeLocalToLocalNonWatcher(ScheduledTask task, Consumer<String> logLine) {
        Path srcDir  = Paths.get(task.getSourcePath());   // local source
        Path destDir = Paths.get(task.getTargetPath());   // local destination

        logLine.accept("[INFO] Local→local transfer (non-watcher)");
        logLine.accept("[INFO]   Source      : " + srcDir);
        logLine.accept("[INFO]   Destination : " + destDir);
        logLine.accept("[INFO]   Mode        : " + task.getTransferMode());

        if (!Files.isDirectory(srcDir)) {
            logLine.accept("[ERROR] Source directory does not exist: " + srcDir);
            return false;
        }

        try {
            Files.createDirectories(destDir);
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot create destination directory: " + ex.getMessage());
            return false;
        }

        TransferMode mode = task.getTransferMode() != null
                ? task.getTransferMode() : TransferMode.ENTIRE_FOLDER;

        switch (mode) {
            case SPECIFIC_FILE: {
                Path src  = Paths.get(task.getSourcePath());
                Path dest = destDir.resolve(src.getFileName());
                return copySingleFile(src, dest, logLine);
            }
            case LATEST_ONLY: {
                List<Path> latest = resolveLatestLocalFiles(srcDir, logLine);
                if (latest.isEmpty()) return false;
                boolean ok = true;
                for (Path src : latest) {
                    ok &= copySingleFile(src, destDir.resolve(src.getFileName()), logLine);
                }
                return ok;
            }
            case ENTIRE_FOLDER:
            default: {
                return copyEntireFolder(srcDir, destDir, logLine);
            }
        }
    }

    /**
     * Returns the file(s) in {@code dir} whose lastModified timestamp equals
     * the maximum lastModified of all regular files in the directory.
     */
    private List<Path> resolveLatestLocalFiles(Path dir, Consumer<String> logLine) {
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) all.add(p);
            }
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot list source directory: " + ex.getMessage());
            return Collections.emptyList();
        }

        if (all.isEmpty()) {
            logLine.accept("[WARN] Source directory is empty: " + dir);
            return Collections.emptyList();
        }

        long maxTs = all.stream()
                .mapToLong(p -> p.toFile().lastModified())
                .max().orElse(0L);

        List<Path> latest = all.stream()
                .filter(p -> p.toFile().lastModified() == maxTs)
                .collect(Collectors.toList());

        logLine.accept("[INFO] LATEST_ONLY resolved " + latest.size()
                + " file(s) with timestamp " + Instant.ofEpochMilli(maxTs));
        return latest;
    }

    /** Copies every regular file (non-recursive) from {@code srcDir} to {@code destDir}. */
    private boolean copyEntireFolder(Path srcDir, Path destDir, Consumer<String> logLine) {
        boolean allOk = true;
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(srcDir)) {
            for (Path entry : ds) {
                if (!Files.isRegularFile(entry)) continue;
                Path dest = destDir.resolve(entry.getFileName());
                allOk &= copySingleFile(entry, dest, logLine);
                count++;
            }
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot list source directory: " + ex.getMessage());
            return false;
        }
        if (count == 0) {
            logLine.accept("[WARN] Source directory contained no regular files: " + srcDir);
        } else if (allOk) {
            logLine.accept("[SUCCESS] Local copy completed (" + count + " file(s)).");
        }
        return allOk;
    }

    /** Copies a single file, replacing the destination if it already exists. */
    private boolean copySingleFile(Path src, Path dest, Consumer<String> logLine) {
        try {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            logLine.accept("[SUCCESS] Copied: " + src + " → " + dest);
            return true;
        } catch (IOException ex) {
            logLine.accept("[ERROR] Failed to copy " + src.getFileName() + ": " + ex.getMessage());
            return false;
        }
    }

    // ─── Watcher transfer — unified for INBOUND and OUTBOUND ─────────────────

    /**
     * Single watcher path used by both directions.
     *
     * <p><b>OUTBOUND:</b> uses {@link LocalFileMetadataService} to list the
     * local source directory.  The watch directory is {@code task.getSourcePath()}.
     * New files are pushed via WinSCP {@code put}.
     *
     * <p><b>INBOUND:</b> uses {@link SftpRemoteFileMetadataService} (or
     * {@link LocalFileMetadataService} for local→local) to list the remote
     * source directory.  The watch directory is {@code task.getTargetPath()}
     * (the remote side).  New files are pulled via WinSCP {@code get} or
     * copied locally.
     *
     * <p>In both cases an empty result throws {@link WatcherSkipException}.
     * On success the newest file's epoch and size are persisted.
     */
    private boolean executeWatcherTransfer(ScheduledTask task,
                                           Credential target,
                                           Consumer<String> logLine)
            throws WatcherSkipException {

        boolean isOutbound = task.getTransferDirection() == TransferDirection.OUTBOUND;

        long lastKnownEpochMillis = task.getLastKnownRemoteFileEpoch();
        Instant modifiedAfter = lastKnownEpochMillis > 0
                ? Instant.ofEpochMilli(lastKnownEpochMillis)
                : Instant.EPOCH;

        logLine.accept("[INFO] Watcher enabled (LATEST_ONLY, "
                + (isOutbound ? "OUTBOUND" : "INBOUND")
                + "). Querying files modified after: " + modifiedAfter
                + " (epoch=" + lastKnownEpochMillis + ")");

        List<RemoteFileMetadata> newFiles;
        boolean isLocalToLocal;

        if (isOutbound) {
            // OUTBOUND: always scan the LOCAL source directory
            String watchDir = resolveOutboundWatchDirectory(task);
            logLine.accept("[INFO] Metadata service: LOCAL | watch directory: " + watchDir);

            LocalFileMetadataService localService = new LocalFileMetadataService();
            try {
                if (lastKnownEpochMillis > 0 && task.getLastKnownRemoteFileSize() >= 0) {
                    Instant modifiedAfterInclusive = Instant.ofEpochMilli(lastKnownEpochMillis - 1);
                    List<RemoteFileMetadata> candidates =
                            localService.getFilesModifiedAfter(watchDir, modifiedAfterInclusive);
                    long baselineSize = task.getLastKnownRemoteFileSize();
                    newFiles = candidates.stream()
                            .filter(f -> f.lastModified().isAfter(modifiedAfter)
                                    || (f.lastModified().equals(modifiedAfter) && f.size() != baselineSize))
                            .collect(Collectors.toList());
                } else {
                    newFiles = localService.getFilesModifiedAfter(watchDir, modifiedAfter);
                }
            } catch (RemoteFileException ex) {
                logLine.accept("[ERROR] Failed to list local source: " + ex.getMessage());
                return false;
            }

            if (lastKnownEpochMillis <= 0 && newFiles != null && !newFiles.isEmpty()) {
                long maxTs = newFiles.stream()
                        .mapToLong(f -> f.lastModified().toEpochMilli()).max().orElse(0L);
                List<RemoteFileMetadata> latestOnly = newFiles.stream()
                        .filter(f -> f.lastModified().toEpochMilli() == maxTs)
                        .collect(Collectors.toList());
                logLine.accept("[INFO] Initial run: reduced watcher candidates to "
                        + latestOnly.size() + " latest file(s) (ts=" + Instant.ofEpochMilli(maxTs) + ")");
                newFiles = latestOnly;
            }

            isLocalToLocal = false; // outbound always uses WinSCP to push

        } else {
            // INBOUND: scan remote (or local-to-local) source directory
            try (ManagedMetadataService managed =
                         metadataServiceFactory.create(task, target)) {

                isLocalToLocal = (managed.sshSession() == null);
                String watchDir = managed.watchDirectory();

//                // Temporary SFTP path probe (only for real SFTP connections)
//                if (!isLocalToLocal) {
//                    probeWindowsRemotePaths(managed.sshSession(), watchDir, logLine);
//                }

                logLine.accept("[INFO] Metadata service: "
                        + (isLocalToLocal ? "LOCAL" : "SFTP")
                        + " | watch directory: " + watchDir);

                if (lastKnownEpochMillis > 0 && task.getLastKnownRemoteFileSize() >= 0) {
                    Instant modifiedAfterInclusive = Instant.ofEpochMilli(lastKnownEpochMillis - 1);
                    List<RemoteFileMetadata> candidates =
                            managed.service().getFilesModifiedAfter(watchDir, modifiedAfterInclusive);
                    long baselineSize = task.getLastKnownRemoteFileSize();
                    newFiles = candidates.stream()
                            .filter(f -> f.lastModified().isAfter(modifiedAfter)
                                    || (f.lastModified().equals(modifiedAfter) && f.size() != baselineSize))
                            .collect(Collectors.toList());
                } else {
                    newFiles = managed.service().getFilesModifiedAfter(watchDir, modifiedAfter);
                }

            } catch (RemoteFileException ex) {
                logLine.accept("[ERROR] Failed to query file metadata: " + ex.getMessage());
                return false;
            } catch (Exception ex) {
                logLine.accept("[ERROR] Unexpected error opening metadata service: " + ex.getMessage());
                return false;
            }

            if (lastKnownEpochMillis <= 0 && newFiles != null && !newFiles.isEmpty()) {
                long maxTs = newFiles.stream()
                        .mapToLong(f -> f.lastModified().toEpochMilli()).max().orElse(0L);
                List<RemoteFileMetadata> latestOnly = newFiles.stream()
                        .filter(f -> f.lastModified().toEpochMilli() == maxTs)
                        .collect(Collectors.toList());
                logLine.accept("[INFO] Initial run: reduced watcher candidates to "
                        + latestOnly.size() + " latest file(s) (ts=" + Instant.ofEpochMilli(maxTs) + ")");
                newFiles = latestOnly;
            }
        }

        // ── Skip check ────────────────────────────────────────────────────────
        if (newFiles.isEmpty()) {
            String skipMsg = "Watcher skipped: no files modified after "
                    + modifiedAfter + " found in watch directory.";
            logLine.accept("[INFO] " + skipMsg);
            throw new WatcherSkipException(skipMsg);
        }

        logLine.accept("[INFO] Watcher found " + newFiles.size() + " new/updated file(s):");
        newFiles.forEach(f -> logLine.accept("[INFO]   " + f.fileName()
                + " | lastModified=" + f.lastModified()
                + " | size=" + f.size()));

        RemoteFileMetadata newest = newFiles.stream()
                .max((a, b) -> a.lastModified().compareTo(b.lastModified()))
                .orElse(null);
        if (newest == null) {
            String skipMsg = "Watcher skipped: no files to process after filtering.";
            logLine.accept("[INFO] " + skipMsg);
            throw new WatcherSkipException(skipMsg);
        }

        // ── Transfer ──────────────────────────────────────────────────────────
        boolean success;
        if (isOutbound) {
            success = executeWinScpWatcherOutbound(task, target, newFiles, logLine);
        } else if (isLocalToLocal) {
            success = executeLocalCopy(task, newFiles, logLine);
        } else {
            success = executeWinScpWatcherInbound(task, target, newFiles, logLine);
        }

        // ── Persist new baseline ──────────────────────────────────────────────
        if (success) {
            long newEpoch = newest.lastModified().toEpochMilli();
            long countWithSameTs = newFiles.stream()
                    .filter(f -> f.lastModified().toEpochMilli() == newEpoch)
                    .count();
            long newSize = countWithSameTs > 1 ? -1L : newest.size();

            task.setLastKnownRemoteFileEpoch(newEpoch);
            task.setLastKnownRemoteFileSize(newSize);
            storage.saveTask(task);
            String readableTime = Instant.ofEpochMilli(newEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logLine.accept("[INFO] Watcher baseline updated → epoch=" + newEpoch
                    + " (" + readableTime + " local)"
                    + ", size=" + newSize
                    + " (newest file: " + newest.fileName() + ")"
                    + " | task saved, ID=" + task.getId());
        }

        return success;
    }

    private void probeWindowsRemotePaths(com.jcraft.jsch.Session session,
                                         String rawPath,
                                         Consumer<String> logLine) {
        try {
            com.jcraft.jsch.ChannelSftp probe =
                    (com.jcraft.jsch.ChannelSftp) session.openChannel("sftp");
            probe.connect();

            String base        = rawPath.replace("\\", "/").replaceAll("/+$", "");
            String noLead      = base.replaceFirst("^/", "");
            String asIs        = base;
            String withSlash   = "/" + noLead;
            String noDrive     = base.replaceFirst("^[A-Za-z]:/", "/");
            String cygwin      = base.replaceFirst("^([A-Za-z]):/", "/$1/");
            String doubleSlash = "//" + noLead;

            logLine.accept("[PROBE] pwd = " + probe.pwd());

            for (String candidate : List.of(asIs, withSlash, noDrive, cygwin, doubleSlash, "/")) {
                try {
                    com.jcraft.jsch.SftpATTRS attrs = probe.stat(candidate);
                    logLine.accept("[PROBE] SUCCESS : '" + candidate
                            + "' | isDir=" + attrs.isDir());
                } catch (com.jcraft.jsch.SftpException e) {
                    logLine.accept("[PROBE] FAILED  : '" + candidate
                            + "' | code=" + e.id + " | " + e.getMessage());
                }
            }

            probe.disconnect();

        } catch (Exception e) {
            logLine.accept("[PROBE] Could not open probe channel: " + e.getMessage());
        }
    }

    // ─── Outbound watcher watch-directory resolution ──────────────────────────

    private String resolveOutboundWatchDirectory(ScheduledTask task) {
        String path = task.getSourcePath();
        if (path == null) return ".";
        if (path.endsWith("*")) {
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (lastSep >= 0) path = path.substring(0, lastSep);
        }
        File f = new File(path);
        if (f.isFile()) return f.getParent() != null ? f.getParent() : ".";
        return path;
    }

    // ─── WinSCP outbound watcher transfer ────────────────────────────────────

    private boolean executeWinScpWatcherOutbound(ScheduledTask task,
                                                 Credential target,
                                                 List<RemoteFileMetadata> files,
                                                 Consumer<String> logLine) {
        if (target == null) {
            logLine.accept("[ERROR] No target credential for outbound transfer.");
            return false;
        }

        String localSourceDir = resolveOutboundWatchDirectory(task);
        String remotePath     = normalizeRemotePath(task.getTargetPath());
        String remoteDir      = remotePath.endsWith("/") ? remotePath : remotePath + "/";

        File scriptFile = null;
        try {
            scriptFile = File.createTempFile("opstool_watcher_out_", ".txt");
            secureTemp(scriptFile);

            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch abort");
                pw.println("option confirm off");
                pw.println("open sftp://"
                        + escapeUrl(target.getUsername()) + ":"
                        + escapeUrl(target.getPassword()) + "@"
                        + target.getHost() + "/ -hostkey=\"*\"");

                for (RemoteFileMetadata meta : files) {
                    String localFile  = normalizeLocalPath(
                            Paths.get(localSourceDir, meta.fileName()).toString());
                    String remoteFile = remoteDir + meta.fileName();
                    pw.println("put "
                            + escapeWinScpPath(localFile)
                            + " " + escapeWinScpRemotePath(remoteFile));
                    logLine.accept("[INFO] Queued outbound: " + localFile + " → " + remoteFile);
                }

                pw.println("close");
                pw.println("exit");
            }

            logLine.accept("[INFO] WinSCP outbound watcher script prepared ("
                    + files.size() + " file(s)). Starting transfer...");
            return runWinScpScript(scriptFile, logLine, task.getId());

        } catch (Exception ex) {
            logLine.accept("[ERROR] Outbound watcher WinSCP transfer failed: " + ex.getMessage());
            return false;
        } finally {
            if (scriptFile != null) scriptFile.delete();
        }
    }

    // ─── WinSCP inbound watcher transfer ─────────────────────────────────────

    private boolean executeWinScpWatcherInbound(ScheduledTask task,
                                                Credential target,
                                                List<RemoteFileMetadata> files,
                                                Consumer<String> logLine) {
        String localDestDir = normalizeLocalPath(task.getSourcePath());
        String remotePath   = normalizeRemotePath(task.getTargetPath());
        String remoteDir    = remotePath.endsWith("/") ? remotePath : remotePath + "/";

        File scriptFile = null;
        try {
            scriptFile = File.createTempFile("opstool_watcher_in_", ".txt");
            secureTemp(scriptFile);

            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch abort");
                pw.println("option confirm off");
                pw.println("open sftp://"
                        + escapeUrl(target.getUsername()) + ":"
                        + escapeUrl(target.getPassword()) + "@"
                        + target.getHost() + "/ -hostkey=\"*\"");

                for (RemoteFileMetadata meta : files) {
                    String remoteFile = remoteDir + meta.fileName();
                    String destPath   = buildLocalDestinationPath(localDestDir, meta.fileName());
                    pw.println("get "
                            + escapeWinScpRemotePath(remoteFile)
                            + " " + escapeWinScpPath(destPath));
                    logLine.accept("[INFO] Queued inbound: " + remoteFile + " → " + destPath);
                }

                pw.println("close");
                pw.println("exit");
            }

            logLine.accept("[INFO] WinSCP inbound watcher script prepared ("
                    + files.size() + " file(s)). Starting transfer...");
            return runWinScpScript(scriptFile, logLine, task.getId());

        } catch (Exception ex) {
            logLine.accept("[ERROR] Inbound watcher WinSCP transfer failed: " + ex.getMessage());
            return false;
        } finally {
            if (scriptFile != null) scriptFile.delete();
        }
    }

    // ─── Local→local copy — watcher path (LATEST_ONLY + watcher enabled) ─────

    /**
     * Called by the watcher path for INBOUND local→local transfers.
     * The {@code files} list is already filtered by the watcher baseline.
     *
     * INBOUND semantics:
     *   targetPath = watch folder (source)
     *   sourcePath = local destination
     */
    private boolean executeLocalCopy(ScheduledTask task,
                                     List<RemoteFileMetadata> files,
                                     Consumer<String> logLine) {
        Path srcDir  = Paths.get(task.getTargetPath());   // watch folder (source)
        Path destDir = Paths.get(task.getSourcePath());   // local destination

        if (!Files.isDirectory(destDir)) {
            try {
                Files.createDirectories(destDir);
                logLine.accept("[INFO] Created destination directory: " + destDir);
            } catch (IOException ex) {
                logLine.accept("[ERROR] Cannot create destination directory '"
                        + destDir + "': " + ex.getMessage());
                return false;
            }
        }

        boolean allOk = true;
        for (RemoteFileMetadata meta : files) {
            Path src  = srcDir.resolve(meta.fileName());
            Path dest = destDir.resolve(meta.fileName());
            allOk &= copySingleFile(src, dest, logLine);
        }

        if (allOk) {
            logLine.accept("[SUCCESS] Local copy completed (" + files.size() + " file(s)).");
        }
        return allOk;
    }

    // ─── WinSCP script builder (non-watcher remote paths) ────────────────────

    private File buildWinScpScript(Credential target, String password,
                                   ScheduledTask task,
                                   Consumer<String> logLine) throws Exception {

        File tmpScript = File.createTempFile("opstool_", ".txt");
        secureTemp(tmpScript);

        String localPath  = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());

        logLine.accept("[INFO] Target OS: " + target.getOsType() + " | Protocol: SFTP");
        logLine.accept("[INFO] Transfer mode: " + task.getTransferMode().name());

        try (PrintWriter pw = new PrintWriter(new FileWriter(tmpScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            pw.println("open sftp://" + escapeUrl(target.getUsername())
                    + ":" + escapeUrl(password) + "@" + target.getHost() + "/ -hostkey=\"*\"");

            boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;
            TransferMode mode = task.getTransferMode();

            if (mode == null) {
                logLine.accept("[WARNING] Transfer mode is null, defaulting to ENTIRE_FOLDER");
                mode = TransferMode.ENTIRE_FOLDER;
            }

            logLine.accept("[DEBUG] Transfer mode: " + mode.name()
                    + " | Direction: " + (inbound ? "INBOUND" : "OUTBOUND"));

            if (inbound) {
                switch (mode) {
                    case LATEST_ONLY: {
                        logLine.accept("[INFO] Mode: Latest file(s) — non-watcher (INBOUND)");
                        LatestRemoteFiles latest = resolveLatestRemoteFilesViaWinScp(
                                target, password, remotePath, logLine);
                        if (latest.files.isEmpty()) {
                            throw new IOException("No files found on remote path: " + remotePath);
                        }
                        for (String remoteFile : latest.files) {
                            String fname    = remoteFile.substring(remoteFile.lastIndexOf('/') + 1);
                            String destPath = buildLocalDestinationPath(localPath, fname);
                            pw.println("get " + escapeWinScpRemotePath(remoteFile)
                                    + " " + escapeWinScpPath(destPath));
                        }
                        break;
                    }
                    case SPECIFIC_FILE: {
                        logLine.accept("[INFO] Mode: Specific file (INBOUND)");
                        String fname    = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                        String destPath = buildLocalDestinationPath(localPath, fname);
                        pw.println("get " + escapeWinScpRemotePath(remotePath)
                                + " " + escapeWinScpPath(destPath));
                        break;
                    }
                    case ENTIRE_FOLDER:
                    default: {
                        logLine.accept("[INFO] Mode: Entire folder (INBOUND)");
                        if (remotePath.endsWith("*") || new File(task.getSourcePath()).isDirectory()) {
                            pw.println("synchronize local "
                                    + escapeWinScpPath(localPath)
                                    + " " + escapeWinScpRemotePath(remotePath));
                        } else {
                            String fname    = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                            String destPath = buildLocalDestinationPath(localPath, fname);
                            pw.println("get " + escapeWinScpRemotePath(remotePath)
                                    + " " + escapeWinScpPath(destPath));
                        }
                        break;
                    }
                }
            } else {
                switch (mode) {
                    case LATEST_ONLY: {
                        logLine.accept("[INFO] Mode: Latest file(s) (OUTBOUND, non-watcher)");
                        File sourceDir = new File(task.getSourcePath());
                        if (sourceDir.isDirectory()) {
                            File[] files = sourceDir.listFiles(File::isFile);
                            if (files == null || files.length == 0) {
                                throw new IOException("No files found in source folder: "
                                        + task.getSourcePath());
                            }
                            long maxTs = Arrays.stream(files).mapToLong(File::lastModified).max().getAsLong();
                            List<File> latestFiles = new ArrayList<>();
                            for (File f : files) if (f.lastModified() == maxTs) latestFiles.add(f);
                            logLine.accept("[INFO] Latest file(s) (timestamp=" + maxTs + ") count=" + latestFiles.size());
                            for (File latestFile : latestFiles) {
                                logLine.accept("[INFO] Latest file: " + latestFile.getAbsolutePath());
                                String remoteDestPath = prepareRemoteDestination(
                                        remotePath, latestFile.getName());
                                pw.println("put "
                                        + escapeWinScpPath(normalizeLocalPath(latestFile.getAbsolutePath()))
                                        + " " + escapeWinScpRemotePath(remoteDestPath));
                            }
                        } else {
                            String remoteDestPath = prepareRemoteDestination(
                                    remotePath, new File(task.getSourcePath()).getName());
                            pw.println("put "
                                    + escapeWinScpPath(normalizeLocalPath(task.getSourcePath()))
                                    + " " + escapeWinScpRemotePath(remoteDestPath));
                        }
                        break;
                    }
                    case SPECIFIC_FILE: {
                        logLine.accept("[INFO] Mode: Specific file (OUTBOUND)");
                        String remoteDestPath = prepareRemoteDestination(
                                remotePath, new File(localPath).getName());
                        pw.println("put "
                                + escapeWinScpPath(localPath)
                                + " " + escapeWinScpRemotePath(remoteDestPath));
                        break;
                    }
                    case ENTIRE_FOLDER:
                    default: {
                        logLine.accept("[INFO] Mode: Entire folder (OUTBOUND)");
                        if (localPath.endsWith("*") || new File(task.getSourcePath()).isDirectory()) {
                            pw.println("synchronize remote "
                                    + escapeWinScpPath(localPath)
                                    + " " + escapeWinScpRemotePath(remotePath));
                        } else {
                            String remoteDestPath = prepareRemoteDestination(
                                    remotePath, new File(localPath).getName());
                            pw.println("put "
                                    + escapeWinScpPath(localPath)
                                    + " " + escapeWinScpRemotePath(remoteDestPath));
                        }
                        break;
                    }
                }
            }

            pw.println("close");
            pw.println("exit");
        }
        return tmpScript;
    }

    // ─── Legacy WinSCP ls (non-watcher INBOUND LATEST_ONLY fallback) ─────────

    private static class LatestRemoteFiles {
        final List<String> files;
        final long latestEpochMillis;
        LatestRemoteFiles(List<String> files, long latestEpochMillis) {
            this.files             = files;
            this.latestEpochMillis = latestEpochMillis;
        }
    }

    private LatestRemoteFiles resolveLatestRemoteFilesViaWinScp(
            Credential target, String password, String remotePath,
            Consumer<String> logLine) throws Exception {

        String dirPath = remotePath.endsWith("*")
                ? remotePath.substring(0, remotePath.lastIndexOf('/') + 1)
                : remotePath.endsWith("/") ? remotePath : remotePath + "/";

        logLine.accept("[INFO] Listing remote directory (WinSCP ls): " + dirPath);

        File listScript = File.createTempFile("opstool_ls_", ".txt");
        secureTemp(listScript);

        try (PrintWriter pw = new PrintWriter(new FileWriter(listScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            pw.println("open sftp://" + escapeUrl(target.getUsername())
                    + ":" + escapeUrl(password) + "@" + target.getHost() + "/ -hostkey=\"*\"");
            pw.println("ls \"" + dirPath + "\"");
            pw.println("close");
            pw.println("exit");
        }

        List<String> rawLines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    winScpPath, "/script=" + listScript.getAbsolutePath(),
                    "/log=" + getTempLogPath(), "/loglevel=1");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine.accept("[LS] " + maskPasswords(line));
                    rawLines.add(line);
                }
            }
            proc.waitFor();
        } finally {
            listScript.delete();
        }

        java.util.Map<String, Integer> MONTH_MAP = new java.util.HashMap<>();
        MONTH_MAP.put("jan",1); MONTH_MAP.put("feb",2); MONTH_MAP.put("mar",3);
        MONTH_MAP.put("apr",4); MONTH_MAP.put("may",5); MONTH_MAP.put("jun",6);
        MONTH_MAP.put("jul",7); MONTH_MAP.put("aug",8); MONTH_MAP.put("sep",9);
        MONTH_MAP.put("oct",10);MONTH_MAP.put("nov",11);MONTH_MAP.put("dec",12);

        int currentYear = LocalDate.now().getYear();
        java.util.Map<String, Long> fileEpochs = new java.util.LinkedHashMap<>();

        for (String line : rawLines) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("-")) continue;
            String[] parts = t.split("\\s+", 10);
            if (parts.length < 9) continue;

            String monthStr   = parts[5].toLowerCase(java.util.Locale.ENGLISH);
            String dayStr     = parts[6].trim();
            String timeOrYear = parts[7];
            String fileName   = parts[8];

            Integer monthNum = MONTH_MAP.get(monthStr);
            if (monthNum == null) continue;
            int dayNum;
            try { dayNum = Integer.parseInt(dayStr); } catch (NumberFormatException e) { continue; }

            long epochMillis;
            try {
                if (timeOrYear.contains(":")) {
                    int colons = (int) timeOrYear.chars().filter(c -> c == ':').count();
                    if (colons == 2) {
                        if (parts.length < 10) continue;
                        int year = Integer.parseInt(parts[8]);
                        fileName = parts[9];
                        String[] tp = timeOrYear.split(":");
                        epochMillis = LocalDateTime.of(year, monthNum, dayNum,
                                        Integer.parseInt(tp[0]), Integer.parseInt(tp[1]),
                                        Integer.parseInt(tp[2]))
                                .toEpochSecond(ZoneOffset.UTC) * 1000L;
                    } else {
                        String[] tp = timeOrYear.split(":");
                        LocalDateTime ldt = LocalDateTime.of(currentYear, monthNum, dayNum,
                                Integer.parseInt(tp[0]), Integer.parseInt(tp[1]), 0);
                        if (ldt.isAfter(LocalDateTime.now())) ldt = ldt.minusYears(1);
                        epochMillis = ldt.toEpochSecond(ZoneOffset.UTC) * 1000L;
                    }
                } else {
                    LocalDate ld = LocalDate.of(Integer.parseInt(timeOrYear), monthNum, dayNum);
                    epochMillis = ld.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L;
                }
            } catch (Exception ex) { continue; }

            fileEpochs.put(dirPath + fileName, epochMillis);
        }

        if (fileEpochs.isEmpty()) return new LatestRemoteFiles(Collections.emptyList(), 0L);

        long maxTs = fileEpochs.values().stream().mapToLong(Long::longValue).max().getAsLong();
        List<String> latest = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : fileEpochs.entrySet()) {
            if (e.getValue() == maxTs) latest.add(e.getKey());
        }
        return new LatestRemoteFiles(latest, maxTs);
    }

    // ─── WinSCP process runner ────────────────────────────────────────────────

    private boolean runWinScpScript(File scriptFile, Consumer<String> logLine,
                                    String taskId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                winScpPath,
                "/script=" + scriptFile.getAbsolutePath(),
                "/log=" + getTempLogPath(),
                "/loglevel=1");
        pb.redirectErrorStream(true);
        Process proc = null;
        try {
            proc = pb.start();
            if (taskId != null) activeProcesses.put(taskId, proc);

            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine.accept(maskPasswords(line));
                    if (Thread.currentThread().isInterrupted()) {
                        try { proc.destroyForcibly(); } catch (Exception ignored) {}
                        throw new InterruptedException("Transfer cancelled");
                    }
                }
            }
            int exitCode = proc.waitFor();
            if (exitCode == 0) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            } else {
                logLine.accept("[ERROR] WinSCP exited with code: " + exitCode);
                return false;
            }
        } catch (InterruptedException ie) {
            logLine.accept("[INFO] Transfer interrupted: " + ie.getMessage());
            if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (taskId != null) activeProcesses.remove(taskId);
        }
    }

    // ─── Exception type ───────────────────────────────────────────────────────

    static class WatcherSkipException extends Exception {
        public WatcherSkipException(String message) { super(message); }
    }

    // ─── Service control ──────────────────────────────────────────────────────

    public boolean executeServiceAction(ScheduledTask task, Consumer<String> logLine) {
        Credential target = resolveTargetCredential(task, logLine);
        if (target == null) return false;

        String action;
        switch (task.getTaskType()) {
            case START_SERVICE:   action = "Start-Service";   break;
            case STOP_SERVICE:    action = "Stop-Service";    break;
            case RESTART_SERVICE: action = "Restart-Service"; break;
            default:
                logLine.accept("[ERROR] Unknown service action.");
                return false;
        }

        logLine.accept("[INFO] Executing " + action + " on " + target.getHost()
                + " for service: " + task.getServiceName());

        String psCmd = String.format(
                "$pw = ConvertTo-SecureString '%s' -AsPlainText -Force; " +
                        "$cred = New-Object System.Management.Automation.PSCredential('%s\\%s', $pw); " +
                        "Invoke-Command -ComputerName '%s' -Credential $cred -ScriptBlock { %s -Name '%s' -Force }",
                target.getPassword().replace("'", "''"),
                target.getHost(), target.getUsername().replace("'", "''"),
                target.getHost(), action,
                task.getServiceName().replace("'", "''")
        );

        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe",
                    "-NonInteractive", "-NoProfile", "-Command", psCmd);
            pb.redirectErrorStream(true);
            Process proc = null;
            try {
                proc = pb.start();
                if (task.getId() != null) activeProcesses.put(task.getId(), proc);
                try (BufferedReader br =
                             new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        logLine.accept(maskPasswords(line));
                        if (Thread.currentThread().isInterrupted()) {
                            try { proc.destroyForcibly(); } catch (Exception ignored) {}
                            throw new InterruptedException("Service action cancelled");
                        }
                    }
                }
                int exit = proc.waitFor();
                if (exit == 0) {
                    logLine.accept("[SUCCESS] Service action completed.");
                    return true;
                } else {
                    logLine.accept("[ERROR] PowerShell remoting failed (exit " + exit + "). "
                            + "Ensure WinRM is enabled on the target and firewall allows port 5985.");
                    return false;
                }
            } catch (InterruptedException ie) {
                logLine.accept("[INFO] Service action interrupted: " + ie.getMessage());
                if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (task.getId() != null) activeProcesses.remove(task.getId());
            }
        } catch (Exception e) {
            logLine.accept("[ERROR] Failed to invoke PowerShell: " + e.getMessage());
            return false;
        }
    }

    // ─── Mail / IMAP ──────────────────────────────────────────────────────────

    public boolean executeImapMailTask(ScheduledTask task, Consumer<String> logLine) {
        boolean isLocalOutlook = "LOCAL".equals(task.getMailOutlookLocation());
        String host, username, password;

        if (isLocalOutlook) {
            host     = getLocalHostname();
            username = task.getMailLocalUsername();
            password = task.getMailLocalPassword();
            if (username == null || username.trim().isEmpty()) {
                logLine.accept("[ERROR] Local Outlook username is required.");
                return false;
            }
            if (password == null || password.isEmpty()) {
                logLine.accept("[ERROR] Local Outlook password is required.");
                return false;
            }
            logLine.accept("[INFO] Using local Outlook IMAP via " + host + ":993 as " + username);
        } else {
            Credential target = resolveTargetCredential(task, logLine);
            if (target == null) return false;
            host     = target.getHost();
            username = target.getUsername();
            password = target.getPassword();
            if (host == null || host.isEmpty()) {
                logLine.accept("[ERROR] IMAP host is not configured for the selected credential.");
                return false;
            }
        }

        String folder   = nvl(task.getImapFolder(),         "INBOX");
        String criteria = nvl(task.getMailSearchCriteria(), "UNSEEN");
        MailFetchMode mode = task.getMailFetchMode() != null
                ? task.getMailFetchMode() : MailFetchMode.BODY_ONLY;

        logLine.accept("[INFO] Connecting to IMAP server " + host + ":993 as " + username);
        try (SSLSocket socket = createSslSocket(host, 993)) {
            BufferedInputStream bis    = new BufferedInputStream(socket.getInputStream());
            BufferedWriter      writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            String greet = readImapLine(bis);
            logLine.accept("[DEBUG] IMAP greeting: " + greet);
            if (greet == null || !greet.contains("OK")) {
                logLine.accept("[ERROR] Unexpected IMAP greeting."); return false;
            }

            if (sendImapCommand(writer, bis,
                    "A001 LOGIN " + quoteImapString(username) + " " + quoteImapString(password),
                    "A001", logLine) == null) return false;
            if (sendImapCommand(writer, bis,
                    "A002 SELECT " + quoteImapString(folder), "A002", logLine) == null) return false;

            logLine.accept("[INFO] Searching folder " + folder + " with criteria: " + criteria);
            List<String> searchResponse = sendImapCommand(writer, bis,
                    "A003 SEARCH " + criteria, "A003", logLine);
            if (searchResponse == null) return false;

            List<Integer> ids = parseSearchIds(searchResponse);
            if (ids.isEmpty()) {
                logLine.accept("[INFO] No messages matched the search criteria.");
                return true;
            }

            int fetchCount = Math.min(ids.size(), 5);
            List<Integer> toFetch = ids.subList(ids.size() - fetchCount, ids.size());
            for (int id : toFetch) {
                String fetchCmd;
                switch (mode) {
                    case FULL_MESSAGE:     fetchCmd = "A004 FETCH " + id + " BODY.PEEK[]"; break;
                    case HEADERS_AND_BODY: fetchCmd = "A004 FETCH " + id + " (BODY.PEEK[HEADER] BODY.PEEK[TEXT])"; break;
                    default:               fetchCmd = "A004 FETCH " + id + " BODY.PEEK[TEXT]"; break;
                }
                logLine.accept("[INFO] Fetching mail ID " + id + " (" + mode.name() + ")");
                List<String> fetchResponse = sendImapCommand(writer, bis, fetchCmd, "A004", logLine);
                if (fetchResponse == null) return false;
                String payload = extractImapPayload(fetchResponse, mode);
                if (payload.isEmpty()) {
                    logLine.accept("[WARN] No payload returned for message " + id);
                } else {
                    logLine.accept("[MAIL MESSAGE " + id + "]");
                    for (String ln : payload.split("\r?\n")) logLine.accept(ln);
                    logLine.accept("[END MAIL MESSAGE " + id + "]");
                }
            }
            sendImapCommand(writer, bis, "A005 LOGOUT", "A005", logLine);
            return true;
        } catch (Exception e) {
            logLine.accept("[ERROR] IMAP task failed: " + e.getMessage());
            return false;
        }
    }

    // ─── IMAP helpers ─────────────────────────────────────────────────────────

    private SSLSocket createSslSocket(String host, int port) throws IOException {
        SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(host, port);
        socket.setSoTimeout(30000);
        socket.startHandshake();
        return socket;
    }

    private List<String> sendImapCommand(BufferedWriter writer, BufferedInputStream bis,
                                         String command, String tag,
                                         Consumer<String> logLine) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        List<String> response = readImapResponse(bis, tag);
        boolean ok = response.stream().anyMatch(ln -> ln.startsWith(tag + " OK"));
        response.forEach(ln -> logLine.accept("[DEBUG] " + ln));
        if (!ok) { logLine.accept("[ERROR] IMAP command failed: " + command); return null; }
        return response;
    }

    private List<String> readImapResponse(BufferedInputStream bis, String tag) throws IOException {
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readImapLine(bis);
            if (line == null) break;
            lines.add(line);
            int literal = parseLiteralLength(line);
            if (literal > 0) lines.add(readImapLiteral(bis, literal));
            if (line.startsWith(tag + " ")) break;
        }
        return lines;
    }

    private String readImapLine(BufferedInputStream bis) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = bis.read()) != -1) {
            if (b == '\r') {
                int next = bis.read();
                if (next == '\n' || next == -1) break;
                buf.write(b); buf.write(next);
            } else { buf.write(b); }
        }
        if (buf.size() == 0 && b == -1) return null;
        return buf.toString("UTF-8");
    }

    private int parseLiteralLength(String line) {
        int idx = line.lastIndexOf('{'), end = line.lastIndexOf('}');
        if (idx < 0 || end < idx) return 0;
        try { return Integer.parseInt(line.substring(idx + 1, end)); }
        catch (NumberFormatException e) { return 0; }
    }

    private String readImapLiteral(BufferedInputStream bis, int count) throws IOException {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int n = bis.read(buffer, read, count - read);
            if (n == -1) throw new IOException("Unexpected end of IMAP literal");
            read += n;
        }
        bis.mark(2);
        int c1 = bis.read(), c2 = bis.read();
        if (c1 != '\r' || c2 != '\n') bis.reset();
        return new String(buffer, "UTF-8");
    }

    private List<Integer> parseSearchIds(List<String> response) {
        for (String line : response) {
            if (line.startsWith("* SEARCH")) {
                List<Integer> ids = new ArrayList<>();
                String[] parts = line.split(" ");
                for (int i = 2; i < parts.length; i++) {
                    try { ids.add(Integer.parseInt(parts[i])); }
                    catch (NumberFormatException ignored) {}
                }
                return ids;
            }
        }
        return new ArrayList<>();
    }

    private String extractImapPayload(List<String> response, MailFetchMode mode) {
        StringBuilder result = new StringBuilder();
        if      (mode == MailFetchMode.FULL_MESSAGE)     result.append(extractFirstLiteral(response, "BODY[]"));
        else if (mode == MailFetchMode.HEADERS_AND_BODY) {
            String h = extractFirstLiteral(response, "BODY[HEADER]");
            String b = extractFirstLiteral(response, "BODY[TEXT]");
            if (!h.isEmpty()) result.append(h).append("\r\n\r\n");
            if (!b.isEmpty()) result.append(b);
        } else result.append(extractFirstLiteral(response, "BODY[TEXT]"));
        if (result.length() == 0) {
            for (String line : response) {
                if (!line.startsWith("*") && !line.startsWith("A"))
                    result.append(line).append("\r\n");
            }
        }
        return result.toString().trim();
    }

    private String extractFirstLiteral(List<String> response, String marker) {
        for (int i = 0; i < response.size() - 1; i++) {
            if (response.get(i).contains(marker)) {
                String next = response.get(i + 1);
                if (!next.startsWith("*") && !next.startsWith("A")) return next;
            }
        }
        return "";
    }

    private String quoteImapString(String value) {
        if (value == null) value = "";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ─── Cancellation ─────────────────────────────────────────────────────────

    public boolean cancelRunningTask(String taskId) {
        if (taskId == null) return false;
        Process p = activeProcesses.remove(taskId);
        if (p == null) return false;
        try { p.destroyForcibly(); return true; }
        catch (Exception e) { return false; }
    }

    // ─── Credential resolution ────────────────────────────────────────────────

    private Credential resolveTargetCredential(ScheduledTask task, Consumer<String> logLine) {
        String uname = task.getTargetUsername();
        if (uname != null && !uname.isEmpty()) {
            Credential c = storage.loadCredentialByUsername(uname);
            if (c != null) return c;
            logLine.accept("[ERROR] No credential file found for username '" + uname
                    + "'. Expected: " + storage.credFileForUser(uname).getName()
                    + " in " + storage.getDataDir().getAbsolutePath());
            return null;
        }
        if (task.getTargetCredentialId() != null && !task.getTargetCredentialId().isEmpty()) {
            return storage.loadAllCredentials().stream()
                    .filter(x -> x.getId().equals(task.getTargetCredentialId()))
                    .findFirst().orElse(null);
        }
        // No username and no credential ID — likely a local→local task; return null cleanly.
        return null;
    }

    // ─── Local file helpers ───────────────────────────────────────────────────

    private File findLatestFile(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }

    // ─── Path / escaping helpers ──────────────────────────────────────────────

    private String normalizeLocalPath(String path) {
        if (path == null) return "";
        return path.trim()
                .replace("/", "\\")
                .replaceAll("\\\\{2,}", "\\\\");
    }

    private String normalizeRemotePath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace("\\", "/");
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private String escapeWinScpPath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String escapeWinScpRemotePath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\"", "\\\"") + '"';
    }

    private String escapeUrl(String s) {
        return s.replace(";", "%3B").replace("@", "%40");
    }

    private String maskPasswords(String line)  { return line.replaceAll("(?i)(password[=:])\\S+", "$1****"); }
    private String getTempLogPath()            { return System.getProperty("java.io.tmpdir") + File.separator + "opstool_winscp.log"; }
    private String nvl(String s, String def)   { return (s != null && !s.isEmpty()) ? s : def; }

    private String buildLocalDestinationPath(String dir, String fileName) {
        return normalizeLocalPath(Paths.get(dir, fileName).toString());
    }

    private String prepareRemoteDestination(String remotePath, String fileName) {
        if (remotePath.endsWith("/") || remotePath.endsWith("\\")) return remotePath + fileName;
        String last = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath.contains("\\")
                ? remotePath.substring(remotePath.lastIndexOf('\\') + 1) : remotePath;
        return last.contains(".") ? remotePath : remotePath + "/" + fileName;
    }

    private void secureTemp(File f) {
        f.setReadable(false, false); f.setReadable(true, true);
        f.setWritable(false, false); f.setWritable(true, true);
    }

    // ─── FIX B: Null-safe logTransferPaths ───────────────────────────────────
    // Previously crashed with NPE when target == null on local→local tasks.

    private void logTransferPaths(ScheduledTask task, Credential target,
                                  Consumer<String> logLine) {
        logLine.accept("[INFO] Transfer direction : " + task.getTransferDirection().name());
        if (task.getTransferDirection() == TransferDirection.LOCAL_TO_LOCAL) {
            logLine.accept("[INFO] Local source path  : " + task.getSourcePath());
            logLine.accept("[INFO] Local target path  : " + task.getTargetPath());
            logLine.accept("[INFO] Transfer type     : local→local");
        } else if (task.getTransferDirection() == TransferDirection.INBOUND) {
            logLine.accept("[INFO] Remote/source path : " + task.getTargetPath());
            logLine.accept("[INFO] Local destination  : " + task.getSourcePath());
        } else {
            logLine.accept("[INFO] Local source path  : " + task.getSourcePath());
            logLine.accept("[INFO] Remote target path : " + task.getTargetPath());
        }
    }
}