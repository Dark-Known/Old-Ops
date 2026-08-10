package util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-CBC encryption for credential passwords.
 * Key is derived from a machine-local passphrase stored in user home directory.
 */
public class CryptoUtil {

    private static final String ALGO = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGO = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65536;
    private static final int KEY_LEN = 256;
    private static final byte[] SALT = "OpsTool_Salt_v1!".getBytes(StandardCharsets.UTF_8);

    private static final String MASTER_PASSPHRASE;

    static {
        // Machine-local, but deliberately NOT tied to which Windows account is
        // running the JVM. The GUI runs as the interactively logged-in user
        // while the Daemon runs as SYSTEM (per app-config.xml's
        // <runAsSystem>) — user.name/user.home differ between them, so
        // deriving the key from those meant a file encrypted by one process
        // was silently undecryptable by the other. Since Microsoft Graph
        // rotates the OAuth refresh token on almost every exchange (see
        // OAuth2TokenService), whichever process last refreshed re-encrypted
        // the cache with ITS key — so the other process failed to decrypt it
        // on every read until it got a turn to refresh and re-encrypt it
        // back with its own key, flip-flopping indefinitely. That surfaced
        // as an OAuth2 "could not be decrypted" / "not authorized" error,
        // but the actual bug was here, not in the OAuth flow.
        // COMPUTERNAME identifies the machine without depending on which
        // account runs the process, so GUI and Daemon now derive the exact
        // same key.
        String machine = System.getenv("COMPUTERNAME");
        if (machine == null || machine.isEmpty()) machine = System.getenv("HOSTNAME");
        if (machine == null || machine.isEmpty()) {
            try { machine = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}
        }
        if (machine == null || machine.isEmpty()) machine = "unknown-host";
        MASTER_PASSPHRASE = "OpsTool_2024_" + machine;
    }

    private static SecretKey deriveKey() throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGO);
        PBEKeySpec spec = new PBEKeySpec(
            MASTER_PASSPHRASE.toCharArray(), SALT, ITERATIONS, KEY_LEN);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String encrypt(String plainText) {
        try {
            SecretKey key = deriveKey();
            Cipher cipher = Cipher.getInstance(ALGO);
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to ciphertext, then Base64 encode
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public static String decrypt(String encryptedText) {
        try {
            SecretKey key = deriveKey();
            byte[] combined = Base64.getDecoder().decode(encryptedText);
            byte[] iv = new byte[16];
            byte[] cipherText = new byte[combined.length - 16];
            System.arraycopy(combined, 0, iv, 0, 16);
            System.arraycopy(combined, 16, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }
}
