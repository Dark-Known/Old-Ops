package ui;

import service.TransferService;
import util.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

public class SettingsPanel extends JPanel {

    private static final String PREF_WINSCP   = "winscp_path";
    private static final String PREF_POLLSEC  = "poll_interval_seconds";
    private static final String TASK_NAME     = "Monitoring-Tool-Daemon";

    private final TransferService transferService;
    private final service.TaskSchedulerService scheduler; // may be null if not wired by caller
    private final Preferences prefs = Preferences.userNodeForPackage(SettingsPanel.class);
    private JTextField tfWinScp;
    private JLabel     lblStatus;
    private JLabel     lblDaemonStatus;
    private javax.swing.JSpinner spinnerPollInterval;

    // Live settings (app-settings.json via util.AppSettings) — see buildRoutingPanel()
    private JTextField tfLdmFolder;
    private JTextField tfPtmFolder;
    private JTextField tfOthersFolder;
    private JTextField tfDefaultStationAddress;
    private JTextField tfAttachmentDir;
    private JComboBox<String> comboLogLevel;
    private JTextField tfJvmMinHeap;
    private JTextField tfJvmMaxHeap;
    private javax.swing.JSpinner spinnerBatchTargetSeconds;
    private javax.swing.JSpinner spinnerBatchThroughputMBps;
    private javax.swing.JSpinner spinnerBatchIntervalSeconds;
    private JTextField tfBatchMaxBytesOverride;
    private javax.swing.JSpinner spinnerBatchConcurrency;
    private javax.swing.JSpinner spinnerStaleThresholdMinutes;

    public SettingsPanel(TransferService transferService) {
        this(transferService, null);
    }

