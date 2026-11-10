package service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TransferPathsTest {

    @Test
    public void localPathsGetBackslashesWithoutDoubles() {
        assertEquals("C:\\data\\in", TransferPaths.normalizeLocalPath("  C:/data//in "));
        assertEquals("", TransferPaths.normalizeLocalPath(null));
    }

    @Test
    public void remotePathsGetForwardSlashesAndDriveLettersAreRooted() {
        assertEquals("/a/b/c", TransferPaths.normalizeRemotePath(" \\a\\\\b//c "));
        assertEquals("/C:/x/y", TransferPaths.normalizeRemotePath("C:\\x\\y"));
        assertEquals("", TransferPaths.normalizeRemotePath(null));
    }

    @Test
    public void nullPathsQuoteToEmptyQuotes() {
        assertEquals("\"\"", TransferPaths.escapeWinScpPath(null));
        assertEquals("\"\"", TransferPaths.escapeWinScpRemotePath(null));
    }

    @Test
    public void remoteDestinationGetsTheFileNameOnlyWhenItIsAFolder() {
        assertEquals("/in/a.csv", TransferPaths.prepareRemoteDestination("/in/", "a.csv"));
        assertEquals("/in/a.csv", TransferPaths.prepareRemoteDestination("/in", "a.csv"));
        assertEquals("/in/fixed.txt", TransferPaths.prepareRemoteDestination("/in/fixed.txt", "a.csv"));
        assertEquals("C:\\in\\a.csv", TransferPaths.prepareRemoteDestination("C:\\in\\", "a.csv"));
    }

    @Test
    public void listingFolderDropsAWildcardOrAddsASlash() {
        assertEquals("/in/", TransferPaths.remoteListingDirFor("/in/*"));
        assertEquals("/in/", TransferPaths.remoteListingDirFor("/in"));
        assertEquals("/in/", TransferPaths.remoteListingDirFor("/in/"));
    }
}
