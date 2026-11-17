package service.schedule;

import static org.junit.Assert.*;

import model.ScheduledTask;
import model.ScheduledTask.ScheduleType;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NextRunLabelTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0, 0);   // a Thursday

    private static ScheduledTask task(ScheduleType type) {
        ScheduledTask t = new ScheduledTask();
        t.setScheduleType(type);
        t.setStatus(ScheduledTask.TaskStatus.PENDING);
        return t;
    }

    private static String label(ScheduledTask t) { return NextRunLabel.of(t, NOW, F); }

    @Test public void aDisabledTaskSaysSoWhateverItsSchedule() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("14:30");
        t.setStatus(ScheduledTask.TaskStatus.DISABLED);
        assertEquals("Disabled", label(t));
    }

    @Test public void runNowIsOnDemand() {
        assertEquals("On demand", label(task(ScheduleType.RUN_NOW)));
    }

    @Test public void onceShowsItsTimeOrOverdueOrInvalid() {
        ScheduledTask t = task(ScheduleType.ONCE);
        assertEquals("Invalid", label(t));
        t.setScheduledAt(NOW.plusDays(1));
        assertEquals("2026-11-13 10:00", label(t));
        t.setScheduledAt(NOW.minusHours(1));
        assertEquals("Overdue", label(t));
    }

    @Test public void dailyShowsTodayOrTomorrow() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("14:30");
        assertEquals("2026-11-12 14:30", label(t));
        t.setCronExpression("09:00");
        assertEquals("2026-11-13 09:00", label(t));
    }

    @Test public void aDailyTaskThatAlreadyRanTodayShowsTomorrowLikeTheSchedulerDoes() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("14:30");
        t.setLastRunAt(NOW.minusMinutes(30));      // run manually this morning
        assertEquals("2026-11-13 14:30", label(t));
    }

    @Test public void weeklyShowsTheNextMatchingDay() {
        ScheduledTask t = task(ScheduleType.WEEKLY);
        t.setCronExpression("FRI 14:30");
        assertEquals("2026-11-13 14:30", label(t));
        t.setCronExpression("THU 14:30");
        assertEquals("later today", "2026-11-12 14:30", label(t));
        t.setCronExpression("THU 09:00");
        assertEquals("a week on", "2026-11-19 09:00", label(t));
    }

    @Test public void unreadableSchedulesAreInvalid() {
        ScheduledTask d = task(ScheduleType.DAILY);
        assertEquals("Invalid", label(d));
        d.setCronExpression("nonsense");
        assertEquals("Invalid", label(d));
        ScheduledTask w = task(ScheduleType.WEEKLY);
        w.setCronExpression("XYZ 10:00");
        assertEquals("Invalid", label(w));
        ScheduledTask i = task(ScheduleType.INTERVAL_MINUTES);
        assertEquals("Invalid", label(i));
    }

    @Test public void intervalsShowLastRunPlusIntervalOrNowWhenNeverRun() {
        ScheduledTask m = task(ScheduleType.INTERVAL_MINUTES);
        m.setIntervalMinutes(15);
        assertEquals("2026-11-12 10:00", label(m));
        m.setLastRunAt(NOW.minusMinutes(5));
        assertEquals("2026-11-12 10:10", label(m));
        ScheduledTask s = task(ScheduleType.INTERVAL_SECONDS);
        s.setIntervalSeconds(90);
        s.setLastRunAt(NOW);
        assertEquals("2026-11-12 10:01", label(s));
    }
}
