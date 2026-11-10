package service.sftp;

import org.junit.Test;
import service.TransferPaths;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * What TransferPaths writes into a script line must be exactly what the built-in SFTP engine reads back.
 */
public class TransferPathsRoundTripTest {

    @Test
    public void quotingEscapesWhatTheScriptParserUnescapes() {
        String local = "C:\\a b\\my \"v2\".txt";
        String remote = "/r/x y/\"q\".txt";
        List<String> t = SftpScriptRunner.tokens("put " + TransferPaths.escapeWinScpPath(local)
                + " " + TransferPaths.escapeWinScpRemotePath(remote));
        assertEquals(3, t.size());
        assertEquals(local, SftpScriptRunner.unescapeLocal(t.get(1)));
        assertEquals(remote, SftpScriptRunner.unescapeRemote(t.get(2)));
    }

}
