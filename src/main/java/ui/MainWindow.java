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
    private NotificationBell notificationBell;
    private ToastManager toastManager;
    private java.awt.event.ComponentListener sidebarResizeListener;
    private java.util.function.Consumer<model.TaskRunRecord> runListener;
    private javax.swing.Timer bellRefreshTimer;

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
        // AppTheme.install() (see App.java's main()) already sets the real look and feel for
        // the whole app before this window is constructed; this fallback only matters if
        // MainWindow is ever instantiated directly without going through App.main().
        if (!(UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLaf)) {
            AppTheme.install();
        }

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

        scheduler.enableStatusExport(dataDir, "gui");
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
        if (sidebarResizeListener != null) {
            removeComponentListener(sidebarResizeListener);
            sidebarResizeListener = null;
        }
        // Both of these are re-registered further down every time buildUI() runs (currently
        // just the theme toggle rebuild) — without removing the previous ones first, each
        // rebuild would add ANOTHER toast listener and start ANOTHER refresh timer on top of
        // the old ones, which never get garbage collected because RunHistoryService/the Timer
        // still holds a reference to them. That's exactly what caused every run to pop one
        // extra toast per theme toggle: the old (now invisible) ToastManager instances kept
        // firing right alongside the current one.
        if (runListener != null) {
            scheduler.getRunHistoryService().removeRunListener(runListener);
            runListener = null;
        }
        if (bellRefreshTimer != null) {
            bellRefreshTimer.stop();
            bellRefreshTimer = null;
        }

        Color bgBase = UIManager.getColor("Panel.background");
        boolean dark = AppTheme.isDark();
        Color sidebarBg = dark ? new Color(0x1B1D28) : new Color(0xF7F7FB);
        Color sidebarBorder = dark ? new Color(0x2B2E3E) : new Color(0xE7E7F0);
        Color navSelectedBg = dark ? withAlpha(AppTheme.ACCENT, 40) : withAlpha(AppTheme.ACCENT, 24);
        Color navIdleFg = dark ? new Color(0x9CA0B5) : new Color(0x6B6F87);

        // ── Header: gradient banner with rounded status chips ───────────────────
        GradientPanel header = new GradientPanel(new BorderLayout(), AppTheme.ACCENT_DARK, AppTheme.ACCENT_SECONDARY);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel title = new JLabel("Monitoring tool");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        title.setForeground(Color.WHITE);

        JLabel subTitle = new JLabel("Schedule file transfers and service actions  \u00B7  Per-user credentials in plain-text XML");
        subTitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subTitle.setForeground(new Color(0xE3E1FB));

        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleStack.add(title);
        titleStack.add(Box.createVerticalStrut(3));
        titleStack.add(subTitle);

        JPanel badges = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        badges.setOpaque(false);

        JLabel guiBadge = statusChip("GUI Scheduler Running", new Color(0x9CB380));
        JLabel daemonBadge = statusChip("Daemon: checking...", new Color(0xE0A458));

        badges.add(guiBadge);
        badges.add(daemonBadge);

        notificationBell = new NotificationBell(storage, scheduler);
        badges.add(notificationBell);

        JButton eventMonitorBtn = new JButton("Event Monitor", VectorIcons.pulse(Color.WHITE, 16));
        eventMonitorBtn.setFocusPainted(false);
        eventMonitorBtn.setBorderPainted(false);
        eventMonitorBtn.setContentAreaFilled(false);
        eventMonitorBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        eventMonitorBtn.setForeground(Color.WHITE);
        eventMonitorBtn.setToolTipText("Open a live view of the scheduler's event queue and worker pool");
        eventMonitorBtn.addActionListener(e -> EventMonitorWindow.open(scheduler, this));
        badges.add(eventMonitorBtn);

        JToggleButton themeToggle = new JToggleButton();
        themeToggle.setSelected(AppTheme.isDark());
        themeToggle.setFocusPainted(false);
        themeToggle.setBorderPainted(false);
        themeToggle.setContentAreaFilled(false);
        themeToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        themeToggle.setMargin(new Insets(4, 8, 4, 8));
        themeToggle.setPreferredSize(new Dimension(30, 30));
        Runnable refreshToggleIcon = () -> {
            boolean isDark = AppTheme.isDark();
            themeToggle.setIcon(isDark ? VectorIcons.sun(Color.WHITE, 18) : VectorIcons.moon(Color.WHITE, 18));
            themeToggle.setToolTipText(isDark ? "Switch to light mode" : "Switch to dark mode");
        };
        refreshToggleIcon.run();
        themeToggle.addActionListener(e -> {
            AppTheme.toggle();
            refreshToggleIcon.run();
            // Rebuild the whole shell so the sidebar/chip colors (computed above from
            // AppTheme.isDark()) match the new theme too — FlatLaf.updateUI() alone only
            // re-styles standard Swing components, not these custom-painted ones.
            getContentPane().removeAll();
            buildUI();
            revalidate();
            repaint();
        });
        badges.add(themeToggle);

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
                        restyleChip(daemonBadge, "Daemon: Active", new Color(0x9CB380));
                    } else {
                        restyleChip(daemonBadge, "Daemon: Not registered", new Color(0xD9785C));
                        daemonBadge.setToolTipText("Go to Settings to register the background daemon");
                    }
                } catch (Exception ignored) {}
            }
        }.execute();

        header.add(titleStack, BorderLayout.WEST);
        header.add(badges, BorderLayout.EAST);

        // ── Sidebar navigation (Tasks / Credentials / Logs / Settings) ─────────
        taskPanel = new TaskManagerPanel(storage, scheduler);
        credPanel = new CredentialManagerPanel(storage);
        RunHistoryPanel runHistoryPanel = new RunHistoryPanel(storage, scheduler.getRunHistoryService());
        SettingsPanel settingsPanel = new SettingsPanel(transferService, scheduler);

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.setBackground(bgBase);
        content.setBorder(new EmptyBorder(18, 20, 18, 20));
        content.add(wrapCard(taskPanel), "Tasks");
        content.add(wrapCard(credPanel), "Credentials");
        content.add(wrapCard(runHistoryPanel), "Logs");
        content.add(wrapCard(settingsPanel), "Settings");

        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(sidebarBg);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, sidebarBorder),
                new EmptyBorder(16, 8, 16, 8)));
        sidebar.setPreferredSize(new Dimension(152, 0));

        String[] navNames = { "Tasks", "Credentials", "Logs", "Settings" };
        Icon[] navIcons = {
                VectorIcons.checklist(navIdleFg, 18),
                VectorIcons.key(navIdleFg, 18),
                VectorIcons.document(navIdleFg, 18),
                VectorIcons.sliders(navIdleFg, 18)
        };
        Icon[] navIconsSelected = {
                VectorIcons.checklist(AppTheme.ACCENT, 18),
                VectorIcons.key(AppTheme.ACCENT, 18),
                VectorIcons.document(AppTheme.ACCENT, 18),
                VectorIcons.sliders(AppTheme.ACCENT, 18)
        };
        JToggleButton[] navButtons = new JToggleButton[navNames.length];
        ButtonGroup navGroup = new ButtonGroup();
        for (int i = 0; i < navNames.length; i++) {
            final int idx = i;
            JToggleButton btn = new JToggleButton(navNames[i], navIcons[i]);
            btn.setHorizontalAlignment(SwingConstants.LEFT);
            btn.setIconTextGap(10);
            btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(true);
            btn.setBackground(sidebarBg);
            btn.setForeground(navIdleFg);
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            btn.setBorder(new EmptyBorder(8, 10, 8, 8));
            btn.putClientProperty("JButton.buttonType", "roundRect");
            navButtons[i] = btn;
            navGroup.add(btn);
            btn.addActionListener(e -> {
                cards.show(content, navNames[idx]);
                for (int j = 0; j < navButtons.length; j++) {
                    boolean sel = j == idx;
                    navButtons[j].setBackground(sel ? navSelectedBg : sidebarBg);
                    navButtons[j].setForeground(sel ? AppTheme.ACCENT : navIdleFg);
                    navButtons[j].setFont(navButtons[j].getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN));
                    navButtons[j].setIcon(sel ? navIconsSelected[j] : navIcons[j]);
                }
                if (navNames[idx].equals("Tasks")) taskPanel.refresh();
                if (navNames[idx].equals("Credentials")) credPanel.refresh();
                if (navNames[idx].equals("Logs")) runHistoryPanel.refresh();
            });
            sidebar.add(btn);
            sidebar.add(Box.createVerticalStrut(4));
        }
        navButtons[0].setSelected(true);
        navButtons[0].setBackground(navSelectedBg);
        navButtons[0].setForeground(AppTheme.ACCENT);
        navButtons[0].setFont(navButtons[0].getFont().deriveFont(Font.BOLD));
        navButtons[0].setIcon(navIconsSelected[0]);
        sidebar.add(Box.createVerticalGlue());

        // ── Responsive sidebar: collapse to icon-only once the window gets too
        // narrow for icon+label to comfortably fit, instead of a fixed width that
        // either wastes space on a wide window or crowds a narrow one. ─────────
        final int expandedWidth = 152, collapsedWidth = 56, collapseBelow = 760;
        sidebarResizeListener = new java.awt.event.ComponentAdapter() {
            private Boolean collapsed = null; // null forces the first resize event to apply state
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                boolean shouldCollapse = getWidth() < collapseBelow;
                if (Boolean.valueOf(shouldCollapse).equals(collapsed)) return;
                collapsed = shouldCollapse;
                sidebar.setPreferredSize(new Dimension(shouldCollapse ? collapsedWidth : expandedWidth, 0));
                for (int i = 0; i < navButtons.length; i++) {
                    JToggleButton b = navButtons[i];
                    b.setText(shouldCollapse ? null : navNames[i]);
                    b.setToolTipText(shouldCollapse ? navNames[i] : null);
                    b.setHorizontalAlignment(shouldCollapse ? SwingConstants.CENTER : SwingConstants.LEFT);
                    b.setMargin(shouldCollapse ? new Insets(8, 0, 8, 0) : null);
                }
                sidebar.revalidate();
                sidebar.getParent().revalidate();
                sidebar.repaint();
            }
        };
        addComponentListener(sidebarResizeListener);
        // Apply the correct state immediately for the window's current size (covers
        // the theme-toggle rebuild path, where no resize event fires on its own).
        SwingUtilities.invokeLater(() -> sidebarResizeListener.componentResized(null));

        // ── Toast popups + live updates for every task run ──────────────────────
        // Fired from RunHistoryService right after each run is recorded — covers
        // success, failure, AND skip, for every task, no matter which nav item is
        // currently showing. The listener itself runs on whatever thread recorded
        // the run (a scheduler worker thread), so everything it touches gets
        // marshaled onto the EDT first.
        toastManager = new ToastManager(this);
        runListener = record ->
                SwingUtilities.invokeLater(() -> {
                    // "Already running in another process" SKIPPED records are
                    // an internal concurrency guard (see TaskSchedulerService's
                    // cross-process file lock), not something the operator
                    // needs to react to — surfacing a toast for every one of
                    // them was pure noise, especially for fast-interval tasks.
                    // The run is still recorded in the DB and visible on the
                    // Logs tab for anyone who does want to see it; it just
                    // doesn't pop a toast or bump the failure bell.
                    if (isInternalConcurrencySkip(record)) {
                        runHistoryPanel.onRunRecorded(record);
                        return;
                    }
                    toastManager.showToast(record);
                    notificationBell.refreshCount();
                    runHistoryPanel.onRunRecorded(record);
                });
        scheduler.getRunHistoryService().addRunListener(runListener);

        // Belt-and-suspenders periodic refresh for the bell badge, in case a
        // task's status changes some way other than a recorded run (e.g. the
        // Restart buttons in the failure-recovery dialog set status directly).
        bellRefreshTimer = new javax.swing.Timer(15_000, e -> notificationBell.refreshCount());
        bellRefreshTimer.start();

        JPanel body = new JPanel(new BorderLayout());
        body.add(sidebar, BorderLayout.WEST);
        body.add(content, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        // ── Status bar ────────────────────────────────────────────────────────
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, sidebarBorder));
        JLabel dataLabel = new JLabel("Data: " + loadDataDir());
        dataLabel.setFont(dataLabel.getFont().deriveFont(11.5f));
        dataLabel.setForeground(navIdleFg);
        statusBar.add(dataLabel);
        add(statusBar, BorderLayout.SOUTH);
    }

    /** Wraps a panel in a rounded "card" container with breathing room, instead of it
     *  butting flush against the sidebar/window edges. */
    private JPanel wrapCard(JComponent inner) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(UIManager.getColor("Panel.background"));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                new EmptyBorder(4, 4, 4, 4)));
        card.add(inner, BorderLayout.CENTER);
        return card;
    }

    /** A small rounded, semi-transparent status pill for the header (e.g. "GUI Scheduler Running"). */
    private JLabel statusChip(String text, Color dotColor) {
        JLabel chip = new JLabel("\u25CF  " + text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 30));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setForeground(Color.WHITE);
        chip.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11.5f > 0 ? 12 : 12));
        chip.setBorder(new EmptyBorder(5, 12, 5, 12));
        chip.putClientProperty("dotColor", dotColor);
        restyleChip(chip, text, dotColor);
        return chip;
    }

    private void restyleChip(JLabel chip, String text, Color dotColor) {
        String hex = String.format("#%02X%02X%02X", dotColor.getRed(), dotColor.getGreen(), dotColor.getBlue());
        chip.setText("<html><span style='color:" + hex + "'>\u25CF</span>&nbsp;&nbsp;" + text + "</html>");
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /**
     * True for a SKIPPED run whose reason is the cross-process file-lock
     * guard in TaskSchedulerService#executeTask (either the normal
     * "... already running in another process (GUI or Daemon) at this
     * tick." message, or the same-process variant "... already running
     * elsewhere in this same application instance." — see the comment
     * next to sameJvmDoubleFire in executeTask()). Both are an
     * implementation detail, not something the operator needs a popup
     * for. The run is still recorded normally; this only controls
     * whether it also interrupts the operator with a toast/bell.
     */
    private boolean isInternalConcurrencySkip(model.TaskRunRecord record) {
        return record.getStatus() == model.TaskRunRecord.Status.SKIPPED
                && record.getReason() != null
                && (record.getReason().contains("already running in another process")
                    || record.getReason().contains("already running elsewhere in this same application instance"));
    }

    private Image buildAppIcon() {
        // 32x32 programmatic icon
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(AppTheme.ACCENT_DARK);
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
