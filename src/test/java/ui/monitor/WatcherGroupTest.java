package ui.monitor;

import model.EventKind;
import model.TaskRunRecord;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * A watcher's "armed" and its SSH session share a group key and read as one Watcher row.
 */
public class WatcherGroupTest {
    private static MonitorEvent ev(long id, EventKind kind, LocalDateTime t, String runKey) {
        return new MonitorEvent(MonitorEvent.Table.ACTIVITY, id, "t1", "Task", "FILE_TRANSFER", null, kind,
                kind.defaultSeverity(), TaskRunRecord.Status.SUCCESS, t, t, 0, kind.label(), null, runKey, null, false, null);
    }

    @Test
    public void armedAndSshSessionFormOneWatcherRow() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 4, 10, 0, 0);
        String key = "watch:t1:1";
        var res = FeedBuilder.build(List.of(
                        ev(1, EventKind.SSH_OPENED, t0, key),
                        ev(2, EventKind.WATCH_ARMED, t0.plusSeconds(1), key),
                        ev(3, EventKind.SSH_CLOSED, t0.plusSeconds(60), key)),
                EventFilter.none(), true, true, Set.of(), Set.of(), t0.plusMinutes(5));
        assertEquals(1, res.rows().size());
        FeedRow row = res.rows().get(0);
        assertEquals(FeedRow.Type.RUN, row.type);
        assertEquals("Watcher", row.label);
        assertEquals(3, row.events.size());
    }

    @Test
    public void withoutAGroupKeyTheyStaySeparate() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 4, 10, 0, 0);
        var res = FeedBuilder.build(List.of(
                        ev(1, EventKind.SSH_OPENED, t0, null), ev(2, EventKind.WATCH_ARMED, t0.plusSeconds(1), null)),
                EventFilter.none(), true, true, Set.of(), Set.of(), t0.plusMinutes(5));
        assertEquals(2, res.rows().size());
    }
}
