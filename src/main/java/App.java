
import ui.MainWindow;
import javax.swing.*;

public class App {
    public static void main(String[] args) {
        // Note: Admin elevation is handled by OpsTransferTool.bat launcher
        // Running directly via java -jar may not have admin privileges
        ui.AppTheme.install();
        SwingUtilities.invokeLater(() -> {
            ui.EdtWatchdog.start(150); // logs any UI event that blocks the EDT for 150 ms or more
            new MainWindow().setVisible(true);
        });
    }
}
