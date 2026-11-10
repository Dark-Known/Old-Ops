package service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * What is said on an SSH session travels with its "closed" event.
 */
public class SshSessionLogTest {
    @Test
    public void closedEventCarriesTheSessionTranscript() {
        List<SshSessionEvents.Event> got = new ArrayList<>();
        Consumer<SshSessionEvents.Event> sink = got::add;
        SshSessionEvents.setSink(sink);
        try {
            SshSessionEvents.opened("t1", "SFTP transfer", "host", "user");
            service.sftp.SessionLog.currentOrNew().add("get /in/a.txt -> a.txt (5 bytes in 3 ms)");
            SshSessionEvents.closed("t1", "SFTP transfer", "host", "user", "finished", 120);
        } finally {
            SshSessionEvents.clearSink(sink);
        }
        assertEquals(2, got.size());
        assertNull(got.get(0).log());
        assertTrue(got.get(1).log(), got.get(1).log().contains("get /in/a.txt"));
        assertFalse(got.get(1).log().toLowerCase().contains("password"));
    }
}
