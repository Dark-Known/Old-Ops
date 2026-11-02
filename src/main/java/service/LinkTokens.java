package service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Access tokens for the socket-link endpoint. A token is generated here (never typed by a person), shown once, and
 * only its SHA-256 hash is stored, so the settings file cannot be used to impersonate a sender. 256 bits of
 * randomness makes a plain hash sufficient: there is nothing to brute-force.
 */
public final class LinkTokens {

    /** Makes the token recognisable (and findable by secret scanners) if it ends up in a file or a log. */
    public static final String PREFIX = "opl_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private LinkTokens() {}

    public static String generate() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Lower-case hex SHA-256 of the token. */
    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is mandatory in every JRE
        }
    }

    /** Constant-time check of a presented token against a stored hash. */
    public static boolean matches(String presentedToken, String storedHash) {
        if (presentedToken == null || storedHash == null || storedHash.isEmpty()) return false;
        return MessageDigest.isEqual(hash(presentedToken).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
