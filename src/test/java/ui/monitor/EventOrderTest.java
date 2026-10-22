package ui.monitor;

import static org.junit.Assert.*;

import model.EventKind;
import model.TaskRunRecord;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class EventOrderTest {

    private static MonitorEvent ev(long id, EventKind kind, LocalDateTime start, LocalDateTime end) {
        return new MonitorEvent(MonitorEvent.Table.ACTIVITY, id, "t1", "Task", "FILE_TRANSFER", null, kind,
                kind.defaultSeverity(), TaskRunRecord.Status.SUCCESS, start, end,
                java.time.Duration.between(start, end).toMillis(), "x", "WATCHER", "run-1", null, false, null);
    }

    /**
     * The remote listing connects first and then lists; "Files detected" is stamped with the START of
     * that scan (so its duration can be shown) while "SSH opened" is stamped when the connection came up,
     * a moment later. Detection must still read as happening after the session opened and before it closed.
     */
    @Test
    public void filesDetectedSortsBetweenSshOpenedAndClosed() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 22, 10, 0, 0);
        List<MonitorEvent> events = new ArrayList<>(List.of(
                ev(4, EventKind.SSH_CLOSED, t0.plusSeconds(9), t0.plusSeconds(9)),
                ev(3, EventKind.FILES_DETECTED, t0.plusSeconds(0), t0.plusSeconds(3)),   // scan began 10:00:00, finished 10:00:03
                ev(2, EventKind.SSH_OPENED, t0.plusSeconds(2), t0.plusSeconds(2)),       // connected while the scan was running
                ev(1, EventKind.RUN_STARTED, t0, t0)));
        events.sort(FeedBuilder.CHRONOLOGICAL);
        List<EventKind> order = new ArrayList<>();
        for (MonitorEvent e : events) order.add(e.kind());
        assertEquals(List.of(EventKind.RUN_STARTED, EventKind.SSH_OPENED, EventKind.FILES_DETECTED, EventKind.SSH_CLOSED), order);
    }
}
