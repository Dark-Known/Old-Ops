package service;

import static org.junit.Assert.*;

import org.junit.Test;

public class AlertThrottleTest {

    private static final long MIN = 60_000L;

    @Test public void theFirstAlertIsRaisedAndRepeatsWaitForTheInterval() {
        AlertThrottle t = new AlertThrottle();
        assertTrue(t.tryRaise("a", 60 * MIN, 0));
        assertFalse(t.tryRaise("a", 60 * MIN, 30 * MIN));
        assertFalse(t.tryRaise("a", 60 * MIN, 59 * MIN));
        assertTrue("after the interval it alerts again", t.tryRaise("a", 60 * MIN, 60 * MIN));
        assertFalse("and the clock restarts", t.tryRaise("a", 60 * MIN, 61 * MIN));
    }

    @Test public void zeroMeansEveryRun() {
        AlertThrottle t = new AlertThrottle();
        assertTrue(t.tryRaise("a", 0, 0));
        assertTrue(t.tryRaise("a", 0, 1));
    }

    @Test public void tasksAreIndependent() {
        AlertThrottle t = new AlertThrottle();
        assertTrue(t.tryRaise("a", 60 * MIN, 0));
        assertTrue(t.tryRaise("b", 60 * MIN, 1));
        assertFalse(t.tryRaise("a", 60 * MIN, 2));
    }

    @Test public void resetLetsTheNextOneThroughAtOnce() {
        AlertThrottle t = new AlertThrottle();
        assertTrue(t.tryRaise("a", 60 * MIN, 0));
        t.reset("a");
        assertTrue(t.tryRaise("a", 60 * MIN, 1));
        assertEquals(-1, t.minutesSince("zzz", 5));
    }

    @Test public void minutesSinceReportsTheAge() {
        AlertThrottle t = new AlertThrottle();
        t.tryRaise("a", 60 * MIN, 0);
        assertEquals(12, t.minutesSince("a", 12 * MIN));
    }
}
