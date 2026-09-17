package ui;

import service.TransferService;
import util.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;

public class SettingsPanel extends JPanel {

    private static final String PREF_WINSCP   = "winscp_path";
    private static final String PREF_POLLSEC  = "poll_interval_seconds";
    private static final String TASK_NAME     = "Monitoring-Tool-Daemon";
    // Windows Service id, must match <id> in packaging/daemon-service.xml —
    // this is what actually runs the daemon now (see setup.ps1 /
    // packaging/install-service.ps1). TASK_NAME above is kept only for the
    // startup-fallback .cmd filename and is otherwise unused now that
    // Scheduled Task registration has been removed from this panel.
    private static final String SERVICE_NAME  = "OpsTransferToolDaemon";

    private final TransferService transferService;
    private final service.TaskSchedulerService scheduler; // may be null if not wired by caller
    private final Preferences prefs = Preferences.userNodeForPackage(SettingsPanel.class);
    private JTextField tfWinScp;
    private JLabel     lblStatus;
    private JLabel     lblDaemonStatus;
    private javax.swing.JSpinner spinnerPollInterval;

    // Live settings (app-settings.db via util.AppSettings) — see buildRoutingPanel()
    private JTextField tfNewRuleKey;
    private JTextField tfNewRuleFolder;
    private JTable mailRoutingPreviewTable;
    private DefaultTableModel mailRoutingPreviewModel;
    private java.util.List<util.MailRoutingRule> mailRoutingRules = new java.util.ArrayList<>();
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
    private javax.swing.JSpinner spinnerWatcherFilesPerThread;
    private javax.swing.JSpinner spinnerStaleThresholdMinutes;
    private javax.swing.JSpinner spinnerMaxConcurrentTaskThreads;
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

        JLabel banner = new JLabel("<html><b>Settings</b></html>");
        banner.setBorder(new EmptyBorder(0, 0, 10, 0));
        // Added directly to `this` (BorderLayout.NORTH) rather than into the BoxLayout
        // column `outer` — BorderLayout.NORTH always stretches its child to the full
        // container width, which a BoxLayout.Y_AXIS column doesn't reliably do for an
        // HTML JLabel narrower than the column (see RunHistoryPanel's buildFilterBar
        // for the full explanation of that quirk). This guarantees the banner spans
        // full width and stays flush-left regardless of its text length.
        add(banner, BorderLayout.NORTH);

        // WinSCP section
        JPanel winscpPanel = new JPanel(new GridBagLayout());
        winscpPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
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
        outer.add(card("WinSCP Configuration", winscpPanel));
        outer.add(Box.createVerticalStrut(12));

        outer.add(card("Message Routing & Attachments (live \u2014 no restart needed)", buildRoutingPanel()));
        outer.add(Box.createVerticalStrut(12));

