package ui;

import model.ScheduledTask;
import model.ScheduledTask.TaskType;
import org.junit.Test;

import static org.junit.Assert.*;

public class SftpTaskTypesTest {

    private static ScheduledTask of(TaskType type, String src, String dst) {
        ScheduledTask t = new ScheduledTask();
        t.setTaskType(type);
        t.setBackupSourceUsername(src);
        t.setBackupDestinationUsername(dst);
        return t;
    }

    @Test
    public void fileTransfersAlwaysUseSftp() {
        assertTrue(TaskManagerPanel.usesSftp(of(TaskType.FILE_TRANSFER, "", "")));
    }

    @Test
    public void backupsUseSftpWhenEitherSideIsRemote() {
        assertTrue(TaskManagerPanel.usesSftp(of(TaskType.BACKUP, "ops-server", "")));
        assertTrue(TaskManagerPanel.usesSftp(of(TaskType.BACKUP, "", "ops-server")));
        assertFalse("local to local", TaskManagerPanel.usesSftp(of(TaskType.BACKUP, "", "")));
        assertFalse("blank is not remote", TaskManagerPanel.usesSftp(of(TaskType.BACKUP, "  ", null)));
    }

    @Test
    public void folderWatchUsesSftpOnlyForARemoteFolder() {
        assertTrue(TaskManagerPanel.usesSftp(of(TaskType.FOLDER_WATCH, "ops-server", "")));
        assertFalse(TaskManagerPanel.usesSftp(of(TaskType.FOLDER_WATCH, "", "")));
        assertFalse("a destination means nothing to a folder watch", TaskManagerPanel.usesSftp(of(TaskType.FOLDER_WATCH, "", "x")));
    }

    @Test
    public void mailAndUnknownTasksNeverDo() {
        assertFalse(TaskManagerPanel.usesSftp(of(TaskType.OUTLOOK_MAIL, "x", "y")));
        assertFalse(TaskManagerPanel.usesSftp(null));
        assertFalse(TaskManagerPanel.usesSftp(new ScheduledTask()));
    }
}
