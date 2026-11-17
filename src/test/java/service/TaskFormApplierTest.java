package service;

import static org.junit.Assert.*;

import model.ScheduledTask;
import model.ScheduledTask.*;
import org.junit.Test;

import java.time.LocalDateTime;

public class TaskFormApplierTest {

    private static TaskForm transferForm() {
        TaskForm f = new TaskForm();
        f.name = "Daily pull";
        f.taskType = "FILE_TRANSFER";
        f.credentialName = "alice@host";
        f.sourceUser = " bob "; f.sourceHost = " src ";
        f.transferDirection = "INBOUND"; f.transferMode = "LATEST_ONLY";
        f.sourcePath = " C:\\in "; f.targetFolder = " /out ";
        f.fileSelectionRules = "rules-text";
        f.additionalDestinations.add(" /a ");
        f.additionalDestinations.add("  ");
        f.additionalDestinations.add("/b");
        f.watcherEnabled = true;
        f.retryCount = 3;
        f.scheduleType = "DAILY"; f.time = " 08:30 ";
        return f;
    }

    @Test public void aNewTransferTaskGetsAnIdPendingStatusAndTrimmedFields() {
        ScheduledTask t = TaskFormApplier.apply(null, transferForm());
        assertNotNull(t.getId());
        assertEquals(TaskStatus.PENDING, t.getStatus());
        assertEquals(TaskType.FILE_TRANSFER, t.getTaskType());
        assertEquals("alice@host", t.getTargetUsername());
        assertEquals("bob@src", t.getSourceCredentialId());
        assertEquals("C:\\in", t.getSourcePath());
        assertEquals("/out", t.getTargetPath());
        assertEquals("/a;/b", t.getAdditionalTargetPaths());
        assertTrue(t.isWatcherEnabled());
        assertEquals(3, t.getRetryCount());
        assertEquals("08:30", t.getCronExpression());
    }

    @Test public void editingKeepsTheIdAndStatus() {
        ScheduledTask existing = new ScheduledTask();
        existing.setId("keep-me");
        existing.setStatus(TaskStatus.FAILED);
        ScheduledTask t = TaskFormApplier.apply(existing, transferForm());
        assertSame(existing, t);
        assertEquals("keep-me", t.getId());
        assertEquals(TaskStatus.FAILED, t.getStatus());
    }

    @Test public void theBaselineResetsOnlyWhenAsked() {
        ScheduledTask existing = new ScheduledTask();
        existing.setLastKnownRemoteFileEpoch(99L);
        existing.setLastKnownRemoteFileSize(5L);
        TaskFormApplier.apply(existing, transferForm());
        assertEquals(99L, existing.getLastKnownRemoteFileEpoch());
        TaskForm f = transferForm();
        f.resetWatcherBaseline = true;
        TaskFormApplier.apply(existing, f);
        assertEquals(0L, existing.getLastKnownRemoteFileEpoch());
        assertEquals(-1L, existing.getLastKnownRemoteFileSize());
    }

    @Test public void backupAndWatchTasksLeaveTheTargetCredentialBlankAndClearLocalSides() {
        TaskForm f = new TaskForm();
        f.name = "b"; f.taskType = "BACKUP"; f.credentialName = "ignored";
        f.backupSourcePath = " /s "; f.backupDestinationPath = " /d ";
        f.backupSourceRemote = false; f.backupSourceUsername = "u1";
        f.backupDestinationRemote = true; f.backupDestinationUsername = " u2 ";
        f.backupRetentionDays = 14;
        f.scheduleType = "INTERVAL_MINUTES"; f.interval = " 15 ";
        ScheduledTask t = TaskFormApplier.apply(null, f);
        assertEquals("", t.getTargetUsername());
        assertEquals("/s", t.getBackupSourcePath());
        assertEquals("", t.getBackupSourceUsername());
        assertEquals("u2", t.getBackupDestinationUsername());
        assertEquals(14, t.getBackupRetentionDays());
        assertEquals(15, t.getIntervalMinutes());
        assertEquals(0, t.getIntervalSeconds());
    }

    @Test public void aFolderWatchTaskStoresItsOptionsAndNeverUsesTheTransferWatcher() {
        TaskForm f = new TaskForm();
        f.name = "w"; f.taskType = "FOLDER_WATCH";
        f.watchPath = " /w "; f.watchRemote = true; f.watchCredential = " u@h ";
        f.watchNew = true; f.watchChanged = false;
        f.fileSelectionRules = "r";
        f.watcherEnabled = true;
        f.scheduleType = "ONCE"; f.scheduledAt = "2026-11-20 09:15";
        ScheduledTask t = TaskFormApplier.apply(null, f);
        assertEquals("/w", t.getBackupSourcePath());
        assertEquals("u@h", t.getBackupSourceUsername());
        assertEquals(new model.WatchOptions(true, false).toText(), t.getWatchOptions());
        assertFalse(t.isWatcherEnabled());
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 15), t.getScheduledAt());
    }

    @Test public void weeklyAndSecondIntervalSchedulesMapToTheRightColumns() {
        TaskForm f = transferForm();
        f.scheduleType = "WEEKLY"; f.dayOfWeek = "MONDAY"; f.time = "07:00";
        ScheduledTask t = TaskFormApplier.apply(null, f);
        assertEquals("MONDAY 07:00", t.getCronExpression());
        f.scheduleType = "INTERVAL_SECONDS"; f.interval = "30";
        t = TaskFormApplier.apply(t, f);
        assertEquals(30, t.getIntervalSeconds());
        assertEquals(0, t.getIntervalMinutes());
    }

    @Test(expected = TaskFormApplier.InvalidScheduleException.class)
    public void aBadDateIsAnInvalidScheduleError() {
        TaskForm f = transferForm();
        f.scheduleType = "ONCE"; f.scheduledAt = "tomorrow";
        TaskFormApplier.apply(null, f);
    }

    @Test(expected = TaskFormApplier.InvalidScheduleException.class)
    public void aNonNumericIntervalIsAnInvalidScheduleError() {
        TaskForm f = transferForm();
        f.scheduleType = "INTERVAL_MINUTES"; f.interval = "often";
        TaskFormApplier.apply(null, f);
    }

    @Test public void aBadMailLimitIsNotReportedAsAScheduleProblem() {
        TaskForm f = new TaskForm();
        f.name = "m"; f.taskType = "OUTLOOK_MAIL";
        f.mailFetchMode = "BODY_ONLY"; f.mailFetchScope = "LATEST_ONLY"; f.mailMaxResults = "x";
        f.scheduleType = "DAILY"; f.time = "08:00";
        try {
            TaskFormApplier.apply(null, f);
            fail();
        } catch (TaskFormApplier.InvalidScheduleException e) {
            fail("a mail-limit error must not be called a schedule error");
        } catch (RuntimeException expected) { }
    }
}
