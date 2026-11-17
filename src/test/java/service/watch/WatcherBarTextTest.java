package service.watch;

import static org.junit.Assert.*;

import org.junit.Test;
import service.TaskSchedulerService.WatchMode;

import java.text.SimpleDateFormat;
import java.util.Date;

public class WatcherBarTextTest {

    private static final long EPOCH = 1_700_000_000_000L;
    private static final String DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(EPOCH));

    @Test public void noBaselineIsGreyAndExplainsTheFirstRun() {
        String h = WatcherBarText.bar(0, -1, "");
        assertTrue(h, h.startsWith("<html>") && h.endsWith("</html>"));
        assertTrue(h, h.contains("#757575") && h.contains("no baseline stored"));
    }

    @Test public void aHealthyBaselineIsGreenWithDateAndSize() {
        String h = WatcherBarText.bar(EPOCH, 1_234_567, "");
        assertTrue(h, h.contains("#2E7D32"));
        assertTrue(h, h.contains("last seen " + DATE));
        assertTrue(h, h.contains("size: " + String.format("%,d", 1_234_567L) + " bytes"));
    }

    @Test public void aBaselineWithoutASizeIsAmberAndTellsTheOperatorToReset() {
        String h = WatcherBarText.bar(EPOCH, -1, "");
        assertTrue(h, h.contains("#E65100"));
        assertTrue(h, h.contains("size: not tracked") && h.contains("reset baseline"));
    }

    @Test public void theModeTagIsAppendedInsideTheHtml() {
        String tag = WatcherBarText.modeTag(WatchMode.NATIVE_WATCH, false);
        String h = WatcherBarText.bar(EPOCH, 5, tag);
        assertTrue(h, h.endsWith(tag + "</html>"));
    }

    @Test public void eachModeHasItsLabelAndColour() {
        assertTrue(WatcherBarText.modeTag(WatchMode.NATIVE_WATCH, false).contains("Live (native watch)"));
        assertTrue(WatcherBarText.modeTag(WatchMode.REMOTE_PUSH, false).contains("Live (remote push)"));
        assertTrue(WatcherBarText.modeTag(WatchMode.POLLING_ONLY, false).contains("Polling only"));
        String unsupported = WatcherBarText.modeTag(WatchMode.POLLING_ONLY_UNSUPPORTED, false);
        assertTrue(unsupported, unsupported.contains("remote push unavailable") && unsupported.contains("#E65100"));
        assertTrue(WatcherBarText.modeTag(WatchMode.NATIVE_WATCH, false).contains("#2E7D32"));
        assertTrue(WatcherBarText.modeTag(WatchMode.POLLING_ONLY, false).contains("#757575"));
    }

    @Test public void aModeFromTheDaemonIsMarkedAsSuch() {
        assertTrue(WatcherBarText.modeTag(WatchMode.REMOTE_PUSH, true).contains("• daemon"));
        assertFalse(WatcherBarText.modeTag(WatchMode.REMOTE_PUSH, false).contains("daemon"));
    }

    @Test public void aModeWithNothingToSayGivesAnEmptyTag() {
        assertEquals("", WatcherBarText.modeTag(WatchMode.NOT_APPLICABLE, false));
        assertEquals("", WatcherBarText.modeTag(WatchMode.NOT_APPLICABLE, true));
    }

    @Test public void anUnknownExportedModeNameIsHarmless() {
        assertEquals(WatchMode.NOT_APPLICABLE, WatcherBarText.parseMode("SOMETHING_FROM_A_NEWER_VERSION"));
        assertEquals(WatchMode.NOT_APPLICABLE, WatcherBarText.parseMode(null));
        assertEquals(WatchMode.REMOTE_PUSH, WatcherBarText.parseMode("REMOTE_PUSH"));
    }
}
