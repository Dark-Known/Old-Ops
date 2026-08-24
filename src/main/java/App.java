
import ui.MainWindow;
import javax.swing.*;

public class App {
    public static void main(String[] args) {
        // Note: Admin elevation is handled by OpsTransferTool.bat launcher
        // Running directly via java -jar may not have admin privileges
        ui.AppTheme.install();
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
