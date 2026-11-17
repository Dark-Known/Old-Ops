package service.transfer;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import model.Credential;
import service.sftp.JschSftpClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs jobs through {@link SftpJobRunner} against a real server (-Dopstool.sftp.port). */
public class SftpJobRunnerIT {

    private static final String PASSWORD = "p@ss:w\"rd%";
    private static SftpJobRunner runner;
    private static int port;
    private static File serverRoot;

    @BeforeClass
    public static void setUp() throws Exception {
        String p = System.getProperty("opstool.sftp.port");
        assumeTrue("no SFTP test server configured", p != null);
        port = Integer.parseInt(p);
        serverRoot = new File(System.getProperty("opstool.sftp.root"));
        util.SshHostKeys.configure(Files.createTempDirectory("opstool-hk2").toFile());
        runner = new SftpJobRunner(
                (host, prt, user, pw) -> JschSftpClient.connect(host, port, user, pw, 10_000), null, null);
    }

    private static Credential tester() {
        Credential c = new Credential();
        c.setUsername("tester");
        c.setHost("127.0.0.1");
        return c;
    }

    private static TransferJob job(String password, TransferCommand... cmds) {
        return TransferJob.strict(tester(), password, Arrays.asList(cmds));
    }

    @Test
    public void putThenGetWithSpacesAndQuotesInNames() throws Exception {
        File dir = Files.createTempDirectory("opstool-sr").toFile();
        File src = new File(dir, "my file \"v2\".txt");
        Files.write(src.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        src.setLastModified(1_700_000_000_000L);
        File dst = new File(dir, "back.txt");
        List<String> log = new ArrayList<>();

        boolean ok = runner.run(job(PASSWORD,
                new TransferCommand.MakeRemoteDir("/sr/in dir"),
                new TransferCommand.Upload(src.getAbsolutePath(), "/sr/in dir/my file \"v2\".txt"),
                new TransferCommand.Download("/sr/in dir/my file \"v2\".txt", dst.getAbsolutePath(), false)), log::add);

        assertTrue(log.toString(), ok);
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
        assertTrue(runner.run(job(PASSWORD,
                new TransferCommand.MakeRemoteDir("/sr/dest"),
                new TransferCommand.Upload(src.getAbsolutePath(), "/sr/dest"),
                new TransferCommand.Download("/sr/dest/a.txt", outDir.getAbsolutePath(), false)), log::add));
        assertTrue(new File(outDir, "a.txt").isFile());
    }

    @Test
    public void abortStopsAtFirstFailure() throws Exception {
        File out = new File(Files.createTempDirectory("opstool-sr4").toFile(), "never.txt");
        List<String> log = new ArrayList<>();
        boolean ok = runner.run(job(PASSWORD,
                new TransferCommand.Download("/sr/does-not-exist.txt", out.getAbsolutePath(), false),
                new TransferCommand.MakeRemoteDir("/sr/should-not-be-created")), log::add);
        assertFalse(ok);
        assertFalse(new File(serverRoot, "sr/should-not-be-created").exists());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[ERROR] get failed")));
    }

    @Test
    public void continueModeToleratesFailuresAndStillSucceeds() throws Exception {
        List<String> log = new ArrayList<>();
        assertTrue(runner.run(TransferJob.lenient(tester(), PASSWORD, Arrays.asList(
                new TransferCommand.DeleteRemote("/sr/not-there.txt"),
                new TransferCommand.MakeRemoteDir("/sr/after-failure"))), log::add));
        assertTrue(new File(serverRoot, "sr/after-failure").isDirectory());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("[WARN] rm failed")));
    }

    @Test
    public void mvMovesAFileAndNeverReplacesAnExistingOne() throws Exception {
        File base = new File(serverRoot, "mv");
        base.mkdirs();
        Files.write(new File(base, "a.txt").toPath(), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(base, "b.txt").toPath(), "B".getBytes(StandardCharsets.UTF_8));
        assertTrue(runner.run(job(PASSWORD, new TransferCommand.MoveRemote("/mv/a.txt", "/mv/moved.txt")), x -> { }));
        assertFalse(new File(base, "a.txt").exists());
        assertEquals("A", new String(Files.readAllBytes(new File(base, "moved.txt").toPath()), StandardCharsets.UTF_8));
        assertFalse("must not replace an existing file",
                runner.run(job(PASSWORD, new TransferCommand.MoveRemote("/mv/b.txt", "/mv/moved.txt")), x -> { }));
        assertEquals("A", new String(Files.readAllBytes(new File(base, "moved.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(base, "b.txt").exists());
    }

    @Test
    public void wrongPasswordFailsTheRun() {
        try {
            boolean ok = runner.run(job("wrong", new TransferCommand.MakeRemoteDir("/sr/nope")), x -> { });
            fail("connect must fail, got " + ok);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("auth"));
        }
    }
}
