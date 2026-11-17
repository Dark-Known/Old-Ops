package service;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;

public class AppFolderTest {

    @Test public void theDaemonStatusFileLivesInTheDataFolder() {
        File dir = new File("data");
        assertEquals(dir.toPath().resolve("scheduler-status-daemon.dat"), AppFolder.at(dir).daemonStatusFile());
        assertEquals("scheduler-status-daemon.dat", AppFolder.DAEMON_STATUS_FILE);
    }

    @Test public void theFolderIsExposedForTheFewFeaturesThatNeedIt() {
        File dir = new File("data");
        assertSame(dir, AppFolder.at(dir).dir());
    }

    @Test public void changeSignalsAreAlwaysAvailableEvenBeforeAnAdapterIsInstalled() {
        assertNotNull(AppFolder.at(new File("data")).changes());
    }
}
