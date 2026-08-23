package ui;

import model.Credential;
import model.ScheduledTask;
import service.TransferService;
import service.XmlStorageService;
import util.MailFetchMode;
import util.*;
import model.ScheduledTask.*;
import javax.swing.*;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Unified task dialog — used for both New Task and Edit Task.
 *
 * Watcher + service integration changes:
 *
 *  FILE TRANSFER — REMOTE ONLY
 *  ────────────────────────────
 *  Local→local file transfers are not supported. Every FILE_TRANSFER task
 *  requires a resolved target credential (Target Credentials tab: host,
 *  username, password) and moves data over SFTP in both directions.
 *
 *  BACKUP — LOCAL OR REMOTE, EITHER SIDE
 *  ──────────────────────────────────────
 *  A Backup task's source and/or destination may each independently be a
 *  local folder or a REMOTE path reached over SFTP (checkbox + username,
 *  reusing the same creds_<username>.xml credential store as Target
 *  Credentials). Source and destination cannot both be remote at once.
 *
 *  WATCHER BASELINE ROW
 *  ────────────────────
 *  The baseline status row is now also tied to cbTransferMode: it is only shown
 *  when TransferMode == LATEST_ONLY (in addition to the watcher checkbox being
 *  checked), because the service is only used in that mode.
 *
 *  RESET BASELINE
 *  ──────────────
 *  Resets BOTH epoch and size (–1) on Save so the composite fingerprint is
 *  fully cleared.  Auto-resets also fire when:
 *    • The target folder path is changed.
 *    • The transfer direction is switched away from INBOUND.
 *    • TransferMode is changed away from LATEST_ONLY while the watcher is on.
 */
public class TaskDialog extends JDialog {

    private final XmlStorageService storage;
    private ScheduledTask result;

    // ── General ───────────────────────────────────────────────────────────────
    private JPanel    general;
    private JTextField  tfName;
    private JComboBox<String> cbTaskType;

    // ── Source system (auto-detected, editable) ───────────────────────────────
    private JPanel    sourcePanel;
    private JTextField  tfSourceHost;
    private JTextField  tfSourceUser;
    private JTextField  tfSourcePath;

    // ── Target system credentials ─────────────────────────────────────────────
    private JPanel    targetPanel;
    private JTextField  tfTargetHost;
    private JTextField  tfTargetUser;
    private JPasswordField pfTargetPass;
    private JComboBox<String> cbTargetOs;
    private JSpinner  spinnerRetryCount;

    // ── File transfer extras ──────────────────────────────────────────────────
    private JComboBox<String> cbTransferDirection;
    private JComboBox<String> cbTransferMode;
    private JLabel      lblSourcePath;
    private JLabel      lblTargetFolder;
    private JLabel      lblSourceHint;
    private JLabel      lblTargetHint;
    private JTextField  tfTargetFolder;
    private JPanel       additionalDestRowsContainer;
    private final java.util.List<JTextField> additionalDestFields = new java.util.ArrayList<>();
    private JLabel      lblAdditionalTargetFolders;
    private JCheckBox   cbWatcherEnabled;
    private JLabel      lblWatcherInfo;
    private JLabel      lblWatcherStatus;
    private JButton     btnResetBaseline;
    private JPanel      transferTab;
    // Watcher status row panel (made a field so visibility can be toggled reliably)
    private JPanel      watcherStatusRow;

    // ── Backup panel ──────────────────────────────────────────────────────────
    private JTextField  tfBackupSourcePath;
    private JTextField  tfBackupDestinationPath;
    private JSpinner    spBackupRetentionDays;
    private JCheckBox   cbBackupSourceRemote;
    private JTextField  tfBackupSourceUsername;
    private JCheckBox   cbBackupDestinationRemote;
    private JTextField  tfBackupDestinationUsername;

    // ── Outlook mail (Microsoft Graph) ─────────────────────────────────────────
    private JPanel      mailPanel;
    private JTextField  tfMailMailboxAddress;
    private JTextField  tfMailTenantId;
    private JTextField  tfMailClientId;
    private JButton      btnAuthorizeMailbox;
    private JLabel        lblMailAuthStatus;
    private JTextField  tfImapFolder;
    private SearchCriteriaPanel pnlMailSearchCriteria;
    private JComboBox<String> cbMailFetchMode;
    private JComboBox<String> cbMailFetchScope;
    private JTextField tfMailMaxResults;
    private JLabel   lblMailMaxResults;
    private JLabel   lblMailFetchScopeNote;

    // ── Mail watcher (baseline = newest processed message's receivedDateTime) ──
    private JCheckBox cbMailWatcherEnabled;
    private JLabel     lblMailWatcherStatus;
    private JButton    btnResetMailBaseline;
    private JPanel     mailWatcherStatusRow;
    private boolean mailWatcherEpochShouldReset = false;

    // ── Post-processing: mark as read / move to another folder ─────────────────
    private JCheckBox cbMailMarkAsRead;
    private JTextField tfMailOutputFolder;

    // ── Schedule ──────────────────────────────────────────────────────────────
    private JComboBox<String> cbScheduleType;
    private JTextField  tfScheduledAt;
    private JTextField  tfInterval;
    private JComboBox<String> cbDayOfWeek;
    private JTextField  tfTime;

    private JPanel fileTransferPanel;
    private JPanel backupPanel;
    private JPanel scheduleDetailsPanel;
    private JTabbedPane tabbedPane;

    // ── Watcher epoch reset tracking ──────────────────────────────────────────
    private boolean uiFullyLoaded            = false;
    private boolean watcherEpochShouldReset  = false;
    private String  originalTargetFolder     = "";
    private String  originalTransferDirection = "OUTBOUND";
    private String  originalTransferMode      = "ENTIRE_FOLDER";

    public TaskDialog(Frame parent, XmlStorageService storage, ScheduledTask existing) {
        super(parent, existing == null ? "New Task" : "Edit Task", true);
        this.storage = storage;
        setResizable(true);
        setMinimumSize(new Dimension(700, 560));
        setLayout(new BorderLayout(10, 10));
        buildUI(existing);
        validate();
        revalidate();
        repaint();
        pack();
        // Ensure dialog never opens smaller than the minimum
        setSize(Math.max(getWidth(), 700), Math.max(getHeight(), 560));
        setLocationRelativeTo(parent);
    }

