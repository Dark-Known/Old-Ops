package service;

import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manages per-task logging — each task has its own log file.
 * Logs are stored in ~/.opstool/logs/task-{taskId}/
 * 
 * Latest log is always in task.log
 * Rotated logs are named task-YYYY-MM-DD-HHmmss.log
 */
public class TaskLogService {
    
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final int MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    
    private final File logsDir;
    private final Map<String, PrintWriter> openWriters = new HashMap<>();
    
    public TaskLogService(String dataDir) {
        this.logsDir = new File(dataDir, "logs");
        this.logsDir.mkdirs();
    }
    
    /**
     * Log a message for a specific task.
     * Automatically rotates logs if they exceed MAX_LOG_SIZE_BYTES.
     */
    public synchronized void log(String taskId, String message) {
        try {
            File taskDir = new File(logsDir, "task-" + taskId);
            taskDir.mkdirs();
            
            File logFile = new File(taskDir, "task.log");
            
            // Check if we need to rotate
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                String rotatedName = "task-" + LocalDateTime.now().format(FILE_FMT) + ".log";
                File rotatedFile = new File(taskDir, rotatedName);
                logFile.renameTo(rotatedFile);
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
    
    /**
     * Retrieve all log lines for a task from the current log file.
     */
    public List<String> getTaskLogs(String taskId) {
        List<String> lines = new ArrayList<>();
        try {
            File taskDir = new File(logsDir, "task-" + taskId);
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
    public List<String> getTaskLogsLastN(String taskId, int maxLines) {
        List<String> allLines = getTaskLogs(taskId);
        if (allLines.size() <= maxLines) {
            return allLines;
        }
        return allLines.subList(allLines.size() - maxLines, allLines.size());
    }
    
    /**
     * Retrieve all rotated log files for a task (for archive/history).
     */
    public List<File> getTaskLogArchives(String taskId) {
        List<File> archives = new ArrayList<>();
        try {
            File taskDir = new File(logsDir, "task-" + taskId);
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
    public void cleanupOldLogs(String taskId, int keepCount) {
        try {
            List<File> archives = getTaskLogArchives(taskId);
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
    public File getTaskLogDirectory(String taskId) {
        return new File(logsDir, "task-" + taskId);
    }
}
