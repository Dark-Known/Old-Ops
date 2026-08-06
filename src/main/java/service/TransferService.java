package service;

import model.Credential;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.RemoteFileMetadataServiceFactory.ManagedMetadataService;
import util.MailFetchMode;

import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
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

    // Outlook mail is read via Microsoft Graph (see class docs on executeImapMailTask).
    private static final String GRAPH_MAIL_SCOPE = "https://graph.microsoft.com/Mail.ReadWrite offline_access";
    private final OAuth2TokenService oauthService = new OAuth2TokenService();
    private final GraphMailService graphMailService = new GraphMailService();

    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public TransferService(XmlStorageService storage) {
        this.storage                = storage;
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath             = detectWinScp();
    }

    /** Exposed so the UI's "Authorize Mailbox" flow can enroll without duplicating OAuth logic. */
    public OAuth2TokenService getOAuthService() { return oauthService; }

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

    // ─── Mail / Outlook (Microsoft Graph) ────────────────────────────────────
    //
    // Originally implemented as raw IMAP socket commands (LOGIN, SELECT,
    // SEARCH, FETCH). That approach stopped working for two independent
    // reasons on modern Microsoft 365 tenants:
    //   1. Microsoft disabled Basic Authentication for IMAP tenant-wide, so
    //      "LOGIN user pass" is rejected regardless of how correct the
    //      credentials are.
    //   2. Many tenants additionally block the IMAP protocol itself (along
    //      with POP/SMTP AUTH) via Conditional Access / authentication
    //      policies, allowing only "modern", HTTPS-based clients — which
    //      OAuth2 alone does not bypass, because the block happens at the
    //      protocol layer before authentication is evaluated.
    //
    // Microsoft Graph (see GraphMailService) is a plain HTTPS REST API — the
    // same path the Outlook web app and mobile apps use — so it is
    // unaffected by both restrictions. Auth uses the OAuth2 device-code grant
    // (see OAuth2TokenService): a person authorizes once via a browser, and
    // every scheduled run after that silently exchanges a cached refresh
    // token for a new access token with no further interaction.

    // ─── Mail / Outlook (Microsoft Graph) ────────────────────────────────────
    //
    // Originally implemented as raw IMAP socket commands (LOGIN, SELECT,
    // SEARCH, FETCH). That approach stopped working for two independent
    // reasons on modern Microsoft 365 tenants:
    //   1. Microsoft disabled Basic Authentication for IMAP tenant-wide, so
    //      "LOGIN user pass" is rejected regardless of how correct the
    //      credentials are.
    //   2. Many tenants additionally block the IMAP protocol itself (along
    //      with POP/SMTP AUTH) via Conditional Access / authentication
    //      policies, allowing only "modern", HTTPS-based clients — which
    //      OAuth2 alone does not bypass, because the block happens at the
    //      protocol layer before authentication is evaluated.
    //
    // Microsoft Graph (see GraphMailService) is a plain HTTPS REST API — the
    // same path the Outlook web app and mobile apps use — so it is
    // unaffected by both restrictions. Auth uses the OAuth2 device-code grant
    // (see OAuth2TokenService): a person authorizes once via a browser, and
    // every scheduled run after that silently exchanges a cached refresh
    // token for a new access token with no further interaction.
    //
    // ── Mail watcher ──────────────────────────────────────────────────────
    // Reuses the task-level watcherEnabled flag (also used by file-transfer
    // watchers — safe, since a task is only ever one TaskType). When enabled,
    // the configured Fetch Scope is overridden: every run fetches ALL messages
    // with receivedDateTime strictly after the last successful run's newest
    // processed message (capped by Max Messages as a safety limit), mirroring
    // the file-transfer watcher's epoch-baseline approach. An empty result
    // throws WatcherSkipException so the scheduler marks the run SKIPPED
    // rather than a bare (and slightly misleading) SUCCESS.
    //
    // ── Mark as read / move to folder ─────────────────────────────────────
    // Optional per-task post-processing, applied to each successfully fetched
    // message: mark as read (Graph PATCH), then optionally move to another
    // folder (Graph POST .../move — done *after* marking read, since a move
    // invalidates the original message ID). Both are best-effort per message:
    // one message's failure is logged and does not fail the whole run.

    public boolean executeImapMailTask(ScheduledTask task, Consumer<String> logLine)
            throws WatcherSkipException {
        String mailbox = nvl(task.getMailMailboxAddress(), "");
        if (mailbox.isEmpty()) {
            logLine.accept("[ERROR] Mailbox address is required for Outlook Mail tasks.");
            return false;
        }

        String tenantId = nvl(task.getMailTenantId(), "common");
        String clientId = nvl(task.getMailClientId(), "");
        if (clientId.isEmpty()) {
            logLine.accept("[ERROR] Azure AD Client ID is not configured on this task. "
                    + "Register a public-client app in Azure AD (see README) and set it "
                    + "in the Mail/Outlook tab, then click \"Authorize Mailbox\" once.");
            return false;
        }

        if (!oauthService.isEnrolled(mailbox)) {
            logLine.accept("[ERROR] Mailbox '" + mailbox + "' has not been authorized yet. "
                    + "Open this task in the editor and click \"Authorize Mailbox\" to complete "
                    + "one-time sign-in — after that this task runs unattended.");
            return false;
        }

        String outputFolder = nvl(task.getMailOutputFolder(), "");
        if (outputFolder.isEmpty()) {
            logLine.accept("[ERROR] Output Folder is not configured on this task — "
                    + "each fetched message is written there as a .RCV file.");
            return false;
        }
        Path outputDir = Paths.get(outputFolder);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot create/access Output Folder '" + outputFolder + "': " + ex.getMessage());
            return false;
        }

        String accessToken;
        try {
            accessToken = oauthService.getValidAccessToken(mailbox, tenantId, clientId, GRAPH_MAIL_SCOPE);
        } catch (IOException ex) {
            logLine.accept("[ERROR] OAuth2 token refresh failed: " + ex.getMessage());
            return false;
        }

        String folder   = nvl(task.getImapFolder(),         "INBOX");
        String criteria = nvl(task.getMailSearchCriteria(), "UNSEEN");
        MailFetchMode mode = task.getMailFetchMode() != null
                ? task.getMailFetchMode() : MailFetchMode.BODY_ONLY;

        boolean watcherOn = task.isWatcherEnabled();
        long afterEpoch = watcherOn ? task.getMailLastKnownEpoch() : 0L;

        ScheduledTask.MailFetchScope scope = task.getMailFetchScope() != null
                ? task.getMailFetchScope() : ScheduledTask.MailFetchScope.LATEST_ONLY;
        int cap = Math.max(1, task.getMailMaxResults() > 0 ? task.getMailMaxResults() : 50);
        // Watcher mode always means "everything new since last run" (capped),
        // overriding LATEST_ONLY/ALL_MATCHING the same way the file-transfer
        // watcher overrides manual TransferMode selection while it's active.
        int maxResults = watcherOn ? cap
                : (scope == ScheduledTask.MailFetchScope.LATEST_ONLY ? 1 : cap);

        logLine.accept("[INFO] Reading mail via Microsoft Graph — mailbox: " + mailbox
                + " | folder: " + folder + " | criteria: " + criteria + " | mode: " + mode
                + (watcherOn
                        ? " | watcher: ON (after " + (afterEpoch > 0 ? Instant.ofEpochMilli(afterEpoch) : "epoch") + ", max " + maxResults + ")"
                        : " | scope: " + scope + (scope == ScheduledTask.MailFetchScope.ALL_MATCHING ? " (max " + maxResults + ")" : "")));

        List<GraphMailService.MailMessage> messages;
        try {
            messages = graphMailService.fetchMessages(
                    accessToken, folder, criteria, mode, maxResults, afterEpoch, logLine);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            logLine.accept("[ERROR] Graph mail task failed: " + ex.getMessage());
            return false;
        }

        if (messages.isEmpty()) {
            if (watcherOn) {
                String skipMsg = "Watcher skipped: no messages received after "
                        + (afterEpoch > 0 ? Instant.ofEpochMilli(afterEpoch) : "the configured baseline") + ".";
                logLine.accept("[INFO] " + skipMsg);
                throw new WatcherSkipException(skipMsg);
            }
            return true; // nothing matched — not a failure outside watcher mode
        }

        boolean markRead = task.isMailMarkAsRead();
        boolean moveEnabled = task.isMailMoveToFolderEnabled()
                && task.getMailMoveToFolderName() != null && !task.getMailMoveToFolderName().trim().isEmpty();

        long newestEpoch = 0L;
        for (GraphMailService.MailMessage m : messages) {
            logLine.accept("[MAIL MESSAGE] " + m.subject + " | from=" + m.from
                    + " | received=" + m.receivedDateTime);
            if (m.bodyContent == null || m.bodyContent.isEmpty()) {
                logLine.accept("[WARN] No payload returned for message id=" + m.id);
            } else {
                for (String ln : m.bodyContent.split("\r?\n")) logLine.accept(ln);
            }
            logLine.accept("[END MAIL MESSAGE]");

            try {
                Path rcvFile = outputDir.resolve(buildRcvFileName(m));
                Files.write(rcvFile, buildRcvFileContent(m, mode).getBytes(StandardCharsets.UTF_8));
                logLine.accept("[INFO] Wrote " + rcvFile.getFileName());
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to write .RCV file for '" + m.subject + "': " + ex.getMessage());
            }

            try {
                long ts = Instant.parse(m.receivedDateTime).toEpochMilli();
                if (ts > newestEpoch) newestEpoch = ts;
            } catch (Exception ignored) {
                // Unparseable timestamp — skip baseline consideration for this message only.
            }

            // Mark-as-read must happen before move — moving invalidates the message ID.
            if (markRead) {
                try {
                    graphMailService.markAsRead(accessToken, m.id);
                    logLine.accept("[INFO] Marked as read: " + m.subject);
                } catch (Exception ex) {
                    logLine.accept("[WARN] Failed to mark message as read (" + m.subject + "): " + ex.getMessage());
                }
            }
            if (moveEnabled) {
                try {
                    graphMailService.moveMessage(accessToken, m.id, task.getMailMoveToFolderName(), logLine);
                    logLine.accept("[INFO] Moved to folder '" + task.getMailMoveToFolderName() + "': " + m.subject);
                } catch (Exception ex) {
                    logLine.accept("[WARN] Failed to move message (" + m.subject + "): " + ex.getMessage());
                }
            }
        }

        if (watcherOn && newestEpoch > 0) {
            task.setMailLastKnownEpoch(newestEpoch);
            storage.saveTask(task);
            String readable = Instant.ofEpochMilli(newestEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logLine.accept("[INFO] Mail watcher baseline updated \u2192 epoch=" + newestEpoch
                    + " (" + readable + " local) | task saved, ID=" + task.getId());
        }

        return true;
    }

    // ─── .RCV file output ─────────────────────────────────────────────────────

    /**
     * Builds a unique, filesystem-safe filename for a fetched message:
     * {@code <timestamp>_<sanitized subject>_<id suffix>.RCV}
     */
    private String buildRcvFileName(GraphMailService.MailMessage m) {
        String subject = (m.subject == null || m.subject.trim().isEmpty()) ? "no_subject" : m.subject.trim();
        String safeSubject = subject.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (safeSubject.length() > 60) safeSubject = safeSubject.substring(0, 60);

        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String idSuffix = (m.id != null && m.id.length() >= 6)
                ? m.id.substring(m.id.length() - 6)
                : String.format("%06d", Math.abs((long) (Math.random() * 1_000_000)));

        return ts + "_" + safeSubject + "_" + idSuffix + ".RCV";
    }

    /**
     * Builds the .RCV file content. FULL_MESSAGE mode already returns raw
     * RFC 2822 MIME (headers included) from Graph, so it's written as-is;
     * BODY_ONLY / HEADERS_AND_BODY only return the body, so a small metadata
     * header is prepended for context.
     */
    private String buildRcvFileContent(GraphMailService.MailMessage m, MailFetchMode mode) {
        if (mode == MailFetchMode.FULL_MESSAGE) {
            return m.bodyContent != null ? m.bodyContent : "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(m.subject != null ? m.subject : "").append("\r\n");
        sb.append("From: ").append(m.from != null ? m.from : "").append("\r\n");
        sb.append("Received: ").append(m.receivedDateTime != null ? m.receivedDateTime : "").append("\r\n");
        sb.append("\r\n");
        sb.append(m.bodyContent != null ? m.bodyContent : "");
        return sb.toString();
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