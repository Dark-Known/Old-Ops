package service.transfer;

import static org.junit.Assert.*;

import org.junit.Test;
import service.sftp.SftpClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionRegistryTest {

    /** A process that only remembers whether it was killed. */
    private static final class FakeProcess extends Process {
        volatile boolean destroyed;
        @Override public OutputStream getOutputStream() { return null; }
        @Override public InputStream getInputStream() { return null; }
        @Override public InputStream getErrorStream() { return null; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
    }

    /** An SFTP client that counts how often it was cancelled; every other call is unsupported. */
    private static SftpClient client(AtomicInteger cancelled) {
        return (SftpClient) Proxy.newProxyInstance(SftpClient.class.getClassLoader(), new Class<?>[]{SftpClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) { cancelled.incrementAndGet(); return null; }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("toString")) return "fake-client";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test public void countsProcessesPerTaskAndInTotal() {
        SessionRegistry r = new SessionRegistry();
        r.addProcess("a", new FakeProcess());
        r.addProcess("a", new FakeProcess());
        r.addProcess("b", new FakeProcess());
        assertEquals(2, r.activeSessionCount("a"));
        assertEquals(1, r.activeSessionCount("b"));
        assertEquals(0, r.activeSessionCount("nobody"));
        assertEquals(0, r.activeSessionCount(null));
        assertEquals(3, r.totalActiveSessionCount());
    }

    @Test public void removingTheLastProcessForgetsTheTask() {
        SessionRegistry r = new SessionRegistry();
        Process p = new FakeProcess();
        r.addProcess("a", p);
        r.removeProcess("a", p);
        assertEquals(0, r.activeSessionCount("a"));
        assertTrue(r.activeSessionCountsByTask().isEmpty());
    }

    @Test public void removingAProcessThatNeverStartedIsHarmless() {
        SessionRegistry r = new SessionRegistry();
        r.addProcess("a", new FakeProcess());
        r.removeProcess("a", null);          // start failed while a sibling batch is registered
        r.removeProcess(null, new FakeProcess());
        r.removeProcess("unknown", new FakeProcess());
        assertEquals(1, r.activeSessionCount("a"));
    }

    @Test public void theByTaskExportListsOnlyBusyTasks() {
        SessionRegistry r = new SessionRegistry();
        r.addProcess("a", new FakeProcess());
        r.addProcess("a", new FakeProcess());
        r.addProcess("b", new FakeProcess());
        assertEquals(2, (int) r.activeSessionCountsByTask().get("a"));
        assertEquals(1, (int) r.activeSessionCountsByTask().get("b"));
        assertEquals(2, r.activeSessionCountsByTask().size());
    }

    @Test public void inProcessSftpClientsAreCounted() {
        SessionRegistry r = new SessionRegistry();
        r.addClient("a", client(new AtomicInteger()));
        r.addClient("a", client(new AtomicInteger()));
        r.addProcess("a", new FakeProcess());
        assertEquals(3, r.activeSessionCount("a"));
        assertEquals(3, r.totalActiveSessionCount());
        assertEquals(3, (int) r.activeSessionCountsByTask().get("a"));
    }

    @Test public void aHelperRegisteredUnderTwoIdsIsCountedOnceInTotal() {
        SessionRegistry r = new SessionRegistry();
        FakeProcess helper = new FakeProcess();
        r.addProcess("backup-mkdir", helper);
        r.addProcess("real-task", helper);
        assertEquals(1, r.activeSessionCount("real-task"));
        assertEquals(1, r.totalActiveSessionCount());
    }

    @Test public void cancelKillsProcessesAndCancelsClients() {
        SessionRegistry r = new SessionRegistry();
        FakeProcess p1 = new FakeProcess(), p2 = new FakeProcess();
        AtomicInteger cancelled = new AtomicInteger();
        r.addProcess("a", p1);
        r.addProcess("a", p2);
        r.addClient("a", client(cancelled));

        assertTrue(r.cancel("a"));

        assertTrue(p1.destroyed && p2.destroyed);
        assertEquals(1, cancelled.get());
        assertEquals("a cancelled task no longer has sessions", 0, r.activeSessionCount("a"));
    }

    @Test public void cancelLeavesOtherTasksAlone() {
        SessionRegistry r = new SessionRegistry();
        FakeProcess mine = new FakeProcess(), theirs = new FakeProcess();
        r.addProcess("a", mine);
        r.addProcess("b", theirs);
        r.cancel("a");
        assertTrue(mine.destroyed);
        assertFalse(theirs.destroyed);
        assertEquals(1, r.activeSessionCount("b"));
    }

    @Test public void cancellingAnIdleOrUnknownTaskReportsNothingStopped() {
        SessionRegistry r = new SessionRegistry();
        assertFalse(r.cancel("idle"));
        assertFalse(r.cancel(null));
    }

    @Test public void cancellingOnlyClientsStillReportsSuccess() {
        SessionRegistry r = new SessionRegistry();
        AtomicInteger cancelled = new AtomicInteger();
        r.addClient("a", client(cancelled));
        assertTrue(r.cancel("a"));
        assertEquals(1, cancelled.get());
    }

    @Test public void aBackupHelperRegisteredUnderTheRealTaskIsCancelledWithIt() {
        SessionRegistry r = new SessionRegistry();
        FakeProcess helper = new FakeProcess();
        r.addProcess("backup-mkdir", helper);
        r.addProcess("real-task", helper);     // what an engine does for pseudo ids
        r.cancel("real-task");
        assertTrue(helper.destroyed);
    }

    @Test public void theRealTaskIdIsPerThread() throws Exception {
        SessionRegistry r = new SessionRegistry();
        r.bindRealTask("mine");
        String[] other = new String[1];
        Thread t = new Thread(() -> other[0] = r.realTaskId());
        t.start(); t.join();
        assertEquals("mine", r.realTaskId());
        assertNull("another thread sees nothing", other[0]);
        r.unbindRealTask();
        assertNull(r.realTaskId());
    }
}
