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
 *  LOCAL-TO-LOCAL MODE
 *  ───────────────────
 *  When the transfer direction is INBOUND and the target host field is left blank
 *  (or explicitly matches the local hostname / "localhost" / "127.0.0.1"), the
 *  dialog treats the task as a local-to-local copy.  In that case:
 *    • The "Target System Credentials" tab is still shown but its fields are
 *      optional — validation is skipped for host/user/pass.
 *    • A blue info banner ("Local→Local mode") is shown in the Transfer tab so
 *      the operator knows no SFTP connection will be opened.
 *    • The source path label changes to "Watch Folder (source) *" and the target
 *      folder label to "Destination Folder *".
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
    private JCheckBox   cbWatcherEnabled;
    private JLabel      lblWatcherInfo;
    private JLabel      lblWatcherStatus;
    private JButton     btnResetBaseline;
    private JPanel      transferTab;
    // Watcher status row panel (made a field so visibility can be toggled reliably)
    private JPanel      watcherStatusRow;

    // ── Local-to-local banner (shown when target host is local / blank) ───────
    private JLabel lblLocalToLocalBanner;

    // ── Service panel ─────────────────────────────────────────────────────────
    private JTextField  tfServiceName;

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
    private JCheckBox cbMailMoveEnabled;
    private JLabel     lblMailMoveFolder;
    private JTextField tfMailMoveFolder;
    private JLabel     lblMailMoveFolderHint;
    private JTextField tfMailOutputFolder;

    // ── Schedule ──────────────────────────────────────────────────────────────
    private JComboBox<String> cbScheduleType;
    private JTextField  tfScheduledAt;
    private JTextField  tfInterval;
    private JComboBox<String> cbDayOfWeek;
    private JTextField  tfTime;

    private JPanel fileTransferPanel;
    private JPanel servicePanel;
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
                "FILE_TRANSFER", "OUTLOOK_MAIL",
                "START_SERVICE", "STOP_SERVICE", "RESTART_SERVICE"}));
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
        addRow(sourcePanel, lblSourcePath = new JLabel(), tfSourcePath, 2);
        lblSourceHint = hint("");
        addRow(sourcePanel, "", lblSourceHint, 3);

        // ── File Transfer panel ───────────────────────────────────────────────
        fileTransferPanel = titledPanel("File Transfer — Destination");

        cbTransferDirection = makeCombo(new JComboBox<>(new String[]{"OUTBOUND", "INBOUND", "LOCAL_TO_LOCAL"}));
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

        // ── Local-to-local info banner ────────────────────────────────────────
        // Visible whenever INBOUND is selected AND the target host looks local.
        // Informs the operator that no SFTP connection will be opened and that
        // files are copied using Files.copy (LocalFileMetadataService path).
        lblLocalToLocalBanner = new JLabel(
                "<html><div style='width:380px'><b style='color:#0D47A1'>Info: Local->Local mode:</b> "
                        + "<i style='color:#1565C0'>Target host is local - files will be discovered with "
                        + "LocalFileMetadataService and copied with Files.copy. No SFTP or WinSCP needed. "
                        + "Leave the Target Credentials tab blank.</i></div></html>");
        lblLocalToLocalBanner.setFont(lblLocalToLocalBanner.getFont().deriveFont(Font.PLAIN, 11f));
        lblLocalToLocalBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 3, 1, 1, new Color(0x1565C0)),
                new EmptyBorder(4, 6, 4, 4)));
        lblLocalToLocalBanner.setBackground(new Color(0xE3F2FD));
        lblLocalToLocalBanner.setOpaque(true);
        lblLocalToLocalBanner.setVisible(false);

        lblTargetFolder = new JLabel();
        lblTargetHint   = hint("");

        // Row layout — rows 8, 9 are the new local-to-local banner + baseline row
        addRow(fileTransferPanel, "Transfer Direction *",    cbTransferDirection,    0);
        addRow(fileTransferPanel, "Transfer Mode *",         cbTransferMode,         1);
        addRow(fileTransferPanel, lblTargetFolder,           tfTargetFolder,         2);
        addRow(fileTransferPanel, "",                        lblTargetHint,          3);
        addRow(fileTransferPanel, "",                        cbWatcherEnabled,4);
        addRow(fileTransferPanel, "",                        lblWatcherInfo,         5);
        addRow(fileTransferPanel, "Watcher baseline",        watcherStatusRow,       6);
        // Local-to-local banner spans both columns
        GridBagConstraints bannerGbc = new GridBagConstraints();
        bannerGbc.gridx = 0; bannerGbc.gridy = 9; bannerGbc.gridwidth = 2;
        bannerGbc.fill = GridBagConstraints.HORIZONTAL;
        bannerGbc.insets = new Insets(6, 4, 4, 4);
        fileTransferPanel.add(lblLocalToLocalBanner, bannerGbc);

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

        // ── Service panel ─────────────────────────────────────────────────────
        servicePanel  = titledPanel("Service Control");
        tfServiceName = makeField(new JTextField(
                existing != null && existing.getServiceName() != null ? existing.getServiceName() : "", 28));
        addRow(servicePanel, "Service Name *", tfServiceName, 0);
        addRow(servicePanel, "", hint("Windows service name, e.g.  Spooler,  W3SVC"), 1);

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

        cbMailMoveEnabled = new JCheckBox("Move processed messages to another folder");
        if (existing != null) cbMailMoveEnabled.setSelected(existing.isMailMoveToFolderEnabled());
        addRow(mailPanel, "", cbMailMoveEnabled, 18);

        tfMailMoveFolder = makeField(new JTextField(
                existing != null && existing.getMailMoveToFolderName() != null
                        ? existing.getMailMoveToFolderName() : "", 28));
        lblMailMoveFolder = new JLabel("Destination Folder");
        addRow(mailPanel, lblMailMoveFolder, tfMailMoveFolder, 19);
        lblMailMoveFolderHint = hint("Any folder name (e.g. \"Processed\") or a well-known name: SENT, DRAFTS, DELETED, JUNK, ARCHIVE. Applied after mark-as-read, since moving changes the message's ID.");
        addRow(mailPanel, "", lblMailMoveFolderHint, 20);

        // ── Output folder (.RCV files) ──────────────────────────────────────
        tfMailOutputFolder = makeField(new JTextField(
                existing != null && existing.getMailOutputFolder() != null
                        ? existing.getMailOutputFolder() : "", 28));
        addRow(mailPanel, "Output Folder *", tfMailOutputFolder, 21);
        addRow(mailPanel, "", hint("Local directory where each fetched message is written as a .RCV file. Created automatically if it doesn't exist yet."), 22);

        cbMailMoveEnabled.addActionListener(e -> updateMailMoveFieldsVisibility());
        updateMailMoveFieldsVisibility();

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
                // Recheck local-to-local banner whenever the user types a host
                updateLocalToLocalBanner();
            }
        });

        // Also recheck banner when tfTargetHost is edited directly
        tfTargetHost.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate (javax.swing.event.DocumentEvent e) { updateLocalToLocalBanner(); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { updateLocalToLocalBanner(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateLocalToLocalBanner(); }
        });

        addRow(targetPanel, "Hostname / IP",  tfTargetHost, 0);
        addRow(targetPanel, "Username",        tfTargetUser, 1);
        addRow(targetPanel, "Password",        pfTargetPass, 2);
        addRow(targetPanel, "OS Type",         cbTargetOs,   3);
        addRow(targetPanel, "",
                hint("For local→local tasks these fields can be left blank. "
                        + "Password is saved in plain text in creds_<username>.xml"), 4);

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
        tabbedPane.addTab("Service",   servicePanel);
        tabbedPane.addTab("Target",    targetPanel);
        tabbedPane.addTab("Mail/IMAP", mailPanel);
        tabbedPane.addTab("Schedule",  sched);
        tabbedPane.addTab("Retry",     retryPanel);

        main.add(tabbedPane, BorderLayout.CENTER);

        // ── Wire listeners ────────────────────────────────────────────────────
        cbTransferDirection.addActionListener(e -> {
            updateTransferDirectionLabels();
            updateWatcherFieldsVisibility();
            updateLocalToLocalBanner();
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
        updateLocalToLocalBanner();
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

    // ── Local-to-local banner ─────────────────────────────────────────────────

    /**
     * Shows the local-to-local info banner when:
     *   • Direction is INBOUND, AND
     *   • The target host field is blank / "localhost" / "127.0.0.1" / equals
     *     the local machine's hostname.
     *
     * Also updates the source-path label to make the "watch folder" intent clear.
     */
    private void updateLocalToLocalBanner() {
        if (lblLocalToLocalBanner == null) return;
        String direction = (String) cbTransferDirection.getSelectedItem();
        boolean inbound     = "INBOUND".equals(direction);
        boolean localTarget = isLocalTargetHost();
        boolean localToLocal = "LOCAL_TO_LOCAL".equals(direction);
        boolean show        = inbound && localTarget && !localToLocal;
        lblLocalToLocalBanner.setVisible(show);

        if (show && lblSourcePath != null) {
            lblSourcePath.setText("Watch Folder (source) *");
            lblTargetFolder.setText("Destination Folder *");
        }

        if (transferTab != null) { transferTab.revalidate(); transferTab.repaint(); }
        revalidate(); repaint();
        pack();
    }

    /** Returns true when the currently entered target host looks local. */
    private boolean isLocalTargetHost() {
        String host = tfTargetHost != null ? tfTargetHost.getText().trim() : "";
        if (host.isEmpty() || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"))
            return true;
        String localHost = TransferService.getLocalHostname();
        return localHost != null && localHost.equalsIgnoreCase(host);
    }

    // ── Visibility helpers ────────────────────────────────────────────────────

    private void updateVisibility() {
        String type        = (String) cbTaskType.getSelectedItem();
        boolean isTransfer = "FILE_TRANSFER".equals(type);
        boolean isMail     = "OUTLOOK_MAIL".equals(type);
        boolean isService  = !isTransfer && !isMail;

        enableTabIfPresent(transferTab,   isTransfer);
        enableTabIfPresent(servicePanel,  isService);
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
        pack();
    }

    private void updateTransferDirectionLabels() {
        String direction = (String) cbTransferDirection.getSelectedItem();
        boolean inbound = "INBOUND".equals(direction);
        boolean localToLocal = "LOCAL_TO_LOCAL".equals(direction);

        if (localToLocal) {
            lblSourcePath.setText("Source Folder *");
            lblTargetFolder.setText("Destination Folder *");
        } else if (!isLocalTargetHost()) {
            lblSourcePath.setText(inbound ? "Local Destination Path *" : "Source Path *");
            lblTargetFolder.setText(inbound ? "Remote Source Folder *" : "Destination Folder *");
        }

        if (localToLocal) {
            lblSourceHint.setText("<html><i style='color:gray'>Local source folder containing files to transfer.</i></html>");
            lblTargetHint.setText("<html><i style='color:gray'>Local destination folder where files will be copied.</i></html>");
        } else {
            lblSourceHint.setText(inbound
                    ? "<html><i style='color:gray'>Local path where files retrieved from the target will be saved.</i></html>"
                    : "<html><i style='color:gray'>Local file or folder to send to the target server.</i></html>");
            lblTargetHint.setText(inbound
                    ? "<html><i style='color:gray'>Remote source file or folder path on the target server "
                    + "(or local source folder for local→local copies).</i></html>"
                    : "<html><i style='color:gray'>Remote destination folder on the target server.</i></html>");
        }

        // Watcher checkbox is available for both directions (watching is supported for inbound and outbound
        // transfers). Keep the checkbox visible whenever the Transfer tab is shown.
        cbWatcherEnabled.setVisible(true);
        transferTab.revalidate(); transferTab.repaint();
        revalidate(); repaint();
        pack();
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
        pack();
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
        boolean localToLocal = "LOCAL_TO_LOCAL".equals(cbTransferDirection.getSelectedItem());
        enableTabIfPresent(targetPanel, !localToLocal);
        if (localToLocal && tabbedPane.getSelectedComponent() == targetPanel) {
            tabbedPane.setSelectedComponent(transferTab);
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

    /** The destination folder field only matters when "move to another folder" is checked. */
    private void updateMailMoveFieldsVisibility() {
        boolean moveOn = cbMailMoveEnabled.isSelected();
        lblMailMoveFolder.setVisible(moveOn);
        tfMailMoveFolder.setVisible(moveOn);
        tfMailMoveFolder.setEnabled(moveOn);
        lblMailMoveFolderHint.setVisible(moveOn);
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
                success -> updateMailAuthStatus())
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

        // For local→local FILE_TRANSFER tasks credentials are not required.
        boolean isLocalToLocal = isTransfer
                && ("INBOUND".equals(cbTransferDirection.getSelectedItem())
                    || "LOCAL_TO_LOCAL".equals(cbTransferDirection.getSelectedItem()))
                && isLocalTargetHost();

        String targetHost = tfTargetHost.getText().trim();
        String targetUser = tfTargetUser.getText().trim();
        String targetPass = new String(pfTargetPass.getPassword());

        // Mail tasks use Microsoft Graph (OAuth2), never the SFTP-style target credential.
        if (!isMail && !isLocalToLocal) {
            if (targetHost.isEmpty()) { msg("Target hostname / IP is required."); return; }
            if (targetUser.isEmpty()) { msg("Target username is required.");       return; }
            if (targetPass.isEmpty()) { msg("Target password is required.");       return; }
        }

        if (isTransfer) {
            if (tfSourcePath.getText().trim().isEmpty()) {
                msg(isLocalToLocal ? "Watch folder (source) is required." : "Source path is required.");
                return;
            }
            if (tfTargetFolder.getText().trim().isEmpty()) {
                msg(isLocalToLocal ? "Destination folder is required." : "Destination folder is required.");
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
            if (cbMailMoveEnabled.isSelected() && tfMailMoveFolder.getText().trim().isEmpty()) {
                msg("Enter the destination folder name, or uncheck \"Move processed messages\".");
                return;
            }
        } else {
            if (tfServiceName.getText().trim().isEmpty()) { msg("Service name is required."); return; }
        }

        // ── Persist credential file creds_<username>.xml ──────────────────────
        // Skipped for mail (uses OAuth2, not a stored credential) and local→local tasks.
        if (!isMail && !isLocalToLocal && !targetUser.isEmpty()) {
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

        // For local→local and mail tasks targetUsername is left blank — mail uses
        // OAuth2/Graph and local→local routes to LocalFileMetadataService via a
        // null credential, per resolveTargetCredential()/isLocalToLocalTask().
        t.setTargetUsername(!isMail && !isLocalToLocal ? targetUser : "");
        t.setSourceCredentialId(
                tfSourceUser.getText().trim() + "@" + tfSourceHost.getText().trim());

        if (isTransfer) {
            t.setTransferDirection(TransferDirection.valueOf(
                    (String) cbTransferDirection.getSelectedItem()));
            t.setTransferMode(TransferMode.valueOf(
                    (String) cbTransferMode.getSelectedItem()));
            t.setSourcePath(tfSourcePath.getText().trim());
            t.setTargetPath(tfTargetFolder.getText().trim());
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
            t.setMailMoveToFolderEnabled(cbMailMoveEnabled.isSelected());
            t.setMailMoveToFolderName(tfMailMoveFolder.getText().trim());
            t.setMailOutputFolder(tfMailOutputFolder.getText().trim());
            if (mailWatcherEpochShouldReset) {
                t.setMailLastKnownEpoch(0L);
            }
        } else {
            t.setServiceName(tfServiceName.getText().trim());
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