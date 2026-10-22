package service.sftp;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import util.SshAuth;

import java.io.IOException;
import java.nio.file.Files;

/** Key login against a real server: -Dopstool.sftp.port, -Dopstool.sftp.keyDir (holds client_plain.pem / client_pass.pem). */
public class SftpKeyAuthIT {

    private static int port;
    private static String keyDir;

    @BeforeClass
    public static void setUp() throws Exception {
        String p = System.getProperty("opstool.sftp.port");
        keyDir = System.getProperty("opstool.sftp.keyDir");
        assumeTrue("no SFTP test server / keys configured", p != null && keyDir != null);
        port = Integer.parseInt(p);
        util.SshHostKeys.configure(Files.createTempDirectory("opstool-hk3").toFile());
    }

    private static JschSftpClient connect(String secret) throws IOException {
        return JschSftpClient.connect("127.0.0.1", port, "tester", secret, 10_000);
    }

    @Test
    public void keyWithoutPassphrase() throws Exception {
        try (JschSftpClient c = connect("key:" + keyDir + "/client_plain.pem")) {
            c.mkdirs("/keyauth");
            assertTrue(c.list("/").stream().anyMatch(e -> e.name().equals("keyauth")));
        }
    }

    @Test
    public void keyWithPassphrase() throws Exception {
        try (JschSftpClient c = connect("key:" + keyDir + "/client_pass.pem|s3cret")) {
            assertNotNull(c.list("/"));
        }
    }

    @Test
    public void wrongPassphraseIsRejected() {
        try {
            connect("key:" + keyDir + "/client_pass.pem|wrong").close();
            fail();
        } catch (IOException expected) { }
    }

    @Test
    public void missingKeyFileGivesClearError() {
        try {
            connect("key:" + keyDir + "/nope.pem").close();
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not found"));
        }
    }

    @Test
    public void parseRecognisesKeyReferencesOnly() {
        assertNull(SshAuth.parse("plain password"));
        assertNull(SshAuth.parse(null));
        assertNull(SshAuth.parse("key:"));
        assertEquals(new SshAuth.KeyRef("C:\\keys\\id", null), SshAuth.parse("key:C:\\keys\\id"));
        assertEquals(new SshAuth.KeyRef("/k/id", "pp"), SshAuth.parse("KEY:/k/id|pp"));
    }
}
