package service.transfer;

import static org.junit.Assert.*;

import model.Credential;
import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

public class ScriptFilesTest {

    private static Credential cred(String user, String host) {
        Credential c = new Credential();
        c.setUsername(user);
        c.setHost(host);
        return c;
    }

    private static String openLine(ScriptFiles s, Credential c, String pw, File script) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw2 = new PrintWriter(sw)) { s.writeOpenLine(pw2, c, pw, script); }
        return sw.toString().trim();
    }

    @Test public void theOpenLineNamesTheServerButNeverThePassword() {
        String line = openLine(new ScriptFiles(), cred("ops", "files.example.com"), "s3cret", new File("a.txt"));
        assertTrue(line, line.startsWith("open sftp://ops@files.example.com/"));
        assertTrue("password comes from the environment", line.contains("%" + ScriptFiles.PW_ENV + "%"));
        assertFalse(line.contains("s3cret"));
    }

    @Test public void theHandedOffPasswordReachesTheProcessEnvironmentOnce() {
        ScriptFiles s = new ScriptFiles();
        File script = new File("job1.txt");
        openLine(s, cred("u", "h"), "p@ss", script);

        ProcessBuilder pb = new ProcessBuilder("x");
        s.applyPassword(pb, script);
        assertEquals("p@ss", pb.environment().get(ScriptFiles.PW_ENV));

        ProcessBuilder again = new ProcessBuilder("x");
        s.applyPassword(again, script);
        assertNull("the password is forgotten once used", again.environment().get(ScriptFiles.PW_ENV));
    }

    @Test public void quotesInAPasswordAreDoubledForWinScp() {
        ScriptFiles s = new ScriptFiles();
        File script = new File("job2.txt");
        openLine(s, cred("u", "h"), "a\"b", script);
        ProcessBuilder pb = new ProcessBuilder("x");
        s.applyPassword(pb, script);
        assertEquals("a\"\"b", pb.environment().get(ScriptFiles.PW_ENV));
    }

    @Test public void aNullPasswordIsHandedOffAsEmpty() {
        ScriptFiles s = new ScriptFiles();
        File script = new File("job4.txt");
        openLine(s, cred("u", "h"), null, script);
        ProcessBuilder pb = new ProcessBuilder("x");
        s.applyPassword(pb, script);
        assertEquals("", pb.environment().get(ScriptFiles.PW_ENV));
    }

    @Test public void passwordsForDifferentScriptsDoNotMix() {
        ScriptFiles s = new ScriptFiles();
        File a = new File("a.txt"), b = new File("b.txt");
        openLine(s, cred("u", "h"), "A", a);
        openLine(s, cred("u", "h"), "B", b);
        ProcessBuilder pb = new ProcessBuilder("x"), pa = new ProcessBuilder("x");
        s.applyPassword(pb, b);
        s.applyPassword(pa, a);
        assertEquals("B", pb.environment().get(ScriptFiles.PW_ENV));
        assertEquals("A", pa.environment().get(ScriptFiles.PW_ENV));
    }

    @Test public void theUserNameIsEscapedForTheUrl() {
        assertEquals("dom%40user%3Bx", ScriptFiles.escapeUrl("dom@user;x"));
        assertEquals("plain", ScriptFiles.escapeUrl("plain"));
        String line = openLine(new ScriptFiles(), cred("a@b", "h"), "p", new File("c.txt"));
        assertTrue(line, line.contains("sftp://a%40b@h/"));
    }

    @Test public void passwordsInProcessOutputAreMasked() {
        assertEquals("login password=****", ScriptFiles.maskPasswords("login password=hunter2"));
        assertEquals("Password:****", ScriptFiles.maskPasswords("Password:abc123"));
        assertEquals("nothing to hide", ScriptFiles.maskPasswords("nothing to hide"));
    }

    @Test public void aTempScriptIsRestrictedToItsOwner() throws Exception {
        File f = File.createTempFile("scriptfiles_", ".txt");
        try {
            ScriptFiles.secureTemp(f);
            assertTrue(f.canRead() && f.canWrite());
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                        java.nio.file.Files.getPosixFilePermissions(f.toPath())));
            }
        } finally {
            f.delete();
        }
    }

    private static java.util.List<TransferCommand> cmds(TransferCommand... c) { return Arrays.asList(c); }

    @Test public void describingAJobSaysWhatItDoes() {
        assertEquals("SFTP: download 2 file(s), upload 1 file(s)",
                SftpEngine.describeJob(cmds(new TransferCommand.Download("/a", "b", false),
                        new TransferCommand.Download("/c", "d", true),
                        new TransferCommand.Upload("e", "/f")), "t1"));
        assertEquals("SFTP: delete 1 original(s), move 2 original(s), create 1 folder(s)",
                SftpEngine.describeJob(cmds(new TransferCommand.DeleteRemote("/a"),
                        new TransferCommand.MoveRemote("/b", "/c"), new TransferCommand.MoveRemote("/d", "/e"),
                        new TransferCommand.MakeRemoteDir("/f")), "t1"));
        assertEquals("SFTP: SFTP commands", SftpEngine.describeJob(cmds(), "t1"));
    }

    @Test public void theBackupCleanupPseudoTaskGetsItsOwnWording() {
        assertEquals("SFTP cleanup after a successful transfer: delete 1 original(s)",
                SftpEngine.describeJob(cmds(new TransferCommand.DeleteRemote("/a")), "backup-cleanup"));
        assertEquals("SFTP: delete 1 original(s)", SftpEngine.describeJob(cmds(new TransferCommand.DeleteRemote("/a")), "backup-mkdir"));
        assertEquals("SFTP: delete 1 original(s)", SftpEngine.describeJob(cmds(new TransferCommand.DeleteRemote("/a")), null));
    }
}
