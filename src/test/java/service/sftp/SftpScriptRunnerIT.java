package service.sftp;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Runs WinSCP-style scripts through {@link SftpScriptRunner} against a real server (-Dopstool.sftp.port).
 */
public class SftpScriptRunnerIT {

    private static final String PASSWORD = "p@ss:w\"rd%";
    private static SftpScriptRunner runner;
    private static File serverRoot;

    @BeforeClass
    public static void setUp() throws Exception {
        String p = System.getProperty("opstool.sftp.port");
        assumeTrue("no SFTP test server configured", p != null);
        int port = Integer.parseInt(p);
        serverRoot = new File(System.getProperty("opstool.sftp.root"));
        util.SshHostKeys.configure(Files.createTempDirectory("opstool-hk2").toFile());
        runner = new SftpScriptRunner(
                (host, prt, user, pw) -> JschSftpClient.connect(host, port, user, pw, 10_000), null, null);
    }

    private static String open() {
        return "open sftp://tester@127.0.0.1/ -hostkey=\"*\" -password=\"%X%\"";
    }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String qr(String s) {
        return '"' + s.replace("\"", "\\\"") + '"';
    }

    private static List<String> script(String... lines) {
        List<String> l = new ArrayList<>(Arrays.asList("option batch abort", "option confirm off", open()));
        l.addAll(Arrays.asList(lines));
        l.add("close");
        l.add("exit");
        return l;
    }

    @Test
    public void putThenGetWithSpacesAndQuotesInNames() throws Exception {
        File dir = Files.createTempDirectory("opstool-sr").toFile();
        File src = new File(dir, "my file \"v2\".txt");
        Files.write(src.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        src.setLastModified(1_700_000_000_000L);
        File dst = new File(dir, "back.txt");
        List<String> log = new ArrayList<>();

        SftpScriptRunner.Outcome o = runner.run(script(
                "mkdir " + qr("/sr/in dir"),
                "put " + q(src.getAbsolutePath()) + " " + qr("/sr/in dir/my file \"v2\".txt"),
                "get " + qr("/sr/in dir/my file \"v2\".txt") + " " + q(dst.getAbsolutePath())), PASSWORD, log::add);

        assertTrue(log.toString(), o.ok());
        assertEquals("tester", o.user());
        assertEquals("payload", new String(Files.readAllBytes(dst.toPath()), StandardCharsets.UTF_8));
        assertEquals("timestamp preserved end to end", 1_700_000_000L, dst.lastModified() / 1000L);
    }

    @Test
    public void getIntoExistingDirectoryAndPutIntoExistingRemoteDirectory() throws Exception {
        File dir = Files.createTempDirectory("opstool-sr2").toFile();
        File src = new File(dir, "a.txt");
        Files.write(src.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File outDir = Files.createTempDirectory("opstool-sr3").toFile();
        List<String> log = new ArrayList<>();
        assertTrue(runner.run(script(
                "mkdir " + qr("/sr/dest"),
                "put " + q(src.getAbsolutePath()) + " " + qr("/sr/dest"),
                "get " + qr("/sr/dest/a.txt") + " " + q(outDir.getAbsolutePath())), PASSWORD, log::add).ok());
        assertTrue(new File(outDir, "a.txt").isFile());
    }

    @Test
    public void abortStopsAtFirstFailure() throws Exception {
        File out = new File(Files.createTempDirectory("opstool-sr4").toFile(), "never.txt");
        List<String> log = new ArrayList<>();
        SftpScriptRunner.Outcome o = runner.run(script(
                "get " + qr("/sr/does-not-exist.txt") + " " + q(out.getAbsolutePath()),
                "mkdir " + qr("/sr/should-not-be-created")), PASSWORD, log::add);
        assertFalse(o.ok());
        assertFalse(new File(serverRoot, "sr/should-not-be-created").exists());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[ERROR] get failed")));
    }

    @Test
    public void continueModeToleratesFailuresAndStillSucceeds() throws Exception {
        List<String> log = new ArrayList<>();
        List<String> s = new ArrayList<>(Arrays.asList("option batch continue", "option confirm off", open(),
                "rm " + qr("/sr/not-there.txt"),
                "mkdir " + qr("/sr/after-failure"), "close", "exit"));
        assertTrue(runner.run(s, PASSWORD, log::add).ok());
        assertTrue(new File(serverRoot, "sr/after-failure").isDirectory());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[WARN] rm failed")));
    }

    @Test
    public void mvMovesAFileAndNeverReplacesAnExistingOne() throws Exception {
        File base = new File(serverRoot, "mv");
        base.mkdirs();
        Files.write(new File(base, "a.txt").toPath(), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(base, "b.txt").toPath(), "B".getBytes(StandardCharsets.UTF_8));
        assertTrue(runner.run(script("mv " + qr("/mv/a.txt") + " " + qr("/mv/moved.txt")), PASSWORD, x -> {
        }).ok());
        assertFalse(new File(base, "a.txt").exists());
        assertEquals("A", new String(Files.readAllBytes(new File(base, "moved.txt").toPath()), StandardCharsets.UTF_8));
        assertFalse("must not replace an existing file",
                runner.run(script("mv " + qr("/mv/b.txt") + " " + qr("/mv/moved.txt")), PASSWORD, x -> {
                }).ok());
        assertEquals("A", new String(Files.readAllBytes(new File(base, "moved.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(base, "b.txt").exists());
    }

    @Test
    public void wrongPasswordFailsTheRun() {
        try {
            SftpScriptRunner.Outcome o = runner.run(script("mkdir " + qr("/sr/nope")), "wrong", x -> {
            });
            fail("connect must fail, got " + o);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("auth"));
        }
    }

    @Test
    public void parsingHelpers() throws Exception {
        assertEquals(Arrays.asList("put", "C:\\\\a b\\\\f.txt", "/r/x y.txt"),
                SftpScriptRunner.tokens("put \"C:\\\\a b\\\\f.txt\" \"/r/x y.txt\""));
        assertEquals("C:\\a b\\f.txt", SftpScriptRunner.unescapeLocal("C:\\\\a b\\\\f.txt"));
        assertEquals("/r/\"q\"", SftpScriptRunner.unescapeRemote("/r/\\\"q\\\""));
        assertArrayEquals(new String[]{"dom\\user", "host", "2200"},
                SftpScriptRunner.parseOpen("sftp://dom%5Cuser@host:2200/"));
    }
}
