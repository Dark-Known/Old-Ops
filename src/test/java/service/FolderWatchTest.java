package service;

import model.FileSelection;
import model.ScheduledTask;
import model.WatchOptions;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class FolderWatchTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private static ScheduledTask task(String opts, FileSelection sel) {
        ScheduledTask t = new ScheduledTask();
        t.setId("w1");
        t.setName("Watch inbox");
        t.setTaskType(ScheduledTask.TaskType.FOLDER_WATCH);
        t.setWatchOptions(opts);
        if (sel != null) t.setFileSelectionRules(sel.toText());
        return t;
    }

    private static List<String> names(List<FolderWatch.Item> l) {
        List<String> n = new ArrayList<>();
        for (FolderWatch.Item i : l) n.add(i.name());
        java.util.Collections.sort(n);
        return n;
    }

    @Test
    public void theFirstLookOnlyLearnsWhatIsThereThenNewAndChangedFilesAreReported() throws Exception {
        File dir = Files.createTempDirectory("fw").toFile();
        List<FolderWatch.Item> folder = new ArrayList<>(List.of(new FolderWatch.Item("old.csv", 10, 1000)));
        FolderWatch w = new FolderWatch(dir, (t, log) -> new ArrayList<>(folder), now::get);
        ScheduledTask t = task("", null);

        FolderWatch.Outcome first = w.look(t, s -> {
        });
        assertTrue(first.baseline());
        assertTrue("nothing is reported on the first look", first.added().isEmpty() && first.changed().isEmpty());

        FolderWatch.Outcome quiet = w.look(t, s -> {
        });
        assertTrue(quiet.quiet());

        folder.add(new FolderWatch.Item("new1.csv", 5, 2000));
        folder.add(new FolderWatch.Item("new2.csv", 6, 2001));
        FolderWatch.Outcome two = w.look(t, s -> {
        });
        assertEquals(List.of("new1.csv", "new2.csv"), names(two.added()));
        assertTrue(two.changed().isEmpty());
        assertTrue("reported once", w.look(t, s -> {
        }).quiet());

        folder.set(0, new FolderWatch.Item("old.csv", 11, 3000));
        FolderWatch.Outcome ch = w.look(t, s -> {
        });
        assertEquals(List.of("old.csv"), names(ch.changed()));
        assertTrue(ch.added().isEmpty());
        assertTrue(w.look(t, s -> {
        }).quiet());
    }

    @Test
    public void anEmptyFolderIsStillABaselineSoLaterFilesAreReported() throws Exception {
        File dir = Files.createTempDirectory("fw2").toFile();
        List<FolderWatch.Item> folder = new ArrayList<>();
        FolderWatch w = new FolderWatch(dir, (t, log) -> new ArrayList<>(folder), now::get);
        ScheduledTask t = task("", null);
        assertTrue(w.look(t, s -> {
        }).baseline());
        // the "no new file" check may start its clock before the first look: that must not turn the next files into a baseline
        new TransferLedger(dir, now::get).startWatching("w1");
        folder.add(new FolderWatch.Item("a.csv", 1, 5));
        assertEquals(List.of("a.csv"), names(w.look(t, s -> {
        }).added()));
    }

    @Test
    public void reportOptionsAndNameRulesAreRespected() throws Exception {
        File dir = Files.createTempDirectory("fw3").toFile();
        List<FolderWatch.Item> folder = new ArrayList<>(List.of(new FolderWatch.Item("a.csv", 1, 1)));
        FileSelection csvOnly = new FileSelection().addRule(new FileSelection.Rule(false, FileSelection.Op.ENDS_WITH, ".csv", true));
        FolderWatch w = new FolderWatch(dir, (t, log) -> new ArrayList<>(folder), now::get);
        ScheduledTask onlyNew = task(new WatchOptions(true, false).toText(), csvOnly);
        w.look(onlyNew, s -> {
        });
        folder.add(new FolderWatch.Item("b.txt", 1, 2));      // ignored by the name rule
        folder.add(new FolderWatch.Item("c.csv", 1, 3));
        folder.set(0, new FolderWatch.Item("a.csv", 2, 9));   // changed, but changes are not wanted
        FolderWatch.Outcome o = w.look(onlyNew, s -> {
        });
        assertEquals(List.of("c.csv"), names(o.added()));
        assertTrue(o.changed().isEmpty());
        assertEquals(1, o.ignored());
        assertTrue("the change that was not wanted is not reported later either", w.look(onlyNew, s -> {
        }).quiet());
    }

    @Test
    public void quietTimeEndsWithAResumedNoteAfterASilenceAlert() throws Exception {
        File dir = Files.createTempDirectory("fw4").toFile();
        List<FolderWatch.Item> folder = new ArrayList<>();
        FolderWatch w = new FolderWatch(dir, (t, log) -> new ArrayList<>(folder), now::get);
        ScheduledTask t = task("", null);
        w.look(t, s -> {
        });
        TransferLedger ledger = new TransferLedger(dir, now::get);
        assertTrue(ledger.claimSilenceAlert("w1", 0) || true);
        ledger.markFilesTransferred("w1");                 // an earlier arrival
        now.addAndGet(3_600_000);
        assertTrue("an alert was raised in the meantime", ledger.claimSilenceAlert("w1", 0));
        folder.add(new FolderWatch.Item("x.csv", 1, 7));
        assertTrue(w.look(t, s -> {
        }).resumed());
        folder.add(new FolderWatch.Item("y.csv", 1, 8));
        assertTrue(!w.look(t, s -> {
        }).resumed());
    }

    @Test
    public void aLocalFolderIsListedAndAMissingOneFails() throws Exception {
        File dir = Files.createTempDirectory("fw5").toFile();
        Files.writeString(new File(dir, "f.txt").toPath(), "hello");
        new File(dir, "sub").mkdir();
        List<FolderWatch.Item> l = FolderWatch.listLocal(dir.getAbsolutePath());
        assertEquals(1, l.size());
        assertEquals(5, l.get(0).size());
        try {
            FolderWatch.listLocal(new File(dir, "missing").getAbsolutePath());
            fail("should fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
        assertEquals(new WatchOptions(false, true), WatchOptions.parse(new WatchOptions(false, true).toText()));
    }

    @Test
    public void aWatchTaskIsSavedAndLoadedWithItsFolderAndOptions() throws Exception {
        TaskDbService db = new TaskDbService(Files.createTempDirectory("fw6").toFile());
        ScheduledTask t = task(new WatchOptions(true, false).toText(), null);
        t.setBackupSourcePath("/inbox");
        t.setBackupSourceUsername("partner-sftp");
        assertTrue(db.save(t));
        ScheduledTask back = db.loadAll().get(0);
        assertEquals(ScheduledTask.TaskType.FOLDER_WATCH, back.getTaskType());
        assertEquals("/inbox", back.getBackupSourcePath());
        assertEquals("partner-sftp", back.getBackupSourceUsername());
        assertEquals(new WatchOptions(true, false), WatchOptions.parse(back.getWatchOptions()));
    }
}
