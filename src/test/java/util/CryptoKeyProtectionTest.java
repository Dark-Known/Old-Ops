package util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Key-wrapping logic with a FAKE protector. This does not exercise real Windows DPAPI
 * (KeyProtector.WindowsDpapi), which can only be tested on Windows.
 */
public class CryptoKeyProtectionTest {

    /**
     * Reversible, machine-independent stand-in: marker + XOR.
     */
    static final KeyProtector FAKE = new KeyProtector() {
        public byte[] protect(byte[] p) {
            byte[] o = new byte[p.length + 4];
            System.arraycopy("FAKE".getBytes(StandardCharsets.US_ASCII), 0, o, 0, 4);
            for (int i = 0; i < p.length; i++) o[i + 4] = (byte) (p[i] ^ 0x5a);
            return o;
        }

        public byte[] unprotect(byte[] b) throws IOException {
            if (b.length < 4 || b[0] != 'F' || b[1] != 'A') throw new IOException("not FAKE-protected");
            byte[] o = new byte[b.length - 4];
            for (int i = 0; i < o.length; i++) o[i] = (byte) (b[i + 4] ^ 0x5a);
            return o;
        }
    };

    static final KeyProtector BROKEN = new KeyProtector() {
        public byte[] protect(byte[] p) throws IOException {
            throw new IOException("powershell missing");
        }

        public byte[] unprotect(byte[] b) throws IOException {
            throw new IOException("powershell missing");
        }
    };

    private static String keyFile(File dir) throws Exception {
        return new String(Files.readAllBytes(new File(dir, CryptoUtil.KEY_FILE_NAME).toPath()), StandardCharsets.US_ASCII).trim();
    }

    @Test
    public void newKeyIsStoredWrappedAndStillWorksAfterRestart() throws Exception {
        File dir = Files.createTempDirectory("opstool-kp").toFile();
        CryptoUtil.resetForTesting(dir, FAKE);
        String enc = CryptoUtil.encrypt("secret");
        String text = keyFile(dir);
        assertTrue(text, text.startsWith("dpapi:"));
        assertEquals("wrapped key is the marker + 32 bytes, not the bare key", 36,
                Base64.getDecoder().decode(text.substring(6)).length);

        CryptoUtil.resetForTesting(dir, FAKE); // "restart": forget the cached key, re-read the file
        assertEquals("secret", CryptoUtil.decrypt(enc));
    }

    @Test
    public void existingPlainKeyIsUpgradedInPlaceWithoutChangingTheKey() throws Exception {
        File dir = Files.createTempDirectory("opstool-kp").toFile();
        CryptoUtil.resetForTesting(dir, null); // no protector: plain file
        String enc = CryptoUtil.encrypt("before-upgrade");
        assertFalse(keyFile(dir).startsWith("dpapi:"));

        CryptoUtil.resetForTesting(dir, FAKE);
        assertEquals("before-upgrade", CryptoUtil.decrypt(enc)); // reading triggers the upgrade
        assertTrue(keyFile(dir).startsWith("dpapi:"));
        assertFalse("no temp file left", new File(dir, CryptoUtil.KEY_FILE_NAME + ".tmp").exists());

        CryptoUtil.resetForTesting(dir, FAKE);
        assertEquals("before-upgrade", CryptoUtil.decrypt(enc)); // same key after the upgrade
    }

    @Test
    public void brokenProtectorFallsBackToPlainKey() throws Exception {
        File dir = Files.createTempDirectory("opstool-kp").toFile();
        CryptoUtil.resetForTesting(dir, BROKEN);
        String enc = CryptoUtil.encrypt("still works");
        assertFalse(keyFile(dir).startsWith("dpapi:"));
        CryptoUtil.resetForTesting(dir, BROKEN);
        assertEquals("still works", CryptoUtil.decrypt(enc));
    }

    @Test
    public void wrappedKeyWithoutAProtectorGivesAClearError() throws Exception {
        File dir = Files.createTempDirectory("opstool-kp").toFile();
        CryptoUtil.resetForTesting(dir, FAKE);
        String enc = CryptoUtil.encrypt("x");
        CryptoUtil.resetForTesting(dir, null); // e.g. the data directory was restored on another OS
        try {
            CryptoUtil.decrypt(enc);
            fail();
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DPAPI") || String.valueOf(e.getCause()).contains("DPAPI"));
        }
    }

    @Test
    public void wrappedKeyThatCannotBeUnwrappedExplainsWhy() throws Exception {
        File dir = Files.createTempDirectory("opstool-kp").toFile();
        CryptoUtil.resetForTesting(dir, FAKE);
        String enc = CryptoUtil.encrypt("x");
        CryptoUtil.resetForTesting(dir, BROKEN); // DPAPI call fails (other machine, wrong account, ...)
        try {
            CryptoUtil.decrypt(enc);
            fail();
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getCause()), String.valueOf(e.getCause()).contains("could not be unwrapped"));
        }
    }

    @Test
    public void scriptsPairUpAndNeverPutDataOnTheCommandLine() {
        assertTrue(KeyProtector.WindowsDpapi.PROTECT_SCRIPT.contains("]::Protect("));
        assertTrue(KeyProtector.WindowsDpapi.UNPROTECT_SCRIPT.contains("]::Unprotect("));
        assertFalse(KeyProtector.WindowsDpapi.UNPROTECT_SCRIPT.contains("]::Protect("));
        assertTrue(KeyProtector.WindowsDpapi.PROTECT_SCRIPT.contains("LocalMachine"));
        assertTrue(KeyProtector.WindowsDpapi.PROTECT_SCRIPT.contains("[Console]::In.ReadLine()"));
    }
}
