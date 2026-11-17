package service.os;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class WindowsDaemonServiceTest {

    @Test public void theInstallScriptPassesEveryFolderAndFileQuoted() {
        String s = WindowsDaemonService.installScript(new File("/opt/ops/install-service.ps1"), new File("/opt/ops"),
                "/data/dir with space", new File("/opt/ops/daemon-service.exe"), "/opt/ops/OpsTransferTool.jar");
        assertTrue(s, s.startsWith("& \""));
        assertTrue(s, s.contains("install-service.ps1\" -InstallDir \""));
        assertTrue(s, s.contains("-DataDir \"/data/dir with space\""));
        assertTrue(s, s.contains("-WinSwPath \""));
        assertTrue(s, s.endsWith("-JarPath \"/opt/ops/OpsTransferTool.jar\""));
    }

    @Test public void theUninstallScriptStopsThenUninstalls() {
        String s = WindowsDaemonService.uninstallScript(new File("/opt/ops/daemon-service.exe"));
        assertTrue(s, s.indexOf("stop;") < s.indexOf("uninstall"));
    }

    @Test public void theStartScriptNamesTheService() {
        assertEquals("Start-Service -Name 'OpsTransferToolDaemon'", WindowsDaemonService.startScript());
    }

    @Test public void theElevatedLauncherCarriesTheScriptAsBase64OfUtf16() {
        String script = "& \"C:\\Ops Tools\\x.ps1\" -DataDir \"D:\\it's\"";
        String launcher = WindowsDaemonService.elevatedLauncher(script);
        assertTrue(launcher, launcher.contains("-Verb RunAs -Wait"));
        assertFalse("no raw quoting survives into the launcher", launcher.contains("C:\\Ops Tools"));
        String blob = launcher.substring(launcher.indexOf("'-EncodedCommand','") + "'-EncodedCommand','".length());
        blob = blob.substring(0, blob.indexOf('\''));
        assertEquals(script, new String(Base64.getDecoder().decode(blob), StandardCharsets.UTF_16LE));
    }
}
