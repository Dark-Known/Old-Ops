package service.watch;

import org.junit.Test;
import service.watch.RemoteListenerRegistry.Proc;
import service.watch.RemoteProcessJanitor.Remote;
import service.watch.RemoteProcessJanitor.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The service remembers what a listener reports and, when it arms again, ends only those processes. The server is
 * replaced by canned answers, so these cover the decisions the service makes (what it sends, what it forgets), not PowerShell.
 */
public class RemoteProcessJanitorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String KEY = "WINDOWS|10.0.0.5|22|gv\\ops|d:\\in";

    private static Proc proc(String key, String role, long pid, long start, long at) {
        return new Proc(key, "10.0.0.5", "gv\\ops", role, pid, start, at);
    }

    private static String script(String command) {
        String b64 = command.substring(command.indexOf("-EncodedCommand ") + 16);
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_16LE);
    }

    /**
     * A server that records every command it is asked to run and answers with fixed lines.
     */
    private static final class FakeServer implements Remote {
        final List<String> commands = new ArrayList<>();
        final List<String> answer;
        final Exception failure;

        FakeServer(String... answer) {
            this.answer = List.of(answer);
            this.failure = null;
        }

        FakeServer(Exception failure) {
            this.answer = List.of();
            this.failure = failure;
        }

        @Override
        public List<String> run(String command, long timeoutMs) throws Exception {
            commands.add(command);
            if (failure != null) throw failure;
            return answer;
        }
    }

    private RemoteListenerRegistry registryWith(Proc... procs) {
        RemoteListenerRegistry r = new RemoteListenerRegistry(null, () -> NOW);
        r.add(List.of(procs));
        return r;
    }

    // ── what the listener reports ───────────────────────────────────────────

    @Test
    public void aFullReportGivesTheListenerAndItsShell() {
        List<Proc> p = RemoteProcessJanitor.parseReport(
                "__PROC__pid=4321;start=133800000000000000;ppid=5678;pname=cmd;pstart=133799999999999999;sshd=910", KEY, "h", "u", NOW);
        assertEquals(2, p.size());
        assertEquals("listener", p.get(0).role);
        assertEquals(4321, p.get(0).pid);
        assertEquals(133800000000000000L, p.get(0).start);
        assertEquals("shell", p.get(1).role);
        assertEquals(5678, p.get(1).pid);
        assertEquals(KEY, p.get(0).key);
    }

    @Test
    public void sshdIsNeverRecordedAsSomethingToEnd() {
        List<Proc> p = RemoteProcessJanitor.parseReport(
                "__PROC__pid=4321;start=5;ppid=910;pname=sshd;pstart=7;sshd=910", KEY, "h", "u", NOW);
        assertEquals("only the listener", 1, p.size());
        assertEquals(4321, p.get(0).pid);
        for (Proc x : p) assertNotEquals(910, x.pid);
    }

    @Test
    public void aReportWithoutAParentStillRecordsTheListener() {
        List<Proc> p = RemoteProcessJanitor.parseReport("__PROC__pid=4321;start=5;sshd=910", KEY, "h", "u", NOW);
        assertEquals(1, p.size());
    }

    @Test
    public void malformedOrHostileReportsRecordNothing() {
        for (String bad : new String[]{
                "__PROC__", "__PROC__pid=;start=5", "__PROC__pid=12", "__PROC__start=5", "__PROC__pid=0;start=5",
                "__PROC__pid=-4;start=5", "__PROC__pid=12;start=0", "__PROC__pid=abc;start=5", "__PROC__pid=12;start=5x",
                "__PROC__pid=99999999999;start=5", "__PROC__pid=12'; Remove-Item C:\\;start=5", "__PROC__pid=12$(evil);start=5", "not a report"}) {
            assertTrue(bad, RemoteProcessJanitor.parseReport(bad, KEY, "h", "u", NOW).isEmpty());
        }
        // a stray token is ignored; only the numbers in the known fields are ever used
        List<Proc> stray = RemoteProcessJanitor.parseReport("__PROC__pid=12; Remove-Item C:\\;start=5", KEY, "h", "u", NOW);
        assertEquals(1, stray.size());
        assertEquals(12, stray.get(0).pid);
        assertEquals(5, stray.get(0).start);
        // an unusable parent is dropped, the listener is still recorded
        assertEquals(1, RemoteProcessJanitor.parseReport("__PROC__pid=12;start=5;ppid=12;pname=cmd;pstart=7", KEY, "h", "u", NOW).size());
        assertEquals(1, RemoteProcessJanitor.parseReport("__PROC__pid=12;start=5;ppid=99;pname=explorer;pstart=7", KEY, "h", "u", NOW).size());
        assertEquals(1, RemoteProcessJanitor.parseReport("__PROC__pid=12;start=5;ppid=99;pname=cmd;pstart=0", KEY, "h", "u", NOW).size());
    }

    // ── ending the recorded processes ───────────────────────────────────────

    @Test
    public void nothingRecordedMeansNothingIsSent() {
        FakeServer server = new FakeServer();
        Result r = RemoteProcessJanitor.endPrevious(registryWith(), KEY, server, s -> {
        }, 5_000);
        assertTrue(server.commands.isEmpty());
        assertEquals(0, r.killed + r.gone + r.kept + r.failed);
    }

    @Test
    public void onlyTheRecordedProcessesOfThisListenerAreNamed() {
        RemoteListenerRegistry reg = registryWith(
                proc(KEY, "listener", 4321, 1000, NOW), proc(KEY, "shell", 5678, 900, NOW),
                proc("WINDOWS|10.0.0.5|22|gv\\ops|d:\\other", "listener", 7777, 5, NOW));
        FakeServer server = new FakeServer("__GONE__4321", "__GONE__5678");
        RemoteProcessJanitor.endPrevious(reg, KEY, server, s -> {
        }, 5_000);

        assertEquals(1, server.commands.size());
        String script = script(server.commands.get(0));
        assertTrue(script, script.contains("'4321:1000,5678:900'"));
        assertFalse("another folder's listener must not be named", script.contains("7777"));
        assertEquals("the other folder's record is untouched", 1, reg.get("WINDOWS|10.0.0.5|22|gv\\ops|d:\\other").size());
    }

    @Test
    public void processesThatAreGoneEndedOrReusedAreForgotten() {
        RemoteListenerRegistry reg = registryWith(
                proc(KEY, "listener", 1, 10, NOW), proc(KEY, "shell", 2, 20, NOW), proc(KEY, "listener", 3, 30, NOW));
        List<String> log = new ArrayList<>();
        Result r = RemoteProcessJanitor.endPrevious(reg, KEY, new FakeServer("__GONE__1", "__KILLED__2", "__KEPT__3"), log::add, 5_000);
        assertEquals(1, r.gone);
        assertEquals(1, r.killed);
        assertEquals(1, r.kept);
        assertTrue(reg.get(KEY).isEmpty());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("pid 2") && l.contains("ended")));
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("pid 3") && l.contains("left alone")));
    }

    @Test
    public void aProcessTheServerCouldNotEndStaysRecordedForNextTime() {
        RemoteListenerRegistry reg = registryWith(proc(KEY, "listener", 1, 10, NOW), proc(KEY, "shell", 2, 20, NOW));
        Result r = RemoteProcessJanitor.endPrevious(reg, KEY, new FakeServer("__FAILED__1", "__KILLED__2"), s -> {
        }, 5_000);
        assertEquals(1, r.failed);
        assertEquals(1, r.killed);
        assertEquals(1, reg.get(KEY).size());
        assertEquals(1, reg.get(KEY).get(0).pid);
    }

    @Test
    public void anEntryThePowerShellNeverAnsweredAboutStaysRecorded() {
        RemoteListenerRegistry reg = registryWith(proc(KEY, "listener", 1, 10, NOW), proc(KEY, "shell", 2, 20, NOW));
        RemoteProcessJanitor.endPrevious(reg, KEY, new FakeServer("__GONE__1"), s -> {
        }, 5_000);   // it stopped after the first one
        assertEquals(2, reg.get(KEY).get(0).pid);
        assertEquals(1, reg.get(KEY).size());
    }

    @Test
    public void ifTheServerCannotBeAskedNothingIsForgottenAndNothingThrows() {
        RemoteListenerRegistry reg = registryWith(proc(KEY, "listener", 1, 10, NOW));
        List<String> log = new ArrayList<>();
        Result r = RemoteProcessJanitor.endPrevious(reg, KEY, new FakeServer(new java.util.concurrent.TimeoutException("no answer within 10 s")), log::add, 10_000);
        assertTrue(r.unreachable);
        assertEquals(1, reg.get(KEY).size());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("no answer within 10 s") && l.contains("again next time")));
    }

    @Test
    public void aPidRecordedTwiceIsAskedAboutOnceWithTheNewestStartTime() {
        RemoteListenerRegistry reg = registryWith(
                proc(KEY, "listener", 4321, 1000, NOW - 5_000), proc(KEY, "listener", 4321, 2000, NOW));
        FakeServer server = new FakeServer("__KILLED__4321");
        RemoteProcessJanitor.endPrevious(reg, KEY, server, s -> {
        }, 5_000);
        String script = script(server.commands.get(0));
        assertTrue(script, script.contains("'4321:2000'"));
        assertFalse(script, script.contains("4321:1000"));
        assertTrue("the older record cannot be alive too", reg.get(KEY).isEmpty());
    }

    @Test
    public void armingTwiceInARowOnlyAsksOnceAboutTheSameProcesses() {
        RemoteListenerRegistry reg = registryWith(proc(KEY, "listener", 1, 10, NOW));
        FakeServer server = new FakeServer("__GONE__1");
        RemoteProcessJanitor.endPrevious(reg, KEY, server, s -> {
        }, 5_000);
        RemoteProcessJanitor.endPrevious(reg, KEY, server, s -> {
        }, 5_000);
        assertEquals("the second arm has nothing left to ask about", 1, server.commands.size());
    }
}
