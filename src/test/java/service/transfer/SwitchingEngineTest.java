package service.transfer;

import static org.junit.Assert.*;

import model.Credential;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Also shows what the {@link TransferEngine} interface buys: an engine can be faked in a few lines.
 */
public class SwitchingEngineTest {

    private static final TransferJob JOB = TransferJob.strict(new Credential(), "pw", List.of());

    private static final class RecordingEngine implements TransferEngine {
        final String name;
        final List<String> calls;
        boolean scriptResult = true;
        RecordingEngine(String name, List<String> calls) { this.name = name; this.calls = calls; }

        @Override public boolean run(TransferJob job, Consumer<String> log, String taskId) {
            calls.add(name + ":run:" + taskId);
            return scriptResult;
        }
        @Override public Map<String, RemoteFileStat> listFiles(Credential t, String pw, String dir, Consumer<String> log) {
            calls.add(name + ":list:" + dir);
            return Collections.singletonMap(dir + "x", new RemoteFileStat(1L, 2L));
        }
    }

    @Test public void usesTheFirstEngineWhileTheFlagIsTrue() throws Exception {
        List<String> calls = new ArrayList<>();
        SwitchingEngine e = new SwitchingEngine(() -> true, new RecordingEngine("sftp", calls), new RecordingEngine("winscp", calls));
        assertTrue(e.run(JOB, x -> { }, "t1"));
        e.listFiles(null, "pw", "/d/", x -> { });
        assertEquals(List.of("sftp:run:t1", "sftp:list:/d/"), calls);
    }

    @Test public void usesTheSecondEngineWhileTheFlagIsFalse() throws Exception {
        List<String> calls = new ArrayList<>();
        SwitchingEngine e = new SwitchingEngine(() -> false, new RecordingEngine("sftp", calls), new RecordingEngine("winscp", calls));
        e.run(JOB, x -> { }, "t1");
        assertEquals(List.of("winscp:run:t1"), calls);
    }

    @Test public void theChoiceIsMadeAgainOnEveryCallSoSettingsApplyWithoutRestart() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicBoolean useSftp = new AtomicBoolean(true);
        SwitchingEngine e = new SwitchingEngine(useSftp::get, new RecordingEngine("sftp", calls), new RecordingEngine("winscp", calls));
        e.run(JOB, x -> { }, "a");
        useSftp.set(false);
        e.run(JOB, x -> { }, "b");
        useSftp.set(true);
        e.run(JOB, x -> { }, "c");
        assertEquals(List.of("sftp:run:a", "winscp:run:b", "sftp:run:c"), calls);
    }

    @Test public void theChosenEnginesResultIsReturnedUnchanged() throws Exception {
        List<String> calls = new ArrayList<>();
        RecordingEngine failing = new RecordingEngine("winscp", calls);
        failing.scriptResult = false;
        SwitchingEngine e = new SwitchingEngine(() -> false, new RecordingEngine("sftp", calls), failing);
        assertFalse(e.run(JOB, x -> { }, "t"));
        RemoteFileStat s = e.listFiles(null, "pw", "/d/", x -> { }).get("/d/x");
        assertEquals(2L, s.size);
    }

    @Test(expected = java.io.IOException.class)
    public void anEnginesExceptionPropagates() throws Exception {
        TransferEngine broken = new TransferEngine() {
            @Override public boolean run(TransferJob j, Consumer<String> l, String t) throws Exception { throw new java.io.IOException("down"); }
            @Override public Map<String, RemoteFileStat> listFiles(Credential c, String p, String d, Consumer<String> l) { return null; }
        };
        new SwitchingEngine(() -> true, broken, broken).run(JOB, x -> { }, "t");
    }
}
