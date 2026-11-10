import ui.MainWindow;

import javax.swing.*;

public class App {
    public static void main(String[] args) {
        // Note: Admin elevation is handled by OpsTransferTool.bat launcher
        // Running directly via java -jar may not have admin privileges
        if (util.LeanLaunch.relaunchIfNeeded(args)) return;   // restarted with lean JVM settings; this copy exits
        // Direct3D pipeline reserves tens of MB of GPU-driver memory on Windows for a plain
        // Swing window; software rendering is plenty for tables and forms. Must precede AWT init.
        if (System.getProperty("sun.java2d.d3d") == null) System.setProperty("sun.java2d.d3d", "false");
        ui.AppTheme.install();
        SwingUtilities.invokeLater(() -> {
            ui.EdtWatchdog.start(150); // logs any UI event that blocks the EDT for 150 ms or more
            new MainWindow().setVisible(true);
            {
                String dd = util.AppConfig.readValue("dataDir");
                MainWindow.startMemoryReports(dd != null ? dd : "C:\\OpsTools\\Data");
            } // trims unused heap; writes logs/memory-gui.txt
        });
    }
}
