package service;

import model.ScheduledTask;
import model.ScheduledTask.TaskStatus;
import model.ScheduledTask.TaskType;
import org.junit.Test;

import java.nio.file.Files;
import java.time.LocalDateTime;

import static org.junit.Assert.*;

/**
 * A long run must not overwrite settings someone changed while it was running. Needs the sqlite-jdbc driver.
 */
public class TaskRunStateSaveTest {

    private static ScheduledTask task(String id) {
        ScheduledTask t = new ScheduledTask();
        t.setId(id);
        t.setName("Nightly");
        t.setTaskType(TaskType.FILE_TRANSFER);
        t.setStatus(TaskStatus.PENDING);
        t.setSourcePath("C:\\in");
        t.setTargetPath("/out");
        return t;
    }

    @Test
    public void runStateIsSavedWithoutTouchingSettingsEditedMeanwhile() throws Exception {
        TaskDbService db = new TaskDbService(Files.createTempDirectory("opstool-rs").toFile());
        assertTrue(db.save(task("t1")));

        ScheduledTask running = db.loadAll().get(0);              // what the scheduler holds during the run

        ScheduledTask edited = db.loadAll().get(0);               // someone edits the task meanwhile
        edited.setName("Renamed");
        edited.setSourcePath("D:\\other");
        edited.setFileSelectionRules("name ends .csv");
        assertTrue(db.save(edited));

        running.setStatus(TaskStatus.SUCCESS);
        running.setLastRunAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        running.setLastRunResult("SUCCESS");
        running.setLastKnownRemoteFileEpoch(12345L);
        running.setLastKnownRemoteFileSize(99L);
        running.setRetryCount(2);
        assertTrue(db.saveRunState(running));

        ScheduledTask now = db.loadAll().get(0);
        assertEquals("settings edited during the run survive", "Renamed", now.getName());
        assertEquals("D:\\other", now.getSourcePath());
        assertEquals("name ends .csv", now.getFileSelectionRules());
        assertEquals("run state is saved", TaskStatus.SUCCESS, now.getStatus());
        assertEquals("SUCCESS", now.getLastRunResult());
        assertEquals(12345L, now.getLastKnownRemoteFileEpoch());
        assertEquals(99L, now.getLastKnownRemoteFileSize());
        assertEquals(2, now.getRetryCount());
        assertEquals(LocalDateTime.of(2026, 10, 27, 10, 0), now.getLastRunAt());
        db.close();
    }

    @Test
    public void aTaskDeletedDuringTheRunIsNotBroughtBack() throws Exception {
        TaskDbService db = new TaskDbService(Files.createTempDirectory("opstool-rs").toFile());
        db.save(task("t1"));
        ScheduledTask running = db.loadAll().get(0);
        db.delete("t1");
        running.setStatus(TaskStatus.SUCCESS);
        assertTrue("nothing to write is not an error", db.saveRunState(running));
        assertEquals(0, db.loadAll().size());
        db.close();
    }

    @Test
    public void aTaskWithoutIdIsRefused() throws Exception {
        TaskDbService db = new TaskDbService(Files.createTempDirectory("opstool-rs").toFile());
        assertFalse(db.saveRunState(new ScheduledTask()));
        db.close();
    }
}
