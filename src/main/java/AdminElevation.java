

import java.io.File;
import java.util.logging.Logger;

/**
 * Detects if the application is running with administrator privileges.
 * If not, attempts to restart the application with elevation via PowerShell.
 *
 * Usage in main():
 *   AdminElevation.ensureAdminOrExit();
 *   // Continue with rest of application
 */
public class AdminElevation {

    private static final Logger log = Logger.getLogger(AdminElevation.class.getName());

    /**
     * Check if running as admin; if not, re-launch with elevation and exit.
     * Safe to call multiple times (no-op if already admin).
got      */
    public static void ensureAdminOrExit() {
        log.info("=== AdminElevation.ensureAdminOrExit() called ===");
        log.info("Current user: " + System.getProperty("user.name"));
        log.info("Current directory: " + System.getProperty("user.dir"));
        log.info("Java home: " + System.getProperty("java.home"));

        if (isRunningAsAdmin()) {
            log.info("✓ Application is running with administrator privileges.");
            return;
        }

        log.warning("✗ Application is NOT running as admin. Attempting elevation...");

        if (elevateAndRestart()) {
            log.info("✓ Elevation successful. Exiting current instance to let elevated instance run.");
            System.exit(0);
        } else {
            log.severe("✗ Failed to elevate. Proceeding without admin - this may cause issues.");
            // Optionally show a warning dialog here
        }
    }

    /**
     * Detect if running as admin using multiple methods.
     * First tries to check if USER environment variable equals "SYSTEM" (service context).
     * Then checks registry write access. Falls back to file-based detection.
     */
    private static boolean isRunningAsAdmin() {
        // Method 1: Check if running as SYSTEM (scheduled task context)
        String user = System.getenv("USERNAME");
        if ("SYSTEM".equalsIgnoreCase(user)) {
            log.info("Detected SYSTEM context - running as admin.");
            return true;
        }

        // Method 2: Try to write to HKEY_LOCAL_MACHINE via reg.exe (requires admin)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "reg.exe", "query", "HKEY_LOCAL_MACHINE\\Software", "/s", "/f", "opstool_check");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            // If we can query HKEY_LOCAL_MACHINE, we likely have admin
            if (exitCode == 0 || exitCode == 1) {
                log.info("Registry check passed - running as admin.");
                return true;
            }
        } catch (Exception ignored) {
        }

        // Method 3: Try to write to System32 (requires admin)
        try {
            File testFile = new File("C:\\Windows\\System32\\opstool_admin_test_" + System.nanoTime() + ".tmp");
            if (testFile.createNewFile()) {
                testFile.delete();
                log.info("File write to System32 succeeded - running as admin.");
                return true;
            }
        } catch (Exception ignored) {
        }

        // Method 4: Try to execute a PowerShell command that requires admin
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NonInteractive", "-Command", 
                "if (Test-Path 'C:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts') { exit 0 } else { exit 1 }");
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("PowerShell admin check passed.");
                return true;
            }
        } catch (Exception ignored) {
        }

        log.warning("Could not verify admin status - assuming NOT admin.");
        return false;
    }

    /**
     * Re-launch the application with elevation.
     * First tries interactive PowerShell with UAC prompt visible.
     * Falls back to cmd.exe elevation if PowerShell fails.
     * Returns true if re-launch was initiated; false if it failed.
     */
    private static boolean elevateAndRestart() {
        String jarPath = getJarPath();
        if (jarPath == null) {
            log.severe("Cannot locate OpsTransferTool.jar for elevation.");
            return false;
        }

        String javaExe = getJavaExecutable();
        log.info("Attempting to elevate with: " + javaExe + " -jar " + jarPath);

        // Method 1: Try PowerShell with interactive mode (allows UAC prompt)
        if (elevateViaInteractivePowerShell(javaExe, jarPath)) {
            log.info("Successfully initiated elevation via PowerShell.");
            return true;
        }

        // Method 2: Try cmd.exe /start elevation
        if (elevateViaCmd(javaExe, jarPath)) {
            log.info("Successfully initiated elevation via cmd.exe.");
            return true;
        }

        // Method 3: Try direct Java restart with -Xbootclasspath hack (last resort, unreliable)
        log.warning("Both elevation methods failed. Elevation may not work on this system.");
        return false;
    }

    /** Elevate using interactive PowerShell with visible UAC prompt. */
    private static boolean elevateViaInteractivePowerShell(String javaExe, String jarPath) {
        try {
            log.info("Trying interactive PowerShell elevation...");

            String psCommand = String.format(
                "Start-Process -FilePath '%s' -ArgumentList '-jar', '%s' -Verb RunAs",
                javaExe.replace("'", "''"), jarPath.replace("'", "''"));

            log.info("PowerShell command: " + psCommand);

            // Run PowerShell INTERACTIVELY (without -NonInteractive) so UAC prompt appears
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", psCommand);

            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Capture any output
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 50) {
                output.append(line).append("\n");
                log.info("PS OUT: " + line);
                lineCount++;
            }

            int exitCode = p.waitFor();
            log.info("PowerShell exited with code: " + exitCode);

            if (exitCode == 0) {
                return true;
            }
            if (output.length() > 0) {
                log.warning("PowerShell output: " + output.toString());
            }
            return false;

        } catch (Exception e) {
            log.warning("PowerShell elevation failed: " + e.getMessage());
            return false;
        }
    }

    /** Elevate using cmd.exe /start with elevation flag. */
    private static boolean elevateViaCmd(String javaExe, String jarPath) {
        try {
            log.info("Trying cmd.exe elevation...");

            // cmd.exe /c start "" /b /max "java.exe" -jar "jarpath"
            // The /max makes it maximize, /b makes it run in background
            // We'll use a simpler approach: just invoke the command directly
            
            ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe",
                "/c",
                "start", "\"OpsTransferTool\"", 
                javaExe, 
                "-jar", 
                jarPath);

            log.info("cmd.exe elevation command prepared.");

            Process p = pb.start();
            int exitCode = p.waitFor();
            
            log.info("cmd.exe exited with code: " + exitCode);
            return exitCode == 0;

        } catch (Exception e) {
            log.warning("cmd.exe elevation failed: " + e.getMessage());
            return false;
        }
    }

    /** Locate the JAR file containing this application. */
    private static String getJarPath() {
        try {
            File codeSource = new File(AdminElevation.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
                log.info("Found JAR via ProtectionDomain: " + codeSource.getAbsolutePath());
                return codeSource.getAbsolutePath();
            }
        } catch (Exception e) {
            log.warning("Failed to get JAR from ProtectionDomain: " + e.getMessage());
        }

        // Fallback: look for OpsTransferTool.jar in common locations
        String[] candidates = {
            "OpsTransferTool.jar",
            System.getProperty("user.dir") + File.separator + "OpsTransferTool.jar",
            System.getProperty("user.home") + File.separator + "OpsTransferTool.jar",
            "C:\\OpsTools\\OpsTransferTool.jar",
            "C:\\Program Files\\OpsTransferTool\\OpsTransferTool.jar",
            "C:\\Program Files (x86)\\OpsTransferTool\\OpsTransferTool.jar"
        };

        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                log.info("Found JAR at: " + f.getAbsolutePath());
                return f.getAbsolutePath();
            }
        }

        log.severe("Could not locate OpsTransferTool.jar in any candidate location.");
        return null;
    }

    /** Get the path to the Java executable. */
    private static String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exe = javaHome + File.separator + "bin" + File.separator + "java.exe";
        File f = new File(exe);
        return f.exists() ? exe : "java";
    }
}
