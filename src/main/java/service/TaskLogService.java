package service;

import util.AppSettings;

import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manages per-task logging — each task has its own log file.
 * Logs are stored in ~/.opstool/logs/{TaskName}_{shortId}/ — the folder is
 * named after the task's display name (sanitized for the filesystem) rather
 * than its raw task id, so logs are browsable/identifiable directly from the
 * file system. A short suffix derived from the task id is still appended to
 * keep folders unique even if two tasks share the same name, and renaming a
 * task later will simply start a new folder under the new name (old logs
 * stay where they were written).
 * 
 * Latest log is always in task.log
 * Rotated logs are named task-YYYY-MM-DD-HHmmss.log
 */
public class TaskLogService {
    
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    // Was a hardcoded 5 MB, ignoring app-config.xml's <logging><maxLogSizeMB>
    // entirely (nothing in the codebase ever read it). Now reads
    // <logRotateMaxBytes>/<logRotateKeepFiles> — bytes rather than MB, since
    // a 10KB target is well below whole-MB granularity. Read once at
    // construction: matches the "install-time config, restart to change"
    // convention the rest of app-config.xml already documents for values
    // like this, rather than re-parsing the XML file on every single log
    // line written.
    private final long maxLogSizeBytes;
    private final int keepArchiveCount;

    // Severity ordering for the app's DEBUG < INFO < WARN < ERROR levels
    // (see util.AppSettings / the Settings panel). A message whose leading
    // "[TAG]" ranks below the currently configured level is skipped.
    private static final List<String> LEVEL_ORDER = List.of("DEBUG", "INFO", "WARN", "ERROR");

    private final File logsDir;
    private final Map<String, PrintWriter> openWriters = new HashMap<>();
    
    public TaskLogService(String dataDir) {
        this.logsDir = new File(dataDir, "logs");
        this.logsDir.mkdirs();
        this.maxLogSizeBytes = readConfiguredMaxBytes();
        this.keepArchiveCount = readConfiguredKeepCount();
    }

    private static long readConfiguredMaxBytes() {
        String raw = util.AppConfig.readValue("logRotateMaxBytes");
        if (raw != null) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return 10 * 1024; // 10 KB default, matching app-config.xml's shipped value
    }

    private static int readConfiguredKeepCount() {
        String raw = util.AppConfig.readValue("logRotateKeepFiles");
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return 5;
    }

    /**
     * Builds the on-disk folder name for a task: sanitized task name plus a
     * short id-derived suffix for uniqueness. Falls back to "task-{taskId}"
     * when no usable name is available (e.g. very old callers that only had
     * an id), and to "system" for the no-task (taskId == null) case used for
     * scheduler-level messages.
     */
    private static String folderNameFor(String taskId, String taskName) {
        if (taskId == null) return "system";
        String sanitized = sanitize(taskName);
        String idSuffix = taskId.length() > 8 ? taskId.substring(taskId.length() - 8) : taskId;
        return sanitized.isEmpty() ? "task-" + taskId : sanitized + "_" + idSuffix;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned;
    }
    
    /**
     * Log a message for a specific task, filtered by the currently
     * configured log level (read fresh from {@link AppSettings} on every
     * call, so a level change in the Settings panel is honored by the very
     * next line any in-progress task writes — no restart is needed for
     * lines from that point on).
     *
     * <p>The message's level is taken from its leading {@code "[TAG]"}
     * (e.g. {@code "[DEBUG] ..."}, {@code "[WARN] ..."}) — the convention
     * already used throughout {@link TransferService}. A message with no
     * recognized tag is treated as INFO. Automatically rotates logs if they
     * exceed MAX_LOG_SIZE_BYTES.
     */
    public synchronized void log(String taskId, String taskName, String message) {
        if (!meetsConfiguredLevel(message)) return;
        try {
            File taskDir = new File(logsDir, folderNameFor(taskId, taskName));
            taskDir.mkdirs();
            
            File logFile = new File(taskDir, "task.log");
            
            // Check if we need to rotate. BUG FIX: this rename used to be
            // attempted while openWriters still held an open PrintWriter on
            // the very file being renamed — on Windows that rename silently
            // fails (the OS won't rename a file that's open for writing
            // without FILE_SHARE_DELETE, which a plain FileWriter doesn't
            // request), so rotation never actually happened in practice
            // despite this code appearing to implement it: task.log just
            // grew forever. Fixed by closing and evicting the cached writer
            // for this task BEFORE renaming, so the next write below opens a
            // fresh writer against a new, empty task.log.
            if (logFile.exists() && logFile.length() > maxLogSizeBytes) {
                PrintWriter existing = openWriters.remove(taskId);
                if (existing != null) existing.close();

                String rotatedName = "task-" + LocalDateTime.now().format(FILE_FMT) + ".log";
                File rotatedFile = new File(taskDir, rotatedName);
                if (logFile.renameTo(rotatedFile)) {
                    cleanupOldLogs(taskId, taskName, keepArchiveCount);
                } else {
                    System.err.println("Failed to rotate log for task " + taskId
                            + " (rename returned false) — continuing to append to the current file.");
                }
            }
            
            // Append message with timestamp
            String line = "[" + LocalDateTime.now().format(DT_FMT) + "] " + message;


            PrintWriter pw = openWriters.computeIfAbsent(taskId, k -> {
                try {
                    return new PrintWriter(new FileWriter(logFile, true));
                } catch (IOException e) {
                    System.err.println("Failed to open log for task " + taskId + ": " + e.getMessage());
                    return null;
                }
            });
            
            if (pw != null) {
                pw.println(line);
                pw.flush();
            }
        } catch (Exception e) {
            System.err.println("Error logging to task " + taskId + ": " + e.getMessage());
        }
    }