        // Background Daemon section
        JPanel daemonPanel = new JPanel(new GridBagLayout());
        daemonPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        daemonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));

        lblDaemonStatus = new JLabel("Checking...");
        lblDaemonStatus.setFont(lblDaemonStatus.getFont().deriveFont(Font.BOLD));

        JButton btnRegister     = new GradientButton("Install Service (Admin required)");
        JButton btnRemove       = new GradientButton("Uninstall Service");
        JButton btnRunNow       = new JButton("Start Service");
        JButton btnViewLog      = new JButton("View Daemon Log");
        JButton btnRefreshStatus = new JButton("Refresh Status");

        styleBtn(btnRegister, AppTheme.EARTH_SIENNA);
        styleBtn(btnRemove,   AppTheme.EARTH_RUST);
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

        // Poll interval control
        JLabel lblPoll = new JLabel("GUI poll interval (seconds):");
        spinnerPollInterval = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(60, 1, 3600, 1));

        GridBagConstraints pc = new GridBagConstraints();
        pc.insets = new Insets(6,4,4,4); pc.gridx = 0; pc.gridy = 3;
        daemonPanel.add(lblPoll, pc);
        GridBagConstraints sc = new GridBagConstraints();
        sc.insets = new Insets(6,0,4,4); sc.gridx = 1; sc.gridy = 3; sc.fill = GridBagConstraints.NONE;
        daemonPanel.add(spinnerPollInterval, sc);

        JPanel btnRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow2.add(btnRegister);
        btnRow2.add(btnRemove);
        btnRow2.add(btnRunNow);
        btnRow2.add(btnViewLog);
        btnRow2.add(btnRefreshStatus);
        g.gridy = 2;
        daemonPanel.add(btnRow2, g);

        outer.add(card("Daemon (Windows Service — runs without GUI)", daemonPanel));
        outer.add(Box.createVerticalStrut(12));

        // App Info section
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        infoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 155));

        String dataDir = resolveActualDataDir();
        addInfoRow(infoPanel, "Data directory:", dataDir, 0);
        addInfoRow(infoPanel, "Tasks file:",     dataDir + File.separator + "tasks.xml", 1);
        addInfoRow(infoPanel, "Credentials & settings (database):", dataDir + File.separator + "app.db", 2);
        addInfoRow(infoPanel, "Daemon log:",     dataDir + File.separator + resolveDaemonLogFileName(), 3);
        addInfoRow(infoPanel, "Run history (database):", dataDir + File.separator + "run_history.db", 4);
        outer.add(card("Application Info", infoPanel));
        outer.add(Box.createVerticalStrut(12));

        // Save button row
        lblStatus = new JLabel(" ");
        JButton btnSave = new GradientButton("Save Settings");
        styleBtn(btnSave, AppTheme.EARTH_SIENNA);
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
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

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
        row = addFieldRow(panel, lc, fc, "Mail routing:", buildMailRoutingWidget(), row);
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

        spinnerWatcherFilesPerThread = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(5, 1, 1000, 1));
        row = addFieldRow(panel, lc, fc, "Watcher files per worker thread:", spinnerWatcherFilesPerThread, row);

        spinnerStaleThresholdMinutes = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(30, 1, 1440, 1));
        row = addFieldRow(panel, lc, fc, "Stale RUNNING task timeout (min):", spinnerStaleThresholdMinutes, row);

        spinnerMaxConcurrentTaskThreads = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(20, 4, 200, 1));
        row = addFieldRow(panel, lc, fc, "Max concurrent task threads (restart required):", spinnerMaxConcurrentTaskThreads, row);

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
        GridBagConstraints heapC = new GridBagConstraints();
        heapC.gridx = 0; heapC.gridy = row++; heapC.gridwidth = 3; heapC.anchor = GridBagConstraints.WEST;
        heapC.insets = new Insets(4, 0, 4, 0);
        panel.add(heapRow, heapC);

        return panel;
    }

    /**
     * Builds the mail-routing add-row + 2-row preview widget that replaces
     * the old fixed LDM/PTM/Others text fields. Field Name is the marker
     * text a message is checked for (case-insensitive, anywhere in subject/
     * attachment/body); Folder Name is the Outlook folder matching messages
     * get moved into — created automatically the first time it's needed if
     * it doesn't already exist (see GraphMailService#resolveFolderSegment).
     */
    private JComponent buildMailRoutingWidget() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));

        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        addRow.setOpaque(false);
        addRow.add(new JLabel("Field Name:"));
        tfNewRuleKey = new JTextField(9);
        addRow.add(tfNewRuleKey);
        addRow.add(new JLabel("Folder Name:"));
        tfNewRuleFolder = new JTextField(11);
        addRow.add(tfNewRuleFolder);
        JButton btnAddRule = new GradientButton("Add");
        styleBtn(btnAddRule, AppTheme.EARTH_MOSS);
        btnAddRule.addActionListener(e -> addMailRoutingRuleFromFields());
        addRow.add(btnAddRule);
        addRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(addRow);

        mailRoutingPreviewModel = new DefaultTableModel(new Object[]{"Field Name", "Folder Name"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        mailRoutingPreviewTable = new JTable(mailRoutingPreviewModel);
        mailRoutingPreviewTable.setRowHeight(24);
        mailRoutingPreviewTable.setFillsViewportHeight(true);
        mailRoutingPreviewTable.getTableHeader().setReorderingAllowed(false);
        mailRoutingPreviewTable.setToolTipText("Click to view, edit, or delete the full list");
        mailRoutingPreviewTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { openMailRoutingEditor(); }
        });
        // No custom colors set anywhere here — a plain JTable/JScrollPane already
        // follows the app's current FlatLaf theme (light/dark) automatically, which
        // is what keeps this correctly readable in both without extra work.
        JScrollPane preview = new JScrollPane(mailRoutingPreviewTable,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        preview.setPreferredSize(new Dimension(360, 2 * 24 + 27)); // header + 2 rows
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(Box.createVerticalStrut(4));
        wrap.add(preview);

        JLabel manageAll = new JLabel("Manage all rules...");
        manageAll.setForeground(AppTheme.EARTH_SIENNA);
        manageAll.setFont(manageAll.getFont().deriveFont(Font.PLAIN, 11.5f));
        manageAll.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        manageAll.setBorder(new EmptyBorder(3, 2, 0, 0));
        manageAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        manageAll.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { openMailRoutingEditor(); }
        });
        wrap.add(manageAll);

        refreshMailRoutingPreview();
        return wrap;
    }

    private void addMailRoutingRuleFromFields() {
        String key = tfNewRuleKey.getText().trim();
        String folder = tfNewRuleFolder.getText().trim();
        if (key.isEmpty() || folder.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Enter both a field name and a folder name.",
                    "Mail Routing", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Replace rather than duplicate if this key is already configured.
        mailRoutingRules.removeIf(r -> key.equalsIgnoreCase(r.getKey()));
        mailRoutingRules.add(new util.MailRoutingRule(key, folder));
        AppSettings.setMailRoutingRules(mailRoutingRules);
        tfNewRuleKey.setText("");
        tfNewRuleFolder.setText("");
        refreshMailRoutingPreview();
    }

    private void refreshMailRoutingPreview() {
        mailRoutingRules = AppSettings.getMailRoutingRules();
        mailRoutingPreviewModel.setRowCount(0);
        for (util.MailRoutingRule r : mailRoutingRules) {
            mailRoutingPreviewModel.addRow(new Object[]{r.getKey(), r.getFolder()});
        }
    }

    /** Full editable list — add/delete rows, edit any cell — opened by clicking the preview table. */
    private void openMailRoutingEditor() {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Field Name", "Folder Name"}, 0);
        for (util.MailRoutingRule r : mailRoutingRules) {
            model.addRow(new Object[]{r.getKey(), r.getFolder()});
        }

        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 220));

        JLabel info = new JLabel("<html><div style='width:400px'>Edit any value directly in the table below. "
                + "The <b>Others</b> row is the fallback for messages that don't match any other field name — "
                + "it can be renamed but a row with that key will always be kept. A folder is created "
                + "automatically the first time it's needed, if it doesn't already exist in Outlook.</div></html>");
        info.setBorder(new EmptyBorder(0, 0, 10, 0));

        JButton btnAddRow = new JButton("Add Row");
        btnAddRow.addActionListener(e -> model.addRow(new Object[]{"", ""}));
        JButton btnDeleteRow = new JButton("Delete Selected");
        btnDeleteRow.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int sel = table.getSelectedRow();
            if (sel >= 0) model.removeRow(sel);
        });
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftButtons.add(btnAddRow);
        leftButtons.add(btnDeleteRow);

        JButton btnCancel = new JButton("Cancel");
        JButton btnSave = new GradientButton("Save");
        styleBtn(btnSave, AppTheme.EARTH_SIENNA);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightButtons.add(btnCancel);
        rightButtons.add(btnSave);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(10, 0, 0, 0));
        south.add(leftButtons, BorderLayout.WEST);
        south.add(rightButtons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));
        content.add(info, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(owner instanceof Frame ? (Frame) owner : null, "Mail Routing Rules", true);
        dlg.setContentPane(content);
        dlg.pack();
        dlg.setLocationRelativeTo(this);

        btnCancel.addActionListener(e -> dlg.dispose());
        btnSave.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            java.util.List<util.MailRoutingRule> updated = new java.util.ArrayList<>();
            boolean hasOthers = false;
            for (int i = 0; i < model.getRowCount(); i++) {
                Object keyVal = model.getValueAt(i, 0);
                Object folderVal = model.getValueAt(i, 1);
                String k = keyVal == null ? "" : String.valueOf(keyVal).trim();
                String f = folderVal == null ? "" : String.valueOf(folderVal).trim();
                if (k.isEmpty()) continue;
                updated.add(new util.MailRoutingRule(k, f));
                if (k.equalsIgnoreCase(util.MailRoutingRule.OTHERS_KEY)) hasOthers = true;
            }
            if (!hasOthers) {
                updated.add(new util.MailRoutingRule(util.MailRoutingRule.OTHERS_KEY, "Others"));
            }
            AppSettings.setMailRoutingRules(updated);
            logActivity("Settings saved", "Mail routing rules updated (" + updated.size() + " rule(s))");
            refreshMailRoutingPreview();
            dlg.dispose();
        });

        dlg.setVisible(true);
    }

    /**
     * Plain section heading — bold label with generous top/bottom breathing
     * room, no box or line border around the section beneath it. Replaces
     * this panel's previous {@code TitledBorder} boxes: a relaxed,
     * whitespace-separated layout consistently reads clearer than a page of
     * bordered boxes, and it's what {@code TaskManagerPanel}'s own redesign
     * moved to as well (a "?" tooltip instead of a boxed legend panel).
     */
    /**
     * Wraps one settings section in a consistent card: a subtly bordered,
     * padded panel with a bold title row, replacing the old flat
     * "bold label directly in the scroll column, then the content panel
     * right below it with its own separate margins" pattern — every section
     * used slightly different spacing/borders before, which is what made
     * the page feel inconsistent rather than like one designed screen.
     */
    private JComponent card(String title, JComponent content) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AppTheme.surface2(), 1, true),
                new EmptyBorder(12, 14, 14, 14)));

        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
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

    /**
     * Installs the daemon as a Windows Service by invoking
     * daemon-service.exe (WinSW) / install-service.ps1 alongside the
     * running jar, elevated. Replaces the old registration logic, which
     * built its OWN Register-ScheduledTask PowerShell script independent
     * of setup.ps1 — the daemon is now a Windows Service (see
     * packaging/daemon-service.xml, packaging/install-service.ps1), not a
     * Scheduled Task, and this panel's old refreshDaemonStatus() was
     * checking for a Scheduled Task via schtasks that setup.ps1 no longer
     * creates at all. That mismatch is exactly why "Not Installed" kept
     * showing here even with the daemon running fine as a service.
     */
    private void registerDaemon() {
        String jarPath = getJarPath();
        if (jarPath == null) {
            showError("Cannot locate the daemon jar.\n"
                + "Please run this from the installed location (C:\\OpsTools).");
            return;
        }
        File installDir = new File(jarPath).getParentFile();
        File winswExe = new File(installDir, "daemon-service.exe");
        File installScript = new File(installDir, "install-service.ps1");
        if (!installScript.exists())
            installScript = new File(installDir, "packaging" + File.separator + "install-service.ps1");

        if (!winswExe.exists()) {
            showError("daemon-service.exe (WinSW) not found in " + installDir + ".\n\n"
                + "Download WinSW-x64.exe from https://github.com/winsw/winsw/releases,\n"
                + "rename it to 'daemon-service.exe', and place it in:\n" + installDir + "\n\n"
                + "Then click Install Service again.");
            return;
        }
        if (!installScript.exists()) {
            showError("install-service.ps1 not found next to the daemon jar.\n"
                + "Re-run setup.ps1, or place packaging\\install-service.ps1 in:\n" + installDir);
            return;
        }

        // FIX: previously hardcoded %USERPROFILE%\.opstool, which is NOT
        // where the rest of the app (MainWindow, AppSettings, tasks.xml)
        // actually lives — that's app-config.xml's <dataDir>. Use the same
        // real dataDir everywhere.
        String dataDir = resolveActualDataDir();
        String script = "& \"" + installScript.getAbsolutePath() + "\" "
            + "-InstallDir \"" + installDir.getAbsolutePath() + "\" "
            + "-DataDir \"" + dataDir + "\" "
            + "-WinSwPath \"" + winswExe.getAbsolutePath() + "\" "
            + "-JarPath \"" + jarPath + "\"";

        int rc = runElevatedPowerShell(script, "Install daemon service");
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        if (serviceExists()) {
            showInfo("Daemon service installed and started.\n\n"
                + "Service name: " + SERVICE_NAME + "\n\n"
                + "It now runs continuously in the background, independent of this window,\n"
                + "and restarts automatically if it crashes (see the service's Recovery tab\n"
                + "in services.msc).");
        } else {
            showError("Service installation may have failed (elevated process exit code " + rc + ").\n"
                + "Check services.msc for '" + SERVICE_NAME + "', or re-run install-service.ps1 manually.\n"
                + "Make sure you clicked Yes on the UAC prompt.");
        }
        refreshDaemonStatus();
    }

    private void removeDaemon() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Uninstall the daemon service?\n"
            + "Scheduled tasks will only run while this window is open.",
            "Confirm Uninstall", JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;

        String jarPath = getJarPath();
        File installDir = jarPath != null ? new File(jarPath).getParentFile() : new File("C:\\OpsTools");
        File winswExe = new File(installDir, "daemon-service.exe");
        if (!winswExe.exists()) {
            showError("daemon-service.exe not found in " + installDir + " — cannot uninstall the service from here.\n"
                + "Remove it manually via services.msc, or run:\n"
                + "  sc.exe delete " + SERVICE_NAME);
            return;
        }

        String script = "& \"" + winswExe.getAbsolutePath() + "\" stop; "
            + "& \"" + winswExe.getAbsolutePath() + "\" uninstall";
        int rc = runElevatedPowerShell(script, "Uninstall daemon service");
        if (rc == 0 && !serviceExists())
            showInfo("Daemon service uninstalled.\nScheduled tasks will only run while this window is open.");
        else
            showError("Uninstall may have failed (exit code " + rc + ").\n"
                + "You can also remove it manually:\n"
                + "  services.msc \u2192 " + SERVICE_NAME + ", or: sc.exe delete " + SERVICE_NAME);
        refreshDaemonStatus();
    }

    private void runDaemonNow() {
        // With the old Scheduled Task this forced an immediate trigger
        // ("schtasks /Run"). A Windows Service isn't triggered periodically
        // — it just runs continuously once installed — so the equivalent
        // action here is simply making sure it's started if it was stopped.
        try {
            int rc = runElevatedPowerShell("Start-Service -Name '" + SERVICE_NAME + "'", "Start daemon service");
            if (rc == 0) {
                showInfo("Daemon service started.\nCheck the daemon log for details.");
            } else {
                showError("Failed to start the service (exit code " + rc + ").\n"
                    + "Make sure it's installed first (Install Service button above).");
            }
            Thread.sleep(500);
            refreshDaemonStatus();
        } catch (Exception e) {
            showError("Error starting daemon service: " + e.getMessage());
        }
    }

    /**
     * Runs a PowerShell script elevated (triggers a UAC prompt) and waits
     * for it to finish. The script reaches the elevated process via
     * {@code -EncodedCommand} — a base64 blob of its UTF-16LE bytes —
     * instead of as literal text threaded through several layers of shell
     * quoting. Base64 contains none of {@code ' " \} or whitespace, so
     * there is nothing left for any layer to misinterpret.
     *
     * @return the elevated process's exit code, or -1 if it couldn't even be launched
     */
    private int runElevatedPowerShell(String script, String description) {
        try {
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            String launcher = "Start-Process powershell.exe -ArgumentList "
                    + "'-NoProfile','-NonInteractive','-EncodedCommand','" + encoded + "' -Verb RunAs -Wait";
            Process p = new ProcessBuilder(
                "powershell.exe", "-NonInteractive", "-NoProfile", "-Command", launcher)
                .redirectErrorStream(true)
                .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) { /* drain — the elevated child's own stdout isn't visible to us anyway */ }
            }
            return p.waitFor();
        } catch (Exception e) {
            showError(description + " failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Queries the Windows Service Control Manager for SERVICE_NAME's
     * current status (e.g. "Running", "Stopped") via Get-Service. This
     * needs no elevation, unlike install/uninstall/start.
     *
     * @return the service's Status string, or null if it isn't installed at all
     */
    private String queryServiceStatus() {
        try {
            Process p = new ProcessBuilder(
                "powershell.exe", "-NonInteractive", "-NoProfile", "-Command",
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

    private boolean serviceExists() {
        return queryServiceStatus() != null;
    }

    private void refreshDaemonStatus() {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            protected String doInBackground() {
                return queryServiceStatus();
            }
            protected void done() {
                try {
                    String status = get();
                    if (status == null) {
                        lblDaemonStatus.setText("Not Installed");
                        lblDaemonStatus.setForeground(AppTheme.EARTH_RUST);
                    } else {
                        lblDaemonStatus.setText(status);
                        lblDaemonStatus.setForeground(
                            status.equalsIgnoreCase("Running") ? AppTheme.EARTH_MOSS : new Color(0xF57C00));
                    }
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
        tf.setBackground(AppTheme.isDark() ? new Color(0x3A3A3A) : new Color(0xF5F5F5));
        tf.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        p.add(new JLabel(label), lc);
        p.add(tf, vc);
    }

    private void loadPrefs() {
        // Read from AppSettings (app.db) — shared with the Daemon/service —
        // rather than this process's per-user Preferences. If AppSettings
        // has no value yet but an old per-user Preferences one exists (from
        // before this change), migrate it in once so nobody's saved WinSCP
        // path/poll interval appears to reset.
        String saved = AppSettings.getWinScpPath();
        if (saved == null || saved.isEmpty()) {
            saved = prefs.get(PREF_WINSCP, null);
        }
        if (saved != null && !saved.isEmpty()) {
            tfWinScp.setText(saved);
            transferService.setWinScpPath(saved);
        }
        int poll = AppSettings.getPollIntervalSeconds();
        if (poll <= 0) poll = prefs.getInt(PREF_POLLSEC, 60);
        spinnerPollInterval.setValue(poll);

        // Live settings (app-settings.db) — loaded fresh every time this
        // panel is built, so it always reflects whatever is currently in
        // effect (including edits made elsewhere, e.g. by hand).
        refreshMailRoutingPreview();
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
        spinnerWatcherFilesPerThread.setValue(AppSettings.getWatcherFilesPerWorkerThread());
        spinnerStaleThresholdMinutes.setValue(AppSettings.getStaleRunningThresholdMinutes());
        spinnerMaxConcurrentTaskThreads.setValue(AppSettings.getMaxConcurrentTaskThreads());
    }

    /**
     * Records an application-activity note (settings saved) into the Event
     * Monitor's activity feed — see
     * {@link service.RunHistoryService#recordActivityEvent}. No-op if this
     * panel was constructed without a scheduler reference (see the
     * single-arg constructor's javadoc).
     */
    private void logActivity(String title, String detail) {
        if (scheduler == null) return;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            scheduler.getRunHistoryService().recordActivityEvent("SETTINGS", title, null, detail, detail, now, now);
        } catch (Exception ignored) {
            // Best-effort — a failure to log this shouldn't block the actual settings save.
        }
    }

    private void savePrefs() {
        String path = tfWinScp.getText().trim();
        String previousLogLevel = AppSettings.getLogLevel();

        // Live settings — one file write, takes effect immediately for both
        // this process and the daemon's next run. Mail routing rules are
        // saved separately, immediately on each add/edit/delete in their own
        // editor (see buildMailRoutingWidget()) rather than batched here.
        try {
            Map<String, String> live = new LinkedHashMap<>();
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
            live.put(AppSettings.KEY_WATCHER_FILES_PER_WORKER_THREAD,
                    String.valueOf((Integer) spinnerWatcherFilesPerThread.getValue()));
            live.put(AppSettings.KEY_STALE_RUNNING_THRESHOLD_MINUTES,
                    String.valueOf((Integer) spinnerStaleThresholdMinutes.getValue()));
            live.put(AppSettings.KEY_MAX_CONCURRENT_TASK_THREADS,
                    String.valueOf((Integer) spinnerMaxConcurrentTaskThreads.getValue()));
            // Poll interval always saved here regardless of WinSCP path below —
            // shared via app.db so the Daemon/service picks it up too.
            live.put(AppSettings.KEY_POLL_INTERVAL_SECONDS,
                    String.valueOf((Integer) spinnerPollInterval.getValue()));
            AppSettings.setAll(live);
            logActivity("Settings saved", "Application settings updated (log level: "
                    + live.get(AppSettings.KEY_LOG_LEVEL) + ")");
        } catch (Exception ex) {
            lblStatus.setText("Could not save live settings: " + ex.getMessage());
            lblStatus.setForeground(Color.RED);
            return;
        }

        if (!path.isEmpty()) {
            AppSettings.set(AppSettings.KEY_WINSCP_PATH, path);
            transferService.setWinScpPath(path);
            lblStatus.setText("✓  Settings saved.");
            lblStatus.setForeground(AppTheme.EARTH_MOSS);
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

        // Tasks now always execute in the Daemon/Windows Service, not this
        // window's own (never-started) TaskSchedulerService — see
        // MainWindow's thin-client migration — so "currently running" is
        // read from the daemon's shared status snapshot, the same
        // cross-process mechanism the Event Monitor already uses for
        // worker-pool/pending/watch status, rather than this process's own
        // (always-empty) in-memory state.
        java.io.File dataDir = scheduler.getStorage() != null ? scheduler.getStorage().getDataDir() : null;
        if (dataDir == null) return;
        java.nio.file.Path daemonStatusFile = dataDir.toPath().resolve("scheduler-status-daemon.dat");
        service.queue.SchedulerStatusSnapshot snapshot = service.queue.SchedulerStatusSnapshot.read(daemonStatusFile);
        if (snapshot == null || !snapshot.isFresh(service.queue.SchedulerStatusSnapshot.DEFAULT_STALE_MS)) return;
        java.util.List<String> runningIds = snapshot.getRunningTaskIds();
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
                service.CommandQueueService.enqueue(scheduler.getStorage().getDataDir(), id,
                        service.CommandQueueService.Action.RESTART, "gui");
            }
            lblStatus.setText("✓  Settings saved. Restarted " + runningIds.size() + " running task(s) for the new log level.");
            lblStatus.setForeground(AppTheme.EARTH_MOSS);
        }
    }

    private void showInfo(String msg)  { JOptionPane.showMessageDialog(this, msg, "Info",  JOptionPane.INFORMATION_MESSAGE); }
    private void showError(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    private void styleBtn(JButton b, Color bg) {
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.setBorderPainted(false);
    }
}