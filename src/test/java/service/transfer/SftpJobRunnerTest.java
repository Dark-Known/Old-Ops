package service.transfer;

import static org.junit.Assert.*;

import model.Credential;
import org.junit.Test;
import service.sftp.RemoteEntry;
import service.sftp.SftpClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** What a job does on an SFTP connection, checked on an in-memory server. */
public class SftpJobRunnerTest {

    /** Minimal in-memory server: one flat namespace of files plus a set of folders. */
    static final class Fake implements SftpClient {
        final Map<String, byte[]> files = new HashMap<>();
        final Set<String> dirs = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        boolean truncate, closed;
        public List<RemoteEntry> list(String d) { return List.of(); }
        public RemoteEntry stat(String p) {
            if (dirs.contains(p)) return new RemoteEntry(p.substring(p.lastIndexOf('/') + 1), 0, 0L, true);
            byte[] b = files.get(p);
            return b == null ? null : new RemoteEntry(p.substring(p.lastIndexOf('/') + 1), b.length, 0L, false);
        }
        public void download(String p, File local) throws IOException {
            calls.add("get " + p);
            byte[] b = files.get(p);
            Files.write(local.toPath(), truncate ? Arrays.copyOf(b, b.length / 2) : b);
        }
        public void upload(File l, String r) throws IOException { calls.add("put " + r); files.put(r, Files.readAllBytes(l.toPath())); }
        public void mkdirs(String d) { calls.add("mkdir " + d); dirs.add(d); }
        public boolean delete(String p) { calls.add("rm " + p); return files.remove(p) != null; }
        public void rename(String a, String b) { calls.add("mv " + a + " " + b); files.put(b, files.remove(a)); }
        public void cancel() {}
        public void close() { closed = true; }
    }

    private static Credential cred(String host) {
        Credential c = new Credential();
        c.setUsername("u");
        c.setHost(host);
        return c;
    }

    private static TransferJob strict(TransferCommand... c) { return TransferJob.strict(cred("h"), "x", Arrays.asList(c)); }
    private static TransferJob lenient(TransferCommand... c) { return TransferJob.lenient(cred("h"), "x", Arrays.asList(c)); }
    private static SftpJobRunner runner(Fake f) { return new SftpJobRunner((h, p, u, pw) -> f, null, null); }
    private static File tmpDir() throws IOException { return Files.createTempDirectory("sjr").toFile(); }

    // ── get -delete ──────────────────────────────────────────────────────────

