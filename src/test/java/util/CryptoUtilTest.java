package util;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.Assert.*;

public class CryptoUtilTest {

    private static File dir;

    @BeforeClass
    public static void setUp() throws Exception {
        // CryptoUtil keeps its key directory in static state (first configure() wins),
        // so every test in this class shares one directory.
        dir = Files.createTempDirectory("opstool-crypto-test").toFile();
        CryptoUtil.resetForTesting(dir, null); // plain key file; shared static state is reset per class
    }

    @Test
    public void roundTripIncludingSpecialCharacters() {
        for (String s : new String[]{"simple", "p@ss:w/rd%\"'", "unicode-é中文", "x"}) {
            String enc = CryptoUtil.encrypt(s);
            assertTrue(CryptoUtil.isV2(enc));
            assertNotEquals(s, enc);
            assertEquals(s, CryptoUtil.decrypt(enc));
        }
    }

    @Test
    public void sameInputGivesDifferentCiphertexts() {
        assertNotEquals(CryptoUtil.encrypt("same"), CryptoUtil.encrypt("same"));
    }

    @Test
    public void keyFileIsCreatedOnceAndIs32Bytes() throws Exception {
        CryptoUtil.encrypt("trigger");
        File key = new File(dir, CryptoUtil.KEY_FILE_NAME);
        assertTrue(key.exists());
        assertEquals(32, Base64.getDecoder().decode(new String(Files.readAllBytes(key.toPath()),
                StandardCharsets.US_ASCII).trim()).length);
    }

    @Test
    public void tamperedValueIsRejected() {
        String enc = CryptoUtil.encrypt("secret");
        char[] c = enc.toCharArray();
        c[c.length - 6] ^= 1;
        try {
            CryptoUtil.decrypt(new String(c));
            fail("tampered ciphertext must not decrypt");
        } catch (RuntimeException expected) {
            // GCM authentication failure
        }
    }

    @Test
    public void legacyCbcValuesStillDecrypt() throws Exception {
        // Re-create what the pre-v2 build wrote: AES/CBC, key from host name, no prefix.
        String machine = System.getenv("COMPUTERNAME");
        if (machine == null || machine.isEmpty()) machine = System.getenv("HOSTNAME");
        if (machine == null || machine.isEmpty()) {
            try {
                machine = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
            }
        }
        if (machine == null || machine.isEmpty()) machine = "unknown-host";
        byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(
                ("OpsTool_2024_" + machine).toCharArray(),
                "OpsTool_Salt_v1!".getBytes(StandardCharsets.UTF_8), 65536, 256)).getEncoded();
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal("old-token".getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        String legacy = Base64.getEncoder().encodeToString(all);

        assertFalse(CryptoUtil.isV2(legacy));
        assertEquals("old-token", CryptoUtil.decrypt(legacy));
    }
}
