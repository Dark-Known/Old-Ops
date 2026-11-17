package service.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The daemon as a Windows Service: the PowerShell that installs, removes and starts it, running that
 * elevated (UAC), and asking the Service Control Manager for its status. No UI here; the settings panel
 * decides what to tell the person.
 */
public final class WindowsDaemonService {

    public static final String SERVICE_NAME = "OpsTransferToolDaemon";

    private WindowsDaemonService() { }

    // ── scripts (pure) ───────────────────────────────────────────────────────

    /** Runs {@code install-service.ps1} with the folders and files the service needs. */
    public static String installScript(File installScript, File installDir, String dataDir, File winSw, String jarPath) {
        return "& \"" + installScript.getAbsolutePath() + "\" "
                + "-InstallDir \"" + installDir.getAbsolutePath() + "\" "
                + "-DataDir \"" + dataDir + "\" "
                + "-WinSwPath \"" + winSw.getAbsolutePath() + "\" "
                + "-JarPath \"" + jarPath + "\"";
    }

    /** Stops then uninstalls the service through WinSW. */
    public static String uninstallScript(File winSw) {
        return "& \"" + winSw.getAbsolutePath() + "\" stop; "
                + "& \"" + winSw.getAbsolutePath() + "\" uninstall";
    }

    /** Starts the service if it was stopped. */
    public static String startScript() {
        return "Start-Service -Name '" + SERVICE_NAME + "'";
    }

    /**
     * The PowerShell command that re-launches {@code script} elevated. The script travels as
     * {@code -EncodedCommand}, a base64 blob of its UTF-16LE bytes, instead of literal text threaded
     * through several layers of shell quoting; base64 holds none of {@code ' " \} or whitespace, so there
     * is nothing left for any layer to misinterpret.
     */
    public static String elevatedLauncher(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return "Start-Process powershell.exe -ArgumentList "
                + "'-NoProfile','-NonInteractive','-EncodedCommand','" + encoded + "' -Verb RunAs -Wait";
    }

    // ── running them ─────────────────────────────────────────────────────────

    /**
     * Runs a PowerShell script elevated (triggers a UAC prompt) and waits for it to finish.
     *
     * @return the elevated process's exit code
     * @throws IOException if PowerShell could not be launched
     */
    public static int runElevated(String script) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("powershell.exe", "-NonInteractive", "-NoProfile", "-Command", elevatedLauncher(script))
                .redirectErrorStream(true)
                .start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (br.readLine() != null) { /* drain: the elevated child's own stdout isn't visible to us anyway */ }
        }
        return p.waitFor();
    }

    /**
     * The service's current status ("Running", "Stopped", ...) from the Service Control Manager; needs no
     * elevation.
     *
     * @return the status text, or null if the service is not installed (or cannot be queried)
     */
    public static String queryStatus() {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NonInteractive", "-NoProfile", "-Command",
                    "Get-Service -Name '" + SERVICE_NAME + "' -ErrorAction SilentlyContinue "
                            + "| Select-Object -ExpandProperty Status")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = br.readLine();
            }
            p.waitFor();
            return (line == null || line.isBlank()) ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean exists() {
        return queryStatus() != null;
    }
}
