package service;

import static org.junit.Assert.*;

import org.junit.Test;

public class SettingsInputTest {
    @Test public void blankMeansNoOverride() {
        assertEquals(Long.valueOf(0), SettingsInput.parseBatchMaxBytes(""));
        assertEquals(Long.valueOf(0), SettingsInput.parseBatchMaxBytes("   "));
        assertEquals(Long.valueOf(0), SettingsInput.parseBatchMaxBytes(null));
    }

    @Test public void wholeNumbersAreKeptAndNegativesBecomeZero() {
        assertEquals(Long.valueOf(1048576), SettingsInput.parseBatchMaxBytes(" 1048576 "));
        assertEquals(Long.valueOf(0), SettingsInput.parseBatchMaxBytes("-5"));
    }

    @Test public void anythingElseIsRejected() {
        assertNull(SettingsInput.parseBatchMaxBytes("10MB"));
        assertNull(SettingsInput.parseBatchMaxBytes("1.5"));
    }
}
