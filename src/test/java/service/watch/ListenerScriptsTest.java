package service.watch;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ListenerScriptsTest {

    /**
     * sshd hands the command to cmd.exe on Windows, which rejects anything longer than this (see listener.ps1).
     */
    private static final int CMD_EXE_LIMIT = 8191;

    /** An unexpanded {{NAME}} or {{include:file}} (the compact scripts legitimately contain "}}" where blocks close). */
    private static boolean hasPlaceholder(String script) {
        return script.contains("{{");
    }

    private static String decodeWindows(String command) {
        String marker = "-EncodedCommand ";
        String b64 = command.substring(command.indexOf(marker) + marker.length());
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_16LE);
    }

    @Test
    public void windowsListenerIsFullyExpanded() {
        String script = decodeWindows(ListenerScripts.windows("D:/Ops/Inbound", "__HB__", "__END__", "__STEP__", "__PROC__", "__WATCH_UNAVAILABLE__"));
        assertFalse("an include or placeholder was left unexpanded:\n" + script, hasPlaceholder(script));
        assertFalse(script, script.contains("include:"));
        assertTrue("path placeholder not filled", script.contains("$p='D:/Ops/Inbound'"));
        // one marker from each included file, so a skipped include is caught
        assertTrue("watch-folder.ps1 missing", script.contains("Test-Path -LiteralPath $p -PathType Container"));
        assertTrue("report-processes.ps1 missing", script.contains("'__PROC__'+$info"));
        assertTrue("event-loop.ps1 missing", script.contains("while($true)"));
        assertTrue("heartbeat not filled", script.contains("__HB__"));
    }

    @Test
    public void windowsListenerReportsEveryStartupPhase() {
        String script = decodeWindows(ListenerScripts.windows("D:/Ops/Inbound", "__HB__", "__END__", "__STEP__", "__PROC__", "__WATCH_UNAVAILABLE__"));
        assertTrue("step prefix not filled", script.contains("'__STEP__'+$m"));
        for (String phase : new String[]{"Mark 'started'", "Mark 'watching the folder'", "Mark('guard: following sshd pid '",
                "Mark 'listening'"}) {
            assertTrue("missing breadcrumb: " + phase, script.contains(phase));
        }
        assertTrue("the watcher arms on this breadcrumb; the script and RemotePushWatcher.STEP_LISTENING must agree",
                script.contains("Mark '" + RemotePushWatcher.STEP_LISTENING + "'"));
        // the order is what makes the last breadcrumb meaningful
        int started = script.indexOf("Mark 'started'"), watching = script.indexOf("Mark 'watching the folder'"),
                guard = script.indexOf("Mark('guard:"), listening = script.indexOf("Mark 'listening'");
        assertTrue(started < watching && watching < guard && guard < listening);
    }

    @Test
    public void theListenerNeverEndsAnotherProcessItself() {
        String script = decodeWindows(ListenerScripts.windows("D:/Ops/Inbound", "__HB__", "__END__", "__STEP__", "__PROC__", "__WATCH_UNAVAILABLE__"));
        assertFalse("the listener must not kill anything: " + script, script.contains("Stop-Process") || script.contains("taskkill"));
        assertFalse("no PID file any more", script.contains(".pid") || script.contains("opstool-w-"));
        assertFalse("no scan of other PowerShell command lines", script.contains("-EncodedCommand"));
    }

    @Test
    public void theListenerReportsItsOwnProcesses() {
        String script = decodeWindows(ListenerScripts.windows("D:/Ops/Inbound", "__HB__", "__END__", "__STEP__", "__PROC__", "__WATCH_UNAVAILABLE__"));
        for (String part : new String[]{"'pid='+$me.Id", "$me.StartTime.ToFileTimeUtc()", "';ppid='+$pp", "';pname='+$pn",
                "';pstart='+$pps", "';sshd='+$g"}) {
            assertTrue("missing from the process report: " + part, script.contains(part));
        }
        // it must be reported after the guard has found the parent, and before the loop that never returns
        assertTrue(script.indexOf("$pp=$a") < script.indexOf("'__PROC__'+$info"));
        assertTrue(script.indexOf("'__PROC__'+$info") < script.indexOf("while($true)"));
    }

    @Test
    public void endPreviousCommandNamesOnlyTheRecordedProcesses() {
        String command = ListenerScripts.windowsEndPrevious("1234:133800000000000000,5678:133800000000000001",
                "__GONE__", "__KILLED__", "__KEPT__", "__FAILED__");
        String script = decodeWindows(command);
        assertFalse(script, hasPlaceholder(script));
        assertTrue(script, script.contains("'1234:133800000000000000,5678:133800000000000001'.Split(',')"));
        // the safety checks that stop it from ending anything else
        assertTrue("must compare the start time", script.contains("$qs -ne $st"));
        assertTrue("must only touch shells", script.contains("'^(powershell|pwsh|cmd)$'"));
        assertTrue(script.contains("'__KILLED__'") && script.contains("'__GONE__'") && script.contains("'__KEPT__'") && script.contains("'__FAILED__'"));
        assertTrue(command.length() < CMD_EXE_LIMIT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void endPreviousRefusesAnythingButNumbers() {
        ListenerScripts.windowsEndPrevious("1234:1'; Remove-Item C:\\ -Recurse; '", "a", "b", "c", "d");
    }

    @Test
    public void windowsCommandFitsInCmdExeLimitWithALongFolderName() {
        String folder = "D:/Operations/Inbound Messages/Partner Airlines/Scheduled Movement Messages/Archive";
        String command = ListenerScripts.windows(folder, "__HB__", "__END__", "__STEP__", "__PROC__", "__WATCH_UNAVAILABLE__");
        assertTrue("command is " + command.length() + " characters; cmd.exe allows " + CMD_EXE_LIMIT,
                command.length() <= CMD_EXE_LIMIT);
    }

    @Test
    public void linuxListenerHasNoUnfilledPlaceholders() {
        String command = ListenerScripts.linux("/data/in", "__HB__", "__WATCH_UNAVAILABLE__");
        assertFalse(command, command.contains("{{") || command.contains("}}"));
        assertTrue(command, command.contains("/data/in"));
    }
}