    private void buildUI(ScheduledTask existing) {
        originalTargetFolder      = existing != null && existing.getTargetPath() != null
                ? existing.getTargetPath() : "";
        originalTransferDirection = existing != null
                ? existing.getTransferDirection().name() : "OUTBOUND";
        originalTransferMode      = existing != null && existing.getTransferMode() != null
                ? existing.getTransferMode().name() : "ENTIRE_FOLDER";

        JPanel main = new JPanel(new BorderLayout(0, 12));
        main.setBackground(new Color(0xF2F4F8));
        main.setBorder(new EmptyBorder(12, 12, 12, 12));

        // ── Page header ───────────────────────────────────────────────────────
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel pageTitle = new JLabel(existing == null ? "Create New Task" : "Edit Task");
        pageTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        pageTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel pageSubTitle = new JLabel(
                "Configure the task, schedule, credentials, and inbound watcher settings.");
        pageSubTitle.setFont(pageSubTitle.getFont().deriveFont(Font.PLAIN, 12f));
        pageSubTitle.setForeground(new Color(0x555555));
        pageSubTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(pageTitle);
        header.add(Box.createRigidArea(new Dimension(0, 6)));
        header.add(pageSubTitle);
        header.add(Box.createRigidArea(new Dimension(0, 12)));
        main.add(header, BorderLayout.NORTH);

        // ── General tab ───────────────────────────────────────────────────────
        general    = titledPanel("General");
        tfName     = makeField(new JTextField(existing != null ? existing.getName() : "", 28));
        cbTaskType = makeCombo(new JComboBox<>(new String[]{
                "FILE_TRANSFER", "OUTLOOK_MAIL", "BACKUP"}));
        if (existing != null) cbTaskType.setSelectedItem(existing.getTaskType().name());

        addRow(general, "Task Name *", tfName,     0);
        addRow(general, "Task Type *", cbTaskType, 1);

        // ── Source system ─────────────────────────────────────────────────────
        sourcePanel  = titledPanel("Source System  (auto-detected — editable if needed)");
        tfSourceHost = makeField(new JTextField(TransferService.getLocalHostname(), 28));
        tfSourceUser = makeField(new JTextField(TransferService.getLocalUsername(), 28));
        tfSourcePath = makeField(new JTextField(
                existing != null && existing.getSourcePath() != null ? existing.getSourcePath() : "", 28));

        tfSourceHost.setForeground(new Color(0x555555));
        tfSourceUser.setForeground(new Color(0x555555));

        addRow(sourcePanel, "Hostname / IP",             tfSourceHost, 0);
        addRow(sourcePanel, "Username",                  tfSourceUser, 1);
        addRow(sourcePanel, lblSourcePath = new JLabel(), withBrowseButton(tfSourcePath,
                () -> browseLocal(tfSourcePath, "This Computer")), 2);
        lblSourceHint = hint("");
        addRow(sourcePanel, "", lblSourceHint, 3);

        // ── File Transfer panel ───────────────────────────────────────────────
        fileTransferPanel = titledPanel("File Transfer — Destination");

        cbTransferDirection = makeCombo(new JComboBox<>(new String[]{"OUTBOUND", "INBOUND"}));
        cbTransferDirection.setSelectedItem(
                existing != null ? existing.getTransferDirection().name() : "OUTBOUND");

        cbTransferMode = makeCombo(new JComboBox<>(
                new String[]{"ENTIRE_FOLDER", "LATEST_ONLY", "SPECIFIC_FILE"}));
        cbTransferMode.setSelectedItem(
                existing != null && existing.getTransferMode() != null
                        ? existing.getTransferMode().name() : "ENTIRE_FOLDER");
        cbTransferMode.setToolTipText("LATEST_ONLY: transfer the newest file(s). When watcher is enabled this applies to both OUTBOUND and INBOUND — multiple files sharing the same timestamp (but with different sizes) will be transferred.");

        tfTargetFolder = makeField(new JTextField(
                existing != null && existing.getTargetPath() != null ? existing.getTargetPath() : "", 28));

        lblAdditionalTargetFolders = new JLabel("Additional destinations");
        lblAdditionalTargetFolders.setToolTipText(
                "Optional. Copy every transferred file to these folders too, in addition to the "
                        + "destination above. OUTBOUND: additional remote folders on the same target "
                        + "server/credential. INBOUND: additional local folders on this machine.");
        JPanel additionalDestinationsField = buildAdditionalDestinationsField(existing);

        // ── Inbound watcher fields ────────────────────────────────────────────
        cbWatcherEnabled = new JCheckBox("Enable watcher task");
        lblWatcherInfo = new JLabel("<html><div style='width:380px'><i style='color:gray'>"
                + "The watcher detects files modified after the last successful run. "
                + "Operates for both OUTBOUND and INBOUND when TransferMode is LATEST_ONLY. "
                + "For SFTP tasks a JSch channel is used; for local→local tasks "
                + "Files.newDirectoryStream is used — no WinSCP required."
                + "</i></div></html>");
        lblWatcherInfo.setFont(lblWatcherInfo.getFont().deriveFont(Font.PLAIN, 11f));

        if (existing != null) {
            cbWatcherEnabled.setSelected(existing.isWatcherEnabled());
        }
        // ── Watcher baseline status row ───────────────────────────────────────
        lblWatcherStatus = new JLabel();
        lblWatcherStatus.setFont(lblWatcherStatus.getFont().deriveFont(Font.PLAIN, 11f));

        btnResetBaseline = new JButton("Reset Baseline");
        btnResetBaseline.setFont(btnResetBaseline.getFont().deriveFont(Font.PLAIN, 11f));
        btnResetBaseline.setToolTipText(
                "Clears the stored epoch and size so the watcher treats the next run as a first run "
                        + "(transfers everything currently in the source directory).");
        btnResetBaseline.addActionListener(e -> {
            watcherEpochShouldReset = true;
            lblWatcherStatus.setText(
                    "<html><i style='color:#E65100'>Baseline will be cleared on Save.</i></html>");
            btnResetBaseline.setEnabled(false);
        });

        watcherStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        watcherStatusRow.setOpaque(false);
        watcherStatusRow.add(lblWatcherStatus);
        watcherStatusRow.add(btnResetBaseline);

        refreshWatcherStatusLabel(existing);

        // ── Local→local mode is not supported — every FILE_TRANSFER task
        // requires a remote target credential (see Target Credentials tab).

        lblTargetFolder = new JLabel();
        lblTargetHint   = hint("");

        addRow(fileTransferPanel, "Transfer Direction *",    cbTransferDirection,    0);
        addRow(fileTransferPanel, "Transfer Mode *",         cbTransferMode,         1);
        addRow(fileTransferPanel, lblTargetFolder,           withBrowseButton(tfTargetFolder, () ->
                browseRemote(tfTargetFolder, tfTargetHost.getText().trim(), tfTargetUser.getText().trim(),
                        new String(pfTargetPass.getPassword()), (String) cbTargetOs.getSelectedItem())),   2);
        addRow(fileTransferPanel, "",                        lblTargetHint,          3);
        addRow(fileTransferPanel, lblAdditionalTargetFolders, additionalDestinationsField, 4);
        addRow(fileTransferPanel, "",                        cbWatcherEnabled,5);
        addRow(fileTransferPanel, "",                        lblWatcherInfo,         6);
        addRow(fileTransferPanel, "Watcher baseline",        watcherStatusRow,       7);

        transferTab = new JPanel(new GridBagLayout());
        transferTab.setOpaque(false);

        transferTab = new JPanel();
        transferTab.setLayout(new BoxLayout(transferTab, BoxLayout.Y_AXIS));
        transferTab.setOpaque(false);
        sourcePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileTransferPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        transferTab.add(sourcePanel);
        transferTab.add(Box.createRigidArea(new Dimension(0, 10)));
        transferTab.add(fileTransferPanel);

        // ── Backup panel ──────────────────────────────────────────────────────
        // Either side (source or destination) may be a plain local folder, or a
        // REMOTE path reached over SFTP using a stored credential (same
        // creds_<username>.xml mechanism as File Transfer's Target Username).
        backupPanel = titledPanel("Backup");
        tfBackupSourcePath = makeField(new JTextField(
                existing != null && existing.getBackupSourcePath() != null ? existing.getBackupSourcePath() : "", 28));
        tfBackupDestinationPath = makeField(new JTextField(
                existing != null && existing.getBackupDestinationPath() != null ? existing.getBackupDestinationPath() : "", 28));
        int existingRetention = existing != null && existing.getBackupRetentionDays() > 0
                ? existing.getBackupRetentionDays() : 3;
        spBackupRetentionDays = new JSpinner(new SpinnerNumberModel(existingRetention, 1, 365, 1));

        String existingSrcUser  = existing != null && existing.getBackupSourceUsername() != null
                ? existing.getBackupSourceUsername() : "";
        String existingDestUser = existing != null && existing.getBackupDestinationUsername() != null
                ? existing.getBackupDestinationUsername() : "";

        cbBackupSourceRemote = new JCheckBox("Source is remote (SFTP)", !existingSrcUser.isEmpty());
        tfBackupSourceUsername = makeField(new JTextField(existingSrcUser, 20));
        tfBackupSourceUsername.setEnabled(cbBackupSourceRemote.isSelected());
        cbBackupSourceRemote.addActionListener(e -> tfBackupSourceUsername.setEnabled(cbBackupSourceRemote.isSelected()));

        cbBackupDestinationRemote = new JCheckBox("Destination is remote (SFTP)", !existingDestUser.isEmpty());
        tfBackupDestinationUsername = makeField(new JTextField(existingDestUser, 20));
        tfBackupDestinationUsername.setEnabled(cbBackupDestinationRemote.isSelected());
        cbBackupDestinationRemote.addActionListener(e -> tfBackupDestinationUsername.setEnabled(cbBackupDestinationRemote.isSelected()));

        addRow(backupPanel, "Source Folder *",      withBrowseButton(tfBackupSourcePath, () ->
                browseBackupSide(tfBackupSourcePath, cbBackupSourceRemote, tfBackupSourceUsername)), 0);
        addRow(backupPanel, "",                     cbBackupSourceRemote, 1);
        addRow(backupPanel, "Source Username",      tfBackupSourceUsername, 2);
        addRow(backupPanel, "", hint("Only needed if 'Source is remote' is checked — must match a saved credential's username (see Target Credentials on a File Transfer task)."), 3);
        addRow(backupPanel, "Backup Folder *",      withBrowseButton(tfBackupDestinationPath, () ->
                browseBackupSide(tfBackupDestinationPath, cbBackupDestinationRemote, tfBackupDestinationUsername)), 4);
        addRow(backupPanel, "",                     cbBackupDestinationRemote, 5);
        addRow(backupPanel, "Destination Username", tfBackupDestinationUsername, 6);
        addRow(backupPanel, "", hint("Only needed if 'Destination is remote' is checked — must match a saved credential's username."), 7);
        addRow(backupPanel, "Days to keep (D..) *", spBackupRetentionDays, 8);
        addRow(backupPanel, "", hint("If today is D, this many days are kept in the source — D, D-1, ... — and everything older becomes eligible for backup. Note: source and destination cannot both be remote."), 9);
        addRow(backupPanel, "", hint("Every eligible file is backed up in a single run. Large runs are split into batches by total file size, not day or file count — see Settings for batch size/interval."), 10);

        // ── Mail / Outlook panel (Microsoft Graph) ────────────────────────────
        // Reads mail via Microsoft Graph rather than IMAP: Microsoft has disabled
        // Basic Auth for IMAP tenant-wide, and many tenants additionally block
        // legacy protocols entirely via Conditional Access (OAuth2 over IMAP does
        // not bypass that). Graph is the same HTTPS path the Outlook web/mobile
        // apps use, so it works under those policies. See README for the one-time
        // Azure AD app registration steps (no tenant admin required in most orgs).
        mailPanel = titledPanel("Outlook Mail (Microsoft Graph)");

        tfMailMailboxAddress = makeField(new JTextField(
                existing != null && existing.getMailMailboxAddress() != null
                        ? existing.getMailMailboxAddress() : "", 28));
        addRow(mailPanel, "Mailbox Address *", tfMailMailboxAddress, 0);

        tfMailTenantId = makeField(new JTextField(
                existing != null && existing.getMailTenantId() != null && !existing.getMailTenantId().isEmpty()
                        ? existing.getMailTenantId() : "common", 28));
        addRow(mailPanel, "Azure AD Tenant ID *", tfMailTenantId, 1);
        addRow(mailPanel, "", hint("Your organization's Directory (tenant) ID, or \"common\" for any org/personal account."), 2);

        tfMailClientId = makeField(new JTextField(
                existing != null && existing.getMailClientId() != null
                        ? existing.getMailClientId() : "", 28));
        addRow(mailPanel, "Azure AD Client ID *", tfMailClientId, 3);
        addRow(mailPanel, "", hint("Application (client) ID from a self-registered Azure AD app — see README \u201cOutlook Mail setup\u201d."), 4);

        JPanel authRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        authRow.setOpaque(false);
        btnAuthorizeMailbox = new JButton("Authorize Mailbox\u2026");
        lblMailAuthStatus = new JLabel();
        authRow.add(btnAuthorizeMailbox);
        authRow.add(lblMailAuthStatus);
        addRow(mailPanel, "", authRow, 5);
        addRow(mailPanel, "", hint("One-time sign-in per mailbox. After this, scheduled runs are unattended."), 6);

        btnAuthorizeMailbox.addActionListener(e -> onAuthorizeMailboxClicked());
        tfMailMailboxAddress.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate (javax.swing.event.DocumentEvent e) { updateMailAuthStatus(); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { updateMailAuthStatus(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateMailAuthStatus(); }
        });

        tfImapFolder = makeField(new JTextField(
                existing != null && existing.getImapFolder() != null
                        ? existing.getImapFolder() : "INBOX", 28));
        addRow(mailPanel, "Mail Folder *", tfImapFolder, 7);

        pnlMailSearchCriteria = new SearchCriteriaPanel();
        if (existing != null && existing.getMailSearchCriteria() != null)
            pnlMailSearchCriteria.setCriteria(existing.getMailSearchCriteria());

        GridBagConstraints gcCriteria = new GridBagConstraints();
        gcCriteria.gridx = 0; gcCriteria.gridy = 8; gcCriteria.gridwidth = 2;
        gcCriteria.fill = GridBagConstraints.BOTH;
        gcCriteria.weightx = 1; gcCriteria.weighty = 1;
        gcCriteria.insets = new Insets(4, 4, 4, 4);
        mailPanel.add(pnlMailSearchCriteria, gcCriteria);
        addRow(mailPanel, "", hint("Best-effort mapping to Graph: UNSEEN/SEEN, FROM/SUBJECT \"text\", SINCE/BEFORE/ON dd-MMM-yyyy. Unsupported criteria are logged and skipped at run time."), 9);

        cbMailFetchMode = makeCombo(new JComboBox<>(
                new String[]{"BODY_ONLY", "HEADERS_AND_BODY", "FULL_MESSAGE"}));
        if (existing != null && existing.getMailFetchMode() != null)
            cbMailFetchMode.setSelectedItem(existing.getMailFetchMode().name());
        addRow(mailPanel, "Fetch Mode *", cbMailFetchMode, 10);

        cbMailFetchScope = makeCombo(new JComboBox<>(new String[]{"LATEST_ONLY", "ALL_MATCHING"}));
        cbMailFetchScope.setSelectedItem(
                existing != null && existing.getMailFetchScope() != null
                        ? existing.getMailFetchScope().name() : "LATEST_ONLY");
        addRow(mailPanel, "Fetch Scope *", cbMailFetchScope, 11);
        addRow(mailPanel, "", hint("LATEST_ONLY = newest matching message. ALL_MATCHING = every matching message, up to the cap below (paginated)."), 12);

        lblMailMaxResults = new JLabel("Max Messages (ALL_MATCHING / watcher cap)");
        tfMailMaxResults = makeField(new JTextField(
                String.valueOf(existing != null && existing.getMailMaxResults() > 0
                        ? existing.getMailMaxResults() : 50), 6));
        addRow(mailPanel, lblMailMaxResults, tfMailMaxResults, 13);

        lblMailFetchScopeNote = hint("Watcher is enabled below \u2014 Fetch Scope is overridden: every run fetches all messages newer than the last successful run, up to the Max Messages cap.");
        addRow(mailPanel, "", lblMailFetchScopeNote, 14);

        // ── Mail watcher ────────────────────────────────────────────────────
        cbMailWatcherEnabled = new JCheckBox("Enable watcher (only fetch messages newer than last successful run)");
        if (existing != null) cbMailWatcherEnabled.setSelected(existing.isWatcherEnabled());
        addRow(mailPanel, "", cbMailWatcherEnabled, 15);

        lblMailWatcherStatus = new JLabel();
        lblMailWatcherStatus.setFont(lblMailWatcherStatus.getFont().deriveFont(Font.PLAIN, 11f));
        btnResetMailBaseline = new JButton("Reset Baseline");
        btnResetMailBaseline.setFont(btnResetMailBaseline.getFont().deriveFont(Font.PLAIN, 11f));
        btnResetMailBaseline.setToolTipText(
                "Clears the stored baseline so the watcher treats the next run as a first run "
                        + "(fetches everything currently matching, up to the cap).");
        btnResetMailBaseline.addActionListener(e -> {
            mailWatcherEpochShouldReset = true;
            lblMailWatcherStatus.setText(
                    "<html><i style='color:#E65100'>Baseline will be cleared on Save.</i></html>");
            btnResetMailBaseline.setEnabled(false);
        });
        mailWatcherStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        mailWatcherStatusRow.setOpaque(false);
        mailWatcherStatusRow.add(lblMailWatcherStatus);
        mailWatcherStatusRow.add(btnResetMailBaseline);
        addRow(mailPanel, "Watcher baseline", mailWatcherStatusRow, 16);
        refreshMailWatcherStatusLabel(existing);

        // ── Post-processing ─────────────────────────────────────────────────
        cbMailMarkAsRead = new JCheckBox("Mark fetched messages as read");
        if (existing != null) cbMailMarkAsRead.setSelected(existing.isMailMarkAsRead());
        addRow(mailPanel, "", cbMailMarkAsRead, 17);

        // Manual "move to folder" configuration removed — processed messages
        // are now automatically routed by content: LDM → the configured LDM
        // folder, PTM → the configured PTM folder, anything else → the
        // configured "Others" folder (see app-config.xml <sitaMessaging>,
        // and TransferService#resolveMoveFolderName).
        addRow(mailPanel, "", hint("Processed messages are automatically moved to a folder based on content "
                + "(LDM / PTM / Others) — folder names are configured in app-config.xml, not here."), 18);

        // ── Output folder (.RCV files) ──────────────────────────────────────
        tfMailOutputFolder = makeField(new JTextField(
                existing != null && existing.getMailOutputFolder() != null
                        ? existing.getMailOutputFolder() : "", 28));
        addRow(mailPanel, "Output Folder *", tfMailOutputFolder, 21);
        addRow(mailPanel, "", hint("Local directory where each fetched message is written as a .RCV file. Created automatically if it doesn't exist yet."), 22);

        cbMailWatcherEnabled.addActionListener(e -> updateMailFetchScopeVisibility());

        cbMailFetchScope.addActionListener(e -> updateMailFetchScopeVisibility());
        updateMailFetchScopeVisibility();

        updateMailAuthStatus();

        // ── Retry panel ───────────────────────────────────────────────────────
        JPanel retryPanel = titledPanel("Failure Retry");
        spinnerRetryCount = makeSpinner(new JSpinner(new SpinnerNumberModel(
                existing != null ? existing.getRetryCount() : 0, 0, 10, 1)));
        addRow(retryPanel, "Retry attempts on failure:", spinnerRetryCount, 0);
        addRow(retryPanel, "", hint("0 = no retries; set to 1–10 for automatic recovery."), 1);

        // ── Target credentials panel ──────────────────────────────────────────
        targetPanel = titledPanel("Target System Credentials");

        String existingUser = existing != null ? existing.getTargetUsername() : null;
        Credential existingCred = existingUser != null
                ? storage.loadCredentialByUsername(existingUser) : null;

        tfTargetHost = makeField(new JTextField(
                existingCred != null ? existingCred.getHost() : "", 28));
        tfTargetUser = makeField(new JTextField(
                existingUser != null ? existingUser : "", 28));
        pfTargetPass = makePasswordField(new JPasswordField(28));
        cbTargetOs   = makeCombo(new JComboBox<>(new String[]{"WINDOWS", "LINUX"}));

        if (existingCred != null) {
            pfTargetPass.setText(existingCred.getPassword() != null ? existingCred.getPassword() : "");
            cbTargetOs.setSelectedItem(existingCred.getOsType());
        }

        tfTargetUser.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                String username = tfTargetUser.getText().trim();
                if (!username.isEmpty()) {
                    Credential cred = storage.loadCredentialByUsername(username);
                    if (cred != null) {
                        pfTargetPass.setText(cred.getPassword() != null ? cred.getPassword() : "");
                        tfTargetHost.setText(cred.getHost()     != null ? cred.getHost()     : "");
                        cbTargetOs.setSelectedItem(
                                cred.getOsType() != null ? cred.getOsType() : "WINDOWS");
                    }
                }
            }
        });

        addRow(targetPanel, "Hostname / IP",  tfTargetHost, 0);
        addRow(targetPanel, "Username",        tfTargetUser, 1);
        addRow(targetPanel, "Password",        pfTargetPass, 2);
        addRow(targetPanel, "OS Type",         cbTargetOs,   3);

        JButton btnTestConnection = new JButton("Test Connection");
        btnTestConnection.setToolTipText("Opens a real SFTP session with the fields above to verify they work");
        btnTestConnection.addActionListener(e -> {
            String host = tfTargetHost.getText().trim();
            String user = tfTargetUser.getText().trim();
            String pass = new String(pfTargetPass.getPassword());
            if (host.isEmpty() || user.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Enter a Hostname / IP and Username first.",
                        "Test Connection", JOptionPane.WARNING_MESSAGE);
                return;
            }
            service.ConnectionTestService.Result result =
                    TestConnectionDialog.show(this, host, user, pass);
            if (result != null && result.success) {
                Credential cred = storage.loadCredentialByUsername(user);
                if (cred == null) cred = new Credential();
                if (cred.getId() == null) cred.setId(UUID.randomUUID().toString());
                cred.setName(user + "@" + host);
                cred.setHost(host);
                cred.setUsername(user);
                cred.setPassword(pass);
                cred.setOsType((String) cbTargetOs.getSelectedItem());
                storage.saveCredential(cred);
                JOptionPane.showMessageDialog(this,
                        "Connection verified — credential for \"" + user + "\" has been saved and is now "
                                + "visible on the Credentials page.",
                        "Credential Saved", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel testConnectionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testConnectionRow.add(btnTestConnection);
        addRow(targetPanel, "", testConnectionRow, 4);

        addRow(targetPanel, "",
                hint("For local→local tasks these fields can be left blank. "
                        + "Password is saved in plain text in credentials.db"), 5);

        // ── Schedule panel ────────────────────────────────────────────────────
        JPanel sched = titledPanel("Schedule");
        cbScheduleType = makeCombo(new JComboBox<>(new String[]{
                "RUN_NOW", "ONCE", "DAILY", "WEEKLY", "INTERVAL_MINUTES", "INTERVAL_SECONDS"}));
        if (existing != null) cbScheduleType.setSelectedItem(existing.getScheduleType().name());

        scheduleDetailsPanel = new JPanel(new GridBagLayout());
        tfScheduledAt = makeField(new JTextField(LocalDateTime.now().plusMinutes(5)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), 18));
        tfInterval    = makeField(new JTextField("30", 6));
        cbDayOfWeek   = makeCombo(new JComboBox<>(new String[]{
                "MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"}));
        tfTime        = makeField(new JTextField("09:00", 8));

        if (existing != null) {
            if (existing.getScheduledAt() != null)
                tfScheduledAt.setText(
                        existing.getScheduledAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            if (existing.getScheduleType() == ScheduleType.INTERVAL_SECONDS)
                tfInterval.setText(String.valueOf(existing.getIntervalSeconds()));
            else
                tfInterval.setText(String.valueOf(existing.getIntervalMinutes()));

            if (existing.getCronExpression() != null && !existing.getCronExpression().isEmpty()) {
                String[] parts = existing.getCronExpression().split(" ");
                if (parts.length >= 2) {
                    cbDayOfWeek.setSelectedItem(parts[0]);
                    tfTime.setText(parts[1]);
                } else {
                    tfTime.setText(existing.getCronExpression());
                }
            }
        }

        addRow(sched, "Schedule Type *", cbScheduleType, 0);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        gc.insets = new Insets(4, 4, 4, 4);
        sched.add(scheduleDetailsPanel, gc);

        // ── Tabbed pane ───────────────────────────────────────────────────────
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, 12f));
        tabbedPane.addTab("General",   general);
        tabbedPane.addTab("Transfer",  transferTab);
        tabbedPane.addTab("Backup",    backupPanel);
        tabbedPane.addTab("Target",    targetPanel);
        tabbedPane.addTab("Mail/IMAP", mailPanel);
        tabbedPane.addTab("Schedule",  sched);
        tabbedPane.addTab("Retry",     retryPanel);

        main.add(tabbedPane, BorderLayout.CENTER);

        // ── Wire listeners ────────────────────────────────────────────────────
        cbTransferDirection.addActionListener(e -> {
            updateTransferDirectionLabels();
            updateWatcherFieldsVisibility();
            updateTransferTabVisibility();
            checkTransferDirectionChanged(existing);
        });

        cbTransferMode.addActionListener(e -> {
            updateWatcherFieldsVisibility();       // shows/hides baseline row
            checkTransferModeChanged(existing);    // auto-resets baseline if needed
        });

        cbWatcherEnabled.addActionListener(e -> updateWatcherFieldsVisibility());

        cbTaskType.addActionListener(e -> {
            updateVisibility();
            updateMailFieldsVisibility();
        });

        cbScheduleType.addActionListener(e -> updateScheduleDetails());

        tfTargetFolder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate (javax.swing.event.DocumentEvent e) { checkTargetFolderChanged(existing); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { checkTargetFolderChanged(existing); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkTargetFolderChanged(existing); }
        });

        updateTransferDirectionLabels();
        updateWatcherFieldsVisibility();
        updateVisibility();
        updateScheduleDetails();
        updateMailFieldsVisibility();

        uiFullyLoaded = true;

        JScrollPane scroll = new JScrollPane(main);
        scroll.setBorder(null);
        // Allow horizontal scrollbar when content cannot fit; better than truncating UI.
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // A JScrollPane's preferred size defaults to its view's full preferred
        // size, which defeats scrolling during pack() — with the expanded
        // Mail/IMAP tab, that let pack() blow the whole dialog up taller than
        // the screen and push the Cancel/Save row off-screen. Capping the
        // scroll pane's own preferred size keeps the dialog at its original
        // ~700x560 footprint; any tab taller than that now scrolls internally
        // instead of growing the window.
        scroll.setPreferredSize(new Dimension(680, 480));
        add(scroll, BorderLayout.CENTER);

        // ── Buttons ───────────────────────────────────────────────────────────
        JButton btnSave   = new JButton(existing == null ? "Create Task" : "Save Changes");
        JButton btnCancel = new JButton("Cancel");
        styleBtn(btnSave, new Color(0x1565C0));
        btnCancel.addActionListener(e -> dispose());
        btnSave.addActionListener(e -> save(existing));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.setBorder(new EmptyBorder(0, 10, 10, 10));
        btnRow.add(btnCancel);
        btnRow.add(btnSave);
        add(btnRow, BorderLayout.SOUTH);
    }

    // ── Watcher baseline status label ─────────────────────────────────────────

    /**
     * Populates lblWatcherStatus and btnResetBaseline visibility based on the
     * task's stored fingerprint (epoch + size).
     *
     * States
     * ──────
     * No existing task / first run (epoch == 0):
     *   Grey italic — "No baseline stored — first run will always transfer."
     *   Reset button hidden.
     *
     * Baseline present, size tracked (size >= 0):
     *   Green italic — "Last seen: YYYY-MM-DD HH:mm:ss | size: N bytes"
     *   Reset button visible.
     *
     * Baseline present, size not tracked (size == -1, legacy):
     *   Amber italic — warns the operator that the size signal is missing.
     *   Reset button visible.
     */
    private void refreshWatcherStatusLabel(ScheduledTask existing) {
        if (existing == null || existing.getLastKnownRemoteFileEpoch() <= 0) {
            lblWatcherStatus.setText(
                    "<html><i style='color:#757575'>No baseline stored — first run will always transfer.</i></html>");
            if (btnResetBaseline != null) btnResetBaseline.setVisible(false);
            return;
        }

        long epoch = existing.getLastKnownRemoteFileEpoch();
        long size  = existing.getLastKnownRemoteFileSize();

        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(epoch));

        String sizeStr;
        String color;
        if (size < 0) {
            sizeStr = "not tracked — size check always passes (legacy; reset baseline to fix)";
            color   = "#E65100";
        } else {
            sizeStr = String.format("%,d bytes", size);
            color   = "#2E7D32";
        }

        lblWatcherStatus.setText(String.format(
                "<html><i style='color:%s'>Last seen: %s &nbsp;|&nbsp; size: %s</i></html>",
                color, dateStr, sizeStr));

        if (btnResetBaseline != null) btnResetBaseline.setVisible(true);
    }

    // ── Visibility helpers ────────────────────────────────────────────────────

    private void updateVisibility() {
        String type        = (String) cbTaskType.getSelectedItem();
        boolean isTransfer = "FILE_TRANSFER".equals(type);
        boolean isMail     = "OUTLOOK_MAIL".equals(type);
        boolean isBackup   = "BACKUP".equals(type);

        enableTabIfPresent(transferTab,   isTransfer);
        enableTabIfPresent(backupPanel,   isBackup);
        enableTabIfPresent(mailPanel,     isMail);

        if (tabbedPane != null) {
            Component sel = tabbedPane.getSelectedComponent();
            if (sel != null) {
                int idx = tabbedPane.indexOfComponent(sel);
                if (idx >= 0 && !tabbedPane.isEnabledAt(idx))
                    tabbedPane.setSelectedComponent(general);
            }
            tabbedPane.revalidate();
            tabbedPane.repaint();
        }
        updateTransferTabVisibility();
        revalidate(); repaint();
    }

    private void updateTransferDirectionLabels() {
        String direction = (String) cbTransferDirection.getSelectedItem();
        boolean inbound = "INBOUND".equals(direction);

        lblSourcePath.setText(inbound ? "Local Destination Path *" : "Source Path *");
        lblTargetFolder.setText(inbound ? "Remote Source Folder *" : "Destination Folder *");

        lblSourceHint.setText(inbound
                ? "<html><i style='color:gray'>Local path where files retrieved from the target will be saved.</i></html>"
                : "<html><i style='color:gray'>Local file or folder to send to the target server.</i></html>");
        lblTargetHint.setText(inbound
                ? "<html><i style='color:gray'>Remote source file or folder path on the target server.</i></html>"
                : "<html><i style='color:gray'>Remote destination folder on the target server.</i></html>");

        // Watcher checkbox is available for both directions (watching is supported for inbound and outbound
        // transfers). Keep the checkbox visible whenever the Transfer tab is shown.
        cbWatcherEnabled.setVisible(true);

        // Multi-destination copying is supported for both directions: OUTBOUND
        // copies to additional remote folders on the same server, INBOUND
        // copies the downloaded file to additional local folders.
        lblAdditionalTargetFolders.setText(inbound ? "Additional local destinations" : "Additional destinations");

        transferTab.revalidate(); transferTab.repaint();
        revalidate(); repaint();
    }

    /**
     * Shows/hides watcher-related fields based on:
     *   • Whether the watcher checkbox is checked (spinners, info text).
     *   • Whether TransferMode is LATEST_ONLY (baseline row — service only runs in this mode).
     */
    private void updateWatcherFieldsVisibility() {
        boolean watcherOn  = cbWatcherEnabled.isSelected();
        boolean latestOnly = "LATEST_ONLY".equals(cbTransferMode.getSelectedItem());

        lblWatcherInfo.setVisible(watcherOn);

        boolean showBaseline = watcherOn && latestOnly;
        if (watcherStatusRow != null) watcherStatusRow.setVisible(showBaseline);
        if (lblWatcherStatus  != null) lblWatcherStatus.setVisible(showBaseline);
        if (btnResetBaseline  != null) btnResetBaseline.setVisible(showBaseline && !watcherEpochShouldReset);

        transferTab.revalidate(); transferTab.repaint();
        revalidate(); repaint();
    }

    private void updateScheduleDetails() {
        scheduleDetailsPanel.removeAll();
        String stype = (String) cbScheduleType.getSelectedItem();
        switch (stype) {
            case "RUN_NOW":
                addRowTo(scheduleDetailsPanel, "",
                        hint("Task will execute on the next scheduler tick (~60 s)"), 0);
                break;
            case "ONCE":
                addRowTo(scheduleDetailsPanel, "Run at (yyyy-MM-dd HH:mm) *", tfScheduledAt, 0);
                break;
            case "DAILY":
                addRowTo(scheduleDetailsPanel, "Time (HH:mm) *", tfTime, 0);
                break;
            case "WEEKLY":
                addRowTo(scheduleDetailsPanel, "Day *",          cbDayOfWeek, 0);
                addRowTo(scheduleDetailsPanel, "Time (HH:mm) *", tfTime,      1);
                break;
            case "INTERVAL_MINUTES":
                addRowTo(scheduleDetailsPanel, "Every N minutes *", tfInterval, 0);
                break;
            case "INTERVAL_SECONDS":
                addRowTo(scheduleDetailsPanel, "Every N seconds *", tfInterval, 0);
                break;
        }
        scheduleDetailsPanel.revalidate();
        scheduleDetailsPanel.repaint();
    }

    private void enableTabIfPresent(Component comp, boolean enabled) {
        if (tabbedPane == null) return;
        int index = tabbedPane.indexOfComponent(comp);
        if (index >= 0 && index < tabbedPane.getTabCount())
            tabbedPane.setEnabledAt(index, enabled);
    }

    private void updateTransferTabVisibility() {
        if (tabbedPane == null) return;
        boolean isBackup = "BACKUP".equals(cbTaskType.getSelectedItem());
        enableTabIfPresent(targetPanel, !isBackup);
        if (isBackup && tabbedPane.getSelectedComponent() == targetPanel) {
            tabbedPane.setSelectedComponent(backupPanel);
        }
    }

    /**
     * Mail tasks read via Microsoft Graph, which is a cloud REST API regardless
     * of where the task runs — there is no "target credential" concept for it,
     * so the Target tab is simply disabled whenever the task type is mail.
     */
    private void updateMailFieldsVisibility() {
        boolean isMail = "OUTLOOK_MAIL".equals(cbTaskType.getSelectedItem());
        if (targetPanel != null && tabbedPane != null)
            enableTabIfPresent(targetPanel, !isMail);
        if (tabbedPane != null) { tabbedPane.revalidate(); tabbedPane.repaint(); }
        if (targetPanel != null) { targetPanel.revalidate(); targetPanel.repaint(); }
        revalidate(); repaint();
    }

    /** Max Messages only matters when fetching everything that matches, or when the watcher is on. */
    private void updateMailFetchScopeVisibility() {
        boolean watcherOn = cbMailWatcherEnabled != null && cbMailWatcherEnabled.isSelected();
        boolean isAll = "ALL_MATCHING".equals(cbMailFetchScope.getSelectedItem());
        cbMailFetchScope.setEnabled(!watcherOn);
        lblMailFetchScopeNote.setVisible(watcherOn);
        lblMailMaxResults.setVisible(isAll || watcherOn);
        tfMailMaxResults.setVisible(isAll || watcherOn);
        mailPanel.revalidate();
        mailPanel.repaint();
    }


    /**
     * Populates lblMailWatcherStatus / btnResetMailBaseline based on the task's
     * stored mail watcher baseline (mirrors refreshWatcherStatusLabel for files).
     */
    private void refreshMailWatcherStatusLabel(ScheduledTask existing) {
        if (existing == null || existing.getMailLastKnownEpoch() <= 0) {
            lblMailWatcherStatus.setText(
                    "<html><i style='color:#757575'>No baseline stored \u2014 first run will fetch up to the cap.</i></html>");
            if (btnResetMailBaseline != null) btnResetMailBaseline.setVisible(false);
            return;
        }
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(existing.getMailLastKnownEpoch()));
        lblMailWatcherStatus.setText(
                "<html><i style='color:#2E7D32'>Last seen: " + dateStr + "</i></html>");
        if (btnResetMailBaseline != null) btnResetMailBaseline.setVisible(true);
    }

    /** Reflects whether the entered mailbox is already authorized (cached refresh token present). */
    private void updateMailAuthStatus() {
        if (lblMailAuthStatus == null) return;
        String mailbox = tfMailMailboxAddress.getText().trim();
        if (mailbox.isEmpty()) {
            lblMailAuthStatus.setText("<html><i style='color:#888888'>Enter a mailbox address first.</i></html>");
            return;
        }
        boolean enrolled = new service.OAuth2TokenService().isEnrolled(mailbox);
        lblMailAuthStatus.setText(enrolled
                ? "<html><span style='color:#2E7D32'>&#10003; Authorized</span></html>"
                : "<html><span style='color:#E65100'>Not authorized yet</span></html>");
    }

    /** Runs the OAuth2 device-code enrollment in the background and shows the sign-in prompt. */
    private void onAuthorizeMailboxClicked() {
        String mailbox  = tfMailMailboxAddress.getText().trim();
        String tenantId = tfMailTenantId.getText().trim();
        String clientId = tfMailClientId.getText().trim();

        if (mailbox.isEmpty())  { msg("Enter the mailbox address first.");        return; }
        if (tenantId.isEmpty()) { msg("Enter the Azure AD Tenant ID first (or \"common\")."); return; }
        if (clientId.isEmpty()) { msg("Enter the Azure AD Client ID first — see README \u201cOutlook Mail setup\u201d."); return; }

        new OAuthAuthorizeDialog((Frame) getOwner(), mailbox, tenantId, clientId,
                storage.getDataDir(), success -> updateMailAuthStatus())
                .setVisible(true);
    }

    // ── Watcher epoch reset helpers ───────────────────────────────────────────

    private void checkTargetFolderChanged(ScheduledTask existing) {
        if (!uiFullyLoaded) return;
        if (existing == null || !existing.isWatcherEnabled()) return;
        if (!tfTargetFolder.getText().equals(originalTargetFolder)) {
            triggerBaselineReset("Target folder changed — baseline will be cleared on Save.");
        }
    }

    private void checkTransferDirectionChanged(ScheduledTask existing) {
        // Historically the watcher checkbox was only available for INBOUND tasks and
        // switching direction would disable it. The service now supports watcher for
        // both directions so we no longer auto-disable or reset the baseline when the
        // transfer direction changes. Keep this method for compatibility but do nothing.
        if (!uiFullyLoaded || existing == null) return;
    }

    /**
     * Resets the baseline when the operator changes transfer mode away from
     * LATEST_ONLY while the watcher is enabled, because the service will not
     * run in any other mode and the stored fingerprint would be stale.
     */
    private void checkTransferModeChanged(ScheduledTask existing) {
        if (!uiFullyLoaded || existing == null) return;
        if (!cbWatcherEnabled.isSelected()) return;
        String current = (String) cbTransferMode.getSelectedItem();
        if ("LATEST_ONLY".equals(originalTransferMode) && !"LATEST_ONLY".equals(current)) {
            triggerBaselineReset("Transfer mode changed away from LATEST_ONLY — baseline will be cleared on Save.");
        }
    }

    /** Marks the baseline for reset and optionally updates the status label. */
    private void triggerBaselineReset(String uiMessage) {
        watcherEpochShouldReset = true;
        if (uiMessage != null && lblWatcherStatus != null) {
            lblWatcherStatus.setText(
                    "<html><i style='color:#E65100'>" + uiMessage + "</i></html>");
        }
        if (btnResetBaseline != null) btnResetBaseline.setEnabled(false);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void save(ScheduledTask existing) {
        // ── Validate ──────────────────────────────────────────────────────────
        String name = tfName.getText().trim();
        if (name.isEmpty()) { msg("Task name is required."); return; }

        boolean isTransfer  = "FILE_TRANSFER".equals(cbTaskType.getSelectedItem());
        boolean isMail      = "OUTLOOK_MAIL".equals(cbTaskType.getSelectedItem());

        boolean isBackup    = "BACKUP".equals(cbTaskType.getSelectedItem());

        String targetHost = tfTargetHost.getText().trim();
        String targetUser = tfTargetUser.getText().trim();
        String targetPass = new String(pfTargetPass.getPassword());

        // Mail and Backup tasks don't use the SFTP-style target credential.
        // Every FILE_TRANSFER task requires one — local→local is not supported.
        if (!isMail && !isBackup) {
            if (targetHost.isEmpty()) { msg("Target hostname / IP is required."); return; }
            if (targetUser.isEmpty()) { msg("Target username is required.");       return; }
            if (targetPass.isEmpty()) { msg("Target password is required.");       return; }
        }

        if (isTransfer) {
            if (tfSourcePath.getText().trim().isEmpty()) {
                msg("Source path is required.");
                return;
            }
            if (tfTargetFolder.getText().trim().isEmpty()) {
                msg("Destination folder is required.");
                return;
            }

            String transferMode = (String) cbTransferMode.getSelectedItem();
            String targetFolder = tfTargetFolder.getText().trim();
            if ("SPECIFIC_FILE".equals(transferMode)) {
                if (targetFolder.endsWith("/") || targetFolder.endsWith("\\")) {
                    msg("For SPECIFIC_FILE mode, destination must include a filename "
                            + "(e.g., /remote/path/filename.txt), not just a folder path.");
                    return;
                }
                if (!targetFolder.contains(".") && !targetFolder.contains("/")
                        && !targetFolder.contains("\\")) {
                    msg("For SPECIFIC_FILE mode, destination should include a filename. "
                            + "Current path looks like a folder name.");
                    return;
                }
            }

            // Watcher requires LATEST_ONLY — warn but don't block (the service falls back gracefully)
            if (cbWatcherEnabled.isSelected()
                    && !"LATEST_ONLY".equals(cbTransferMode.getSelectedItem())) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "The watcher uses RemoteFileMetadataService which only operates in "
                                + "LATEST_ONLY mode.\n\nFor other modes the watcher checkbox has no effect. "
                                + "Continue anyway?",
                        "Watcher / Mode mismatch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
            }

        } else if (isMail) {
            String mailbox  = tfMailMailboxAddress.getText().trim();
            String tenantId = tfMailTenantId.getText().trim();
            String clientId = tfMailClientId.getText().trim();
            if (mailbox.isEmpty())  { msg("Mailbox address is required.");        return; }
            if (tenantId.isEmpty()) { msg("Azure AD Tenant ID is required (or \"common\")."); return; }
            if (clientId.isEmpty()) { msg("Azure AD Client ID is required — see README \u201cOutlook Mail setup\u201d."); return; }
            if (tfImapFolder.getText().trim().isEmpty()) { msg("Mail folder is required."); return; }
            if (tfMailOutputFolder.getText().trim().isEmpty()) {
                msg("Output Folder is required — each fetched message is written there as a .RCV file.");
                return;
            }
            if (pnlMailSearchCriteria.getCriteria().trim().isEmpty()) {
                msg("Search criteria is required."); return;
            }
            int mailMaxResults;
            try {
                mailMaxResults = Integer.parseInt(tfMailMaxResults.getText().trim());
            } catch (NumberFormatException nfe) {
                msg("Max Messages must be a whole number."); return;
            }
            if (mailMaxResults < 1 || mailMaxResults > 5000) {
                msg("Max Messages must be between 1 and 5000."); return;
            }
            if (!new service.OAuth2TokenService().isEnrolled(mailbox)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Mailbox '" + mailbox + "' has not completed the one-time \"Authorize Mailbox\" "
                                + "sign-in yet. The task will fail when it runs until this is done.\n\n"
                                + "Save anyway?",
                        "Mailbox not yet authorized", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
            }
        } else if (isBackup) {
            if (tfBackupSourcePath.getText().trim().isEmpty()) { msg("Backup source folder is required."); return; }
            if (tfBackupDestinationPath.getText().trim().isEmpty()) { msg("Backup destination folder is required."); return; }
            boolean srcRemote  = cbBackupSourceRemote.isSelected();
            boolean destRemote = cbBackupDestinationRemote.isSelected();
            if (srcRemote && destRemote) {
                msg("Backup source and destination cannot both be remote — make one side local.");
                return;
            }
            if (srcRemote && tfBackupSourceUsername.getText().trim().isEmpty()) {
                msg("Source Username is required when 'Source is remote' is checked."); return;
            }
            if (destRemote && tfBackupDestinationUsername.getText().trim().isEmpty()) {
                msg("Destination Username is required when 'Destination is remote' is checked."); return;
            }
        }

        // ── Persist credential file creds_<username>.xml ──────────────────────
        // Skipped for mail (uses OAuth2, not a stored credential) and backup
        // (backup credentials are persisted separately, see below).
        if (!isMail && !isBackup && !targetUser.isEmpty()) {
            Credential cred = storage.loadCredentialByUsername(targetUser);
            if (cred == null) cred = new Credential();
            if (cred.getId() == null) cred.setId(UUID.randomUUID().toString());
            cred.setName(targetUser + "@" + targetHost);
            cred.setHost(targetHost);
            cred.setUsername(targetUser);
            cred.setPassword(targetPass);
            cred.setOsType((String) cbTargetOs.getSelectedItem());
            storage.saveCredential(cred);
        }

        try {
            spinnerRetryCount.commitEdit();
        } catch (java.text.ParseException ex) {
            msg("Invalid numeric value in watcher or retry fields.");
            return;
        }

        // ── Build / update task ───────────────────────────────────────────────
        ScheduledTask t = existing != null ? existing : new ScheduledTask();
        if (t.getId() == null) t.setId(UUID.randomUUID().toString());
        t.setName(name);
        t.setTaskType(TaskType.valueOf((String) cbTaskType.getSelectedItem()));
        if (existing == null) t.setStatus(TaskStatus.PENDING);

        // Mail tasks use OAuth2/Graph, so targetUsername is left blank for them
        // (and for Backup, which stores its own source/destination usernames).
        t.setTargetUsername(!isMail && !isBackup ? targetUser : "");
        t.setSourceCredentialId(
                tfSourceUser.getText().trim() + "@" + tfSourceHost.getText().trim());

        if (isTransfer) {
            t.setTransferDirection(TransferDirection.valueOf(
                    (String) cbTransferDirection.getSelectedItem()));
            t.setTransferMode(TransferMode.valueOf(
                    (String) cbTransferMode.getSelectedItem()));
            t.setSourcePath(tfSourcePath.getText().trim());
            t.setTargetPath(tfTargetFolder.getText().trim());
            {
                StringBuilder extraPaths = new StringBuilder();
                for (JTextField f : additionalDestFields) {
                    String v = f.getText().trim();
                    if (v.isEmpty()) continue;
                    if (extraPaths.length() > 0) extraPaths.append(';');
                    extraPaths.append(v);
                }
                t.setAdditionalTargetPaths(extraPaths.toString());
            }
            t.setWatcherEnabled(cbWatcherEnabled.isSelected());


            // Reset BOTH parts of the composite fingerprint when the operator
            // deliberately changed path / direction / mode, or clicked Reset Baseline.
            if (watcherEpochShouldReset) {
                t.setLastKnownRemoteFileEpoch(0L);
                t.setLastKnownRemoteFileSize(-1L);
            }

        } else if (isMail) {
            t.setImapFolder(tfImapFolder.getText().trim());
            t.setMailSearchCriteria(pnlMailSearchCriteria.getCriteria());
            t.setMailFetchMode(MailFetchMode.valueOf((String) cbMailFetchMode.getSelectedItem()));
            t.setMailFetchScope(ScheduledTask.MailFetchScope.valueOf((String) cbMailFetchScope.getSelectedItem()));
            t.setMailMaxResults(Integer.parseInt(tfMailMaxResults.getText().trim()));
            t.setMailMailboxAddress(tfMailMailboxAddress.getText().trim());
            t.setMailTenantId(tfMailTenantId.getText().trim());
            t.setMailClientId(tfMailClientId.getText().trim());
            t.setWatcherEnabled(cbMailWatcherEnabled.isSelected());
            t.setMailMarkAsRead(cbMailMarkAsRead.isSelected());
            t.setMailOutputFolder(tfMailOutputFolder.getText().trim());
            if (mailWatcherEpochShouldReset) {
                t.setMailLastKnownEpoch(0L);
            }
        } else if (isBackup) {
            t.setBackupSourcePath(tfBackupSourcePath.getText().trim());
            t.setBackupDestinationPath(tfBackupDestinationPath.getText().trim());
            t.setBackupRetentionDays((Integer) spBackupRetentionDays.getValue());
            t.setBackupSourceUsername(cbBackupSourceRemote.isSelected()
                    ? tfBackupSourceUsername.getText().trim() : "");
            t.setBackupDestinationUsername(cbBackupDestinationRemote.isSelected()
                    ? tfBackupDestinationUsername.getText().trim() : "");
        }

        t.setRetryCount((Integer) spinnerRetryCount.getValue());

        // ── Schedule ──────────────────────────────────────────────────────────
        String stype = (String) cbScheduleType.getSelectedItem();
        t.setScheduleType(ScheduleType.valueOf(stype));
        try {
            switch (stype) {
                case "ONCE":
                    t.setScheduledAt(LocalDateTime.parse(tfScheduledAt.getText().trim(),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                    break;
                case "DAILY":
                    t.setCronExpression(tfTime.getText().trim());
                    break;
                case "WEEKLY":
                    t.setCronExpression(cbDayOfWeek.getSelectedItem() + " " + tfTime.getText().trim());
                    t.setIntervalMinutes(0);
                    t.setIntervalSeconds(0);
                    break;
                case "INTERVAL_MINUTES":
                    t.setIntervalMinutes(Integer.parseInt(tfInterval.getText().trim()));
                    t.setIntervalSeconds(0);
                    break;
                case "INTERVAL_SECONDS":
                    t.setIntervalSeconds(Integer.parseInt(tfInterval.getText().trim()));
                    t.setIntervalMinutes(0);
                    break;
                default:
                    t.setIntervalMinutes(0);
                    t.setIntervalSeconds(0);
                    break;
            }
        } catch (Exception ex) {
            msg("Invalid schedule values: " + ex.getMessage());
            return;
        }

        storage.saveTask(t);
        result = t;
        dispose();
    }

    public ScheduledTask getResult() { return result; }

    // ── Directory browsing ──────────────────────────────────────────────────

    /** Wraps a path field with a small "..." button that opens the folder browser. */
    private JPanel withBrowseButton(JTextField field, Runnable onBrowse) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JButton btn = new JButton("...");
        btn.setToolTipText("Browse for a folder");
        btn.setMargin(new Insets(2, 8, 2, 8));
        btn.addActionListener(e -> onBrowse.run());
        row.add(field, BorderLayout.CENTER);
        row.add(btn, BorderLayout.EAST);
        return row;
    }

    /** "WINDOWS" or "LINUX" for whatever machine this app is currently running on. */
    private static String localOsType() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "WINDOWS" : "LINUX";
    }

    /** Opens the browser against the local filesystem this app is running on. */
    private void browseLocal(JTextField field, String label) {
        String selected = DirectoryBrowserDialog.show(this, label, localOsType(),
                new DirectoryBrowserDialog.LocalProvider(field.getText().trim()));
        if (selected != null) field.setText(selected);
    }

    /**
     * Opens the browser against a remote host over SFTP — connects first
     * (with a small "Connecting…" wait dialog since a bad host can take a
     * few seconds to time out), then shows the themed browser window.
     */
    private void browseRemote(JTextField field, String host, String username, String password, String osType) {
        if (host.isEmpty() || username.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Enter a Hostname / IP and Username first.",
                    "Browse", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog wait = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Connecting", true);
        wait.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(20, 24, 16, 24));
        content.add(new JLabel("Connecting to " + host + " \u2026", SwingConstants.CENTER), BorderLayout.CENTER);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        content.add(bar, BorderLayout.SOUTH);
        wait.add(content);
        wait.setSize(320, 120);
        wait.setLocationRelativeTo(this);

        final service.SftpBrowseService[] connection = new service.SftpBrowseService[1];
        final Exception[] failure = new Exception[1];

        SwingWorker<Void, Void> connector = new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    connection[0] = service.SftpBrowseService.connect(host, username, password);
                } catch (Exception ex) {
                    failure[0] = ex;
                }
                return null;
            }
            @Override protected void done() {
                wait.dispose();
            }
        };
        connector.execute();
        wait.setVisible(true); // blocks (modal) until connector's done() disposes it

        if (failure[0] != null) {
            JOptionPane.showMessageDialog(this,
                    "Could not connect to " + host + ":\n" + failure[0].getMessage(),
                    "Connection failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String initial = field.getText().trim();
        String selected = DirectoryBrowserDialog.show(this, username + "@" + host, osType,
                new DirectoryBrowserDialog.RemoteProvider(connection[0], initial.isEmpty() ? null : initial));
        if (selected != null) field.setText(selected);
    }

    /** Backup source/destination fields are local or remote depending on their checkbox; resolves the right one. */
    private void browseBackupSide(JTextField field, JCheckBox remoteCheckbox, JTextField usernameField) {
        if (!remoteCheckbox.isSelected()) {
            browseLocal(field, "This Computer");
            return;
        }
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Enter the Username first — it must match a saved credential.",
                    "Browse", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Credential cred = storage.loadCredentialByUsername(username);
        if (cred == null) {
            JOptionPane.showMessageDialog(this,
                    "No saved credential found for username \"" + username + "\".\n"
                            + "Save one first (e.g. via a File Transfer task's Target Credentials tab, "
                            + "or the Credentials page).",
                    "Browse", JOptionPane.WARNING_MESSAGE);
            return;
        }
        browseRemote(field, cred.getHost(), cred.getUsername(), cred.getPassword(), cred.getOsType());
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private JPanel titledPanel(String title) {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD1D9E6)),
                BorderFactory.createCompoundBorder(
                        new TitledBorder(title),
                        new EmptyBorder(10, 10, 10, 10))));
        p.setBackground(Color.WHITE);
        p.setOpaque(true);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private void addRow(JPanel p, String label, JComponent field, int row) {
        p.add(new JLabel(label), labelGbc(row));
        p.add(field, fieldGbc(row));
    }

    private void addRow(JPanel p, JLabel label, JComponent field, int row) {
        p.add(label, labelGbc(row));
        p.add(field, fieldGbc(row));
    }

    private void addRowTo(JPanel p, String label, JComponent field, int row) {
        addRow(p, label, field, row);
    }

    private JLabel hint(String text) {
        // Wrap in a fixed-width div so long sentences wrap onto multiple lines
        // instead of rendering as one unbroken line. Without this, a JLabel's
        // HTML content sizes to its full unwrapped text width, which stretches
        // the GridBagLayout "field" column (weightx=1) — and every text field/
        // combo box sharing that column — far wider than intended.
        JLabel l = new JLabel("<html><div style='width:360px'><i style='color:gray'>" + text + "</i></div></html>");
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        return l;
    }

    private JTextField makeField(JTextField field) {
        field.setColumns(20);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        return field;
    }

    /**
     * Builds the "Additional destinations" field as a dynamic list of rows —
     * one text field per extra destination path, each with a "−" button to
     * remove it, plus a "+ Add destination" button below to append more.
     * Replaces the old single semicolon-separated text field so users don't
     * have to hand-edit a delimited string.
     */
    private JPanel buildAdditionalDestinationsField(ScheduledTask existing) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

        additionalDestRowsContainer = new JPanel();
        additionalDestRowsContainer.setLayout(new BoxLayout(additionalDestRowsContainer, BoxLayout.Y_AXIS));
        additionalDestRowsContainer.setOpaque(false);
        additionalDestRowsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(additionalDestRowsContainer);

        java.util.List<String> initialPaths = existing != null
                ? existing.getAdditionalTargetPathList() : java.util.Collections.emptyList();
        if (initialPaths.isEmpty()) {
            addAdditionalDestinationRow("");
        } else {
            for (String p : initialPaths) addAdditionalDestinationRow(p);
        }

        JButton btnAdd = new JButton("+ Add destination");
        btnAdd.setFocusPainted(false);
        btnAdd.addActionListener(e -> {
            addAdditionalDestinationRow("");
            refreshAdditionalDestinationsLayout();
        });
        JPanel addBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        addBtnRow.setOpaque(false);
        addBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        addBtnRow.add(btnAdd);
        wrapper.add(addBtnRow);

        return wrapper;
    }

    /** Appends one destination row (text field + "−" remove button) to additionalDestRowsContainer. */
    private void addAdditionalDestinationRow(String initialValue) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JTextField tf = new JTextField(initialValue);
        tf.setColumns(24);
        additionalDestFields.add(tf);

        JButton btnRemove = new JButton("\u2212"); // minus sign
        btnRemove.setToolTipText("Remove this destination");
        btnRemove.setFocusPainted(false);
        btnRemove.setMargin(new Insets(0, 8, 0, 8));
        btnRemove.addActionListener(e -> {
            additionalDestFields.remove(tf);
            additionalDestRowsContainer.remove(row);
            refreshAdditionalDestinationsLayout();
        });

        row.add(tf, BorderLayout.CENTER);
        row.add(btnRemove, BorderLayout.EAST);

        additionalDestRowsContainer.add(row);
    }

    /**
     * Re-lays-out the destination rows after one is added or removed.
     *
     * This intentionally does NOT call pack() on the dialog. The dialog's
     * content sits inside a JScrollPane with a fixed preferred size (see
     * buildUI), so extra rows scroll into view instead of resizing the
     * window. Previously this called Window.pack() on every add/remove,
     * which fought with the dialog's minimum size and repeatedly grew it —
     * the dialog is meant to open at a fixed size and only change size if
     * the user deliberately drags an edge (setResizable(true) already
     * allows that).
     */
    private void refreshAdditionalDestinationsLayout() {
        additionalDestRowsContainer.revalidate();
        additionalDestRowsContainer.repaint();
    }

    private JPasswordField makePasswordField(JPasswordField field) {
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        return field;
    }

    private JComboBox<String> makeCombo(JComboBox<String> combo) {
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, combo.getPreferredSize().height));
        return combo;
    }

    private JSpinner makeSpinner(JSpinner spinner) {
        spinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, spinner.getPreferredSize().height));
        return spinner;
    }

    private GridBagConstraints labelGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 8);
        return c;
    }

    private GridBagConstraints fieldGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(4, 0, 4, 4);
        return c;
    }

    private void styleBtn(JButton b, Color bg) {
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.setOpaque(true); b.setBorderPainted(false);
    }

    private void msg(String text) {
        JOptionPane.showMessageDialog(this, text);
    }
}