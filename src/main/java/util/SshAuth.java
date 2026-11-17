package util;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.File;

/**
 * Chooses password or SSH key authentication from the stored "password" value of a credential,
 * so key login needs no schema or screen changes: enter
 * <pre>key:C:\keys\id_ed25519</pre> or <pre>key:C:\keys\id_ed25519|passphrase</pre>
 * in the credential's password field. Anything else is used as an ordinary password.
 * (A password that really starts with "key:" cannot be used.)
 *
 * <p>The key file must be readable by the account that runs the connection (the service
 * account for the Daemon). The value is stored encrypted like any other password. Only the
 * built-in SFTP engine supports keys; {@code transferEngine=winscp} does not.
 */
public final class SshAuth {

    public static final String KEY_PREFIX = "key:";

    private SshAuth() {}

    /** Parsed key reference. */
    public record KeyRef(String path, String passphrase) {}

    /** The key reference in {@code secret}, or null if it is a plain password. */
    public static KeyRef parse(String secret) {
        if (secret == null || !secret.regionMatches(true, 0, KEY_PREFIX, 0, KEY_PREFIX.length())) return null;
        String rest = secret.substring(KEY_PREFIX.length()).trim();
        String pass = null;
        int bar = rest.indexOf('|');
        if (bar >= 0) {
            pass = rest.substring(bar + 1);
            rest = rest.substring(0, bar).trim();
        }
        return rest.isEmpty() ? null : new KeyRef(rest, pass == null || pass.isEmpty() ? null : pass);
    }

    /** Call after {@code jsch.getSession(...)} and before {@code session.connect(...)}. */
    public static void apply(JSch jsch, Session session, String secret) throws JSchException {
        KeyRef k = parse(secret);
        if (k == null) {
            session.setPassword(secret == null ? "" : secret);
            return;
        }
        if (!new File(k.path()).isFile()) {
            throw new JSchException("SSH key file not found or not readable: " + k.path());
        }
        if (k.passphrase() == null) jsch.addIdentity(k.path());
        else jsch.addIdentity(k.path(), k.passphrase());
        session.setConfig("PreferredAuthentications", "publickey");
    }
}
