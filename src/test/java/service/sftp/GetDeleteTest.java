package service.sftp;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** "get -delete" downloads and removes the original in the same session, and only if the copy is complete. */
public class GetDeleteTest {

    /** Minimal in-memory server: one flat folder. */
    static final class Fake implements SftpClient {
        final Map<String, byte[]> files = new HashMap<>();
        boolean truncate;
        int connects;
        public List<RemoteEntry> list(String d) { return List.of(); }
        public RemoteEntry stat(String p) {
            byte[] b = files.get(p);
            return b == null ? null : new RemoteEntry(p.substring(p.lastIndexOf('/') + 1), b.length, 0L, false);
        }
        public void download(String p, File local) throws java.io.IOException {
            byte[] b = files.get(p);
            Files.write(local.toPath(), truncate ? Arrays.copyOf(b, b.length / 2) : b);
        }
        public void upload(File l, String r) {}
        public void mkdirs(String d) {}
        public boolean delete(String p) { return files.remove(p) != null; }
        public void rename(String a, String b) {}
        public void cancel() {}
        public void close() {}
    }

    private static List<String> script(String get) {
        return List.of("option batch abort", "option confirm off", "open sftp://u@h/ -password=\"x\"", get, "close", "exit");
    }

    @Test
    public void deletesOriginalInSameSession() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a.txt", "hello world".getBytes());
        File dir = Files.createTempDirectory("gd").toFile();
        List<String> logs = new ArrayList<>();
        var out = new SftpScriptRunner((h, p, u, pw) -> { f.connects++; return f; }, null, null)
                .run(script("get -delete \"/in/a.txt\" \"" + dir.getAbsolutePath().replace("\\", "\\\\") + "/a.txt\""), "x", logs::add);
        assertTrue(logs.toString(), out.ok());
        assertEquals(1, f.connects);
        assertTrue(new File(dir, "a.txt").isFile());
        assertFalse(f.files.containsKey("/in/a.txt"));
    }

    @Test
    public void keepsOriginalWhenCopyIsIncomplete() throws Exception {
        Fake f = new Fake();
        f.truncate = true;
        f.files.put("/in/a.txt", "hello world".getBytes());
        File dir = Files.createTempDirectory("gd").toFile();
        var out = new SftpScriptRunner((h, p, u, pw) -> f, null, null)
                .run(script("get -delete \"/in/a.txt\" \"" + dir.getAbsolutePath().replace("\\", "\\\\") + "/a.txt\""), "x", s -> {});
        assertFalse(out.ok());
        assertTrue(f.files.containsKey("/in/a.txt"));
    }

    @Test
    public void plainGetStillKeepsOriginal() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a.txt", "hello".getBytes());
        File dir = Files.createTempDirectory("gd").toFile();
        var out = new SftpScriptRunner((h, p, u, pw) -> f, null, null)
                .run(script("get \"/in/a.txt\" \"" + dir.getAbsolutePath().replace("\\", "\\\\") + "/a.txt\""), "x", s -> {});
        assertTrue(out.ok());
        assertTrue(f.files.containsKey("/in/a.txt"));
    }
}
