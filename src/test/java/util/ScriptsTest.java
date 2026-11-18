package util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScriptsTest {

    @Test public void dpapiScriptIsTheSameOneLineAsBefore() {
        String expected = "Add-Type -AssemblyName System.Security; $in = [Console]::In.ReadLine(); $b = [Convert]::FromBase64String($in); "
                + "[Console]::Out.Write([Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::Protect($b, $null, "
                + "[System.Security.Cryptography.DataProtectionScope]::LocalMachine)))";
        assertEquals(expected, Scripts.oneLine("security/dpapi.ps1", "OPERATION", "Protect"));
        assertEquals(expected.replace("]::Protect(", "]::Unprotect("), Scripts.oneLine("security/dpapi.ps1", "OPERATION", "Unprotect"));
    }

    @Test public void placeholdersAreFilledAndCommentsDropped() {
        String s = Scripts.compact("security/dpapi.ps1", "OPERATION", "Protect");
        assertTrue(s.contains("::Protect("));
        assertFalse(s.contains("{{") || s.contains("#"));
    }

    @Test public void everyScriptLoads() {
        for (String n : new String[] {"link/link-health-check.ps1", "link/link-pin-check.ps1", "link/send-message-example.ps1", "security/dpapi.ps1",
                "daemon/install-daemon.ps1", "daemon/uninstall-daemon.ps1", "daemon/start-service.ps1", "daemon/query-service.ps1", "daemon/run-elevated.ps1",
                "watcher/windows/listener.ps1", "watcher/linux/listener.sh"}) {
            assertTrue(n, !Scripts.raw(n).isEmpty());
        }
    }
}
