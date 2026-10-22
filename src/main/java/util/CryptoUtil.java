package util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Encryption for secrets stored at rest (credential passwords, OAuth refresh tokens).
 *
 * <h3>Current format ("v2")</h3>
 * {@code "v2:" + Base64(iv[12] || AES-256-GCM ciphertext+tag)}. The 256-bit key is random,
 * generated once per installation and kept in {@value #KEY_FILE_NAME} inside the shared
 * data directory. GCM authenticates the data, so a modified value fails to decrypt instead
 * of yielding garbage.
 *
 * <p>The key file sits in the data directory on purpose: the GUI (interactive user) and the
 * Daemon (service account) must both read it, so it cannot be tied to either account. It
 * therefore protects against a copied or backed-up {@code app.db} and against guessing the
 * key from the machine name, but NOT against someone who can read the data directory itself.
 * Restrict the data directory's ACL to the accounts that run the tool.
 *
 * <h3>Legacy format</h3>
 * Values written by older builds (AES-CBC, key derived from the host name, no prefix) are
 * still decrypted so existing OAuth caches keep working; they are re-written in the v2
 * format the next time they are saved.
 */
public class CryptoUtil {

    private static final Logger log = Logger.getLogger(CryptoUtil.class.getName());

    public static final String KEY_FILE_NAME = "opstool.key";
    private static final String V2_PREFIX = "v2:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    // ── legacy (v1) parameters, decrypt-only ───────────────────────────────
    private static final String LEGACY_ALGO = "AES/CBC/PKCS5Padding";
    private static final String LEGACY_KEY_ALGO = "PBKDF2WithHmacSHA256";
    private static final int LEGACY_ITERATIONS = 65536;
    private static final int LEGACY_KEY_LEN = 256;
    private static final byte[] LEGACY_SALT = "OpsTool_Salt_v1!".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private static File keyDir;          // directory that holds KEY_FILE_NAME
    private static SecretKey cachedKey;  // loaded lazily from keyDir
    private static KeyProtector protector = KeyProtector.system();
    private static Boolean protectorUsable; // result of a one-time round-trip self-test

    private static final String PROTECTED_PREFIX = "dpapi:";

    /** Test hook: replace the OS key protector and forget cached state. */
    static void resetForTesting(File dir, KeyProtector p) {
        synchronized (LOCK) {
            keyDir = dir;
            cachedKey = null;
            protector = p;
            protectorUsable = null;
        }
    }

    /**
     * app-config.xml {@code <keyProtection>}: AUTO (default) wraps the key with the OS facility
     * (Windows DPAPI, machine scope) when it works and otherwise keeps it as plain base64;
     * NONE never wraps (portable key file); REQUIRED fails if wrapping is not possible.
     */
    private static String protectionMode() {
        String v = AppConfig.readValue("keyProtection");
        return v == null ? "AUTO" : v.trim().toUpperCase();
    }

    private static boolean protectorUsable() {
        if (protector == null) return false;
        if (protectorUsable == null) {
            try {
                byte[] probe = new byte[16];
                RANDOM.nextBytes(probe);
                protectorUsable = java.util.Arrays.equals(probe, protector.unprotect(protector.protect(probe)));
            } catch (Exception e) {
                log.warning("Key protection (DPAPI) is not usable here (" + e.getMessage() + "); the key file stays unwrapped");
                protectorUsable = false;
            }
        }
        return protectorUsable;
    }

    private CryptoUtil() {}

    /**
     * Tells CryptoUtil which directory holds the key file. The first call wins, so the
     * data directory passed to the storage layer is used consistently by every caller in
     * the process. Safe to call repeatedly.
     */
    public static void configure(File dir) {
        if (dir == null) return;
        synchronized (LOCK) {
            if (keyDir == null) keyDir = dir;
        }
    }

    /** True if the value was produced by {@link #encrypt} in the current format. */
    public static boolean isV2(String value) {
        return value != null && value.startsWith(V2_PREFIX);
    }

    public static String encrypt(String plainText) {
        try {
            SecretKey key = key();
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public static String decrypt(String encryptedText) {
        try {
            if (isV2(encryptedText)) {
                byte[] combined = Base64.getDecoder().decode(encryptedText.substring(V2_PREFIX.length()));
                if (combined.length <= GCM_IV_BYTES) throw new IllegalArgumentException("value too short");
                byte[] iv = new byte[GCM_IV_BYTES];
                System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES),
                        StandardCharsets.UTF_8);
            }
            return decryptLegacy(encryptedText);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    // ── key management ─────────────────────────────────────────────────────

    private static SecretKey key() throws IOException {
        synchronized (LOCK) {
            if (cachedKey != null) return cachedKey;
            File dir = keyDir;
            if (dir == null) {
                // Not configured by a storage service yet: use the same data directory the
                // rest of the app resolves from app-config.xml.
                String configured = AppConfig.readValue("dataDir");
                dir = new File(configured != null ? configured : "C:\\OpsTools\\Data");
                keyDir = dir;
                log.warning("CryptoUtil used before configure(); using data directory " + dir);
            }
            Path path = new File(dir, KEY_FILE_NAME).toPath();
            byte[] raw = readKey(path);
            if (raw == null) {
                raw = new byte[KEY_BYTES];
                RANDOM.nextBytes(raw);
                String text;
                String mode = protectionMode();
                if (!mode.equals("NONE") && protectorUsable()) {
                    text = PROTECTED_PREFIX + Base64.getEncoder().encodeToString(protector.protect(raw));
                } else if (mode.equals("REQUIRED")) {
                    throw new IOException("keyProtection=REQUIRED but OS key protection is not available");
                } else {
                    text = Base64.getEncoder().encodeToString(raw);
                }
                try {
                    Files.createDirectories(path.getParent());
                    // CREATE_NEW is atomic: if the GUI and the Daemon start together, exactly
                    // one of them creates the key and the other reads it.
                    Files.write(path, text.getBytes(StandardCharsets.US_ASCII),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    restrictPermissions(path);
                    log.info("Created encryption key file " + path);
                } catch (FileAlreadyExistsException race) {
                    raw = readKey(path);
                    if (raw == null) throw new IOException("Encryption key file " + path + " exists but is unreadable or invalid");
                }
            }
            cachedKey = new SecretKeySpec(raw, "AES");
            return cachedKey;
        }
    }

    private static byte[] readKey(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        String text = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
        boolean wrapped = text.startsWith(PROTECTED_PREFIX);
        byte[] raw;
        try {
            if (wrapped) {
                if (protector == null) {
                    throw new IOException("Encryption key file " + path + " is protected by the operating system "
                            + "(DPAPI) but no protector is available on this machine");
                }
                raw = protector.unprotect(Base64.getDecoder().decode(text.substring(PROTECTED_PREFIX.length())));
            } else {
                raw = Base64.getDecoder().decode(text);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Encryption key file " + path + " is corrupt", e);
        } catch (IOException e) {
            if (wrapped) {
                throw new IOException("Encryption key file " + path + " could not be unwrapped. DPAPI keys only work on "
                        + "the machine that created them; restore the data directory together with a key file saved "
                        + "with <keyProtection>NONE</keyProtection>, or re-enter the stored passwords. (" + e.getMessage() + ")", e);
            }
            throw e;
        }
        if (raw.length != KEY_BYTES) throw new IOException("Encryption key file " + path + " has the wrong length");
        if (!wrapped && !protectionMode().equals("NONE") && protectorUsable()) upgradeToProtected(path, raw);
        return raw;
    }

    /** Rewrites a plain key file in wrapped form (atomic replace); the key itself does not change. */
    private static void upgradeToProtected(Path path, byte[] raw) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, (PROTECTED_PREFIX + Base64.getEncoder().encodeToString(protector.protect(raw)))
                    .getBytes(StandardCharsets.US_ASCII));
            restrictPermissions(tmp);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Encryption key file " + path + " is now protected by the operating system");
        } catch (Exception e) {
            log.warning("Could not wrap the existing key file (" + e.getMessage() + "); leaving it as is");
        }
    }

    /** Best effort: owner-only on POSIX. On Windows the file inherits the data directory's ACL. */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: nothing portable to do from here.
        }
    }

    // ── legacy decrypt ─────────────────────────────────────────────────────

    private static String decryptLegacy(String encryptedText) throws Exception {
        String machine = System.getenv("COMPUTERNAME");
        if (machine == null || machine.isEmpty()) machine = System.getenv("HOSTNAME");
        if (machine == null || machine.isEmpty()) {
            try { machine = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}
        }
        if (machine == null || machine.isEmpty()) machine = "unknown-host";
        SecretKeyFactory factory = SecretKeyFactory.getInstance(LEGACY_KEY_ALGO);
        byte[] keyBytes = factory.generateSecret(new PBEKeySpec(
                ("OpsTool_2024_" + machine).toCharArray(), LEGACY_SALT, LEGACY_ITERATIONS, LEGACY_KEY_LEN)).getEncoded();
        byte[] combined = Base64.getDecoder().decode(encryptedText);
        byte[] iv = new byte[16];
        byte[] cipherText = new byte[combined.length - 16];
        System.arraycopy(combined, 0, iv, 0, 16);
        System.arraycopy(combined, 16, cipherText, 0, cipherText.length);
        Cipher cipher = Cipher.getInstance(LEGACY_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }
}
