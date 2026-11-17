package service;

import static org.junit.Assert.*;

import org.junit.Test;

public class SaveFailureTextTest {

    @Test public void aHealthyConnectionWithAFailedWriteSaysSoAndIncludesTheDetail() {
        SaveFailureText.Failure f = SaveFailureText.taskWriteFailed("Nightly", "database is locked");
        assertEquals("Could not save task \"Nightly\" — database connected, but this write failed: database is locked", f.activityDetail);
        assertTrue(f.userMessage.contains("the database is connected"));
        assertTrue(f.userMessage.contains(":\ndatabase is locked"));
        assertTrue(f.userMessage.contains("try again"));
    }

    @Test public void aDroppedConnectionIsNotReportedAsAFailedWrite() {
        SaveFailureText.Failure f = SaveFailureText.taskNotConnected("Nightly", null);
        assertEquals("Could not save task \"Nightly\" — database not connected.", f.activityDetail);
        assertTrue(f.userMessage.contains("the database is not connected."));
        assertFalse(f.userMessage.contains("write failed"));
    }

    @Test public void credentialFailuresSayTheTaskWasNotSaved() {
        assertTrue(SaveFailureText.credentialWriteFailed("T", "x").activityDetail.endsWith("Task was not saved."));
        assertTrue(SaveFailureText.credentialNotConnected("T", null).userMessage.contains("The task has NOT been saved"));
        assertTrue(SaveFailureText.credentialNotConnected("T", "no file").activityDetail.contains("database not connected: no file"));
    }

    @Test public void settingsFailuresKeepTheirOriginalWording() {
        SaveFailureText.Failure f = SaveFailureText.settingsNotConnected("no file");
        assertEquals("Could not save application settings \u2014 database not connected: no file", f.activityDetail);
        assertEquals("Could not save settings \u2014 the database is not connected: no file Your changes were NOT saved.", f.userMessage);
        assertEquals("Could not save WinSCP path \u2014 database not connected.", SaveFailureText.winScpPathNotConnected(null).activityDetail);
        assertEquals("Could not save the WinSCP path \u2014 the database is not connected.", SaveFailureText.winScpPathNotConnected(null).userMessage);
    }
}
