package service;

import model.EventKind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LinkMonitorTest {

    private static LinkMonitor monitor() {
        LinkMonitor m = new LinkMonitor(0);
        m.setSources(List.of(new LinkMonitor.Source("a", "A to B", true, LinkTokens.hash("t"), "", 0, 0, 60, 180, 30, 3, 10)), 0);
        return m;
    }    private static final long SEC = 1000L, MIN = 60 * SEC;

    private static LinkMonitor.Report ev(String type, long seq) {
        return new LinkMonitor.Report(type, null, seq, "i1", null, 0);
    }

    private static List<LinkMonitor.Alert> send(LinkMonitor m, long now, LinkMonitor.Report... r) {
        return m.ingest("a", List.of(r), now).alerts();
    }

    @Test
    public void downIsAlertedOnceThenRestoredOnce() {
        LinkMonitor m = monitor();
        assertTrue(send(m, 1 * MIN, ev("connected", 1)).isEmpty());
        List<LinkMonitor.Alert> down = send(m, 5 * MIN, new LinkMonitor.Report("disconnected", null, 2, "i1", "connection reset", 0));
        assertEquals(1, down.size());
        assertEquals(EventKind.LINK_DOWN, down.get(0).kind());
        assertTrue(down.get(0).details().contains("connection reset"));
        assertTrue("a heartbeat repeating the bad state is not a new alert",
                send(m, 6 * MIN, new LinkMonitor.Report("heartbeat", "disconnected", 3, "i1", null, 0)).isEmpty());
        List<LinkMonitor.Alert> up = send(m, 9 * MIN, ev("connected", 4));
        assertEquals(1, up.size());
        assertEquals(EventKind.LINK_UP, up.get(0).kind());
        assertTrue(up.get(0).details().contains("4 min"));
    }

    @Test
    public void stalledIsItsOwnAlert() {
        LinkMonitor m = monitor();
        send(m, MIN, ev("connected", 1));
        List<LinkMonitor.Alert> a = send(m, 2 * MIN, ev("stalled", 2));
        assertEquals(EventKind.LINK_STALLED, a.get(0).kind());
        assertEquals(EventKind.LINK_UP, send(m, 3 * MIN, ev("recovered", 3)).get(0).kind());
    }

    @Test
    public void reconnectingWhileDownIsNotRecovery() {
        LinkMonitor m = monitor();
        send(m, MIN, ev("connected", 1));
        send(m, 2 * MIN, ev("disconnected", 2));
        assertTrue(send(m, 3 * MIN, ev("reconnecting", 3)).isEmpty());
        assertEquals(LinkMonitor.State.DISCONNECTED, m.stateOf("a"));
        assertEquals("only a real connect restores it", EventKind.LINK_UP, send(m, 4 * MIN, ev("connected", 4)).get(0).kind());
    }

    @Test
    public void duplicatesAndOldSequenceNumbersAreIgnored() {
        LinkMonitor m = monitor();
        send(m, MIN, ev("connected", 5));
        LinkMonitor.Ingest r = m.ingest("a", List.of(ev("disconnected", 5), ev("disconnected", 4)), 2 * MIN);
        assertEquals(0, r.accepted());
        assertEquals(2, r.duplicates());
        assertEquals(LinkMonitor.State.CONNECTED, m.stateOf("a"));
    }

    @Test
    public void aRestartedApplicationStartsCountingAgain() {
        LinkMonitor m = monitor();
        send(m, MIN, new LinkMonitor.Report("connected", null, 900, "old", null, 0));
        LinkMonitor.Ingest r = m.ingest("a", List.of(new LinkMonitor.Report("disconnected", null, 1, "new", null, 0)), 2 * MIN);
        assertEquals(1, r.accepted());
        assertEquals(EventKind.LINK_DOWN, r.alerts().get(0).kind());
    }

    @Test
    public void silenceIsAlertedOnceThenRemindersThenResumes() {
        LinkMonitor m = monitor();
        send(m, 0, ev("connected", 1));
        assertTrue(m.tick(2 * MIN).isEmpty());
        List<LinkMonitor.Alert> s = m.tick(4 * MIN);
        assertEquals(1, s.size());
        assertEquals(EventKind.LINK_SILENT, s.get(0).kind());
        assertTrue(m.tick(5 * MIN).isEmpty());
        assertEquals("reminder after the repeat interval", 1, m.tick(34 * MIN + 1).size());
        List<LinkMonitor.Alert> back = send(m, 40 * MIN, new LinkMonitor.Report("heartbeat", "connected", 2, "i1", null, 0));
        assertEquals(1, back.size());
        assertEquals(EventKind.LINK_UP, back.get(0).kind());
        assertFalse(m.isSilent("a"));
    }

    @Test
    public void aSourceThatNeverReportsAlertsAfterTheQuietLimit() {
        LinkMonitor m = monitor();
        assertTrue(m.tick(3 * MIN).isEmpty());
        assertEquals(EventKind.LINK_SILENT, m.tick(3 * MIN + 1).get(0).kind());
    }

    @Test
    public void heartbeatsKeepASourceFromGoingSilent() {
        LinkMonitor m = monitor();
        for (int i = 0; i < 20; i++) {
            send(m, i * MIN, new LinkMonitor.Report("heartbeat", "connected", i, "i1", null, 0));
            assertTrue(m.tick(i * MIN + 30 * SEC).isEmpty());
        }
    }

    @Test
    public void aHeartbeatReportingConnectedBringsADownLinkBackUp() {
        LinkMonitor m = monitor();
        send(m, MIN, ev("connected", 1));
        send(m, 2 * MIN, ev("disconnected", 2));
        assertEquals(LinkMonitor.State.DISCONNECTED, m.stateOf("a"));
        List<LinkMonitor.Alert> up = send(m, 3 * MIN, new LinkMonitor.Report("heartbeat", "connected", 3, "i1", null, 0));
        assertEquals(1, up.size());
        assertEquals(EventKind.LINK_UP, up.get(0).kind());
        assertEquals(LinkMonitor.State.CONNECTED, m.stateOf("a"));
    }

    @Test
    public void aHeartbeatWithoutStateProvesLifeButCannotChangeTheState() {
        LinkMonitor m = monitor();
        send(m, MIN, ev("disconnected", 1));
        for (int i = 2; i < 10; i++) {
            assertTrue(send(m, i * MIN, new LinkMonitor.Report("heartbeat", null, i, "i1", null, 0)).isEmpty());
            assertTrue("not silent: heartbeats arrive", m.tick(i * MIN + 10 * SEC).stream().noneMatch(a -> a.kind() == EventKind.LINK_SILENT));
        }
        assertEquals(LinkMonitor.State.DISCONNECTED, m.stateOf("a"));
    }

    @Test
    public void repeatedSequenceNumbersStillCountAsHeardFrom() {
        LinkMonitor m = monitor();
        send(m, MIN, new LinkMonitor.Report("heartbeat", "connected", 5, "i1", null, 0));
        for (int i = 2; i < 20; i++) {
            send(m, i * MIN, new LinkMonitor.Report("heartbeat", "connected", 5, "i1", null, 0));   // sender repeats seq 5
            assertTrue(m.tick(i * MIN + 10 * SEC).stream().noneMatch(a -> a.kind() == EventKind.LINK_SILENT));
        }
    }

    @Test
    public void repeatedDropsRaiseAFlappingAlertWithItsOwnThrottle() {
        LinkMonitor m = monitor();               // flapCount 3 in 10 minutes
        long t = MIN;
        send(m, t, ev("connected", 1));
        int flaps = 0;
        long seq = 2;
        for (int i = 0; i < 3; i++) {
            for (LinkMonitor.Alert a : send(m, t += MIN, ev("disconnected", seq++)))
                if (a.kind() == EventKind.LINK_FLAPPING) flaps++;
            send(m, t += MIN, ev("connected", seq++));
        }
        assertEquals(1, flaps);
        for (LinkMonitor.Alert a : send(m, t += MIN, ev("disconnected", seq++)))
            assertTrue("throttled", a.kind() != EventKind.LINK_FLAPPING);
    }

    @Test
    public void stillDownRemindersFollowTheRepeatInterval() {
        LinkMonitor m = monitor();
        send(m, 0, new LinkMonitor.Report("disconnected", null, 1, "i1", null, 0));
        // keep the source alive so only the state reminder fires
        send(m, 29 * MIN, new LinkMonitor.Report("heartbeat", "disconnected", 2, "i1", null, 0));
        assertTrue(m.tick(29 * MIN + 10 * SEC).isEmpty());
        send(m, 30 * MIN, new LinkMonitor.Report("heartbeat", "disconnected", 3, "i1", null, 0));
        List<LinkMonitor.Alert> r = m.tick(30 * MIN + 5 * SEC);
        assertEquals(1, r.size());
        assertEquals(EventKind.LINK_DOWN, r.get(0).kind());
    }

    @Test
    public void unknownSourcesAreIgnored() {
        LinkMonitor m = monitor();
        assertEquals(0, m.ingest("zzz", List.of(ev("connected", 1)), 0).accepted());
    }

    @Test
    public void aSourceAddedLaterStartsItsQuietClockWhenItIsAdded() {
        LinkMonitor m = monitor();
        long later = 500 * MIN;
        m.setSources(List.of(new LinkMonitor.Source("a", "A to B", true, LinkTokens.hash("t"), "", 0, 0, 60, 180, 30, 3, 10),
                new LinkMonitor.Source("b", "New one", true, LinkTokens.hash("u"), "", 0, 0, 60, 180, 30, 3, 10)), later);
        List<LinkMonitor.Alert> alerts = m.tick(later + MIN);
        for (LinkMonitor.Alert a : alerts) assertTrue("no instant alert for the new source", !a.sourceId().equals("b"));
        assertEquals("the old one has really been quiet", 1, alerts.size());
    }

    @Test
    public void aDisabledSourceIsNotMonitored() {
        LinkMonitor m = new LinkMonitor(0);
        m.setSources(List.of(new LinkMonitor.Source("a", "A", false, LinkTokens.hash("t"), "", 0, 0, 60, 180, 30, 3, 10)), 0);
        assertTrue(m.tick(100 * MIN).isEmpty());
        assertTrue(m.sourceIds().isEmpty());
    }

    @Test
    public void thePreviousTokenWorksOnlyInsideItsGracePeriod() {
        LinkMonitor.Source s = new LinkMonitor.Source("a", "A", true, LinkTokens.hash("new"), LinkTokens.hash("old"), 1000, 0, 60, 180, 30, 3, 10);
        assertTrue(s.accepts("new", 5000));
        assertTrue(s.accepts("old", 999));
        assertFalse(s.accepts("old", 1000));
        assertFalse(s.accepts("other", 0));
        assertFalse(new LinkMonitor.Source("a", "A", false, LinkTokens.hash("new"), "", 0, 0, 60, 180, 30, 3, 10).accepts("new", 0));
    }


}
