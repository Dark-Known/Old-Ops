package service.watch;

import model.Credential;
import model.ScheduledTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Tasks that watch the same remote folder share one listener. No SSH here: the connection start is replaced
 * by a recorder, so these tests cover only who subscribes to what and who is told about a change.
 */
public class RemotePushWatcherSharingTest {

    private final List<RemotePushWatcher.ListenerHandle> launched = new CopyOnWriteArrayList<>();
    private final Map<String, Credential> credentialByUser = new ConcurrentHashMap<>();
    private final BlockingQueue<String> woken = new LinkedBlockingQueue<>();
    private final List<String> caughtUp = new CopyOnWriteArrayList<>();   // "taskId:why" for every catch-up wake-up
    private volatile String catchUpFailsFor = null;
    private RemotePushWatcher watcher;

    private static Credential cred(String host, String user, String os) {
        Credential c = new Credential();
        c.setHost(host);
        c.setUsername(user);
        c.setOsType(os);
        return c;
    }

    private ScheduledTask task(String id, String user, String path) {
        ScheduledTask t = new ScheduledTask();
        t.setId(id);
        t.setTaskType(ScheduledTask.TaskType.FILE_TRANSFER);
        t.setWatcherEnabled(true);
        t.setTransferDirection(ScheduledTask.TransferDirection.INBOUND);
        t.setTransferMode(ScheduledTask.TransferMode.LATEST_ONLY);
        t.setStatus(ScheduledTask.TaskStatus.PENDING);
        t.setTargetUsername(user);
        t.setTargetPath(path);
        return t;
    }

    @Before
    public void setUp() {
        credentialByUser.put("ops", cred("10.0.0.5", "gv\\ops", "WINDOWS"));
        credentialByUser.put("other", cred("10.0.0.5", "gv\\other", "WINDOWS"));
        credentialByUser.put("lnx", cred("10.0.0.9", "ops", "LINUX"));
        watcher = new RemotePushWatcher(null, (id, names) -> woken.add(id + ":" + new TreeSet<>(names)), (id, why) -> {
            if (id.equals(catchUpFailsFor)) throw new IllegalStateException("boom");
            caughtUp.add(id + ":" + why);
        }) {
            @Override
            Credential resolveCredential(ScheduledTask t) {
                return credentialByUser.get(t.getTargetUsername());
            }

            @Override
            void launch(ListenerHandle handle, Credential cred, RemoteOs os) {
                launched.add(handle);
            }
        };
        watcher.start();
    }

    @After
    public void tearDown() {
        watcher.stop();
    }

    // ── which tasks share a listener ────────────────────────────────────────

