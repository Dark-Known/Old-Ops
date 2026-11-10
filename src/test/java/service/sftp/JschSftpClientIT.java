package service.sftp;

import org.junit.BeforeClass;
import org.junit.Test;
import util.SshHostKeys;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Integration test against a real SFTP server. Skipped unless -Dopstool.sftp.port=NNNN is given
 * (user "tester", password p@ss:w"rd%, server root is whatever directory the server exposes,
 * see -Dopstool.sftp.root). -Dopstool.sftp.otherPort=NNNN points at a second server that uses a
 * DIFFERENT host key, for the host-key-change test.
 */
public class JschSftpClientIT {

    private static final String PASSWORD = "p@ss:w\"rd%";
    private static int port;
    private static int otherPort;
    private static File root;
    private static File knownHosts;

    @BeforeClass
    public static void setUp() throws Exception {
        String p = System.getProperty("opstool.sftp.port");
        assumeTrue("no SFTP test server configured", p != null);
        port = Integer.parseInt(p);
        otherPort = Integer.parseInt(System.getProperty("opstool.sftp.otherPort", "0"));
        root = new File(System.getProperty("opstool.sftp.root"));
        File dataDir = Files.createTempDirectory("opstool-hostkeys").toFile();
        SshHostKeys.configure(dataDir);
        knownHosts = SshHostKeys.knownHostsFile(); // first configure() in the JVM wins
    }

    private static JschSftpClient connect() throws IOException {
        return JschSftpClient.connect("127.0.0.1", port, "tester", PASSWORD, 10_000);
    }

    @Test
    public void wrongPasswordFails() {
        try {
            JschSftpClient.connect("127.0.0.1", port, "tester", "nope", 10_000).close();
            fail("must not authenticate");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("auth"));
        }
    }

    @Test
    public void uploadListStatDownloadDelete() throws Exception {
        File src = File.createTempFile("opstool-up", ".txt");
        Files.write(src.toPath(), "hello sftp".getBytes(StandardCharsets.UTF_8));
        try (JschSftpClient c = connect()) {
            c.mkdirs("/it/a/b");
            c.mkdirs("/it/a/b"); // idempotent
            c.upload(src, "/it/a/b/file.txt");

            List<RemoteEntry> entries = c.list("/it/a/b");
            assertEquals("no .part file may be left behind", 1, entries.size());
            assertEquals("file.txt", entries.get(0).name());
            assertEquals(10, entries.get(0).size());
            assertFalse(entries.get(0).directory());
            assertNull(c.stat("/it/a/b/missing.txt"));
            assertEquals(10, c.stat("/it/a/b/file.txt").size());

            // overwrite an existing remote file
            Files.write(src.toPath(), "second version".getBytes(StandardCharsets.UTF_8));
            c.upload(src, "/it/a/b/file.txt");
            assertEquals(14, c.stat("/it/a/b/file.txt").size());

            File dst = new File(Files.createTempDirectory("opstool-down").toFile(), "got.txt");
            c.download("/it/a/b/file.txt", dst);
            assertEquals("second version", new String(Files.readAllBytes(dst.toPath()), StandardCharsets.UTF_8));
            assertEquals("no temp files next to the download", 1, dst.getParentFile().list().length);

            assertTrue(c.delete("/it/a/b/file.txt"));
            assertFalse(c.delete("/it/a/b/file.txt"));
        }
    }

    @Test
    public void failedDownloadLeavesNothingBehind() throws Exception {
        File dir = Files.createTempDirectory("opstool-down2").toFile();
        try (JschSftpClient c = connect()) {
            try {
                c.download("/it/does-not-exist", new File(dir, "x.txt"));
                fail();
            } catch (IOException expected) {
            }
        }
        assertEquals(0, dir.list().length);
    }

    @Test
    public void cancelAbortsFurtherOperations() throws Exception {
        JschSftpClient c = connect();
        c.cancel();
        c.cancel(); // idempotent
        try {
            c.list("/");
            fail("operations after cancel must fail");
        } catch (IOException | RuntimeException expected) {
        }
    }

    @Test
    public void firstContactIsRememberedAndChangedKeyIsRefused() throws Exception {
        assumeTrue("second server not configured", otherPort != 0);
        try (JschSftpClient c = connect()) { /* TOFU: remembers key of [127.0.0.1]:port */ }
        assertTrue("known_hosts must now exist and be non-empty", knownHosts.length() > 0);
        try (JschSftpClient c = connect()) { /* same key again: accepted */ }

        // Pretend the other server (different key) is the host we already know.
        String line = new String(Files.readAllBytes(knownHosts.toPath()), StandardCharsets.UTF_8).trim();
        String forged = line.replace("[127.0.0.1]:" + port, "[127.0.0.1]:" + otherPort);
        assertNotEquals("test setup: expected the host label in known_hosts", line, forged);
        Files.write(knownHosts.toPath(), (line + "\n" + forged + "\n").getBytes(StandardCharsets.UTF_8));
        // known_hosts is read when the client is created, so this connection sees the forged entry.
        try {
            JschSftpClient.connect("127.0.0.1", otherPort, "tester", PASSWORD, 10_000).close();
            fail("a different host key for a known host must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("host"));
        }
    }
}
