package util;

import static org.junit.Assert.*;
import org.junit.Test;

public class TimersTest {
    @Test public void missingTagGivesTheDefault() {
        assertEquals(1234, Timers.ms("timerThatDoesNotExist", 1234));
        assertEquals(77, Timers.msInt("timerThatDoesNotExist", 77));
    }
}
