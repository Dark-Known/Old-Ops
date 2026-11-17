package service.transfer;

import model.Credential;

import java.io.File;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WinSCP-script side of a transfer (used only by {@link WinScpEngine} and {@link WinScpScript}): the
 * {@code open} line, how the password reaches the process that runs the script, and the safety of the
 * temp script files.
 *
 * <p>The password is NOT written to the script file. The script's open line refers to
 * {@code %OPSTOOL_SFTP_PW%}, which WinSCP expands from the environment of the one process that runs
 * that script (WinSCP resolves environment variables before parsing a command).
 */
public final class ScriptFiles {

    /** Environment variable that carries the password into the WinSCP process. */
    public static final String PW_ENV = "OPSTOOL_SFTP_PW";

    private final ConcurrentHashMap<String, String> passwords = new ConcurrentHashMap<>();

    /** Writes the {@code open} line for {@code target} and remembers {@code password} for this script file. */
    public void writeOpenLine(PrintWriter pw, Credential target, String password, File script) {
        passwords.put(script.getAbsolutePath(), password == null ? "" : password);
        pw.println("open sftp://" + escapeUrl(target.getUsername()) + "@" + target.getHost()
                + "/ -hostkey=\"*\" -password=\"%" + PW_ENV + "%\"");
    }

    /** Puts the remembered password into the environment of the process about to run {@code script} (and forgets it). */
    public void applyPassword(ProcessBuilder pb, File script) {
        String pwd = passwords.remove(script.getAbsolutePath());
        if (pwd != null) pb.environment().put(PW_ENV, pwd.replace("\"", "\"\""));
    }

    /** Escapes the characters that would end the user name in an {@code sftp://user@host} URL. */
    public static String escapeUrl(String s) {
        return s.replace(";", "%3B").replace("@", "%40");
    }

    /** Hides anything that looks like {@code password=...} in a line of process output. */
    public static String maskPasswords(String line) {
        return line.replaceAll("(?i)(password[=:])\\S+", "$1****");
    }

    /** Restricts a temp file to its owner, since a script may name a server and user. */
    public static void secureTemp(File f) {
        f.setReadable(false, false); f.setReadable(true, true);
        f.setWritable(false, false); f.setWritable(true, true);
    }
}
