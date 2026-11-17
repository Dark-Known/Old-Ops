package service.exec;

import static org.junit.Assert.*;

import model.ScheduledTask;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TaskExecutorTest {

    private static ScheduledTask task(ScheduledTask.TaskType type) {
        ScheduledTask t = new ScheduledTask();
        t.setId("t1");
        t.setName("demo");
        t.setTaskType(type);
        return t;
    }

    private static RunContext ctx(ScheduledTask t, List<String> log) {
        return new RunContext(t, log::add, Collections.emptySet());
    }

    /** A minimal executor, to show a new task type needs nothing but registering one. */
    private static TaskExecutor fixed(ScheduledTask.TaskType type, TaskOutcome result) {
        return new TaskExecutor() {
            @Override public ScheduledTask.TaskType type() { return type; }
            @Override public TaskOutcome execute(RunContext c) { return result; }
        };
    }

    // ── registry ─────────────────────────────────────────────────────────────

    @Test public void theRegistryFindsTheExecutorForItsType() {
        TaskExecutor backup = fixed(ScheduledTask.TaskType.BACKUP, TaskOutcome.ok());
        TaskExecutorRegistry r = new TaskExecutorRegistry().register(backup);
        assertSame(backup, r.find(ScheduledTask.TaskType.BACKUP));
    }

    @Test public void aTypeWithNoExecutorIsNotFound() {
        TaskExecutorRegistry r = new TaskExecutorRegistry().register(fixed(ScheduledTask.TaskType.BACKUP, TaskOutcome.ok()));
        assertNull(r.find(ScheduledTask.TaskType.OUTLOOK_MAIL));
    }

    @Test public void aNullTypeIsNotFoundRatherThanFailing() {
        assertNull(new TaskExecutorRegistry().find(null));
    }

    @Test(expected = IllegalStateException.class)
    public void twoExecutorsCannotClaimTheSameType() {
        new TaskExecutorRegistry()
                .register(fixed(ScheduledTask.TaskType.BACKUP, TaskOutcome.ok()))
                .register(fixed(ScheduledTask.TaskType.BACKUP, TaskOutcome.ok()));
    }

    // ── outcomes ─────────────────────────────────────────────────────────────

    @Test public void okIsASuccessThatWasNotSkipped() {
        TaskOutcome o = TaskOutcome.ok();
        assertTrue(o.success());
        assertFalse(o.skipped());
        assertNull(o.skipReason());
        assertNull(o.failureReason());
    }

    @Test public void aSkipIsStillASuccessAndKeepsItsReason() {
        TaskOutcome o = TaskOutcome.skip("nothing new");
        assertTrue(o.success());
        assertTrue(o.skipped());
        assertEquals("nothing new", o.skipReason());
    }

    @Test public void aFailureCarriesItsReasonAndIsNotASkip() {
        TaskOutcome o = TaskOutcome.failure("boom");
        assertFalse(o.success());
        assertFalse(o.skipped());
        assertEquals("boom", o.failureReason());
    }

    // ── folder-watch executor (the one that needs no transfer engine) ────────

    @Test public void eachExecutorReportsTheTypeItRuns() {
        assertEquals(ScheduledTask.TaskType.FOLDER_WATCH, new FolderWatchExecutor((t, l) -> null, new FolderEvents()).type());
        assertEquals(ScheduledTask.TaskType.BACKUP, new BackupExecutor(null).type());
        assertEquals(ScheduledTask.TaskType.OUTLOOK_MAIL, new MailExecutor(null).type());
        assertEquals(ScheduledTask.TaskType.FILE_TRANSFER, new FileTransferExecutor(null, (t, f, s) -> { }).type());
    }

    // ── folder watch ─────────────────────────────────────────────────────────

    /** Records what the executor reports, instead of writing events anywhere. */
    private static class FolderEvents implements FolderWatchExecutor.Events {
        final List<String> calls = new ArrayList<>();
        @Override public void armed(ScheduledTask t, int watching, String where) { calls.add("armed:" + watching + ":" + where); }
        @Override public void found(model.EventKind kind, ScheduledTask t, List<service.FolderWatch.Item> files, String where, String title) {
            calls.add(kind + ":" + files.size() + ":" + title);
        }
        @Override public void resumed(ScheduledTask t, String where) { calls.add("resumed:" + where); }
    }

    private static service.FolderWatch.Item item(String n) { return new service.FolderWatch.Item(n, 1, 2); }

    private static service.FolderWatch.Outcome outcome(boolean baseline, List<service.FolderWatch.Item> added,
            List<service.FolderWatch.Item> changed, boolean resumed, int watching) {
        return new service.FolderWatch.Outcome(baseline, added, changed, resumed, watching, 0);
    }

    private static ScheduledTask watchTask() {
        ScheduledTask t = task(ScheduledTask.TaskType.FOLDER_WATCH);
        t.setBackupSourcePath("/in");
        return t;
    }

    @Test public void aFirstLookRemembersTheFilesAndReportsNoneOfThem() {
        FolderEvents ev = new FolderEvents();
        List<String> log = new ArrayList<>();
        TaskOutcome o = new FolderWatchExecutor((t, l) -> outcome(true, List.of(), List.of(), false, 7), ev)
                .execute(ctx(watchTask(), log));
        assertTrue(o.success());
        assertFalse(o.skipped());
        assertEquals(List.of("armed:7:/in"), ev.calls);
        assertTrue(log.toString(), log.stream().anyMatch(x -> x.contains("First look: 7 file(s)")));
    }

    @Test public void newAndChangedFilesAreReportedSeparately() {
        FolderEvents ev = new FolderEvents();
        TaskOutcome o = new FolderWatchExecutor(
                (t, l) -> outcome(false, List.of(item("a"), item("b")), List.of(item("c")), false, 9), ev)
                .execute(ctx(watchTask(), new ArrayList<>()));
        assertTrue(o.success());
        assertFalse(o.skipped());
        assertEquals(List.of("FILES_DETECTED:2:New file(s)", "FILES_CHANGED:1:Changed file(s)"), ev.calls);
    }

    @Test public void filesArrivingAgainIsReported() {
        FolderEvents ev = new FolderEvents();
        new FolderWatchExecutor((t, l) -> outcome(false, List.of(item("a")), List.of(), true, 1), ev)
                .execute(ctx(watchTask(), new ArrayList<>()));
        assertEquals(List.of("FILES_DETECTED:1:New file(s)", "resumed:/in"), ev.calls);
    }

    @Test public void aLookThatFindsNothingNewIsReportedAsSkipped() {
        FolderEvents ev = new FolderEvents();
        TaskOutcome o = new FolderWatchExecutor((t, l) -> outcome(false, List.of(), List.of(), false, 4), ev)
                .execute(ctx(watchTask(), new ArrayList<>()));
        assertTrue(o.success());
        assertTrue(o.skipped());
        assertEquals("No new or changed files", o.skipReason());
        assertTrue(ev.calls.isEmpty());
    }

    @Test public void aRemoteFolderIsNamedWithItsCredentialInLogsAndEvents() {
        FolderEvents ev = new FolderEvents();
        ScheduledTask t = watchTask();
        t.setBackupSourceUsername(" srv ");
        List<String> log = new ArrayList<>();
        new FolderWatchExecutor((x, l) -> outcome(true, List.of(), List.of(), false, 0), ev).execute(ctx(t, log));
        assertEquals("[INFO] Watching folder: /in (SFTP, srv)", log.get(0));
        assertEquals(List.of("armed:0:/in (SFTP, srv)"), ev.calls);
    }

    @Test public void anUnreadableFolderFailsWithAReasonAndALogLine() {
        List<String> log = new ArrayList<>();
        FolderEvents ev = new FolderEvents();
        TaskOutcome o = new FolderWatchExecutor((t, l) -> { throw new IOException("share is offline"); }, ev)
                .execute(ctx(watchTask(), log));
        assertFalse(o.success());
        assertEquals("Could not look at the folder: share is offline", o.failureReason());
        assertTrue(log.contains("[ERROR] Could not look at the folder: share is offline"));
        assertTrue(ev.calls.isEmpty());
    }

    @Test public void aFailureWhileRecordingAnEventFailsTheRunToo() {
        FolderWatchExecutor.Events broken = new FolderEvents() {
            @Override public void found(model.EventKind k, ScheduledTask t, List<service.FolderWatch.Item> f, String w, String title) {
                throw new IllegalStateException("history unavailable");
            }
        };
        TaskOutcome o = new FolderWatchExecutor((t, l) -> outcome(false, List.of(item("a")), List.of(), false, 1), broken)
                .execute(ctx(watchTask(), new ArrayList<>()));
        assertFalse(o.success());
        assertEquals("Could not look at the folder: history unavailable", o.failureReason());
    }
}
