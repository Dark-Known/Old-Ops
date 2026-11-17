package service.exec;

import model.ScheduledTask;
import service.backup.BackupService;

/** Runs {@link ScheduledTask.TaskType#BACKUP} tasks. */
public final class BackupExecutor implements TaskExecutor {

    private final BackupService backups;

    public BackupExecutor(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public ScheduledTask.TaskType type() {
        return ScheduledTask.TaskType.BACKUP;
    }

    @Override
    public TaskOutcome execute(RunContext ctx) throws Exception {
        boolean success = backups.run(ctx.task(), ctx.log());
        return success ? TaskOutcome.ok() : TaskOutcome.failure(null);
    }
}
