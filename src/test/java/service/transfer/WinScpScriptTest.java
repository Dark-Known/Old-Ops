package service.transfer;

import static org.junit.Assert.*;

import model.Credential;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** WinSCP syntax now lives in exactly one place; the planner only builds structured commands. */
public class WinScpScriptTest {

    private static Credential cred() {
        Credential c = new Credential();
        c.setUsername("ops");
        c.setHost("files.example.com");
        return c;
    }

    @Test public void eachCommandRendersAsItsWinScpLine() {
        assertEquals("get \"/in/a.csv\" \"C:\\\\data\\\\a.csv\"",
                WinScpScript.commandLine(new TransferCommand.Download("/in/a.csv", "C:\\data\\a.csv", false)));
        assertEquals("get -delete \"/in/a.csv\" \"/tmp/a.csv\"",
                WinScpScript.commandLine(new TransferCommand.Download("/in/a.csv", "/tmp/a.csv", true)));
        assertEquals("put \"/tmp/a.csv\" \"/out/a.csv\"",
                WinScpScript.commandLine(new TransferCommand.Upload("/tmp/a.csv", "/out/a.csv")));
        assertEquals("rm \"/in/a.csv\"", WinScpScript.commandLine(new TransferCommand.DeleteRemote("/in/a.csv")));
        assertEquals("mv \"/in/a.csv\" \"/done/a.csv\"",
                WinScpScript.commandLine(new TransferCommand.MoveRemote("/in/a.csv", "/done/a.csv")));
        assertEquals("mkdir \"/archive/2026-11-01\"", WinScpScript.commandLine(new TransferCommand.MakeRemoteDir("/archive/2026-11-01")));
    }

    @Test public void spacesAndQuotesInPathsAreQuotedForWinScp() {
        assertEquals("put \"C:\\\\my dir\\\\say \\\"hi\\\".txt\" \"/r/x y/\\\"q\\\".txt\"",
                WinScpScript.commandLine(new TransferCommand.Upload("C:\\my dir\\say \"hi\".txt", "/r/x y/\"q\".txt")));
    }

    @Test public void aWrittenScriptHasOptionsOpenLineCommandsAndExit() throws Exception {
        ScriptFiles scripts = new ScriptFiles();
        TransferJob job = TransferJob.strict(cred(), "s3cret", List.of(
                new TransferCommand.Download("/in/a", "/tmp/a", false), new TransferCommand.DeleteRemote("/in/b")));
        File f = WinScpScript.write(scripts, job);
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            assertEquals("option batch abort", lines.get(0));
            assertEquals("option confirm off", lines.get(1));
            assertTrue(lines.get(2), lines.get(2).startsWith("open sftp://ops@files.example.com/"));
            assertEquals("get \"/in/a\" \"/tmp/a\"", lines.get(3));
            assertEquals("rm \"/in/b\"", lines.get(4));
            assertEquals(List.of("close", "exit"), lines.subList(5, 7));
            assertFalse("the password never reaches the file", String.join("\n", lines).contains("s3cret"));

            ProcessBuilder pb = new ProcessBuilder("x");
            scripts.applyPassword(pb, f);
            assertEquals("it is handed over through the environment instead", "s3cret", pb.environment().get(ScriptFiles.PW_ENV));
        } finally {
            f.delete();
        }
    }

    @Test public void aLenientJobContinuesPastFailures() throws Exception {
        File f = WinScpScript.write(new ScriptFiles(), TransferJob.lenient(cred(), "p", List.of(new TransferCommand.MakeRemoteDir("/a"))));
        try {
            assertEquals("option batch continue", Files.readAllLines(f.toPath()).get(0));
        } finally {
            f.delete();
        }
    }

    @Test public void aJobNeverPrintsItsPassword() {
        TransferJob job = TransferJob.strict(cred(), "s3cret", List.of(new TransferCommand.DeleteRemote("/x")));
        assertFalse(job.toString(), job.toString().contains("s3cret"));
        assertTrue(job.toString(), job.toString().contains("ops@files.example.com"));
    }

    @Test public void aJobKeepsItsOwnCopyOfTheCommands() {
        java.util.List<TransferCommand> mutable = new java.util.ArrayList<>(List.of(new TransferCommand.DeleteRemote("/x")));
        TransferJob job = TransferJob.strict(cred(), "p", mutable);
        mutable.clear();
        assertEquals(1, job.commands().size());
    }
}