    @Test
    public void tasksOnTheSameFolderShareOneListener() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "d:/in/")));
        assertEquals("one connection for the folder", 1, launched.size());
        assertTrue(watcher.isPushActive("A"));
        assertTrue(watcher.isPushActive("B"));
        assertEquals(Set.of("A", "B"), launched.get(0).subscribers);
    }

    @Test
    public void differentFoldersGetTheirOwnListener() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\Out")));
        assertEquals(2, launched.size());
    }

    @Test
    public void sameFolderUnderAnotherUserIsNotShared() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "other", "D:\\In")));
        assertEquals(2, launched.size());
    }

    @Test
    public void aTaskThatJoinsLaterReusesTheLiveListener() {
        watcher.sync(List.of(task("A", "ops", "D:\\In")));
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertEquals(1, launched.size());
        assertTrue(watcher.isPushActive("B"));
        assertFalse(launched.get(0).closed);
    }

    @Test
    public void repeatedSyncsDoNotReconnect() {
        List<ScheduledTask> tasks = List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In"));
        watcher.sync(tasks);
        watcher.sync(tasks);
        watcher.sync(tasks);
        assertEquals(1, launched.size());
    }

    // ── lifetime ────────────────────────────────────────────────────────────

    @Test
    public void listenerStaysUpUntilTheLastSubscriberLeaves() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        RemotePushWatcher.ListenerHandle h = launched.get(0);

        watcher.sync(List.of(task("A", "ops", "D:\\In")));      // B is gone
        assertFalse("A still needs it", h.closed);
        assertFalse(watcher.isPushActive("B"));
        assertTrue(watcher.isPushActive("A"));

        watcher.sync(List.of());                                  // A is gone too
        assertTrue(h.closed);
        assertFalse(watcher.isPushActive("A"));
    }

    @Test
    public void aTaskThatMovesToAnotherFolderLeavesTheOldListener() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        RemotePushWatcher.ListenerHandle shared = launched.get(0);

        watcher.sync(List.of(task("A", "ops", "D:\\Archive"), task("B", "ops", "D:\\In")));
        assertEquals(2, launched.size());
        assertEquals(Set.of("B"), shared.subscribers);
        assertFalse("B still uses the first one", shared.closed);
        assertEquals(Set.of("A"), launched.get(1).subscribers);
    }

    // ── events and failures reach everyone ──────────────────────────────────

    @Test
    public void aChangeOnTheFolderWakesEverySubscribedTask() throws Exception {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        watcher.fanOut(launched.get(0), "LDM_0001.txt");

        Set<String> got = new TreeSet<>();
        got.add(woken.poll(5, TimeUnit.SECONDS));
        got.add(woken.poll(5, TimeUnit.SECONDS));
        assertEquals(Set.of("A:[LDM_0001.txt]", "B:[LDM_0001.txt]"), got);
    }

    @Test
    public void aTaskOnAnotherFolderIsNotWoken() throws Exception {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\Out")));
        watcher.fanOut(launched.get(0), "x.txt");   // launched.get(0) is A's folder
        assertEquals("A:[x.txt]", woken.poll(5, TimeUnit.SECONDS));
        assertNull(woken.poll(2, TimeUnit.SECONDS));
    }

    @Test
    public void aListenerThatCannotWorkMarksEveryTaskOnIt() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        watcher.markUnsupported(launched.get(0), "remote PowerShell reported: access denied");
        for (String id : new String[]{"A", "B"}) {
            assertTrue(id, watcher.isRecentlyUnsupported(id));
            assertEquals("remote PowerShell reported: access denied", watcher.getReason(id));
        }
    }

    @Test
    public void tasksInBackoffDoNotRestartAFailedListener() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        watcher.markUnsupported(launched.get(0), "nope");
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertEquals("no reconnect storm while both are backing off", 1, launched.size());
    }

    // ── manual Reconnect ────────────────────────────────────────────────────

    @Test
    public void reconnectRestartsTheSharedListenerAndTheOtherTaskRejoinsOnTheNextSync() {
        ScheduledTask a = task("A", "ops", "D:\\In"), b = task("B", "ops", "D:\\In");
        watcher.sync(List.of(a, b));
        RemotePushWatcher.ListenerHandle first = launched.get(0);

        watcher.forceReconnect(a);
        assertTrue("old connection ended", first.closed);
        assertEquals(2, launched.size());
        assertTrue(watcher.isPushActive("A"));
        assertFalse("B has not rejoined yet", watcher.isPushActive("B"));
        assertNotNull(watcher.getReason("B"));

        watcher.sync(List.of(a, b));
        assertEquals("B joins the new listener, no third connection", 2, launched.size());
        assertTrue(watcher.isPushActive("B"));
        assertEquals(Set.of("A", "B"), launched.get(1).subscribers);
    }

    // ── catching up after a gap ─────────────────────────────────────────────

    @Test
    public void nobodyCatchesUpBeforeTheListenerIsWatching() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertTrue(caughtUp.toString(), caughtUp.isEmpty());
    }

    @Test
    public void everySubscriberCatchesUpOnceWhenTheListenerIsReady() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        watcher.armed(launched.get(0), "armed");
        assertEquals(2, caughtUp.size());
        assertTrue(caughtUp.contains("A:armed"));
        assertTrue(caughtUp.contains("B:armed"));
    }

    @Test
    public void aTaskThatJoinsARunningListenerCatchesUpAloneAndTheOthersAreNotRepeated() {
        watcher.sync(List.of(task("A", "ops", "D:\\In")));
        watcher.armed(launched.get(0), "armed");
        caughtUp.clear();
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertEquals(1, caughtUp.size());
        assertTrue(caughtUp.get(0), caughtUp.get(0).startsWith("B:"));
    }

    @Test
    public void aTaskThatJoinsWhileTheListenerIsNotWatchingWaitsUntilItIs() {
        watcher.sync(List.of(task("A", "ops", "D:\\In")));
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertTrue("not watching yet, so nothing to catch up with", caughtUp.isEmpty());
        watcher.armed(launched.get(0), "armed");
        assertEquals(2, caughtUp.size());
    }

    @Test
    public void everyReconnectCatchesUpAgainButNotDuringTheGap() {
        watcher.sync(List.of(task("A", "ops", "D:\\In")));
        RemotePushWatcher.ListenerHandle h = launched.get(0);
        watcher.armed(h, "first");
        assertEquals(1, caughtUp.size());

        h.armed = false;                                   // the connection was lost (what runListenerOnce does when it ends)
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        assertEquals("B joined during the gap: no catch-up yet", 1, caughtUp.size());

        watcher.armed(h, "second");                        // reconnected
        assertEquals(3, caughtUp.size());
        assertTrue(caughtUp.contains("A:second") && caughtUp.contains("B:second"));
    }

    @Test
    public void aCatchUpThatFailsDoesNotStopTheOtherTasks() {
        watcher.sync(List.of(task("A", "ops", "D:\\In"), task("B", "ops", "D:\\In")));
        catchUpFailsFor = "A";
        watcher.armed(launched.get(0), "armed");
        assertEquals(List.of("B:armed"), caughtUp);
    }

    // ── the key ─────────────────────────────────────────────────────────────

    @Test
    public void windowsFolderSpellingsOfTheSameFolderGiveOneKey() {
        Credential c = credentialByUser.get("ops");
        String expected = RemotePushWatcher.listenerKey(c, RemotePushWatcher.RemoteOs.WINDOWS, "D:\\In");
        for (String spelling : new String[]{"d:/in/", "D:\\IN\\", "/D:/In", "D:\\In\\*", "D:\\\\In"}) {
            assertEquals(spelling, expected, RemotePushWatcher.listenerKey(c, RemotePushWatcher.RemoteOs.WINDOWS, spelling));
        }
    }

    @Test
    public void linuxPathsAreCaseSensitiveButIgnoreATrailingSlash() {
        Credential c = credentialByUser.get("lnx");
        RemotePushWatcher.RemoteOs os = RemotePushWatcher.RemoteOs.LINUX;
        assertEquals(RemotePushWatcher.listenerKey(c, os, "/data/in"), RemotePushWatcher.listenerKey(c, os, "/data/in/"));
        assertNotEquals(RemotePushWatcher.listenerKey(c, os, "/data/in"), RemotePushWatcher.listenerKey(c, os, "/Data/in"));
    }

    @Test
    public void hostAndUserSeparateKeys() {
        RemotePushWatcher.RemoteOs os = RemotePushWatcher.RemoteOs.WINDOWS;
        String base = RemotePushWatcher.listenerKey(cred("10.0.0.5", "gv\\ops", "WINDOWS"), os, "D:\\In");
        assertEquals("user name case is ignored on Windows", base, RemotePushWatcher.listenerKey(cred("10.0.0.5", "GV\\OPS", "WINDOWS"), os, "D:\\In"));
        assertNotEquals(base, RemotePushWatcher.listenerKey(cred("10.0.0.6", "gv\\ops", "WINDOWS"), os, "D:\\In"));
        assertNotEquals(base, RemotePushWatcher.listenerKey(cred("10.0.0.5", "gv\\other", "WINDOWS"), os, "D:\\In"));
    }
}
