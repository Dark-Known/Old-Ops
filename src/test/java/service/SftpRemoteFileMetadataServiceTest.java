package service;

import static org.junit.Assert.*;

import model.RemoteFileException;
import model.RemoteFileMetadata;
import org.junit.Test;
import service.sftp.RemoteEntry;
import service.sftp.SftpClient;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** The watcher's listing and stat rules, on an in-memory client (no JSch, no server). */
public class SftpRemoteFileMetadataServiceTest {

    private static final class Fake implements SftpClient {
        List<RemoteEntry> entries = List.of();
        RemoteEntry statResult;
        boolean failList, failStat;
        String listedVia, statted;
        public List<RemoteEntry> list(String d) throws IOException { listedVia = "list " + d; if (failList) throw new IOException("boom"); return entries; }
        public List<RemoteEntry> listViaCd(String d) throws IOException { if (failList) throw new IOException("boom"); listedVia = "cd " + d; return entries; }
        public RemoteEntry stat(String p) throws IOException { statted = p; if (failStat) throw new IOException("denied"); return statResult; }
        public void download(String p, File l) { }
        public void upload(File l, String r) { }
        public void mkdirs(String d) { }
        public boolean delete(String p) { return true; }
        public void rename(String a, String b) { }
        public void cancel() { }
        public void close() { }
    }

    @Test public void listsOnlyFilesNewerThanTheCutoffOldestFirstAndListsViaCd() throws Exception {
        Fake f = new Fake();
        f.entries = List.of(
                new RemoteEntry("new2.txt", 20, 300, false),
                new RemoteEntry("old.txt", 5, 100, false),
                new RemoteEntry("sub", 0, 400, true),
                new RemoteEntry("new1.txt", 10, 200, false));
        List<RemoteFileMetadata> got = new SftpRemoteFileMetadataService(f)
                .getFilesModifiedAfter("C:\\in\\data", Instant.ofEpochSecond(150));
        assertEquals("cd C:/in/data", f.listedVia);
        assertEquals(2, got.size());
        assertEquals("new1.txt", got.get(0).fileName());
        assertEquals("new2.txt", got.get(1).fileName());
        assertEquals(10, got.get(0).size());
    }

    @Test(expected = RemoteFileException.class)
    public void aListingFailureBecomesARemoteFileException() throws Exception {
        Fake f = new Fake();
        f.failList = true;
        new SftpRemoteFileMetadataService(f).getFilesModifiedAfter("/in", Instant.EPOCH);
    }

    @Test public void statBuildsMetadataAndAddsTheSlash() throws Exception {
        Fake f = new Fake();
        f.statResult = new RemoteEntry("a.txt", 7, 500, false);
        RemoteFileMetadata m = new SftpRemoteFileMetadataService(f).statFile("/in", "a.txt");
        assertEquals("/in/a.txt", f.statted);
        assertEquals(7, m.size());
        assertEquals(Instant.ofEpochSecond(500), m.lastModified());
    }

    @Test public void aMissingFileOrADirectoryIsNull() throws Exception {
        Fake f = new Fake();
        assertNull(new SftpRemoteFileMetadataService(f).statFile("/in", "gone.txt"));
        f.statResult = new RemoteEntry("d", 0, 1, true);
        assertNull(new SftpRemoteFileMetadataService(f).statFile("/in", "d"));
    }

    @Test(expected = RemoteFileException.class)
    public void aStatFailureBecomesARemoteFileException() throws Exception {
        Fake f = new Fake();
        f.failStat = true;
        new SftpRemoteFileMetadataService(f).statFile("/in", "a.txt");
    }
}
