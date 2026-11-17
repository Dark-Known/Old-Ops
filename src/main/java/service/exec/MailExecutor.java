package service.exec;

import model.ScheduledTask;
import service.WatcherSkipException;
import service.mail.MailIngestService;

/** Runs {@link ScheduledTask.TaskType#OUTLOOK_MAIL} tasks (mailbox fetch and save). */
public final class MailExecutor implements TaskExecutor {

    private final MailIngestService mail;

    public MailExecutor(MailIngestService mail) {
        this.mail = mail;
    }

    @Override
    public ScheduledTask.TaskType type() {
        return ScheduledTask.TaskType.OUTLOOK_MAIL;
    }

    @Override
    public TaskOutcome execute(RunContext ctx) throws Exception {
        try {
            boolean success = mail.executeImapMailTask(ctx.task(), ctx.log());
            return success ? TaskOutcome.ok() : TaskOutcome.failure(null);
        } catch (WatcherSkipException e) {
            ctx.log().accept("[INFO] Mail watcher skipped run: " + e.getMessage());
            return TaskOutcome.skip(e.getMessage());
        }
    }
}