    @Test public void deletesOriginalInSameSession() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a.txt", "hello world".getBytes());
        File dir = tmpDir();
        int[] connects = {0};
        List<String> logs = new ArrayList<>();
        boolean ok = new SftpJobRunner((h, p, u, pw) -> { connects[0]++; return f; }, null, null)
                .run(strict(new TransferCommand.Download("/in/a.txt", new File(dir, "a.txt").getPath(), true)), logs::add);
        assertTrue(logs.toString(), ok);
        assertEquals(1, connects[0]);
        assertTrue(new File(dir, "a.txt").isFile());
        assertFalse(f.files.containsKey("/in/a.txt"));
    }

    @Test public void keepsOriginalWhenCopyIsIncomplete() throws Exception {
        Fake f = new Fake();
        f.truncate = true;
        f.files.put("/in/a.txt", "hello world".getBytes());
        assertFalse(runner(f).run(strict(new TransferCommand.Download("/in/a.txt", new File(tmpDir(), "a.txt").getPath(), true)), s -> { }));
        assertTrue(f.files.containsKey("/in/a.txt"));
    }

    @Test public void plainGetStillKeepsOriginal() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a.txt", "hello".getBytes());
        assertTrue(runner(f).run(strict(new TransferCommand.Download("/in/a.txt", new File(tmpDir(), "a.txt").getPath(), false)), s -> { }));
        assertTrue(f.files.containsKey("/in/a.txt"));
    }

    @Test public void aLocalFolderAsTheDestinationReceivesTheFileUnderItsOwnName() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a.txt", "x".getBytes());
        File dir = tmpDir();
        assertTrue(runner(f).run(strict(new TransferCommand.Download("/in/a.txt", dir.getPath(), false)), s -> { }));
        assertTrue(new File(dir, "a.txt").isFile());
    }

    // ── upload / mkdir / rm / mv ─────────────────────────────────────────────

    @Test public void anExistingRemoteFolderReceivesTheUploadUnderTheLocalName() throws Exception {
        Fake f = new Fake();
        f.dirs.add("/dest");
        File src = new File(tmpDir(), "a.txt");
        Files.write(src.toPath(), "x".getBytes());
        assertTrue(runner(f).run(strict(new TransferCommand.Upload(src.getPath(), "/dest")), s -> { }));
        assertTrue(f.files.containsKey("/dest/a.txt"));
    }

    @Test public void aMissingLocalFileFailsTheUpload() throws Exception {
        List<String> log = new ArrayList<>();
        assertFalse(runner(new Fake()).run(strict(new TransferCommand.Upload(new File(tmpDir(), "nope").getPath(), "/x")), log::add));
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[ERROR] put failed: Local file not found")));
    }

    @Test public void commandsRunInOrderOnOneConnection() throws Exception {
        Fake f = new Fake();
        f.files.put("/in/a", "A".getBytes());
        assertTrue(runner(f).run(strict(
                new TransferCommand.MakeRemoteDir("/done"),
                new TransferCommand.MoveRemote("/in/a", "/done/a"),
                new TransferCommand.DeleteRemote("/done/a")), s -> { }));
        assertEquals(List.of("mkdir /done", "mv /in/a /done/a", "rm /done/a"), f.calls);
        assertTrue("the session is closed at the end", f.closed);
    }

    // ── error policy ─────────────────────────────────────────────────────────

    @Test public void aStrictJobStopsAtTheFirstFailure() throws Exception {
        Fake f = new Fake();
        List<String> log = new ArrayList<>();
        boolean ok = runner(f).run(strict(
                new TransferCommand.DeleteRemote("/missing"),
                new TransferCommand.MakeRemoteDir("/never")), log::add);
        assertFalse(ok);
        assertFalse("the second command must not run", f.dirs.contains("/never"));
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[ERROR] rm failed")));
        assertTrue(f.closed);
    }

    @Test public void aLenientJobLogsFailuresAndStillSucceeds() throws Exception {
        Fake f = new Fake();
        List<String> log = new ArrayList<>();
        boolean ok = runner(f).run(lenient(
                new TransferCommand.DeleteRemote("/missing"),
                new TransferCommand.MakeRemoteDir("/after")), log::add);
        assertTrue(ok);
        assertTrue(f.dirs.contains("/after"));
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[WARN] rm failed (continuing)")));
    }

    @Test public void aFailureToConnectIsThrownSoItCanBeClassified() {
        SftpJobRunner r = new SftpJobRunner((h, p, u, pw) -> { throw new IOException("Auth fail"); }, null, null);
        try {
            r.run(strict(new TransferCommand.MakeRemoteDir("/a")), s -> { });
            fail("connect failure must propagate");
        } catch (IOException expected) {
            assertEquals("Auth fail", expected.getMessage());
        }
    }

    @Test public void anInterruptedThreadCancelsTheJobAndStillClosesTheSession() {
        Fake f = new Fake();
        Thread.currentThread().interrupt();
        try {
            runner(f).run(strict(new TransferCommand.MakeRemoteDir("/a")), s -> { });
            fail("should be cancelled");
        } catch (IOException expected) {
            assertEquals("Transfer cancelled", expected.getMessage());
            assertTrue(f.closed);
        } finally {
            Thread.interrupted();   // clear the flag for other tests
        }
    }

    // ── connection details ───────────────────────────────────────────────────

    @Test public void theCredentialGivesHostPortUserAndPassword() throws Exception {
        String[] seen = new String[4];
        new SftpJobRunner((h, p, u, pw) -> { seen[0] = h; seen[1] = String.valueOf(p); seen[2] = u; seen[3] = pw; return new Fake(); }, null, null)
                .run(TransferJob.strict(cred("files.example.com:2200"), "pw!", List.of()), s -> { });
        assertArrayEquals(new String[]{"files.example.com", "2200", "u", "pw!"}, seen);
    }

    @Test public void thePortDefaultsTo22() throws Exception {
        int[] port = new int[1];
        new SftpJobRunner((h, p, u, pw) -> { port[0] = p; return new Fake(); }, null, null)
                .run(TransferJob.strict(cred("files.example.com"), "x", List.of()), s -> { });
        assertEquals(22, port[0]);
    }

    @Test public void openAndCloseCallbacksSeeTheSameClient() throws Exception {
        Fake f = new Fake();
        List<SftpClient> opened = new ArrayList<>(), closed = new ArrayList<>();
        new SftpJobRunner((h, p, u, pw) -> f, opened::add, closed::add).run(strict(), s -> { });
        assertEquals(List.of(f), opened);
        assertEquals(List.of(f), closed);
    }
}
