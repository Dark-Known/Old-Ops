package service.backup;

import static org.junit.Assert.*;

import model.Credential;
import model.ScheduledTask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import service.transfer.RemoteDirs;
import service.transfer.RemoteFileStat;
import service.transfer.SizedCommand;
import service.transfer.TransferCommand;
import service.transfer.TransferEngine;
import service.transfer.TransferJob;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The backup service needs no database, no WinSCP and no scheduler: every collaborator is a few lines of fake. */
public class BackupServiceTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> log = new ArrayList<>();
    private final Consumer<String> out = log::add;

    /** Engine that lists a canned directory and records scripts; never touches a network. */
    private static final class FakeEngine implements TransferEngine {
        final Map<String, RemoteFileStat> listing = new LinkedHashMap<>();
        final List<String> listedDirs = new ArrayList<>();
        final List<String> scriptTaskIds = new ArrayList<>();
        final List<TransferJob> jobs = new ArrayList<>();
        @Override public boolean run(TransferJob job, Consumer<String> l, String taskId) { scriptTaskIds.add(taskId); jobs.add(job); return true; }
        @Override public Map<String, RemoteFileStat> listFiles(Credential c, String pw, String dir, Consumer<String> l) {
            listedDirs.add(dir);
            return listing;
        }
    }

    private static Credential cred(String name, String host) {
        Credential c = new Credential();
        c.setUsername(name);
        c.setHost(host);
        return c;
    }

    private final FakeEngine engine = new FakeEngine();
    private final List<List<SizedCommand>> batchCalls = new ArrayList<>();
    private boolean batchResult = true;
    private long maxBytes = 1_000_000L;

    private BackupService service(Map<String, Credential> known) {
        return new BackupService(
                (name, side, l) -> {
                    Credential c = known.get(name);
                    if (c == null) l.accept("[ERROR] No credential named '" + name + "' for backup " + side);
                    return c;
                },
                engine,
                new RemoteDirs(engine),
                (target, pw, cmds, l, taskId) -> { batchCalls.add(cmds); return batchResult; },
                new BackupService.BatchSettings() {
                    @Override public long batchMaxBytes() { return maxBytes; }
                    @Override public int batchIntervalSeconds() { return 0; }
                });
    }

    private ScheduledTask task(String src, String dst, int days) {
        ScheduledTask t = new ScheduledTask();
        t.setId("bk1");
        t.setBackupSourcePath(src);
        t.setBackupDestinationPath(dst);
        t.setBackupRetentionDays(days);
        return t;
    }

    private File fileAgedDays(File dir, String name, int daysOld) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[]{1, 2, 3});
        long ms = LocalDate.now().minusDays(daysOld).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertTrue(f.setLastModified(ms));
        return f;
    }

    private boolean logged(String fragment) {
        return log.stream().anyMatch(s -> s.contains(fragment));
    }

    @Test public void aMissingSourceOrDestinationPathIsRejected() {
        BackupService s = service(Map.of());
        assertFalse(s.run(task("", "/d", 3), out));
        assertTrue(logged("source path is not configured"));
        log.clear();
        assertFalse(s.run(task("/s", "  ", 3), out));
        assertTrue(logged("destination path is not configured"));
    }

    @Test public void remoteToRemoteIsRefused() {
        ScheduledTask t = task("/s", "/d", 3);
        t.setBackupSourceUsername("a");
        t.setBackupDestinationUsername("b");
        assertFalse(service(Map.of("a", cred("a", "h1"), "b", cred("b", "h2"))).run(t, out));
        assertTrue(logged("cannot both be remote"));
    }

    @Test public void anUnknownCredentialStopsTheRun() {
        ScheduledTask t = task("/s", "/d", 3);
        t.setBackupSourceUsername("ghost");
        assertFalse(service(Map.of()).run(t, out));
        assertTrue(logged("No credential named 'ghost'"));
    }

    @Test public void localToLocalMovesOldFilesIntoDayFoldersAndKeepsRecentOnes() throws Exception {
        File src = tmp.newFolder("src"), dst = tmp.newFolder("dst");
        fileAgedDays(src, "old.txt", 10);
        fileAgedDays(src, "today.txt", 0);
        fileAgedDays(src, "edge.txt", 2);                  // retention 3 keeps today, -1, -2

        assertTrue(service(Map.of()).run(task(src.getPath(), dst.getPath(), 3), out));

        File dayDir = new File(dst, LocalDate.now().minusDays(10).toString());
        assertTrue(new File(dayDir, "old.txt").isFile());
        assertFalse("moved, not copied", new File(src, "old.txt").exists());
        assertTrue(new File(src, "today.txt").isFile());
        assertTrue(new File(src, "edge.txt").isFile());
    }

    @Test public void nothingOldEnoughIsASuccessWithAnExplanation() throws Exception {
        File src = tmp.newFolder("src"), dst = tmp.newFolder("dst");
        fileAgedDays(src, "fresh.txt", 0);
        assertTrue(service(Map.of()).run(task(src.getPath(), dst.getPath(), 3), out));
        assertTrue(logged("Nothing to back up"));
    }

    @Test public void aMissingLocalSourceFolderFails() throws Exception {
        File dst = tmp.newFolder("dst");
        assertFalse(service(Map.of()).run(task(new File(tmp.getRoot(), "nope").getPath(), dst.getPath(), 3), out));
        assertTrue(logged("source folder does not exist"));
    }

    @Test public void aRetentionBelowOneDayIsTreatedAsOne() throws Exception {
        File src = tmp.newFolder("src"), dst = tmp.newFolder("dst");
        fileAgedDays(src, "yesterday.txt", 1);
        fileAgedDays(src, "today.txt", 0);
        assertTrue(service(Map.of()).run(task(src.getPath(), dst.getPath(), 0), out));
        assertTrue("yesterday is outside a 1-day window", new File(new File(dst, LocalDate.now().minusDays(1).toString()), "yesterday.txt").isFile());
        assertTrue(new File(src, "today.txt").isFile());
    }

    @Test public void remoteToLocalPullsOldFilesWithGetDeleteIntoDayFolders() throws Exception {
        File dst = tmp.newFolder("dst");
        long old = LocalDate.now().minusDays(9).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long fresh = System.currentTimeMillis();
        engine.listing.put("/in/old file.csv", new RemoteFileStat(old, 50L));
        engine.listing.put("/in/new.csv", new RemoteFileStat(fresh, 10L));
        ScheduledTask t = task("/in", dst.getPath(), 3);
        t.setBackupSourceUsername("srv");

        assertTrue(service(Map.of("srv", cred("srv", "files.example.com"))).run(t, out));

        assertEquals(List.of("/in/"), engine.listedDirs);
        assertEquals("one batch call", 1, batchCalls.size());
        assertEquals("only the old file is pulled", 1, batchCalls.get(0).size());
        TransferCommand.Download cmd = (TransferCommand.Download) batchCalls.get(0).get(0).command;
        assertEquals("/in/old file.csv", cmd.remotePath());
        assertTrue("the original is removed in the same session", cmd.deleteRemoteAfter());
        assertTrue(cmd.localPath(), cmd.localPath().contains(LocalDate.now().minusDays(9).toString()));
        assertTrue(cmd.localPath(), cmd.localPath().endsWith("old file.csv"));
        assertEquals(50L, batchCalls.get(0).get(0).size);
        assertTrue("the day folder is created locally", new File(dst, LocalDate.now().minusDays(9).toString()).isDirectory());
    }

    @Test public void aFailedRemoteBatchFailsTheBackup() throws Exception {
        File dst = tmp.newFolder("dst");
        engine.listing.put("/in/a.csv", new RemoteFileStat(
                LocalDate.now().minusDays(9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), 5L));
        batchResult = false;
        ScheduledTask t = task("/in", dst.getPath(), 3);
        t.setBackupSourceUsername("srv");
        assertFalse(service(Map.of("srv", cred("srv", "h"))).run(t, out));
        assertTrue(logged("[WARNING] Backup run complete."));
    }

    @Test public void localToRemoteMakesDayFoldersThenPutsAndOnlyThenDeletesLocalOriginals() throws Exception {
        File src = tmp.newFolder("src");
        File old = fileAgedDays(src, "old.txt", 10);
        ScheduledTask t = task(src.getPath(), "/archive", 3);
        t.setBackupDestinationUsername("srv");

        assertTrue(service(Map.of("srv", cred("srv", "h"))).run(t, out));

        assertEquals("mkdir session ran under its pseudo id", List.of("backup-mkdir"), engine.scriptTaskIds);
        assertEquals(1, batchCalls.size());
        assertEquals("the day folder is created first, leniently", 1, engine.jobs.size());
        assertFalse(engine.jobs.get(0).stopOnError());
        assertEquals(List.of(new TransferCommand.MakeRemoteDir("/archive/" + LocalDate.now().minusDays(10))),
                engine.jobs.get(0).commands());
        TransferCommand.Upload cmd = (TransferCommand.Upload) batchCalls.get(0).get(0).command;
        assertEquals("/archive/" + LocalDate.now().minusDays(10) + "/old.txt", cmd.remotePath());
        assertTrue(cmd.localPath(), cmd.localPath().endsWith("old.txt"));
        assertFalse("uploaded, so the local original is removed", old.exists());
    }

    @Test public void localOriginalsSurviveAFailedUpload() throws Exception {
        File src = tmp.newFolder("src");
        File old = fileAgedDays(src, "old.txt", 10);
        batchResult = false;
        ScheduledTask t = task(src.getPath(), "/archive", 3);
        t.setBackupDestinationUsername("srv");
        assertFalse(service(Map.of("srv", cred("srv", "h"))).run(t, out));
        assertTrue("never delete what was not uploaded", old.exists());
    }

    @Test public void aBacklogLargerThanTheByteCapIsSplitIntoBatches() throws Exception {
        File src = tmp.newFolder("src"), dst = tmp.newFolder("dst");
        for (int i = 0; i < 4; i++) fileAgedDays(src, "f" + i + ".txt", 10);   // 3 bytes each
        maxBytes = 6;                                                            // two files per batch
        assertTrue(service(Map.of()).run(task(src.getPath(), dst.getPath(), 3), out));
        assertTrue(log.toString(), logged("Splitting into 2 batch(es)"));
        assertEquals(4, new File(dst, LocalDate.now().minusDays(10).toString()).list().length);
    }
}
