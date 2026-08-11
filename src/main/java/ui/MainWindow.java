package ui;

import ui.CredentialManagerPanel;
import model.ScheduledTask;
import service.TaskSchedulerService;
import service.TransferService;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class MainWindow extends JFrame {

    private final XmlStorageService storage;
    private final TransferService transferService;
    private final TaskSchedulerService scheduler;

    private TaskManagerPanel taskPanel;
    private CredentialManagerPanel credPanel;

    public MainWindow() {
        String dataDir = loadDataDir();
        this.storage = new XmlStorageService(dataDir);
        this.transferService = new TransferService(storage);
        // Read poll interval preference (default 60s)
        Preferences prefs = Preferences.userNodeForPackage(SettingsPanel.class);
        int poll = prefs.getInt("poll_interval_seconds", 60);
        this.scheduler = new TaskSchedulerService(storage, transferService, poll);

        setTitle("Monitoring tool");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(950, 700);
        setMinimumSize(new Dimension(750, 520));
        setLocationRelativeTo(null);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        setIconImage(buildAppIcon());
        buildUI();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(MainWindow.this,
                    "Quit Monitoring tool? Scheduled tasks will stop running.",
                    "Exit", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    scheduler.stop();
                    dispose();
                    System.exit(0);
                }
            }
        });

        scheduler.start();
        showStartupFailures();
    }
    private String loadDataDir() {
        String defaultDir = "C:\\OpsTools\\Data";
        String val = util.AppConfig.readValue("dataDir");
        return val != null ? val : defaultDir;
    }

    private void showStartupFailures() {
        List<ScheduledTask> failedTasks = storage.loadTasks().stream()
            .filter(t -> t.getStatus() == ScheduledTask.TaskStatus.FAILED
                      || t.getStatus() == ScheduledTask.TaskStatus.RETRYING
                      || t.getStatus() == ScheduledTask.TaskStatus.RUNNING)
            .collect(Collectors.toList());
        List<ScheduledTask> skippedTasks = storage.loadTasks().stream()
            .filter(t -> "SKIPPED".equals(t.getLastRunResult()))
            .collect(Collectors.toList());
        if (failedTasks.isEmpty() && skippedTasks.isEmpty()) {
            return;
        }

        StringBuilder details = new StringBuilder();
        details.append("The following tasks require attention:\n\n");
        if (!failedTasks.isEmpty()) {
            details.append("Failures / Stale Running:\n");
            for (ScheduledTask task : failedTasks) {
                details.append("Task: ").append(task.getName()).append("\n");
                details.append("Status: ").append(task.getStatus().name()).append("\n");
                details.append("Last run: ")
                    .append(task.getLastRunAt() != null ? task.getLastRunAt().toString() : "Never")
                    .append("\n");
                details.append("Result: ")
                    .append(task.getLastRunResult() != null ? task.getLastRunResult() : "Unknown")
                    .append("\n");
                details.append("Retries left: ").append(task.getRetryCount()).append("\n");
                details.append("Last started: ")
                    .append(task.getLastStartedAt() != null ? task.getLastStartedAt().toString() : "Never")
                    .append("\n\n");
            }
        }
        if (!skippedTasks.isEmpty()) {
            details.append("Skipped Tasks:\n");
            for (ScheduledTask task : skippedTasks) {
                details.append("Task: ").append(task.getName()).append("\n");
                details.append("Result: SKIPPED\n");
                details.append("Last run: ")
                    .append(task.getLastRunAt() != null ? task.getLastRunAt().toString() : "Never")
                    .append("\n\n");
            }
        }

        JTextArea textArea = new JTextArea(details.toString());
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setBackground(new Color(0xFFEBEE));
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 280));

        JOptionPane.showMessageDialog(this,
            scrollPane,
            "Task Alert",
            JOptionPane.WARNING_MESSAGE);
    }

    private void buildUI() {
        // ── Header ────────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x1A237E));
        header.setBorder(new EmptyBorder(10, 16, 10, 16));

        JLabel title = new JLabel("Monitoring tool");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel subTitle = new JLabel("Schedule file transfers and service actions • Per-user credentials in plain-text XML");
        subTitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subTitle.setForeground(new Color(0xBBDEFB));

        JPanel titleStack = new JPanel(new BorderLayout(0, 2));
        titleStack.setOpaque(false);
        titleStack.add(title, BorderLayout.NORTH);
        titleStack.add(subTitle, BorderLayout.SOUTH);

        // Status badges panel
        JPanel badges = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        badges.setOpaque(false);

        JLabel guiBadge = new JLabel("● GUI Scheduler Running");
        guiBadge.setForeground(new Color(0xA5D6A7));
        guiBadge.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        JLabel daemonBadge = new JLabel("● Daemon: checking...");
        daemonBadge.setForeground(new Color(0xFFCC80));
        daemonBadge.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        badges.add(guiBadge);
        badges.add(daemonBadge);

        // Check daemon status in background
        new javax.swing.SwingWorker<String, Void>() {
            protected String doInBackground() {
                try {
                    Process p = Runtime.getRuntime().exec(
                        new String[]{"schtasks", "/Query", "/TN", "Monitoring-Tool-Daemon", "/FO", "LIST"});
                    p.waitFor();
                    return p.exitValue() == 0 ? "registered" : "not registered";
                } catch (Exception e) { return "unknown"; }
            }
            protected void done() {
                try {
                    String s = get();
                    if ("registered".equals(s)) {
                        daemonBadge.setText("● Daemon: Active");
                        daemonBadge.setForeground(new Color(0xA5D6A7));
                    } else {
                        daemonBadge.setText("● Daemon: Not registered");
                        daemonBadge.setForeground(new Color(0xEF9A9A));
                        daemonBadge.setToolTipText("Go to Settings tab to register the background daemon");
                    }
                } catch (Exception ignored) {}
            }
        }.execute();

        header.add(titleStack, BorderLayout.WEST);
        header.add(badges, BorderLayout.EAST);

        // ── Tabs ─────────────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(tabs.getFont().deriveFont(Font.PLAIN, 13f));

        taskPanel = new TaskManagerPanel(storage, scheduler);
        credPanel = new CredentialManagerPanel(storage);
        JPanel notificationPanel = new NotificationPanel(storage, scheduler);

        tabs.addTab("Tasks", iconFor("tasks"), taskPanel);
        tabs.addTab("Credentials", iconFor("creds"), credPanel);
        tabs.addTab("Notifications", iconFor("settings"), notificationPanel);
        tabs.addTab("Settings", iconFor("settings"), new SettingsPanel(transferService, scheduler));

        // Refresh task panel when switching back from credentials
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == taskPanel) taskPanel.refresh();
            if (tabs.getSelectedComponent() == credPanel) credPanel.refresh();
        });

        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        // ── Status bar ────────────────────────────────────────────────────────
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        statusBar.add(new JLabel("Data: " + loadDataDir()));
        add(statusBar, BorderLayout.SOUTH);
    }

    private Icon iconFor(String name) {
        // Simple colored square icons (no external icon files needed)
        return new Icon() {
            public int getIconWidth() { return 16; }
            public int getIconHeight() { return 16; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color col;
                switch (name) {
                    case "tasks": col = new Color(0x1565C0); break;
                    case "creds": col = new Color(0x2E7D32); break;
                    default: col = new Color(0x6A1B9A); break;
                }
                g2.setColor(col);
                g2.fillRoundRect(x + 1, y + 1, 14, 14, 4, 4);
                g2.dispose();
            }
        };
    }

    private Image buildAppIcon() {
        // 32x32 programmatic icon
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0x1A237E));
        g2.fillRoundRect(0, 0, 32, 32, 8, 8);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g2.drawString("O", 7, 24);
        g2.dispose();
        return img;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