    /** Extracts the leading "[TAG]" (if any) and compares it against the configured level. */
    private static boolean meetsConfiguredLevel(String message) {
        String configured = AppSettings.getLogLevel();
        int configuredRank = LEVEL_ORDER.indexOf(configured);
        if (configuredRank < 0) configuredRank = LEVEL_ORDER.indexOf("INFO"); // unknown value -> safe default

        String tag = extractTag(message);
        int msgRank = tag != null ? LEVEL_ORDER.indexOf(tag) : LEVEL_ORDER.indexOf("INFO");
        if (msgRank < 0) msgRank = LEVEL_ORDER.indexOf("INFO");

        return msgRank >= configuredRank;
    }

    private static String extractTag(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '[') return null;
        int close = message.indexOf(']');
        if (close <= 1) return null;
        String tag = message.substring(1, close).trim().toUpperCase(Locale.ROOT);
        return LEVEL_ORDER.contains(tag) ? tag : null;
    }
    
    /**
     * Retrieve all log lines for a task from the current log file.
     */
    public List<String> getTaskLogs(String taskId, String taskName) {
        List<String> lines = new ArrayList<>();
        try {
            File taskDir = new File(logsDir, folderNameFor(taskId, taskName));
            File logFile = new File(taskDir, "task.log");
            
            if (logFile.exists()) {
                lines = Files.readAllLines(logFile.toPath());
            }
        } catch (Exception e) {
            lines.add("[ERROR] Failed to read logs: " + e.getMessage());
        }
        return lines;
    }
    
    /**
     * Retrieve the last N log lines for a task (most recent first from bottom of file).
     */
    public List<String> getTaskLogsLastN(String taskId, String taskName, int maxLines) {
        List<String> allLines = getTaskLogs(taskId, taskName);
        if (allLines.size() <= maxLines) {
            return allLines;
        }
        return allLines.subList(allLines.size() - maxLines, allLines.size());
    }
    
    /**
     * Retrieve all rotated log files for a task (for archive/history).
     */
    public List<File> getTaskLogArchives(String taskId, String taskName) {
        List<File> archives = new ArrayList<>();
        try {
            File taskDir = new File(logsDir, folderNameFor(taskId, taskName));
            if (taskDir.exists()) {
                File[] files = taskDir.listFiles((dir, name) -> 
                    name.startsWith("task-") && name.endsWith(".log") && !name.equals("task.log"));
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    archives.addAll(Arrays.asList(files));
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return archives;
    }

    /**
     * Clear old rotated logs, keeping only the last N files per task.
     */
    public void cleanupOldLogs(String taskId, String taskName, int keepCount) {
        try {
            List<File> archives = getTaskLogArchives(taskId, taskName);
            while (archives.size() > keepCount) {
                File toDelete = archives.remove(archives.size() - 1);
                toDelete.delete();
            }
        } catch (Exception ignored) {
        }
    }
    
    // ── Retention / purge ────────────────────────────────────────────────

    /** What one {@link #purgeLogs} call did (or, for a dry run, would do). */
    public static final class PurgeStats {
        public int archivesDeleted;
        public int orphanFoldersDeleted;
        public int failures;       // files/folders that could not be deleted (e.g. still open on Windows)
        public long bytesFreed;
    }

    /**
     * Keeps the per-task log folders under {@code <dataDir>/logs} from
     * growing forever. Size-based rotation already caps each live task, but
     * nothing ever removed (a) rotated archives once they were no longer
     * interesting or (b) the folder of a task that was deleted or renamed
     * (a rename starts a new folder, see {@link #folderNameFor}).
     *
     * <ul>
     *   <li><b>archives:</b> {@code task-*.log} files older than
     *       {@code archiveDays} are deleted in every folder. The live
     *       {@code task.log} is never touched. 0 = off.</li>
     *   <li><b>orphans:</b> a folder that matches no current task, and in
     *       which nothing has been written for {@code orphanDays}, is
     *       deleted whole. The {@code system} folder is always kept. If
     *       {@code tasks} is empty (storage may simply have failed to load)
     *       no folder is considered an orphan. 0 = off.</li>
     * </ul>
     *
     * Only direct children of the logs directory are ever touched.
     */
    public synchronized PurgeStats purgeLogs(Collection<model.ScheduledTask> tasks,
                                             int archiveDays, int orphanDays, boolean dryRun) {
        PurgeStats stats = new PurgeStats();
        File[] folders = logsDir.listFiles(File::isDirectory);
        if (folders == null) return stats;

        Set<String> activeFolders = new HashSet<>();
        activeFolders.add("system");
        boolean taskListTrusted = tasks != null && !tasks.isEmpty();
        if (tasks != null) {
            for (model.ScheduledTask t : tasks) activeFolders.add(folderNameFor(t.getId(), t.getName()));
        }

        long now = System.currentTimeMillis();
        long archiveCutoff = archiveDays > 0 ? now - archiveDays * 86_400_000L : Long.MIN_VALUE;
        long orphanCutoff  = orphanDays  > 0 ? now - orphanDays  * 86_400_000L : Long.MIN_VALUE;
        boolean closedWriters = false;

        for (File folder : folders) {
            if (Files.isSymbolicLink(folder.toPath())) continue;
            File[] files = folder.listFiles();
            if (files == null) continue;

            boolean isOrphan = taskListTrusted && orphanDays > 0 && !activeFolders.contains(folder.getName());
            if (isOrphan) {
                // Judge idleness by the files inside, not the folder's own
                // timestamp: deleting an archive (below) bumps the folder's
                // mtime, which would make a dead folder look freshly active.
                // An empty folder falls back to its own mtime so a task that
                // has just created its folder is never caught mid-write.
                long newest = 0;
                boolean hasSubdir = false;
                for (File f : files) {
                    if (f.isDirectory()) hasSubdir = true;
                    newest = Math.max(newest, f.lastModified());
                }
                if (files.length == 0) newest = folder.lastModified();
                if (!hasSubdir && newest < orphanCutoff) {
                    long size = 0;
                    for (File f : files) size += f.length();
                    if (dryRun) {
                        stats.orphanFoldersDeleted++;
                        stats.bytesFreed += size;
                    } else {
                        if (!closedWriters) { closeAll(); closedWriters = true; } // release handles; they reopen lazily
                        boolean allGone = true;
                        for (File f : files) {
                            long len = f.length();
                            if (f.delete()) stats.bytesFreed += len; else { allGone = false; stats.failures++; }
                        }
                        if (allGone && folder.delete()) stats.orphanFoldersDeleted++;
                        else if (allGone) stats.failures++;
                    }
                    continue;   // whole folder handled
                }
            }

            if (archiveDays > 0) {
                for (File f : files) {
                    String n = f.getName();
                    boolean isArchive = f.isFile() && n.startsWith("task-") && n.endsWith(".log") && !n.equals("task.log");
                    if (!isArchive || f.lastModified() >= archiveCutoff) continue;
                    long len = f.length();
                    if (dryRun) {
                        stats.archivesDeleted++;
                        stats.bytesFreed += len;
                    } else if (f.delete()) {
                        stats.archivesDeleted++;
                        stats.bytesFreed += len;
                    } else {
                        stats.failures++;
                    }
                }
            }
        }
        return stats;
    }

    /**
     * Close all open writers (call on shutdown).
     */
    public synchronized void closeAll() {
        for (PrintWriter pw : openWriters.values()) {
            try { pw.close(); } catch (Exception ignored) {}
        }
        openWriters.clear();
    }
    
    /** Get the directory for a specific task's logs. */
    public File getTaskLogDirectory(String taskId, String taskName) {
        return new File(logsDir, folderNameFor(taskId, taskName));
    }
}
