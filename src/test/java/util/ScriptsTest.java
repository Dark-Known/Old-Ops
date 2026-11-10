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
        String s = Scripts.compact("daemon/install-daemon.ps1", "INSTALL_SCRIPT", "C:\\O\\i.ps1", "INSTALL_DIR", "C:\\O",
                "DATA_DIR", "C:\\d", "WINSW", "C:\\O\\w.exe", "JAR", "C:\\O\\a.jar");
        assertEquals("& \"C:\\O\\i.ps1\" -InstallDir \"C:\\O\" -DataDir \"C:\\d\" -WinSwPath \"C:\\O\\w.exe\" -JarPath \"C:\\O\\a.jar\"", s);
        assertFalse(s.contains("{{") || s.contains("#"));
    }

    @Test public void everyScriptLoads() {
        for (String n : new String[] {"daemon/start-service.ps1", "daemon/query-service.ps1", "daemon/run-elevated.ps1",
                "daemon/uninstall-daemon.ps1", "link/link-health-check.ps1", "link/link-pin-check.ps1"}) {
            assertTrue(n, !Scripts.raw(n).isEmpty());
        }
    }
}
