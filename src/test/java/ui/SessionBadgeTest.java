package ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class SessionBadgeTest {

    @Test
    public void openSessionsAreShownLive() {
        SessionBadge.Look l = SessionBadge.of(2, 3, 3);
        assertEquals("SFTP ● 2 open", l.text());
        assertEquals(SessionBadge.Kind.LIVE, l.kind());
        assertTrue(l.tooltip(), l.tooltip().contains("2 SFTP sessions open") && l.tooltip().contains("Last run used 3 sessions for 3 batches"));
    }

    @Test
    public void oneSessionIsSingular() {
        assertEquals("SFTP ● 1 open", SessionBadge.of(1, -1, -1).text());
        assertTrue(SessionBadge.of(1, -1, -1).tooltip().contains("1 SFTP session open"));
        assertEquals("SFTP · last run: 1 session", SessionBadge.of(0, 1, 1).text());
        assertTrue(SessionBadge.of(0, 1, 1).tooltip().contains("for 1 batch."));
    }

    @Test
    public void whenNothingIsOpenTheLastRunIsShown() {
        SessionBadge.Look l = SessionBadge.of(0, 3, 3);
        assertEquals("SFTP · last run: 3 sessions", l.text());
        assertEquals(SessionBadge.Kind.LAST, l.kind());
    }

    @Test
    public void withNoInformationItSaysNoneAreOpen() {
        SessionBadge.Look l = SessionBadge.of(0, -1, -1);
        assertEquals("SFTP · none open", l.text());
        assertEquals(SessionBadge.Kind.IDLE, l.kind());
    }

    @Test
    public void lastRunIsReadFromTheNewestSummaryLine() {
        String log = "[INFO] Transfer summary: 5 file(s), 1.2 MB total, 1 batch(es), 1 session(s), 1 worker thread(s).\n"
                + "[INFO] something else\n"
                + "[INFO] Transfer summary: 40 file(s), 3.4 GB total, 4 batch(es), 4 session(s), 2 worker thread(s).\n";
        assertArrayEquals(new int[]{4, 4}, SessionBadge.lastRun(log));
    }

    @Test
    public void noSummaryMeansUnknown() {
        assertNull(SessionBadge.lastRun(null));
        assertNull(SessionBadge.lastRun(""));
        assertNull(SessionBadge.lastRun("[INFO] Nothing to transfer.\n"));
    }

    @Test
    public void theSummaryLineThePanelParsesIsTheOneTheTransferServiceWrites() throws Exception {
        // guards against the wording in TransferService drifting from what the badge reads
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/service/TransferService.java"));
        assertTrue(src.contains("Transfer summary: \""));
        assertTrue(src.contains("\" total, \" + batches.size() + \" batch(es), \""));
        assertTrue(src.contains("\" session(s), \" + threadsUsed + \" worker thread(s).\""));
    }
}