    /**
     * @param scheduler used only to detect currently in-flight tasks and
     *                  offer to restart them when the log level changes, so
     *                  their logs come out consistently at the new level for
     *                  the whole run (see {@link #maybeOfferRestartOnLevelChange}).
     *                  Pass {@code null} if this panel is used somewhere the
     *                  scheduler isn't available — the log level still saves
     *                  and still applies live to every future log line, it
     *                  just won't offer to restart already-running tasks.
     */
    public SettingsPanel(TransferService transferService, service.TaskSchedulerService scheduler) {
        this.transferService = transferService;
        this.scheduler = scheduler;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 15, 15, 15));
        buildUI();
        loadPrefs();
        refreshDaemonStatus();
    }

    private void buildUI() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));

        // WinSCP section
        JPanel winscpPanel = new JPanel(new GridBagLayout());
        winscpPanel.setBorder(new TitledBorder("WinSCP Configuration"));
        winscpPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        tfWinScp = new JTextField(transferService.getWinScpPath(), 40);
        JButton btnBrowse = new JButton("Browse...");
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select WinSCP.com");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "WinSCP.com", "com", "exe"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfWinScp.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JLabel hint = new JLabel("<html><i style='color:gray'>WinSCP.com is the command-line "
            + "interface. Default: C:\\Program Files (x86)\\WinSCP\\WinSCP.com</i></html>");

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(6, 4, 4, 8); lc.gridx = 0;
        GridBagConstraints fc2 = new GridBagConstraints();
        fc2.fill = GridBagConstraints.HORIZONTAL; fc2.weightx = 1;
        fc2.insets = new Insets(6, 0, 4, 6); fc2.gridx = 1;
        GridBagConstraints bc = new GridBagConstraints();
        bc.insets = new Insets(6, 0, 4, 4); bc.gridx = 2;

        lc.gridy = fc2.gridy = bc.gridy = 0;
        winscpPanel.add(new JLabel("WinSCP.com path:"), lc);
        winscpPanel.add(tfWinScp, fc2);
        winscpPanel.add(btnBrowse, bc);
        GridBagConstraints hintC = new GridBagConstraints();
        hintC.gridx = 0; hintC.gridy = 1; hintC.gridwidth = 3;
        hintC.anchor = GridBagConstraints.WEST; hintC.insets = new Insets(0, 4, 4, 0);
        winscpPanel.add(hint, hintC);
        outer.add(winscpPanel);
        outer.add(Box.createVerticalStrut(12));

        outer.add(buildRoutingPanel());
        outer.add(Box.createVerticalStrut(12));

        // Background Daemon section
        JPanel daemonPanel = new JPanel(new GridBagLayout());
        daemonPanel.setBorder(new TitledBorder("Background Scheduler (runs without GUI)"));
        daemonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));

        lblDaemonStatus = new JLabel("Checking...");
        lblDaemonStatus.setFont(lblDaemonStatus.getFont().deriveFont(Font.BOLD));

        JTextArea daemonInfo = new JTextArea(
            "The background daemon runs via Windows Task Scheduler so scheduled tasks\n"
          + "execute even when this GUI is closed or you are logged out.\n"
          + "It shares the same tasks.xml and credentials as the GUI.\n"
          + "Registering/removing requires Administrator privileges.\n\n"
          + "Important: scheduled tasks are stored in %USERPROFILE%\\.opstool\\tasks.xml.\n"
          + "Redeploying the application jar does not disturb this file unless you delete the data folder.");
        daemonInfo.setEditable(false);
        daemonInfo.setBackground(new Color(0xF0F4FF));
        daemonInfo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        daemonInfo.setBorder(new EmptyBorder(6, 6, 6, 6));

        JButton btnRegister     = new JButton("Register Daemon (Admin required)");
        JButton btnRemove       = new JButton("Remove Daemon");
        JButton btnRunNow       = new JButton("Run Daemon Now");
        JButton btnViewLog      = new JButton("View Daemon Log");
        JButton btnRefreshStatus = new JButton("Refresh Status");

        styleBtn(btnRegister, new Color(0x1565C0));
        styleBtn(btnRemove,   new Color(0xC62828));
        styleBtn(btnRunNow,   new Color(0xF57C00));

        btnRegister.addActionListener(e      -> registerDaemon());
        btnRemove.addActionListener(e        -> removeDaemon());
        btnRunNow.addActionListener(e        -> runDaemonNow());
        btnViewLog.addActionListener(e       -> openDaemonLog());
        btnRefreshStatus.addActionListener(e -> refreshDaemonStatus());

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);

        g.gridx = 0; g.gridy = 0; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        daemonPanel.add(new JLabel("Status:"), g);
        g.gridx = 1; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        daemonPanel.add(lblDaemonStatus, g);

        g.gridx = 0; g.gridy = 1; g.gridwidth = 4; g.fill = GridBagConstraints.HORIZONTAL;
        daemonPanel.add(daemonInfo, g);

        // Poll interval control
        JLabel lblPoll = new JLabel("GUI poll interval (seconds):");
        spinnerPollInterval = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(60, 1, 3600, 1));
        JLabel pollHint = new JLabel("Lower values (eg. 5s) enable high-frequency tasks - consider using the Daemon for reliability.");
        pollHint.setFont(pollHint.getFont().deriveFont(Font.ITALIC, 11f));

        GridBagConstraints pc = new GridBagConstraints();
        pc.insets = new Insets(6,4,4,4); pc.gridx = 0; pc.gridy = 3;
        daemonPanel.add(lblPoll, pc);
        GridBagConstraints sc = new GridBagConstraints();
        sc.insets = new Insets(6,0,4,4); sc.gridx = 1; sc.gridy = 3; sc.fill = GridBagConstraints.NONE;
        daemonPanel.add(spinnerPollInterval, sc);
        GridBagConstraints hc = new GridBagConstraints();
        hc.insets = new Insets(6,4,4,4); hc.gridx = 0; hc.gridy = 4; hc.gridwidth = 4; hc.fill = GridBagConstraints.HORIZONTAL;
        daemonPanel.add(pollHint, hc);

        JPanel btnRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow2.add(btnRegister);
        btnRow2.add(btnRemove);
        btnRow2.add(btnRunNow);
        btnRow2.add(btnViewLog);
        btnRow2.add(btnRefreshStatus);
        g.gridy = 2;
        daemonPanel.add(btnRow2, g);

        outer.add(daemonPanel);
        outer.add(Box.createVerticalStrut(12));

        // App Info section
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setBorder(new TitledBorder("Application Info"));
        infoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 155));

        String dataDir = resolveActualDataDir();
        addInfoRow(infoPanel, "Data directory:", dataDir, 0);
        addInfoRow(infoPanel, "Tasks file:",     dataDir + File.separator + "tasks.xml", 1);
        addInfoRow(infoPanel, "Credentials:",    dataDir + File.separator + "creds_<username>.xml", 2);
        addInfoRow(infoPanel, "Daemon log:",     dataDir + File.separator + resolveDaemonLogFileName(), 3);
        addInfoRow(infoPanel, "Live settings (JSON):", AppSettings.filePath(), 4);
        outer.add(infoPanel);
        outer.add(Box.createVerticalStrut(12));

        // Notes section
        JTextArea notes = new JTextArea(
            "WINDOWS file transfer target:\n"
          + "  OpenSSH Server must be installed (Settings > Apps > Optional Features)\n\n"
          + "LINUX file transfer target:\n"
          + "  sshd must be running on port 22\n\n"
          + "Service Start/Stop/Restart target:\n"
          + "  WinRM must be enabled: Enable-PSRemoting -Force  (run as admin)\n\n"
          + "Wildcard transfers:\n"
          + "  Use * in source path (e.g. C:\\data\\*.csv) or a folder for full sync.\n\n"
          + "Redeploy safely:\n"
          + "  Keep %USERPROFILE%\\.opstool unchanged.\n"
          + "  Use redeploy-safe.ps1 to backup and restore it before upgrading."
        );
        notes.setEditable(false);
        notes.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        notes.setBackground(new Color(0xF5F5F5));
        notes.setBorder(new TitledBorder("Setup Notes"));
        outer.add(notes);

        // Save button row
        lblStatus = new JLabel(" ");
        JButton btnSave = new JButton("Save Settings");
        styleBtn(btnSave, new Color(0x1565C0));
        btnSave.addActionListener(e -> savePrefs());

        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        saveRow.add(btnSave);
        saveRow.add(lblStatus);

        add(new JScrollPane(outer), BorderLayout.CENTER);
        add(saveRow, BorderLayout.SOUTH);
    }

    /**
     * "Message Routing &amp; Attachments" section — the settings backed by
     * app-settings.json (via {@link AppSettings}) rather than app-config.xml.
     * Every field here except the two JVM heap fields takes effect
     * immediately on Save, for both the GUI and the next daemon run — no
     * restart required, since {@link TransferService} reads them fresh
     * (with cheap mtime-checked reload) on every message it processes.
     */
    private JPanel buildRoutingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Message Routing & Attachments (live — no restart needed)"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(4, 4, 4, 8);
        GridBagConstraints fc = new GridBagConstraints();
        // fill = NONE (not HORIZONTAL): these are short folder-name/number fields,
        // so each should render at its own preferred width (set via JTextField(cols)
        // below) and stay left-anchored, instead of stretching to match whatever
        // column width the widest row in this panel happens to need. Note that
        // weightx=0 alone is NOT enough here — GridBagLayout still sizes a shared
        // column to fit its widest occupant (e.g. the "Attachment download
        // location" field below), and HORIZONTAL fill would stretch every other
        // field in that column to match regardless of weightx. fill=NONE is what
        // actually decouples each field's rendered size from its neighbours'.
        fc.fill = GridBagConstraints.NONE; fc.weightx = 0; fc.anchor = GridBagConstraints.WEST;
        fc.insets = new Insets(4, 0, 4, 4);
        GridBagConstraints bc = new GridBagConstraints();
        bc.insets = new Insets(4, 0, 4, 4);

        tfLdmFolder = new JTextField(16);
        tfPtmFolder = new JTextField(16);
        tfOthersFolder = new JTextField(16);
        tfDefaultStationAddress = new JTextField(16);
        tfAttachmentDir = new JTextField(30);
        JButton btnBrowseAttach = new JButton("Browse...");
        btnBrowseAttach.addActionListener(e -> {
            JFileChooser fc2 = new JFileChooser();
            fc2.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc2.setDialogTitle("Select Attachment Download Location");
            if (fc2.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfAttachmentDir.setText(fc2.getSelectedFile().getAbsolutePath());
        });
        comboLogLevel = new JComboBox<>(new String[]{"DEBUG", "INFO", "WARN", "ERROR"});

        int row = 0;
        row = addFieldRow(panel, lc, fc, "LDM mailbox folder:", tfLdmFolder, row);
        row = addFieldRow(panel, lc, fc, "PTM mailbox folder:", tfPtmFolder, row);
        row = addFieldRow(panel, lc, fc, "Others mailbox folder:", tfOthersFolder, row);
        row = addFieldRow(panel, lc, fc, "Default SITA station address:", tfDefaultStationAddress, row);

        lc.gridy = fc.gridy = bc.gridy = row; lc.gridx = 0; fc.gridx = 1; bc.gridx = 2;
        panel.add(new JLabel("Attachment download location:"), lc);
        panel.add(tfAttachmentDir, fc);
        panel.add(btnBrowseAttach, bc);
        row++;

        row = addFieldRow(panel, lc, fc, "Log level:", comboLogLevel, row);

        spinnerBatchTargetSeconds = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(5, 1, 3600, 1));
        row = addFieldRow(panel, lc, fc, "Batch target duration (sec):", spinnerBatchTargetSeconds, row);

        spinnerBatchThroughputMBps = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(5, 1, 10000, 1));
        row = addFieldRow(panel, lc, fc, "Assumed link speed (MB/s):", spinnerBatchThroughputMBps, row);

        spinnerBatchIntervalSeconds = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(5, 0, 3600, 1));
        row = addFieldRow(panel, lc, fc, "Interval between batches (sec):", spinnerBatchIntervalSeconds, row);

        tfBatchMaxBytesOverride = new JTextField(12);
        row = addFieldRow(panel, lc, fc, "Batch size override (bytes, 0=auto):", tfBatchMaxBytesOverride, row);

        spinnerBatchConcurrency = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(1, 1, 50, 1));
        row = addFieldRow(panel, lc, fc, "Batches/files to run at once:", spinnerBatchConcurrency, row);

        JLabel concurrencyHint = new JLabel("<html><body style='width: 480px'><i style='color:gray'>"
            + "1 = one operation at a time (original behavior) — one WinSCP/SFTP session for remote "
            + "transfers/backups, one file copy/move at a time for local backups. Raising this runs that "
            + "many in parallel — the main lever for backlogs with a huge number of small files, where "
            + "per-file overhead (network round trips, or local open/copy/close calls) is the real "
            + "bottleneck rather than link speed or disk throughput. Increase gradually; for remote "
            + "transfers, confirm the server tolerates multiple simultaneous connections.</i></body></html>");
        GridBagConstraints chc = new GridBagConstraints();
        chc.gridx = 0; chc.gridy = row++; chc.gridwidth = 3; chc.anchor = GridBagConstraints.WEST;
        chc.insets = new Insets(0, 4, 8, 0);
        panel.add(concurrencyHint, chc);

        spinnerStaleThresholdMinutes = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(30, 1, 1440, 1));
        row = addFieldRow(panel, lc, fc, "Stale RUNNING task timeout (min):", spinnerStaleThresholdMinutes, row);

        JLabel staleHint = new JLabel("<html><body style='width: 480px'><i style='color:gray'>"
            + "If a task stays RUNNING longer than this, the scheduler assumes it crashed/hung and "
            + "force-cancels it, marking it FAILED (stale). Raise this for backups/transfers with large "
            + "backlogs that legitimately take a long time, so a slow-but-progressing run isn't killed "
            + "and reported as failed partway through.</i></body></html>");
        GridBagConstraints shc = new GridBagConstraints();
        shc.gridx = 0; shc.gridy = row++; shc.gridwidth = 3; shc.anchor = GridBagConstraints.WEST;
        shc.insets = new Insets(0, 4, 8, 0);
        panel.add(staleHint, shc);

        // Fixed pixel width in the <body> style forces the HTML renderer to wrap
        // this onto multiple lines instead of laying it out as one long line that
        // would otherwise force the whole panel (and every field in it) wider.
        JLabel batchHint = new JLabel("<html><body style='width: 480px'><i style='color:gray'>"
            + "Transfers and backups (any mode/"
            + "direction) are split by total FILE SIZE, not file count: each batch is capped so it "
            + "should complete in roughly the target duration above, given the assumed link speed "
            + "(cap = target seconds &times; link speed). A single file larger than the cap is still "
            + "sent alone in its own batch. Between batches the run pauses for the configured "
            + "interval. Set the override above (&gt;0 bytes) to bypass the derived cap and use an "
            + "exact byte limit instead.</i></body></html>");
        GridBagConstraints bhc = new GridBagConstraints();
        bhc.gridx = 0; bhc.gridy = row++; bhc.gridwidth = 3; bhc.anchor = GridBagConstraints.WEST;
        bhc.insets = new Insets(0, 4, 8, 0);
        panel.add(batchHint, bhc);

        JLabel hint = new JLabel("<html><body style='width: 480px'><i style='color:gray'>"
            + "Attachments are saved to "
            + "&lt;location&gt;\\LDM|PTM|Others\\&lt;message&gt;\\&lt;file&gt;. Leave blank to keep "
            + "saving them inside each task's own output folder instead.</i></body></html>");
        GridBagConstraints hc = new GridBagConstraints();
        hc.gridx = 0; hc.gridy = row++; hc.gridwidth = 3; hc.anchor = GridBagConstraints.WEST;
        hc.insets = new Insets(0, 4, 8, 0);
        panel.add(hint, hc);

        // JVM heap — stored in the same file for one consistent place to
        // edit everything, but genuinely cannot apply to the already-running
        // JVM; only takes effect the next time the app/daemon process starts.
        tfJvmMinHeap = new JTextField(8);
        tfJvmMaxHeap = new JTextField(8);
        JPanel heapRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        heapRow.add(new JLabel("JVM min heap:"));
        heapRow.add(tfJvmMinHeap);
        heapRow.add(new JLabel("JVM max heap:"));
        heapRow.add(tfJvmMaxHeap);
        JLabel heapHint = new JLabel("  (applies on next app/daemon restart — not live)");
        heapHint.setFont(heapHint.getFont().deriveFont(Font.ITALIC, 11f));
        heapHint.setForeground(Color.GRAY);
        heapRow.add(heapHint);
        GridBagConstraints heapC = new GridBagConstraints();
        heapC.gridx = 0; heapC.gridy = row++; heapC.gridwidth = 3; heapC.anchor = GridBagConstraints.WEST;
        heapC.insets = new Insets(4, 0, 4, 0);
        panel.add(heapRow, heapC);

        return panel;
    }

    private int addFieldRow(JPanel panel, GridBagConstraints lc, GridBagConstraints fc, String label, JComponent field, int row) {
        lc.gridx = 0; lc.gridy = row;
        fc.gridx = 1; fc.gridy = row; fc.gridwidth = 2;
        panel.add(new JLabel(label), lc);
        panel.add(field, fc);
        fc.gridwidth = 1;
        return row + 1;
    }

    /**
     * Resolves the same real dataDir that MainWindow, Daemon, and AppSettings
     * all use — app-config.xml's &lt;dataDir&gt;, falling back to
     * {@code C:\OpsTools\Data} if that can't be read. Used only to display
     * accurate paths in the "Application Info" section below; previously this
     * section hardcoded {@code %USERPROFILE%\.opstool} regardless of what
     * was actually configured, which could point users at the wrong folder
     * entirely when looking for tasks.xml, daemon.log, or app-settings.json.
     */
    private static String resolveActualDataDir() {
        String val = util.AppConfig.readValue("dataDir");
        return (val != null && !val.isEmpty()) ? val : "C:\\OpsTools\\Data";
    }

    /** Mirrors Daemon.daemonLogFileName() — see note in openDaemonLog(). */
    private static String resolveDaemonLogFileName() {
        String name = util.AppConfig.readValue("daemonLogFile");
        return (name != null && !name.trim().isEmpty()) ? name.trim() : "daemon.log";
    }

    // ── Daemon management ────────────────────────────────────────────────────

    private void registerDaemon() {
        String jarPath = getJarPath();
        String javaExe = getJavaExe();
        // FIX: previously hardcoded %USERPROFILE%\.opstool, which is NOT where
        // the rest of the app (MainWindow, AppSettings, tasks.xml) actually
        // lives — that's app-config.xml's <dataDir>. Since the scheduled task
        // runs as SYSTEM, %USERPROFILE% there resolves to SYSTEM's own profile
        // too, so the daemon was silently reading/writing an entirely
        // different, invisible folder. Use the same real dataDir everywhere.
        String dataDir = resolveActualDataDir();

        if (jarPath == null) {
            showError("Cannot locate OpsTransferTool.jar.\n"
                + "Please run this from the installed location (C:\\OpsTools).");
            return;
        }

        // Use schtasks.exe for reliable task registration
        // This is simpler and more reliable than PowerShell Register-ScheduledTask
        String taskPath = "C:\\OpsTools\\daemon-register.ps1";

        // Create a temporary PowerShell script that registers the daemon
        String psScript = String.format(
            "$action = New-ScheduledTaskAction -Execute '%s' -Argument '-cp \"%s\" com.opstool.Daemon \"%s\"'\n" +
            "$trigStart = New-ScheduledTaskTrigger -AtStartup\n" +
            "$trigStart.Delay = 'PT1M'\n" +
            "$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit 0 -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5) -MultipleInstances IgnoreNew\n" +
            "Register-ScheduledTask -TaskName '%s' -Action $action -Trigger $trigStart -Settings $settings -RunLevel Highest -User 'SYSTEM' -Force | Out-Null\n" +
            "Write-Host 'Daemon registered successfully'",
            javaExe.replace("\\", "\\\\"),
            jarPath.replace("\\", "\\\\"),
            dataDir.replace("\\", "\\\\"),
            TASK_NAME);

        try {
            // Write script to temp file
            File tempScript = File.createTempFile("opstool_daemon_", ".ps1");
            try (PrintWriter pw = new PrintWriter(new FileWriter(tempScript))) {
                pw.print(psScript);
            }
            tempScript.deleteOnExit();

            // Execute with elevation via PowerShell -Verb RunAs
            String elevCmd = String.format(
                "Start-Process powershell -ArgumentList '-NoProfile', '-NonInteractive', '-Command', 'Unblock-File -Path \\\"%s\\\"; & \\\"%s\\\"' -Verb RunAs -Wait",
                tempScript.getAbsolutePath().replace("\\", "\\\\"),
                tempScript.getAbsolutePath().replace("\\", "\\\\"));

            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NonInteractive",
                "-NoProfile",
                "-Command",
                elevCmd);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }
            int rc = p.waitFor();

            // Give task scheduler a moment to process
            Thread.sleep(2000);

            // Verify registration by checking if task exists
            ProcessBuilder checkPb = new ProcessBuilder(
                "powershell.exe",
                "-NonInteractive",
                "-NoProfile",
                "-Command",
                "Get-ScheduledTask -TaskName '" + TASK_NAME + "' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty TaskName");
            Process checkProc = checkPb.start();
            BufferedReader checkBr = new BufferedReader(new InputStreamReader(checkProc.getInputStream()));
            String taskStatus = checkBr.readLine();
            checkProc.waitFor();

            if (taskStatus != null && taskStatus.contains(TASK_NAME)) {
                showInfo("Daemon registered successfully!\n\n"
                    + "Scheduled task: " + TASK_NAME + "\n"
                    + "Trigger: At system startup (1-minute delay)\n"
                    + "Account: SYSTEM\n\n"
                    + "Your scheduled tasks will now run automatically.");
            } else {
                showError("Daemon registration may have failed.\n"
                    + "Verify manually in Task Scheduler:\n"
                    + "  Win+R > taskschd.msc\n"
                    + "  Look for '" + TASK_NAME + "'\n\n"
                    + "Make sure you clicked Yes on the UAC prompt.");
            }
        } catch (Exception e) {
            showError("Failed to register daemon: " + e.getMessage());
        }

        refreshDaemonStatus();
    }

    private void removeDaemon() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Remove the background daemon?\n"
            + "Scheduled tasks will only run while the GUI is open.",
            "Confirm Remove", JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;

        int rc = runElevated(
            "schtasks /Delete /TN \"" + TASK_NAME + "\" /F",
            "Remove daemon");
        if (rc == 0)
            showInfo("Daemon removed.\nScheduled tasks will only run while the GUI is open.");
        else
            showError("Removal failed (exit code " + rc + ").\n"
                + "You can also remove it manually via Task Scheduler:\n"
                + "Task Scheduler > Task Scheduler Library > " + TASK_NAME);
        refreshDaemonStatus();
    }

    private void runDaemonNow() {
        try {
            String ps = "$taskName = '" + TASK_NAME + "'\r\n"
                + "schtasks /Run /TN $taskName /I 2>&1\r\n"
                + "exit $LASTEXITCODE\r\n";

            int rc = runElevated("schtasks /Run /TN " + TASK_NAME, "Run Daemon Now");

            if (rc == 0) {
                showInfo("Daemon triggered successfully!\n\n"
                    + "It is now running and will check all scheduled tasks\n"
                    + "for execution. Check the daemon log for details.");
            } else {
                showError("Failed to trigger daemon (exit code " + rc + ").\n"
                    + "Make sure the daemon is registered first.");
            }
            Thread.sleep(500);
            refreshDaemonStatus();
        } catch (Exception e) {
            showError("Error triggering daemon: " + e.getMessage());
        }
    }

    /** Runs a shell command elevated via PowerShell -Verb RunAs (triggers UAC). */
    private int runElevated(String command, String description) {
        try {
            String psCmd = "Start-Process cmd -ArgumentList '/c "
                + command.replace("'", "''").replace("\"", "`\"")
                + "' -Verb RunAs -Wait -WindowStyle Hidden";
            Process p = new ProcessBuilder(
                "powershell.exe", "-NonInteractive", "-NoProfile", "-Command", psCmd)
                .redirectErrorStream(true)
                .start();
            return p.waitFor();
        } catch (Exception e) {
            showError(description + " failed: " + e.getMessage());
            return -1;
        }
    }


    private void refreshDaemonStatus() {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            protected String doInBackground() {
                try {
                    Process p = Runtime.getRuntime().exec(
                        new String[]{"schtasks", "/Query", "/TN", TASK_NAME, "/FO", "LIST"});
                    p.waitFor();

                    // Exit code 0 = found, 1 = not found, other = error
                    if (p.exitValue() == 0) {
                        java.util.Scanner sc = new java.util.Scanner(p.getInputStream());
                        StringBuilder sb = new StringBuilder();
                        String taskStatus = "REGISTERED";
                        String nextRun = "";

                        while (sc.hasNextLine()) {
                            String line = sc.nextLine();
                            if (line.contains("Status:")) {
                                int idx = line.indexOf(":");
                                if (idx >= 0) taskStatus = line.substring(idx + 1).trim();
                            }
                            if (line.contains("Next Run Time:")) {
                                int idx = line.indexOf(":");
                                if (idx >= 0) nextRun = line.substring(idx + 1).trim();
                            }
                        }
                        sc.close();

                        // Return status with next run time if available
                        if (!nextRun.isEmpty() && !nextRun.equals("N/A")) {
                            return taskStatus + " (Next: " + nextRun + ")";
                        }
                        return taskStatus.isEmpty() ? "REGISTERED" : taskStatus;
                    } else if (p.exitValue() == 1) {
                        return "NOT REGISTERED";
                    } else {
                        // Other exit codes
                        return "Unknown (check permissions)";
                    }
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
            protected void done() {
                try {
                    String status = get();
                    lblDaemonStatus.setText(status);
                    lblDaemonStatus.setForeground(
                        status.contains("NOT REGISTERED") ? new Color(0xC62828) :
                        status.contains("Error") ? new Color(0xFF9800) :
                        new Color(0x2E7D32));
                } catch (Exception e) {
                    lblDaemonStatus.setText("Error checking status");
                }
            }
        };
        worker.execute();
    }

    private void openDaemonLog() {
        // FIX: previously hardcoded %USERPROFILE%\.opstool\daemon.log, which
        // is not where the daemon actually writes (see registerDaemon()
        // above) — resolve the same real dataDir + log filename the running
        // daemon uses, both sourced from app-config.xml. (Daemon.java lives
        // in the default package and can't be imported from here, so this
        // mirrors its daemonLogFileName() lookup directly — same as
        // resolveActualDataDir() already mirrors Daemon's dataDir lookup.)
        String logPath = resolveActualDataDir() + File.separator + resolveDaemonLogFileName();
        File logFile = new File(logPath);
        if (!logFile.exists()) {
            showInfo("No daemon log found yet.\nExpected: " + logPath
                + "\n\nThe log is created when the daemon runs for the first time.");
            return;
        }
        try {
            Desktop.getDesktop().open(logFile);
        } catch (IOException e) {
            showError("Could not open log file: " + e.getMessage() + "\nPath: " + logPath);
        }
    }

    private String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        }
    }

    private String getJarPath() {
        try {
            String path = getClass().getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
            if (path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':')
                path = path.substring(1);
            path = path.replace("/", "\\");
            if (path.endsWith(".jar")) return path;
            File installed = new File("C:\\OpsTools\\OpsTransferTool.jar");
            return installed.exists() ? installed.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private File getStartupFallbackFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return new File(appData + File.separator + "Microsoft" + File.separator
            + "Windows" + File.separator + "Start Menu" + File.separator
            + "Programs" + File.separator + "Startup" + File.separator
            + TASK_NAME + "Startup.cmd");
    }

    private boolean createStartupFallback(String jarPath, String dataDir) {
        File fallback = getStartupFallbackFile();
        if (fallback == null) return false;
        try {
            fallback.getParentFile().mkdirs();
            String content = "@echo off\r\n"
                + "set \"JAVA_EXE=" + getJavaExe() + "\"\r\n"
                + "set \"JAR_PATH=" + jarPath + "\"\r\n"
                + "set \"DATA_DIR=" + dataDir + "\"\r\n"
                + "start \"" + TASK_NAME + "\" /MIN %JAVA_EXE% -cp %JAR_PATH% com.opstool.Daemon \"%DATA_DIR%\"\r\n";
            try (FileWriter fw = new FileWriter(fallback)) {
                fw.write(content);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean removeStartupFallback() {
        File fallback = getStartupFallbackFile();
        return fallback != null && fallback.exists() && fallback.delete();
    }

    private boolean startupFallbackExists() {
        File fallback = getStartupFallbackFile();
        return fallback != null && fallback.exists();
    }

    private String getJavaExe() {
        String javaHome = System.getProperty("java.home");
        String exe = javaHome + File.separator + "bin" + File.separator + "java.exe";
        return new File(exe).exists() ? exe : "java";
    }

    private void addInfoRow(JPanel p, String label, String value, int row) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row; lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(3, 4, 3, 10);
        GridBagConstraints vc = new GridBagConstraints();
        vc.gridx = 1; vc.gridy = row; vc.fill = GridBagConstraints.HORIZONTAL;
        vc.weightx = 1; vc.insets = new Insets(3, 0, 3, 4);
        JTextField tf = new JTextField(value);
        tf.setEditable(false);
        tf.setBackground(new Color(0xF5F5F5));
        tf.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        p.add(new JLabel(label), lc);
        p.add(tf, vc);
    }

    private void loadPrefs() {
        String saved = prefs.get(PREF_WINSCP, null);
        if (saved != null) {
            tfWinScp.setText(saved);
            transferService.setWinScpPath(saved);
        }
        int poll = prefs.getInt(PREF_POLLSEC, 60);
        spinnerPollInterval.setValue(poll);

        // Live settings (app-settings.json) — loaded fresh every time this
        // panel is built, so it always reflects whatever is currently in
        // effect (including edits made elsewhere, e.g. by hand).
        tfLdmFolder.setText(AppSettings.getLdmFolder());
        tfPtmFolder.setText(AppSettings.getPtmFolder());
        tfOthersFolder.setText(AppSettings.getOthersFolder());
        String defaultAddr = AppSettings.getDefaultStationAddress();
        tfDefaultStationAddress.setText(defaultAddr != null ? defaultAddr : "");
        String attachDir = AppSettings.getAttachmentDownloadLocation();
        tfAttachmentDir.setText(attachDir != null ? attachDir : "");
        comboLogLevel.setSelectedItem(AppSettings.getLogLevel());
        tfJvmMinHeap.setText(AppSettings.getJvmMinHeap());
        tfJvmMaxHeap.setText(AppSettings.getJvmMaxHeap());
        spinnerBatchTargetSeconds.setValue(AppSettings.getTransferBatchTargetSeconds());
        spinnerBatchThroughputMBps.setValue(AppSettings.getTransferAssumedThroughputMBps());
        spinnerBatchIntervalSeconds.setValue(AppSettings.getTransferBatchIntervalSeconds());
        tfBatchMaxBytesOverride.setText(AppSettings.get(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES));
        spinnerBatchConcurrency.setValue(AppSettings.getTransferBatchConcurrency());
        spinnerStaleThresholdMinutes.setValue(AppSettings.getStaleRunningThresholdMinutes());
    }

    private void savePrefs() {
        String path = tfWinScp.getText().trim();
        String previousLogLevel = AppSettings.getLogLevel();

        // Always save poll interval, regardless of WinSCP path
        try {
            int poll = (Integer) spinnerPollInterval.getValue();
            prefs.putInt(PREF_POLLSEC, poll);
        } catch (Exception ignored) {}

        // Live settings — one file write, takes effect immediately for both
        // this process and the daemon's next run.
        try {
            Map<String, String> live = new LinkedHashMap<>();
            live.put(AppSettings.KEY_LDM_FOLDER, tfLdmFolder.getText().trim());
            live.put(AppSettings.KEY_PTM_FOLDER, tfPtmFolder.getText().trim());
            live.put(AppSettings.KEY_OTHERS_FOLDER, tfOthersFolder.getText().trim());
            live.put(AppSettings.KEY_DEFAULT_STATION_ADDR, tfDefaultStationAddress.getText().trim());
            live.put(AppSettings.KEY_ATTACHMENT_DOWNLOAD_DIR, tfAttachmentDir.getText().trim());
            live.put(AppSettings.KEY_LOG_LEVEL, String.valueOf(comboLogLevel.getSelectedItem()));
            live.put(AppSettings.KEY_JVM_MIN_HEAP, tfJvmMinHeap.getText().trim());
            live.put(AppSettings.KEY_JVM_MAX_HEAP, tfJvmMaxHeap.getText().trim());
            live.put(AppSettings.KEY_TRANSFER_BATCH_TARGET_SECONDS,
                    String.valueOf((Integer) spinnerBatchTargetSeconds.getValue()));
            live.put(AppSettings.KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS,
                    String.valueOf((Integer) spinnerBatchThroughputMBps.getValue()));
            live.put(AppSettings.KEY_TRANSFER_BATCH_INTERVAL_SECONDS,
                    String.valueOf((Integer) spinnerBatchIntervalSeconds.getValue()));
            String maxBytesOverride = tfBatchMaxBytesOverride.getText().trim();
            try {
                long parsed = maxBytesOverride.isEmpty() ? 0 : Long.parseLong(maxBytesOverride);
                live.put(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, String.valueOf(Math.max(parsed, 0)));
            } catch (NumberFormatException nfe) {
                lblStatus.setText("Batch size override must be a whole number of bytes.");
                lblStatus.setForeground(Color.RED);
                return;
            }
            live.put(AppSettings.KEY_TRANSFER_BATCH_CONCURRENCY,
                    String.valueOf((Integer) spinnerBatchConcurrency.getValue()));
            live.put(AppSettings.KEY_STALE_RUNNING_THRESHOLD_MINUTES,
                    String.valueOf((Integer) spinnerStaleThresholdMinutes.getValue()));
            AppSettings.setAll(live);
        } catch (Exception ex) {
            lblStatus.setText("Could not save live settings: " + ex.getMessage());
            lblStatus.setForeground(Color.RED);
            return;
        }

        if (!path.isEmpty()) {
            prefs.put(PREF_WINSCP, path);
            transferService.setWinScpPath(path);
            lblStatus.setText("✓  Settings saved.");
            lblStatus.setForeground(new Color(0x2E7D32));
        } else {
            lblStatus.setText("WinSCP path cannot be empty.");
            lblStatus.setForeground(Color.RED);
        }

        maybeOfferRestartOnLevelChange(previousLogLevel, String.valueOf(comboLogLevel.getSelectedItem()));
    }

    /**
     * The new log level already applies live to every log line written from
     * this point on — {@link service.TaskLogService} re-reads
     * {@link AppSettings#getLogLevel()} on every call, no restart required
     * for that. But a task that's *already running* will have a task.log
     * that started under the old level and switches partway through, which
     * is confusing to read back (e.g. missing DEBUG lines for the portion
     * of the run that already happened). So: if the level actually changed
     * and any task is currently executing, offer to restart just those
     * tasks — cancel + immediately re-run from the top — so their log ends
     * up consistent at the new level for the entire run.
     */
    private void maybeOfferRestartOnLevelChange(String previousLevel, String newLevel) {
        if (scheduler == null) return;
        if (previousLevel == null || previousLevel.equals(newLevel)) return;

        java.util.List<String> runningIds = scheduler.getRunningTaskIds();
        if (runningIds.isEmpty()) return;

        java.util.List<model.ScheduledTask> allTasks = null;
        StringBuilder names = new StringBuilder();
        try {
            allTasks = scheduler.getStorage() != null ? scheduler.getStorage().loadTasks() : null;
        } catch (Exception ignored) {}
        for (String id : runningIds) {
            String display = id;
            if (allTasks != null) {
                for (model.ScheduledTask t : allTasks) {
                    if (t.getId().equals(id)) { display = t.getName(); break; }
                }
            }
            if (names.length() > 0) names.append(", ");
            names.append(display);
        }

        int choice = JOptionPane.showConfirmDialog(this,
            "Log level changed from " + previousLevel + " to " + newLevel + ".\n\n"
            + "The following task(s) are currently running and already have log lines\n"
            + "written under the old level: " + names + "\n\n"
            + "Restart them now so their log is generated entirely at the new " + newLevel + " level?\n"
            + "(Their current run will be cancelled and immediately re-run from the start.)",
            "Restart running tasks to apply new log level?",
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            for (String id : runningIds) {
                try { scheduler.restartTask(id); } catch (Exception ignored) {}
            }
            lblStatus.setText("✓  Settings saved. Restarted " + runningIds.size() + " running task(s) for the new log level.");
            lblStatus.setForeground(new Color(0x2E7D32));
        }
    }

    private void showInfo(String msg)  { JOptionPane.showMessageDialog(this, msg, "Info",  JOptionPane.INFORMATION_MESSAGE); }
    private void showError(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    private void styleBtn(JButton b, Color bg) {
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.setOpaque(true); b.setBorderPainted(false);
    }
}