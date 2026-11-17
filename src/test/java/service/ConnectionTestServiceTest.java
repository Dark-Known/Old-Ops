package service;

import static org.junit.Assert.*;

import org.junit.Test;
import service.sftp.ConnectOptions;

import java.io.IOException;

public class ConnectionTestServiceTest {

    /** The client wraps the driver's message; the reason must be classified on the original, not the wrapper. */
    private static IOException wrapped(String original) {
        return new IOException("SFTP connect to h:22 failed: " + original, new Exception(original));
    }

    @Test public void authenticationFailureIsRecognisedThroughTheWrapper() {
        assertTrue(ConnectionTestService.describeFailure(wrapped("Auth fail")).startsWith("Authentication failed"));
    }

    @Test public void timeoutIsRecognisedThroughTheWrapper() {
        assertTrue(ConnectionTestService.describeFailure(wrapped("timeout: socket is not established")).startsWith("Connection timed out"));
    }

    @Test public void unknownHostIsRecognisedThroughTheWrapper() {
        assertTrue(ConnectionTestService.describeFailure(wrapped("java.net.UnknownHostException: nope")).startsWith("Unknown host"));
    }

    @Test public void refusedConnectionIsRecognisedThroughTheWrapper() {
        assertTrue(ConnectionTestService.describeFailure(wrapped("Connection refused")).startsWith("Could not reach host"));
    }

    @Test public void anUnwrappedExceptionStillWorks() {
        assertTrue(ConnectionTestService.describeFailure(new Exception("Auth cancel")).startsWith("Authentication failed"));
        assertEquals("Connection failed: weird", ConnectionTestService.describeFailure(new Exception("weird")));
    }

    @Test public void missingHostOrUserIsReportedWithoutConnecting() {
        assertFalse(ConnectionTestService.testSftp(" ", "u", "p").success);
        assertFalse(ConnectionTestService.testSftp("h", "", "p").success);
    }

    @Test public void connectOptionsKeepThePortAndBothTimeouts() {
        ConnectOptions o = ConnectOptions.of(2222, 8000, 6000);
        assertEquals(2222, o.port);
        assertEquals(8000, o.sessionTimeoutMs);
        assertEquals(6000, o.channelTimeoutMs);
        ConnectOptions t = ConnectOptions.timeout(5000);
        assertEquals(22, t.port);
        assertEquals(5000, t.sessionTimeoutMs);
        assertEquals(5000, t.channelTimeoutMs);
        assertEquals(2200, t.withPort(2200).port);
        assertEquals(5000, t.withPort(2200).sessionTimeoutMs);
    }
}
