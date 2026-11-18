package service.sftp;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class LocalSftpClientTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void uploadCopiesAndKeepsTheModifiedTime() throws Exception {
        File src = tmp.newFile("a.txt");
        Files.writeString(src.toPath(), "hello");
        src.setLastModified(1_600_000_000_000L);
        File dst = new File(tmp.getRoot(), "out/sub/a.txt");
        try (LocalSftpClient c = new LocalSftpClient()) {
            c.mkdirs(dst.getParent());
            c.upload(src, dst.getPath());
        }
        assertEquals("hello", Files.readString(dst.toPath(), StandardCharsets.UTF_8));
        assertEquals(1_600_000_000_000L, dst.lastModified());
        assertFalse(new File(dst.getPath() + ".part").exists());
    }

    @Test public void listStatDeleteAndRename() throws Exception {
        File d = tmp.newFolder("d");
        Files.writeString(new File(d, "b.txt").toPath(), "x");
        try (LocalSftpClient c = new LocalSftpClient()) {
            List<RemoteEntry> l = c.list(d.getPath());
            assertEquals(1, l.size());
            assertEquals("b.txt", l.get(0).name());
            assertNull(c.stat(new File(d, "none").getPath()));
            c.rename(new File(d, "b.txt").getPath(), new File(d, "c.txt").getPath());
            assertTrue(c.delete(new File(d, "c.txt").getPath()));
            assertFalse(c.delete(new File(d, "c.txt").getPath()));
        }
    }
}
