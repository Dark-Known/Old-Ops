package service.sftp;

import static org.junit.Assert.*;

import org.junit.Test;
import service.sftp.UploadStrategy.Mode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class UploadStrategyTest {

    /** Records every call; can be told to refuse a rename or fail a put. */
    private static final class Fake implements UploadStrategy.RemoteFiles {
        final List<String> calls = new ArrayList<>();
        int renameFailures;
        boolean putFails, timeFails;
        public void put(String l, String r) throws IOException { calls.add("put " + r); if (putFails) throw new IOException("put"); }
        public void rename(String a, String b) throws IOException {
            calls.add("rename " + a + " " + b);
            if (renameFailures-- > 0) throw new IOException("exists");
        }
        public void remove(String r) { calls.add("rm " + r); }
        public void setModifiedTime(String r, long ms) throws IOException { calls.add("mtime " + r + " " + ms); if (timeFails) throw new IOException("no"); }
    }

    @Test public void atomicWritesPartThenRenamesThenSetsTime() throws Exception {
        Fake f = new Fake();
        UploadStrategy.upload(f, Mode.ATOMIC, "/l/a.txt", "/in/a.txt", 5000L);
        assertEquals(List.of("put /in/a.txt.part", "rename /in/a.txt.part /in/a.txt", "mtime /in/a.txt 5000"), f.calls);
    }

    @Test public void inPlaceWritesStraightToTheFinalName() throws Exception {
        Fake f = new Fake();
        UploadStrategy.upload(f, Mode.IN_PLACE, "/l/a.txt", "/in/a.txt", 5000L);
        assertEquals(List.of("put /in/a.txt"), f.calls);
    }

    @Test public void aRefusedRenameRemovesTheOldFileAndRetriesOnce() throws Exception {
        Fake f = new Fake();
        f.renameFailures = 1;
        UploadStrategy.upload(f, Mode.ATOMIC, "/l/a", "/in/a", 1L);
        assertEquals(List.of("put /in/a.part", "rename /in/a.part /in/a", "rm /in/a", "rename /in/a.part /in/a", "mtime /in/a 1"), f.calls);
    }

    @Test public void aFailedUploadCleansUpThePartFileAndRethrows() {
        Fake f = new Fake();
        f.putFails = true;
        try { UploadStrategy.upload(f, Mode.ATOMIC, "/l/a", "/in/a", 1L); fail(); }
        catch (IOException expected) { }
        assertEquals(List.of("put /in/a.part", "rm /in/a.part"), f.calls);
    }

    @Test public void aRenameThatFailsTwiceCleansUpAndRethrows() {
        Fake f = new Fake();
        f.renameFailures = 2;
        try { UploadStrategy.upload(f, Mode.ATOMIC, "/l/a", "/in/a", 1L); fail(); }
        catch (IOException expected) { }
        assertEquals("rm /in/a.part", f.calls.get(f.calls.size() - 1));
    }

    @Test public void aServerThatRefusesTimestampsStillCountsAsUploaded() throws Exception {
        Fake f = new Fake();
        f.timeFails = true;
        UploadStrategy.upload(f, Mode.ATOMIC, "/l/a", "/in/a", 1L);
        assertEquals("mtime /in/a 1", f.calls.get(f.calls.size() - 1));
    }

    @Test public void modeParsingDefaultsToAtomic() {
        assertEquals(Mode.ATOMIC, Mode.parse(null));
        assertEquals(Mode.ATOMIC, Mode.parse(""));
        assertEquals(Mode.ATOMIC, Mode.parse("whatever"));
        assertEquals(Mode.IN_PLACE, Mode.parse("in-place"));
        assertEquals(Mode.IN_PLACE, Mode.parse(" IN_PLACE "));
    }
}
