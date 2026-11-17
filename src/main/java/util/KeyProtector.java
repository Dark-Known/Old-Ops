package util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the installation's encryption key with an operating-system facility, so the key file on
 * disk is not usable on another machine.
 *
 * <p>The Windows implementation uses DPAPI with <b>machine scope</b> ({@code LocalMachine}),
 * because the GUI (interactive user) and the daemon (service account) must both unwrap the same
 * key; user-scope DPAPI would lock one of them out. Machine scope therefore protects against a
 * copied or backed-up key file, not against another process on the same machine.
 */
public interface KeyProtector {

    byte[] protect(byte[] plain) throws IOException;

    byte[] unprotect(byte[] protectedBytes) throws IOException;

    /** The protector for this operating system, or null if there is none (non-Windows). */
    static KeyProtector system() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows") ? new WindowsDpapi() : null;
    }

    /** DPAPI via the PowerShell that ships with every supported Windows. Data goes through stdin/stdout only. */
    final class WindowsDpapi implements KeyProtector {

        // Both scripts read one base64 line from stdin and write one base64 line to stdout.
        static final String PROTECT_SCRIPT =
                "Add-Type -AssemblyName System.Security; "
                + "$in = [Console]::In.ReadLine(); "
                + "$b = [Convert]::FromBase64String($in); "
                + "[Console]::Out.Write([Convert]::ToBase64String("
                + "[System.Security.Cryptography.ProtectedData]::Protect($b, $null, "
                + "[System.Security.Cryptography.DataProtectionScope]::LocalMachine)))";
        static final String UNPROTECT_SCRIPT = PROTECT_SCRIPT
                .replace("]::Protect(", "]::Unprotect(");

        @Override
        public byte[] protect(byte[] plain) throws IOException {
            return run(PROTECT_SCRIPT, plain);
        }

        @Override
        public byte[] unprotect(byte[] protectedBytes) throws IOException {
            return run(UNPROTECT_SCRIPT, protectedBytes);
        }

        private static byte[] run(String script, byte[] input) throws IOException {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).start();
            try {
                p.getOutputStream().write((Base64.getEncoder().encodeToString(input) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
                p.getOutputStream().close();
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("DPAPI helper timed out");
                }
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
                if (p.exitValue() != 0 || out.isEmpty()) {
                    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    throw new IOException("DPAPI helper failed (exit " + p.exitValue() + "): " + err);
                }
                return Base64.getDecoder().decode(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling DPAPI", e);
            } finally {
                p.destroy();
            }
        }
    }
}
