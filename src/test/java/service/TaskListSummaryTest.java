package service;

import static org.junit.Assert.*;

import model.ScheduledTask;
import model.ScheduledTask.*;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;

public class TaskListSummaryTest {

    private static ScheduledTask task(TaskStatus status, String result, boolean hasRun) {
        ScheduledTask t = new ScheduledTask();
        t.setTaskType(TaskType.BACKUP);
        t.setStatus(status);
        t.setLastRunResult(result);
        if (hasRun) t.setLastRunAt(LocalDateTime.of(2026, 11, 1, 8, 0));
        return t;
    }

    @Test public void aTransferTaskShowsDirectionAndMode() {
        ScheduledTask t = new ScheduledTask();
        t.setTaskType(TaskType.FILE_TRANSFER);
        t.setTransferDirection(TransferDirection.INBOUND);
        t.setTransferMode(TransferMode.LATEST_ONLY);
        assertEquals("FILE TRANSFER — INBOUND (LATEST ONLY)", TaskListSummary.typeLabel(t));
        t.setTransferMode(null);
        assertEquals("FILE TRANSFER — INBOUND", TaskListSummary.typeLabel(t));
    }

    @Test public void otherTypesShowJustTheType() {
        ScheduledTask t = new ScheduledTask();
        t.setTaskType(TaskType.OUTLOOK_MAIL);
        assertEquals("OUTLOOK MAIL", TaskListSummary.typeLabel(t));
    }

    @Test public void resultSuffixReadsNaturally() {
        assertEquals("", TaskListSummary.resultSuffix(null));
        assertEquals("", TaskListSummary.resultSuffix(""));
        assertEquals(" · Skipped", TaskListSummary.resultSuffix("SKIPPED"));
        assertEquals(" · FAILED", TaskListSummary.resultSuffix("FAILED"));
    }

    @Test public void countsRunningFailedAndTheSuccessRate() {
        TaskListSummary.Counts c = TaskListSummary.count(List.of(
                task(TaskStatus.RUNNING, "SUCCESS", true),
                task(TaskStatus.PENDING, "SKIPPED", true),
                task(TaskStatus.PENDING, "FAILED", true),
                task(TaskStatus.PENDING, null, false)));
        assertEquals(4, c.total);
        assertEquals(1, c.running);
        assertEquals(1, c.failed);
        assertEquals("67%", c.successRate);
    }

    @Test public void nothingHasRunMeansADash() {
        TaskListSummary.Counts c = TaskListSummary.count(List.of(task(TaskStatus.PENDING, null, false)));
        assertEquals("—", c.successRate);
        assertEquals("—", TaskListSummary.count(List.of()).successRate);
    }
}
