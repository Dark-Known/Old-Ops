package service;

import model.Credential;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.RemoteFileMetadataServiceFactory.ManagedMetadataService;
import util.MailFetchMode;
import util.MiniJson;
import util.AppConfig;

import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final OAuth2TokenService oauthService;
    private final GraphMailService graphMailService = new GraphMailService();

    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public TransferService(XmlStorageService storage) {
        this.storage                = storage;
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath             = detectWinScp();
        // Shared dataDir-based path, NOT the per-user-home default — the
        // Daemon runs as SYSTEM (see app-config.xml <runAsSystem>) while the
        // GUI runs as the logged-in user, so a user-home-based token path
        // would put them in two different, mutually invisible directories.
        this.oauthService           = new OAuth2TokenService(OAuth2TokenService.sharedTokenDir(storage.getDataDir()));
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
//            logLine.accept("[DEBUG] OAuth token cache directory: "
//                    + OAuth2TokenService.sharedTokenDir(storage.getDataDir()).toAbsolutePath());
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
                Files.write(rcvFile, buildRcvFileContent(m, mode, logLine).getBytes(StandardCharsets.UTF_8));
                logLine.accept("[INFO] Wrote " + rcvFile.getFileName());
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to write .RCV file for '" + m.subject + "': " + ex.getMessage());
            }

            // Classify once and reuse for both attachment placement and the
            // mailbox folder move below, so the two stay consistent.
            String classification = classifyForFolderRouting(m);
            saveAttachmentsToDisk(m, outputDir, classification, logLine);

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

            // Manual "move to a configured folder" is gone — every processed
            // message is now routed automatically by content: LDM/PTM
            // messages go to their respective configured folder, anything
            // else goes to the configured "Others" folder.
            String destinationFolder = resolveMoveFolderName(classification);
            try {
                graphMailService.moveMessage(accessToken, m.id, destinationFolder, logLine);
                logLine.accept("[INFO] Moved to folder '" + destinationFolder + "': " + m.subject);
            } catch (Exception ex) {
                logLine.accept("[WARN] Failed to move message (" + m.subject + "): " + ex.getMessage());
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

    // ─── Attachment download ────────────────────────────────────────────────

    /**
     * Saves every downloadable (non-inline, file-type) attachment of a
     * message to disk under:
     *   <outputDir>/Attachments/<LDM|PTM|Others>/<message-scoped subfolder>/<original filename>
     * using the same LDM/PTM/Others classification that decides which
     * mailbox folder the message itself gets moved to, so attachments and
     * their parent message end up filed the same way. Each message gets its
     * own subfolder (named the same as its .RCV file, minus the extension)
     * so that attachments from different messages sharing a filename (e.g.
     * two "report.pdf") never collide or overwrite one another.
     */
    private void saveAttachmentsToDisk(GraphMailService.MailMessage m, Path outputDir,
                                        String classification, Consumer<String> logLine) {
        if (m.attachmentFiles == null || m.attachmentFiles.isEmpty()) return;

        String bucket = "LDM".equals(classification) ? "LDM"
                : "PTM".equals(classification) ? "PTM"
                : "Others";

        String messageFolderName = buildRcvFileName(m);
        int dot = messageFolderName.lastIndexOf('.');
        if (dot > 0) messageFolderName = messageFolderName.substring(0, dot);

        Path attachmentsDir = outputDir.resolve("Attachments").resolve(bucket).resolve(messageFolderName);
        try {
            Files.createDirectories(attachmentsDir);
        } catch (IOException ex) {
            logLine.accept("[WARN] Could not create attachments folder '" + attachmentsDir + "': " + ex.getMessage());
            return;
        }

        for (GraphMailService.AttachmentFile file : m.attachmentFiles) {
            String safeName = (file.name == null || file.name.trim().isEmpty() ? "attachment" : file.name.trim())
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest = attachmentsDir.resolve(safeName);
            // Guard against two attachments on the SAME message sharing a name.
            int suffix = 1;
            while (Files.exists(dest)) {
                int extDot = safeName.lastIndexOf('.');
                String base = extDot > 0 ? safeName.substring(0, extDot) : safeName;
                String ext  = extDot > 0 ? safeName.substring(extDot) : "";
                dest = attachmentsDir.resolve(base + "_" + (suffix++) + ext);
            }
            try {
                Files.write(dest, file.bytes);
                logLine.accept("[INFO] Saved attachment '" + file.name + "' -> " + dest);
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to save attachment '" + file.name + "': " + ex.getMessage());
            }
        }
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
     * RFC 2822 MIME (headers included) from Graph, so it's written as-is.
     *
     * Content source preference: a message's own (non-inline) attachment text
     * takes priority over its body when a parseable text attachment exists
     * (see GraphMailService#fetchTextAttachments) — the structured content
     * these tasks care about is more often carried in an attached .txt/.csv
     * than typed into the body. Falls back to the body when there's no
     * usable attachment.
     *
     * BODY_ONLY and HEADERS_AND_BODY previously produced identical output —
     * both prepended Subject/From/Received regardless of mode, so "BODY_ONLY"
     * never actually meant body-only. Fixed to a real three-tier distinction:
     *   BODY_ONLY        → the message content: HTML converted to plain text,
     *                       automated banners (Outlook "first contact" /
     *                       external-sender warnings) removed, truncated to
     *                       start at the LDM/MVT/PTM marker, then
     *                       signatures/sign-offs, footers/disclaimers, and
     *                       quoted reply history stripped out (best-effort).
     *                       When an LDM/MVT/PTM marker is found, a SITA-style
     *                       =HEADER/=PRIORITY/... block (see
     *                       buildSitaHeaderBlock) is prepended.
     *   HEADERS_AND_BODY → Subject/From/Received + the full content as plain
     *                       text, untouched (signatures/quotes kept, no SITA
     *                       header — that block is specific to BODY_ONLY).
     *   FULL_MESSAGE     → raw MIME from Graph, as before.
     */
    private String buildRcvFileContent(GraphMailService.MailMessage m, MailFetchMode mode, Consumer<String> logLine) {
        if (mode == MailFetchMode.FULL_MESSAGE) {
            // Raw MIME — left completely untouched. A blank line here is
            // structurally meaningful (it's what separates the MIME headers
            // from the body per RFC 2822), so blank-line removal must never
            // be applied to this branch.
            return m.bodyContent != null ? m.bodyContent : "";
        }

        boolean useAttachment = m.attachmentText != null && !m.attachmentText.trim().isEmpty();
        String sourceText = useAttachment ? m.attachmentText : toPlainText(m.bodyContent, m.bodyType);

        if (mode == MailFetchMode.BODY_ONLY) {
            String bannerStripped = removeAutomatedBanners(sourceText);
            MarkerMatch marker = findMessageTypeMarker(bannerStripped);
            String markerBody = marker != null ? bannerStripped.substring(marker.index) : bannerStripped;

            // For LDM/PTM, the literal marker is immediately followed by an
            // "=TEXT" tag line marking the start of the free-text content,
            // matching the =FIELD convention used by the rest of the header.
            if (marker != null && ("LDM".equals(marker.type) || "PTM".equals(marker.type))) {
                markerBody = marker.type + "\n=TEXT" + markerBody.substring(marker.type.length());
            }

            String messageBody = removeBlankLines(stripSignatureAndQuotedContent(markerBody));

            if (marker == null) {
                // Not an LDM/MVT/PTM message — no SITA header applies.
                return messageBody;
            }

            String originCode = null;
            String destinationCode = null;
            if ("LDM".equals(marker.type)) {
                originCode = extractLdmOriginCode(bannerStripped, messageBody);
                destinationCode = extractLdmDestinationCode(messageBody);
            } else if ("PTM".equals(marker.type)) {
                String[] od = extractPtmOriginDestination(messageBody);
                originCode = od[0];
                destinationCode = od[1];
            } else {
                // No extraction rule has been specified yet for MVT —
                // the configured default address is used for both fields.
                logLine.accept("[INFO] No origin/destination extraction rule configured for " + marker.type
                        + " messages yet — falling back to the configured default SITA address.");
            }

            String originAddress = resolveStationAddress(originCode, marker.type, "ORIGIN", logLine);
            String destinationAddress = resolveStationAddress(destinationCode, marker.type, "DESTINATION TYPE B", logLine);

            String header = buildSitaHeaderBlock(marker.type, originAddress, destinationAddress, nextMessageId());
            return header + messageBody;
        }

        // HEADERS_AND_BODY
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(m.subject != null ? m.subject : "").append("\r\n");
        sb.append("From: ").append(m.from != null ? m.from : "").append("\r\n");
        sb.append("Received: ").append(m.receivedDateTime != null ? m.receivedDateTime : "").append("\r\n");
        if (useAttachment && !m.attachmentNames.isEmpty()) {
            sb.append("Source: attachment (").append(String.join(", ", m.attachmentNames)).append(")\r\n");
        }
        sb.append("\r\n");
        sb.append(removeBlankLines(sourceText));
        return sb.toString();
    }

    // ─── SITA-style header block (LDM / MVT / PTM) ─────────────────────────

    private static final DateTimeFormatter RCV_HEADER_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * Builds the fixed-format routing header prepended to LDM/MVT/PTM
     * message content:
     *   =HEADER
     *   RCV,<yyyy/MM/dd HH:mm>
     *   =PRIORITY
     *   QU (LDM/MVT) or QN (PTM)
     *   =DESTINATION TYPE B
     *   STX,<destination SITA address>
     *   =ORIGIN
     *   <origin SITA address>
     *   =MSGID
     *   <5-digit sequential id>
     *   =SMI
     * The template is identical across all three message types — only the
     * field values differ. Missing origin/destination addresses (extraction
     * or lookup failure) are left blank rather than guessed; a WARN is
     * already logged by the caller in that case.
     */
    private String buildSitaHeaderBlock(String messageType, String originAddress,
                                         String destinationAddress, String msgId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=HEADER\n");
        sb.append("RCV,").append(LocalDateTime.now().format(RCV_HEADER_DATE_FMT)).append("\n");
        sb.append("=PRIORITY\n");
        sb.append("PTM".equals(messageType) ? "QN" : "QU").append("\n");
        sb.append("=DESTINATION TYPE B\n");
        sb.append("STX,").append(destinationAddress != null ? destinationAddress : "").append("\n");
        sb.append("=ORIGIN\n");
        sb.append(originAddress != null ? originAddress : "").append("\n");
        sb.append("=MSGID\n");
        sb.append(msgId).append("\n");
        sb.append("=SMI\n");
        return sb.toString();
    }

    // ─── LDM origin / destination extraction ───────────────────────────────

    // Second line of the raw message packet: a dot, a 7-letter SITA address
    // (3-letter station + 4-letter org code), a space, then a 6-digit
    // date-time group. Captures the 3-letter station code.
    private static final Pattern LDM_ORIGIN_SECOND_LINE =
            Pattern.compile("^\\.([A-Z]{3})[A-Z]{4}\\s+\\d{6}");
    // An isolated line of exactly 3 uppercase letters immediately followed by
    // a baggage (B) or cargo (C) distribution line starting with a dot.
    private static final Pattern LDM_ORIGIN_BEFORE_DISTRIBUTION =
            Pattern.compile("^([A-Z]{3})\\s*$\\R^(?:B|C)\\s+\\.", Pattern.MULTILINE);
    // First run of 3 capital letters right after a '-' on the same line.
    private static final Pattern LDM_DESTINATION_AFTER_DASH =
            Pattern.compile("-\\s*([A-Z]{3})");

    /**
     * Origin station code for an LDM message. Tries the "second line of the
     * packet" SITA-address convention first (against the untouched, pre-
     * truncation body, since that addressing line precedes the LDM marker);
     * falls back to the "isolated line before a B/C distribution line"
     * convention (against the LDM-truncated body) if the first doesn't match.
     */
    private String extractLdmOriginCode(String fullBodyText, String ldmBody) {
        if (fullBodyText != null) {
            String[] lines = fullBodyText.split("\n", -1);
            if (lines.length > 1) {
                Matcher m = LDM_ORIGIN_SECOND_LINE.matcher(lines[1].trim());
                if (m.find()) return m.group(1);
            }
        }
        if (ldmBody != null) {
            Matcher m = LDM_ORIGIN_BEFORE_DISTRIBUTION.matcher(ldmBody);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Destination Type B station code for an LDM message: only the first
     * line containing a '-' is checked (per spec) — if that line's dash
     * isn't immediately followed by 3 capital letters, extraction fails
     * rather than scanning further lines.
     */
    private String extractLdmDestinationCode(String ldmBody) {
        if (ldmBody == null) return null;
        for (String line : ldmBody.split("\n", -1)) {
            int dash = line.indexOf('-');
            if (dash < 0) continue;
            Matcher m = LDM_DESTINATION_AFTER_DASH.matcher(line.substring(dash));
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

    // ─── PTM origin / destination extraction ───────────────────────────────

    // Flight designator + departure date line, e.g. "SV753/06AUG HYDJED":
    // 2-3 char airline code + 1-4 digit flight number, "/", 2-digit day +
    // 3-letter month, then a run of 6 capital letters split into two 3-letter
    // station codes — origin (where these passengers boarded) then the
    // immediate/transit destination (where they disembark).
    private static final Pattern PTM_FLIGHT_ORIGIN_DEST =
            Pattern.compile("^[A-Z0-9]{2,3}\\d{1,4}/\\d{2}[A-Z]{3}\\s+([A-Z]{3})([A-Z]{3})", Pattern.MULTILINE);

    /** Returns {origin, destination} station codes for a PTM message, or {null, null} if not found. */
    private String[] extractPtmOriginDestination(String ptmBody) {
        if (ptmBody != null) {
            Matcher m = PTM_FLIGHT_ORIGIN_DEST.matcher(ptmBody);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
        }
        return new String[]{null, null};
    }

    // ─── Station code → full SITA address lookup (app-config.xml / JSON) ──

    private Map<String, Object> stationCodeCache;
    private String defaultStationAddressCache;
    private boolean defaultStationAddressLoaded = false;

    /**
     * Loads and caches the station-code → 7-character SITA address map from
     * the JSON file whose path is configured in app-config.xml under
     * <sitaMessaging><stationCodesFile>. Missing config/file/JSON just
     * yields an empty map (best-effort — lookups then fail through to the
     * configured default rather than the app crashing).
     */
    private synchronized Map<String, Object> loadStationCodes() {
        if (stationCodeCache != null) return stationCodeCache;
        stationCodeCache = new HashMap<>();
        try {
            String path = readAppConfigValue("stationCodesFile");
            if (path == null || path.isEmpty()) return stationCodeCache;
            File f = new File(path);
            if (!f.exists()) return stationCodeCache;
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = MiniJson.parseObject(json);
            if (parsed != null) stationCodeCache.putAll(parsed);
        } catch (Exception ignored) {
            // best-effort; missing/invalid config just means codes won't expand
        }
        return stationCodeCache;
    }

    /** Reads a single top-level-named element's text from app-config.xml. */
    private String readAppConfigValue(String tagName) {
        // Delegates to util.AppConfig, which resolves the file next to the
        // running JAR as a fallback when it's not found relative to the
        // process's working directory — a plain new File("app-config.xml")
        // here silently returned null (and therefore no station-code lookup
        // and no default fallback) whenever the process wasn't launched with
        // the install directory as its working directory.
        return AppConfig.readValue(tagName);
    }

    private synchronized String getDefaultStationAddress() {
        if (!defaultStationAddressLoaded) {
            defaultStationAddressCache = readAppConfigValue("defaultStationAddress");
            defaultStationAddressLoaded = true;
        }
        return defaultStationAddressCache;
    }

    /**
     * Resolves a station code to its full SITA address via the JSON lookup,
     * falling back to the <sitaMessaging><defaultStationAddress> configured
     * in app-config.xml when the code couldn't be extracted from the message
     * at all, or couldn't be found in the lookup — so =ORIGIN and
     * =DESTINATION TYPE B are never left blank as long as a default is set.
     */
    private String resolveStationAddress(String code, String messageType, String fieldName, Consumer<String> logLine) {
        if (code != null) {
            Object v = loadStationCodes().get(code);
            if (v != null) return v.toString();
            logLine.accept("[WARN] " + messageType + ": no SITA address configured for station code '" + code
                    + "' (needed for =" + fieldName + ") — check stationCodesFile in app-config.xml.");
        }
        String fallback = getDefaultStationAddress();
        if (fallback != null && !fallback.isEmpty()) {
            logLine.accept("[INFO] " + messageType + ": using configured default SITA address '" + fallback
                    + "' for =" + fieldName + ".");
            return fallback;
        }
        return null;
    }

    // ─── Sequential 5-digit message id ──────────────────────────────────────

    private final Object msgIdLock = new Object();

    /**
     * Persistent, sequential 5-digit =MSGID, shared across all LDM/MVT/PTM
     * messages regardless of task. Stored as a plain counter file in the
     * app's data directory so it survives restarts; wraps from 99999 back to
     * 00001. Best-effort: if the counter file can't be read/written, a value
     * is still returned for the current message rather than failing the run.
     */
    private String nextMessageId() {
        synchronized (msgIdLock) {
            File counterFile = new File(storage.getDataDir(), "msgid-counter.txt");
            int next = 1;
            try {
                if (counterFile.exists()) {
                    String s = Files.readString(counterFile.toPath(), StandardCharsets.UTF_8).trim();
                    int current = Integer.parseInt(s);
                    next = (current % 99999) + 1;
                }
            } catch (Exception ignored) {
                next = 1;
            }
            try {
                Files.writeString(counterFile.toPath(), String.valueOf(next), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best-effort persistence; still return a value for this run
            }
            return String.format("%05d", next);
        }
    }

    /**
     * Drops every blank/whitespace-only line so the .RCV file has no gaps
     * between lines of actual content — HTML paragraph/line breaks and the
     * earlier cleanup passes leave behind blank lines (collapsed to at most
     * one), which this removes entirely.
     */
    private String removeBlankLines(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Converts a Graph message body to plain text. Graph returns bodyType
     * "html" for the vast majority of real-world mail — writing that raw
     * markup into a .RCV file isn't usable as "body text", so block-level
     * tags are turned into line breaks, all remaining tags are stripped, and
     * the handful of entities that actually show up in mail are decoded.
     * bodyType "text" is returned as-is.
     */
    private String toPlainText(String content, String bodyType) {
        if (content == null) return "";
        if (!"html".equalsIgnoreCase(bodyType)) return content;

        String html = content;
        html = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", "");
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        html = html.replaceAll("(?i)<(p|div|li)[^>]*>", "\n");
        html = html.replaceAll("(?s)<[^>]+>", "");
        html = html.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        html = html.replaceAll("[ \\t]+\\n", "\n");
        html = html.replaceAll("\\n{3,}", "\n\n");
        return html.trim();
    }

    /**
     * Strips automated banners that mail gateways/clients inject into the
     * body — Outlook's "first contact" safety tip and generic external-sender
     * warnings — since they're not part of the actual message. Applied
     * wherever the banner appears in the text, not just at the start, since
     * some gateways insert it inline rather than as a strict prefix.
     */
    private String removeAutomatedBanners(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        for (Pattern p : BANNER_REMOVAL_PATTERNS) {
            result = p.matcher(result).replaceAll("");
        }
        return result.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static final Pattern[] BANNER_REMOVAL_PATTERNS = new Pattern[] {
            // Outlook's "You don't often get email from X. Learn why this is
            // important" safety tip — X varies per message, so match the fixed
            // wrapper text around it rather than a literal sender.
            Pattern.compile("(?is)you don't often get email from .*?learn why this is important\\.?"),
            // "** EXTERNAL EMAIL - Please verify the sender ... **" style banners.
            Pattern.compile("(?is)\\*\\*\\s*external email\\s*-.*?\\*\\*"),
            // Common Office 365 default external-sender banner text.
            Pattern.compile("(?is)this message originated from outside your organization\\.?\\s*"
                    + "(do not click links or open attachments unless you recognize the sender[^.\\n]*\\.)?"),
            // "[EXTERNAL]" tag some gateways prepend to the subject/body.
            Pattern.compile("(?i)^\\s*\\[external]\\s*", Pattern.MULTILINE),
            Pattern.compile("(?i)^caution:\\s*this email originated from outside.*$", Pattern.MULTILINE),
    };

    /**
     * Company convention for these mailboxes: the substantive message content
     * begins at a literal "LDM" / "MVT" / "PTM" marker in the body — whichever
     * one starts the actual message — and everything before it (banners,
     * greetings, etc.) is boilerplate that gets discarded. The marker found
     * also identifies the message type, which drives the =PRIORITY value and
     * whether/how origin+destination are extracted. If none of the three
     * markers are present, no marker is returned and the text is left as-is
     * (not an LDM/MVT/PTM message, so no SITA header is generated for it).
     */
    private static final String[] MESSAGE_TYPE_MARKERS = {"LDM", "MVT", "PTM"};

    private static class MarkerMatch {
        final String type;
        final int index;
        MarkerMatch(String type, int index) { this.type = type; this.index = index; }
    }

    private MarkerMatch findMessageTypeMarker(String text) {
        if (text == null) return null;
        MarkerMatch best = null;
        for (String type : MESSAGE_TYPE_MARKERS) {
            int idx = text.indexOf(type);
            if (idx >= 0 && (best == null || idx < best.index)) {
                best = new MarkerMatch(type, idx);
            }
        }
        return best;
    }

    /**
     * Classifies a message for automatic post-processing folder routing:
     * checks Subject, attachment text, and body TOGETHER, case-insensitively
     * ("LDM", "Ldm", "LDm", etc. all count), for "LDM" or "PTM" — checked in
     * that order, so a message containing both (unlikely) routes as LDM.
     *
     * Deliberately separate from findMessageTypeMarker (used for the SITA
     * header): that one is a strict, single-source, case-sensitive match
     * against the structured message content, because getting the header
     * wrong has real consequences. This one is a looser "does this message
     * relate to LDM/PTM at all" check across everywhere that word could
     * appear, since misrouting a message to the wrong folder because of a
     * stray lowercase letter or because the word only appeared in the
     * subject is the actual reported problem.
     */
    private String classifyForFolderRouting(GraphMailService.MailMessage m) {
        String subject = m.subject != null ? m.subject : "";
        String attachment = m.attachmentText != null ? m.attachmentText : "";
        String body = toPlainText(m.bodyContent, m.bodyType);

        String combined = (subject + "\n" + attachment + "\n" + body).toUpperCase(Locale.ROOT);
        if (combined.contains("LDM")) return "LDM";
        if (combined.contains("PTM")) return "PTM";
        return null;
    }

    /**
     * Resolves the destination folder name for automatic post-processing
     * move: LDM/PTM messages go to their respective configured folder,
     * anything else (including MVT, until it has its own rule) goes to the
     * configured "Others" folder. Folder names come from app-config.xml
     * under <sitaMessaging><ldmFolder>/<ptmFolder>/<othersFolder>, each
     * falling back to a sensible default (LDM / PTM / Others) if unset.
     */
    private String resolveMoveFolderName(String messageType) {
        if ("LDM".equals(messageType)) {
            String v = readAppConfigValue("ldmFolder");
            return v != null && !v.isEmpty() ? v : "LDM";
        }
        if ("PTM".equals(messageType)) {
            String v = readAppConfigValue("ptmFolder");
            return v != null && !v.isEmpty() ? v : "PTM";
        }
        String v = readAppConfigValue("othersFolder");
        return v != null && !v.isEmpty() ? v : "Others";
    }

    // Lines matching any of these mark the start of a signature, footer/
    // disclaimer, or quoted reply history — everything from the first match
    // onward is dropped. Best-effort: real-world mail signature formats
    // vary too much to catch every case, but this covers the common ones
    // (Outlook/Gmail/mobile clients, RFC 3676 "-- " delimiter, disclaimers).
    private static final Pattern[] BODY_CUTOFF_PATTERNS = new Pattern[] {
            Pattern.compile("^-- ?$"),
            Pattern.compile("^_{5,}$"),
            Pattern.compile("(?i)^-{3,}\\s*original message\\s*-{3,}$"),
            Pattern.compile("(?i)^on .{5,120} wrote:\\s*$"),
            Pattern.compile("^from:\\s+\\S.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^>.*$"),
            Pattern.compile("(?i)^sent from my (iphone|android|mobile|samsung|ipad).*$"),
            Pattern.compile("(?i)^get outlook for (ios|android).*$"),
            Pattern.compile("(?i)^(confidentiality notice|disclaimer)\\b.*$"),
            // Common sign-off lines — "Thanks & Regards", "Best Regards,", etc.
            // Anchored to a line containing (at most) a short trailing name/
            // title so it doesn't accidentally match the phrase mid-sentence.
            Pattern.compile("(?i)^thanks\\s*(&|and)\\s*regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|warm|kind|many)\\s+regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(thank\\s*you|thanks)\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|sincerely|cheers|respectfully)\\s*,?\\s*$"),
            Pattern.compile("(?i)^this (e-?mail|message)( and any (attachments|files))?"
                    + " (is|are|contains|may contain).*confidential.*$"),
    };

    private String stripSignatureAndQuotedContent(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        int cutoff = lines.length;

        outer:
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            for (Pattern p : BODY_CUTOFF_PATTERNS) {
                if (!p.matcher(line).matches()) continue;
                // A bare "From:" line is ambiguous on its own (could be body text
                // someone typed) — only treat it as a quoted-reply header block
                // when a Sent:/Date: line follows shortly after, matching the
                // Outlook "From: / Sent: / To: / Subject:" quote-block pattern.
                if (line.toLowerCase().startsWith("from:")) {
                    boolean looksLikeQuoteBlock = false;
                    for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                        String next = lines[j].trim().toLowerCase();
                        if (next.startsWith("sent:") || next.startsWith("date:")) {
                            looksLikeQuoteBlock = true;
                            break;
                        }
                    }
                    if (!looksLikeQuoteBlock) continue;
                }
                cutoff = i;
                break outer;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cutoff; i++) {
            sb.append(lines[i]);
            if (i < cutoff - 1) sb.append("\n");
        }
        return sb.toString().trim();
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