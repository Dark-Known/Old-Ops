package service.exec;

import model.EventKind;
import model.ScheduledTask;
import service.FolderWatch;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs {@link ScheduledTask.TaskType#FOLDER_WATCH} tasks: one look at a watched folder, then a report
 * of what is new or changed. Knows nothing about where the folder is listed from (that is the
 * {@link Look}) or how events are stored (that is {@link Events}).
 */
public final class FolderWatchExecutor implements TaskExecutor {

    /** One look at the folder; {@code FolderWatch::look} fits. Throws when the folder cannot be read. */
    @FunctionalInterface
    public interface Look {
        FolderWatch.Outcome look(ScheduledTask task, Consumer<String> log) throws Exception;
    }

    /** Where the executor reports what it found. */
    public interface Events {
        /** First look: the files already there are remembered and not reported. */
        void armed(ScheduledTask task, int watching, String where);

        /** New ({@code FILES_DETECTED}) or changed ({@code FILES_CHANGED}) files. {@code title} is the heading for the detail text. */
        void found(EventKind kind, ScheduledTask task, List<FolderWatch.Item> files, String where, String title);

        /** Files are arriving again after a "no new files" alert. */
        void resumed(ScheduledTask task, String where);
    }

    private final Look look;
    private final Events events;

    public FolderWatchExecutor(Look look, Events events) {
        this.look = look;
        this.events = events;
    }

    @Override
    public ScheduledTask.TaskType type() {
        return ScheduledTask.TaskType.FOLDER_WATCH;
    }

    @Override
    public TaskOutcome execute(RunContext ctx) {
        ScheduledTask task = ctx.task();
        Consumer<String> log = ctx.log();
        try {
            boolean remote = task.getBackupSourceUsername() != null && !task.getBackupSourceUsername().trim().isEmpty();
            String where = task.getBackupSourcePath() + (remote ? " (SFTP, " + task.getBackupSourceUsername().trim() + ")" : "");
            log.accept("[INFO] Watching folder: " + where);
            FolderWatch.Outcome o = look.look(task, log);
            if (o.baseline()) {
                log.accept("[INFO] First look: " + o.watching() + " file(s) already there are remembered and not reported.");
                events.armed(task, o.watching(), where);
                return TaskOutcome.ok();
            }
            if (!o.added().isEmpty()) {
                log.accept("[INFO] " + o.added().size() + " new file(s) in " + where);
                events.found(EventKind.FILES_DETECTED, task, o.added(), where, "New file(s)");
            }
            if (!o.changed().isEmpty()) {
                log.accept("[INFO] " + o.changed().size() + " changed file(s) in " + where);
                events.found(EventKind.FILES_CHANGED, task, o.changed(), where, "Changed file(s)");
            }
            if (o.resumed()) events.resumed(task, where);
            if (o.quiet()) {
                log.accept("[INFO] Nothing new or changed (" + o.watching() + " file(s) watched).");
                return TaskOutcome.skip("No new or changed files");
            }
            return TaskOutcome.ok();
        } catch (Exception e) {
            log.accept("[ERROR] Could not look at the folder: " + e.getMessage());
            return TaskOutcome.failure("Could not look at the folder: " + e.getMessage());
        }
    }
}
