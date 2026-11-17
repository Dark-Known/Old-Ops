package service.schedule;

import static org.junit.Assert.*;

import model.ScheduledTask;
import model.ScheduledTask.ScheduleType;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;

/** "When is this task next due" on fixed clock values. NOW is Thursday 12 Nov 2026, 10:00:00. */
public class NextFireTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0, 0);
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    private static ScheduledTask task(ScheduleType type) {
        ScheduledTask t = new ScheduledTask();
        t.setScheduleType(type);
        return t;
    }

    private static Long delay(ScheduledTask t) { return NextFire.delayMs(t, NOW); }
    private static Long delayAt(ScheduledTask t, LocalDateTime now) { return NextFire.delayMs(t, now); }

    // ── run now / once ───────────────────────────────────────────────────────

    @Test public void runNowIsDueImmediatelyUntilItHasRun() {
        ScheduledTask t = task(ScheduleType.RUN_NOW);
        assertEquals(Long.valueOf(0), delay(t));
        t.setLastRunAt(NOW.minusMinutes(1));
        assertNull(delay(t));
    }

    @Test public void onceCountsDownToItsTimeAndIsDueAtOncePastIt() {
        ScheduledTask t = task(ScheduleType.ONCE);
        t.setScheduledAt(NOW.plusMinutes(90));
        assertEquals(Long.valueOf(90 * MIN), delay(t));
        t.setScheduledAt(NOW.minusHours(2));
        assertEquals("overdue, never negative", Long.valueOf(0), delay(t));
    }

    @Test public void onceNeverFiresTwiceOrWithoutATime() {
        ScheduledTask t = task(ScheduleType.ONCE);
        assertNull("no time set", delay(t));
        t.setScheduledAt(NOW.plusHours(1));
        t.setLastRunAt(NOW.minusMinutes(5));
        assertNull("already ran", delay(t));
    }

    // ── daily ────────────────────────────────────────────────────────────────

    @Test public void dailyLaterTodayWaitsUntilThen() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("14:30");
        assertEquals(Long.valueOf(4 * HOUR + 30 * MIN), delay(t));
    }

    @Test public void dailyEarlierTodayWaitsUntilTomorrow() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("09:00");
        t.setLastRunAt(NOW.minusDays(1).withHour(9));
        assertEquals(Long.valueOf(23 * HOUR), delay(t));
    }

    @Test public void dailyThatAlreadyRanTodayWaitsUntilTomorrow() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("10:30");
        t.setLastRunAt(NOW.minusMinutes(10));
        assertEquals(Long.valueOf(DAY + 30 * MIN), delay(t));
    }

    @Test public void dailyThatJustPassedAndNotYetRunIsDueNow() {
        // Delivered a couple of seconds late: it must still run, not be re-armed for tomorrow.
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("10:00");
        t.setLastRunAt(NOW.minusDays(1));
        assertEquals(Long.valueOf(0), delayAt(t, NOW.plusSeconds(2)));
    }

    @Test public void dailyPastTheToleranceWaitsUntilTomorrow() {
        ScheduledTask t = task(ScheduleType.DAILY);
        t.setCronExpression("10:00");
        t.setLastRunAt(NOW.minusDays(1));
        long late = NextFire.DUE_TOLERANCE_MS / 1000 + 1;
        assertEquals(Long.valueOf(DAY - late * 1000), delayAt(t, NOW.plusSeconds(late)));
    }

    @Test public void dailyWithAMissingOrUnreadableTimeHasNoOccurrence() {
        ScheduledTask t = task(ScheduleType.DAILY);
        assertNull(delay(t));
        t.setCronExpression("25:99");
        assertNull(delay(t));
        t.setCronExpression("nonsense");
        assertNull(delay(t));
    }

    // ── weekly ───────────────────────────────────────────────────────────────

    @Test public void weeklyOnALaterDayCountsTheDays() {
        ScheduledTask t = task(ScheduleType.WEEKLY);
        t.setCronExpression("FRI 14:30");
        assertEquals(Long.valueOf(DAY + 4 * HOUR + 30 * MIN), delay(t));
    }

    @Test public void weeklyOnTheSameDayButEarlierWaitsAWeek() {
        ScheduledTask t = task(ScheduleType.WEEKLY);
        t.setCronExpression("THU 09:00");
        t.setLastRunAt(NOW.minusHours(1));
        assertEquals(Long.valueOf(7 * DAY - HOUR), delay(t));
    }

    @Test public void weeklyThatJustPassedIsDueNow() {
        ScheduledTask t = task(ScheduleType.WEEKLY);
        t.setCronExpression("THURSDAY 10:00");
        t.setLastRunAt(NOW.minusDays(7));
        assertEquals(Long.valueOf(0), delayAt(t, NOW.plusSeconds(1)));
    }

    @Test public void weeklyWithABadDayOrShapeHasNoOccurrence() {
        ScheduledTask t = task(ScheduleType.WEEKLY);
        t.setCronExpression("XYZ 10:00");
        assertNull(delay(t));
        t.setCronExpression("FRI");
        assertNull(delay(t));
        t.setCronExpression("FRI 99:99");
        assertNull(delay(t));
        t.setCronExpression(null);
        assertNull(delay(t));
    }

    // ── intervals ────────────────────────────────────────────────────────────

    @Test public void intervalsStartImmediatelyThenCountFromTheLastRun() {
        ScheduledTask t = task(ScheduleType.INTERVAL_MINUTES);
        t.setIntervalMinutes(5);
        assertEquals("never run", Long.valueOf(0), delay(t));
        t.setLastRunAt(NOW.minusMinutes(3));
        assertEquals(Long.valueOf(2 * MIN), delay(t));
        t.setLastRunAt(NOW.minusMinutes(30));
        assertEquals("overdue", Long.valueOf(0), delay(t));
    }

    @Test public void secondIntervalsWorkTheSameWay() {
        ScheduledTask t = task(ScheduleType.INTERVAL_SECONDS);
        t.setIntervalSeconds(30);
        t.setLastRunAt(NOW.minusSeconds(10));
        assertEquals(Long.valueOf(20_000), delay(t));
    }

    @Test public void aZeroOrNegativeIntervalHasNoOccurrence() {
        ScheduledTask m = task(ScheduleType.INTERVAL_MINUTES);
        m.setIntervalMinutes(0);
        assertNull(delay(m));
        ScheduledTask s = task(ScheduleType.INTERVAL_SECONDS);
        s.setIntervalSeconds(-1);
        assertNull(delay(s));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test public void dayNamesAreCaseAndLengthTolerant() {
        assertEquals(DayOfWeek.MONDAY, NextFire.parseDayOfWeek("mon"));
        assertEquals(DayOfWeek.SATURDAY, NextFire.parseDayOfWeek("SATURDAY"));
        assertEquals(DayOfWeek.TUESDAY, NextFire.parseDayOfWeek(" Tues "));
        assertEquals(DayOfWeek.THURSDAY, NextFire.parseDayOfWeek("thu"));
        assertNull(NextFire.parseDayOfWeek("xyz"));
        assertNull(NextFire.parseDayOfWeek("  "));
        assertNull(NextFire.parseDayOfWeek(null));
    }

    @Test public void justDueNeedsAPassedOccurrenceInsideTheToleranceThatHasNotRunYet() {
        LocalDateTime occ = NOW;
        assertFalse("still in the future", NextFire.justDue(occ, NOW.minusSeconds(1), null));
        assertTrue(NextFire.justDue(occ, NOW.plusSeconds(1), null));
        assertFalse("too late", NextFire.justDue(occ, NOW.plus(Duration.ofMillis(NextFire.DUE_TOLERANCE_MS + 1)), null));
        assertFalse("already ran for it", NextFire.justDue(occ, NOW.plusSeconds(1), NOW));
        assertTrue("last ran before it", NextFire.justDue(occ, NOW.plusSeconds(1), NOW.minusDays(1)));
    }
}
