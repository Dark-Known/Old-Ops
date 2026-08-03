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
        // Derive a machine-local passphrase from user name + home dir + a fixed app secret
        // In production you'd store this in OS keystore; this approach avoids external deps
        String user = System.getProperty("user.name", "ops");
        String home = System.getProperty("user.home", "C:\\");
        MASTER_PASSPHRASE = "OpsTool_2024_" + user + "_" + home.hashCode();
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
