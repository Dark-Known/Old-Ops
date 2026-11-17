package service;

import util.AppSettings;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
        this.maxLogSizeBytes = configuredMaxBytes();
        this.keepArchiveCount = configuredKeepCount();
    }

    /**
     * Rotation size in bytes. Precedence: {@code <logRotateMaxBytes>} (exact bytes, handy for tests),
     * then {@code <maxLogSizeMB>} (the key the shipped app-config.xml actually defines), then 10 MB.
     * This used to read only logRotateMaxBytes — which the shipped config never defines — so every
     * task log silently rotated at a 10 KB fallback and the Logs view showed only a tiny tail.
     */
    public static long configuredMaxBytes() {
        String bytes = util.AppConfig.readValue("logRotateMaxBytes");
        if (bytes != null) {
            try { long v = Long.parseLong(bytes.trim()); if (v > 0) return v; } catch (NumberFormatException ignored) { }
        }
        String mb = util.AppConfig.readValue("maxLogSizeMB");
        if (mb != null) {
            try { double v = Double.parseDouble(mb.trim()); if (v > 0) return (long) (v * 1024 * 1024); } catch (NumberFormatException ignored) { }
        }
        return 10L * 1024 * 1024;
    }

    /** Rotated files kept per task: {@code <logRotateKeepFiles>}, then {@code <maxLogFiles>}, then 5. */
    public static int configuredKeepCount() {
        for (String key : new String[] { "logRotateKeepFiles", "maxLogFiles" }) {
            String raw = util.AppConfig.readValue(key);
            if (raw == null) continue;
            try { int v = Integer.parseInt(raw.trim()); if (v > 0) return v; } catch (NumberFormatException ignored) { }
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
                PrintWriter existing = openWriters.remove(logFile.getAbsolutePath());
                if (existing != null) existing.close();

                // Millisecond resolution plus a collision counter: with whole-second names, several
                // rotations inside one second overwrote each other and that part of the log was lost.
                String base = "task-" + LocalDateTime.now().format(FILE_FMT) + "-"
                        + String.format("%03d", System.currentTimeMillis() % 1000);
                File rotatedFile = new File(taskDir, base + ".log");
                for (int n = 1; rotatedFile.exists() && n < 1000; n++) rotatedFile = new File(taskDir, base + "-" + n + ".log");
                if (logFile.renameTo(rotatedFile)) {
                    cleanupOldLogs(taskId, taskName, keepArchiveCount);
                } else {
                    System.err.println("Failed to rotate log for task " + taskId
                            + " (rename returned false) — continuing to append to the current file.");
                }
            }
            
            // Append message with timestamp
            String line = "[" + LocalDateTime.now().format(DT_FMT) + "] " + message;


            // Keyed by the file path (not the task id): renaming a task moves its folder, and a
            // writer cached by id would keep appending to the old folder where nothing reads it.
            PrintWriter pw = openWriters.computeIfAbsent(logFile.getAbsolutePath(), k -> {
                try {
                    return new PrintWriter(new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(logFile, true), StandardCharsets.UTF_8));
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
     * Retrieve all log lines for a task from the current log file. Decoded leniently: one stray
     * byte (a log written by an older build in the platform charset) no longer makes the whole
     * file unreadable.
     */
    public List<String> getTaskLogs(String taskId, String taskName) {
        File logFile = new File(new File(logsDir, folderNameFor(taskId, taskName)), "task.log");
        try {
            return logFile.exists() ? tailLines(logFile, Integer.MAX_VALUE) : new ArrayList<>();
        } catch (Exception e) {
            List<String> lines = new ArrayList<>();
            lines.add("[ERROR] Failed to read logs: " + e.getMessage());
            return lines;
        }
    }

    /** The last N lines of the current log file only. */
    public List<String> getTaskLogsLastN(String taskId, String taskName, int maxLines) {
        return getTaskLogsRecent(taskId, taskName, maxLines);
    }

    /**
     * The last {@code maxLines} lines of the task's log <b>across rotations</b>: the current file
     * first, then the newest archives until enough lines are found, returned oldest-first. A run
     * that straddles a rotation is shown whole instead of being cut where the file rolled over.
     * Only the tail of each file is read, so this stays cheap on a multi-megabyte log.
     */
    public List<String> getTaskLogsRecent(String taskId, String taskName, int maxLines) {
        List<String> out = new ArrayList<>();
        try {
            File dir = new File(logsDir, folderNameFor(taskId, taskName));
            List<File> newestFirst = new ArrayList<>();
            File current = new File(dir, "task.log");
            if (current.exists()) newestFirst.add(current);
            newestFirst.addAll(getTaskLogArchives(taskId, taskName));
            for (File f : newestFirst) {
                int need = maxLines == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxLines - out.size();
                if (need <= 0) break;
                List<String> part = tailLines(f, need);
                out.addAll(0, part);
            }
        } catch (Exception e) {
            out.add("[ERROR] Failed to read logs: " + e.getMessage());
        }
        return out;
    }

    /**
     * Cheap change detector for the log view: differs whenever the current file grows, rotates or
     * an archive appears. Lets a poller skip re-reading and re-rendering an unchanged log.
     */
    public String getLogStamp(String taskId, String taskName) {
        File dir = new File(logsDir, folderNameFor(taskId, taskName));
        File cur = new File(dir, "task.log");
        File[] all = dir.listFiles((d, n) -> n.endsWith(".log"));
        return cur.length() + ":" + cur.lastModified() + ":" + (all == null ? 0 : all.length);
    }

    /** Reads the last {@code max} lines of a file by scanning backwards from the end. */
    static List<String> tailLines(File f, int max) throws IOException {
        if (max <= 0) return new ArrayList<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long pos = raf.length();
            java.util.ArrayDeque<byte[]> chunks = new java.util.ArrayDeque<>();
            long newlines = 0;
            final int CHUNK = 64 * 1024;
            while (pos > 0 && newlines <= max) {
                int n = (int) Math.min(CHUNK, pos);
                pos -= n;
                byte[] buf = new byte[n];
                raf.seek(pos);
                raf.readFully(buf);
                for (byte b : buf) if (b == '\n') newlines++;
                chunks.addFirst(buf);
            }
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] all = new byte[total];
            int off = 0;
            for (byte[] c : chunks) { System.arraycopy(c, 0, all, off, c.length); off += c.length; }
            String text = new String(all, StandardCharsets.UTF_8); // malformed bytes become U+FFFD, never throw
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
            if (pos > 0 && !lines.isEmpty()) lines.remove(0); // first line is a partial one
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.endsWith("\r")) lines.set(i, l.substring(0, l.length() - 1));
            }
            return lines.size() > max ? new ArrayList<>(lines.subList(lines.size() - max, lines.size())) : lines;
        }
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
                    // Newest first. By name: the timestamp is in it, and unlike lastModified it survives
                    // several rotations in the same instant or a restored/copied file.
                    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
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
