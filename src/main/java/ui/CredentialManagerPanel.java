package ui;

import model.Credential;
import model.TaskRunRecord;
import service.RunHistoryService;
import service.XmlStorageService;
import ui.components.IconTile;
import ui.components.PillBadge;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Displays all stored server credentials (see {@link service.CredentialDbService},
 * a small SQLite database at {@code <dataDir>/credentials.db}).
 * Passwords are shown and stored as plain text.
 *
 * Admins can add / edit / delete credentials here directly.
 * The ops team can also manage them implicitly via the Task Dialog.
 *
 * <p>Same {@code JList} + card-row pattern as {@link TaskManagerPanel} (Phase 1
 * of the row-card redesign) — an icon tile (OS type), a two-line text block
 * (display name; username · host · OS type), and a trailing pill showing
 * whether any tasks currently reference this credential.
 */
public class CredentialManagerPanel extends JPanel {

    private final XmlStorageService storage;
    // Nullable — audit trail for credential add/edit/delete, shown in the
    // Event Monitor feed alongside task activity. Null is tolerated (no-op)
    // rather than required, since a credential dialog opened outside the
    // normal MainWindow flow (if any ever exists) shouldn't have to wire one
    // up just to compile.
    private final RunHistoryService auditLog;
    private DefaultListModel<Credential> listModel;
    private JList<Credential> credList;
    // taskId-using-count doesn't live on Credential itself — populated once
    // per refresh() (a DB count query per credential) and looked up by the
    // row renderer, same "compute once, render many" split TaskManagerPanel
    // uses for its own per-row daemon-derived fields.
    private final Map<String, Integer> usageByUsername = new HashMap<>();

    public CredentialManagerPanel(XmlStorageService storage, RunHistoryService auditLog) {
        this.storage = storage;
        this.auditLog = auditLog;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();
    }

    private void buildUI() {
        // ── Info banner ───────────────────────────────────────────────────────
        JLabel banner = new JLabel("<html><b>Server Credentials</b></html>");
        banner.setBorder(new EmptyBorder(0, 0, 6, 0));

        // ── List ──────────────────────────────────────────────────────────────
        listModel = new DefaultListModel<>();
        credList = new JList<>(listModel);
        credList.setCellRenderer(new CredentialRowRenderer());
        credList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        credList.setFixedCellHeight(52);
        JScrollPane scroll = new JScrollPane(credList);

        // ── Buttons ───────────────────────────────────────────────────────────
        // Only "Add Credential" is accent-styled — same one-primary-action
        // restraint as TaskManagerPanel's toolbar; Delete keeps a red-tinted
        // label as its only distinguishing mark instead of a loud gradient.
        JButton btnAdd     = new GradientButton("Add Credential");
        JButton btnEdit    = new JButton("Edit");
        JButton btnDelete  = new JButton("Delete");
        JButton btnRefresh = new JButton("Refresh");

        styleButton(btnAdd, AppTheme.EARTH_MOSS);
        btnDelete.setForeground(AppTheme.EARTH_RUST);

        btnAdd.addActionListener(e -> showDialog(null));
        btnEdit.addActionListener(e -> {
            Credential c = credList.getSelectedValue();
            if (c == null) { JOptionPane.showMessageDialog(this, "Select a credential to edit."); return; }
            showDialog(c);
        });
        btnDelete.addActionListener(e -> {
            Credential c = credList.getSelectedValue();
            if (c == null) { JOptionPane.showMessageDialog(this, "Select a credential to delete."); return; }
            String username = c.getUsername();
            int inUse = storage.countTasksUsingCredential(username);
            String warning = inUse > 0
                ? "\n\nWarning: " + inUse + " task" + (inUse == 1 ? " is" : "s are")
                    + " currently configured to use this credential.\n"
                    + "Deleting it will make " + (inUse == 1 ? "that task" : "those tasks") + " fail at their next run."
                : "";
            int ok = JOptionPane.showConfirmDialog(this,
                "Delete credential for \"" + username + "\"?" + warning,
                "Confirm Delete", JOptionPane.YES_NO_OPTION,
                inUse > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                storage.deleteCredential(username);
                logActivity("Credential deleted", "Deleted credential for " + username
                        + (inUse > 0 ? " (was in use by " + inUse + " task" + (inUse == 1 ? "" : "s") + ")" : ""));
                refresh();
            }
        });
        btnRefresh.addActionListener(e -> refresh());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnPanel.add(btnAdd);
        btnPanel.add(btnEdit);
        btnPanel.add(btnDelete);
        btnPanel.add(btnRefresh);

        add(banner,    BorderLayout.NORTH);
        add(scroll,    BorderLayout.CENTER);
        add(btnPanel,  BorderLayout.SOUTH);

        refresh();
    }

    public void refresh() {
        Credential selected = credList.getSelectedValue();
        String selectedUsername = selected != null ? selected.getUsername() : null;

        listModel.clear();
        usageByUsername.clear();
        List<Credential> creds = storage.loadAllCredentials();
        for (Credential c : creds) {
            usageByUsername.put(c.getUsername(), storage.countTasksUsingCredential(c.getUsername()));
            listModel.addElement(c);
        }

        if (selectedUsername != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (selectedUsername.equals(listModel.get(i).getUsername())) {
                    credList.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** Card row: OS-type icon tile, display name + "username · host · OS type"
     *  meta line, and a trailing pill for in-use vs unused. */
    private class CredentialRowRenderer extends JPanel implements ListCellRenderer<Credential> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final PillBadge usagePill = new PillBadge("", AppTheme.NEUTRAL_FG, null);
        private IconTile currentIcon;

        CredentialRowRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(6, 10, 6, 10));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 11f));
            metaLabel.setForeground(new Color(0x8A8378));

            JPanel textBlock = new JPanel();
            textBlock.setOpaque(false);
            textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
            nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textBlock.add(nameLabel);
            textBlock.add(metaLabel);
            add(textBlock, BorderLayout.CENTER);

            JPanel right = new JPanel(new GridBagLayout());
            right.setOpaque(false);
            right.add(usagePill);
            add(right, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Credential> list, Credential c,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(c.getName() != null && !c.getName().isBlank()
                    ? c.getName() : c.getUsername() + "@" + c.getHost());
            metaLabel.setText(c.getUsername() + "  \u00b7  " + c.getHost()
                    + "  \u00b7  " + (c.getOsType() != null ? c.getOsType() : "?"));

            boolean windows = "WINDOWS".equalsIgnoreCase(c.getOsType());
            String glyph = windows ? "W" : "L";
            Color tileBg = windows ? AppTheme.EARTH_TEAL : AppTheme.EARTH_OCHRE;
            if (currentIcon != null) remove(currentIcon);
            currentIcon = new IconTile(glyph, Color.WHITE, tileBg);
            add(currentIcon, BorderLayout.WEST);

            int inUse = usageByUsername.getOrDefault(c.getUsername(), 0);
            if (inUse > 0) {
                usagePill.setText(inUse + (inUse == 1 ? " task" : " tasks"));
                usagePill.setColors(AppTheme.SUCCESS_FG, AppTheme.SUCCESS_BG);
            } else {
                usagePill.setText("Unused");
                usagePill.setColors(AppTheme.NEUTRAL_FG, null);
            }

            if (isSelected) {
                // Same fix as TaskManagerPanel's row renderer: the pale
                // (fg, bg) pairs above are tuned for the row's normal
                // background, not the selection highlight — force
                // high-contrast selection colors instead of leaving text/pill
                // colors fixed regardless of what's now behind them.
                nameLabel.setForeground(list.getSelectionForeground());
                metaLabel.setForeground(list.getSelectionForeground());
                usagePill.setColors(list.getSelectionForeground(), null);
            } else {
                nameLabel.setForeground(UIManager.getColor("List.foreground"));
                metaLabel.setForeground(new Color(0x8A8378));
            }

            setBackground(isSelected ? list.getSelectionBackground()
                    : (index % 2 == 0 ? list.getBackground() : AppTheme.surface2()));
            setOpaque(true);
            revalidate();
            return this;
        }
    }

    /**
     * Records an application-activity note (credential added/edited/deleted)
     * into the Event Monitor's activity feed — see
     * {@link RunHistoryService#recordActivityEvent}. Uses the fixed
     * pseudo-task-id "CREDENTIALS" since these aren't tied to any one
     * scheduled task. No-op if this panel was constructed without an audit
     * log reference.
     */
    private void logActivity(String title, String detail) {
        if (auditLog == null) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            auditLog.recordActivityEvent("CREDENTIALS", title, null, detail, detail, now, now);
        } catch (Exception ignored) {
            // Best-effort — a failure to log this shouldn't block the actual credential change.
        }
    }

    /** Same as {@link #logActivity} but marks the event FAILED, so it renders
     *  as a failure (red, "Failed: ...") in the feed instead of a neutral note. */
    private void logActivityFailed(String title, String detail) {
        if (auditLog == null) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            auditLog.recordActivityEvent("CREDENTIALS", title, null,
                    model.TaskRunRecord.Status.FAILED, detail, detail, now, now);
        } catch (Exception ignored) {
            // Best-effort — a failure to log this shouldn't block the actual credential change.
        }
    }

    private void showDialog(Credential existing) {
        JDialog dlg = new JDialog(
            (Frame) SwingUtilities.getWindowAncestor(this),
            existing == null ? "Add Credential" : "Edit Credential", true);
        dlg.setSize(460, 380);
        dlg.setLocationRelativeTo(this);
        dlg.setLayout(new BorderLayout(10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(15, 15, 5, 15));

        JTextField    tfHost = new JTextField(existing != null ? existing.getHost()     : "", 22);
        JTextField    tfUser = new JTextField(existing != null ? existing.getUsername() : "", 22);
        JPasswordField tfPass = new JPasswordField(22);
        if (existing != null && existing.getPassword() != null)
            tfPass.setText(existing.getPassword());
        JComboBox<String> cbOs = new JComboBox<>(new String[]{"WINDOWS", "LINUX"});
        if (existing != null) cbOs.setSelectedItem(existing.getOsType());

        addFormRow(form, "Hostname / IP *", tfHost, 0);
        addFormRow(form, "Username *",       tfUser, 1);
        // Make username read-only when editing (it is the key)
        if (existing != null) {
            tfUser.setEditable(false);
            tfUser.setBackground(AppTheme.isDark() ? new Color(0x3A3A3A) : new Color(0xF0F0F0));
        }
        addFormRow(form, "Password *",  tfPass, 2);
        addFormRow(form, "OS Type *",   cbOs,   3);

        JButton btnTest = new JButton("Test Connection");
        btnTest.setToolTipText("Opens a real SFTP session with the fields above to verify they work");
        btnTest.addActionListener(e -> {
            String host = tfHost.getText().trim();
            String user = tfUser.getText().trim();
            String pass = new String(tfPass.getPassword());
            if (host.isEmpty() || user.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "Enter a Hostname / IP and Username first.");
                return;
            }
            TestConnectionDialog.show(dlg, host, user, pass);
        });
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testRow.add(btnTest);
        addFormRow(form, "", testRow, 4);

        addFormRow(form, "",
            new JLabel("<html><i style='color:#888'>Password stored as plain text in"
                + " credentials.db</i></html>"), 5);

        JButton btnSave   = new GradientButton("Save");
        JButton btnCancel = new JButton("Cancel");
        styleButton(btnSave, AppTheme.EARTH_SIENNA);
        btnCancel.addActionListener(e -> dlg.dispose());
        btnSave.addActionListener(e -> {
            String host = tfHost.getText().trim();
            String user = tfUser.getText().trim();
            String pass = new String(tfPass.getPassword());
            if (host.isEmpty() || user.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "Host and Username are required."); return;
            }
            if (pass.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "Password is required."); return;
            }
            Credential c = existing != null ? existing : new Credential();
            if (c.getId() == null) c.setId(UUID.randomUUID().toString());
            c.setName(user + "@" + host);
            c.setHost(host);
            c.setUsername(user);
            c.setPassword(pass);
            c.setOsType((String) cbOs.getSelectedItem());
            // Same silent-failure trap as tasks (see TaskDialog#save): a disconnected
            // app.db used to make this look like it saved fine, then the credential
            // was just gone. Check the return value and keep the dialog open on failure.
            boolean saved = storage.saveCredential(c);
            if (!saved) {
                if (storage.isConnected()) {
                    String detail = storage.getLastCredentialSaveError();
                    logActivityFailed("Credential save failed",
                            "Could not save credential for " + user + "@" + host
                                    + " — database connected, but this write failed"
                                    + (detail != null ? ": " + detail : "."));
                    JOptionPane.showMessageDialog(dlg,
                            "Could not save this credential — the database is connected, but this "
                                    + "write failed" + (detail != null ? (":\n" + detail) : ".")
                                    + "\n\nYour changes have NOT been saved. This is often transient — "
                                    + "try again.",
                            "Save failed", JOptionPane.ERROR_MESSAGE);
                } else {
                    String detail = storage.getConnectionError();
                    logActivityFailed("Credential save failed",
                            "Could not save credential for " + user + "@" + host
                                    + " — database not connected"
                                    + (detail != null ? ": " + detail : "."));
                    JOptionPane.showMessageDialog(dlg,
                            "Could not save this credential — the database is not connected"
                                    + (detail != null ? (":\n" + detail) : ".")
                                    + "\n\nYour changes have NOT been saved.",
                            "Save failed", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
            logActivity(existing == null ? "Credential added" : "Credential edited",
                    (existing == null ? "Added credential for " : "Edited credential for ")
                            + user + "@" + host + " (" + c.getOsType() + ")");
            refresh();
            dlg.dispose();
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.setBorder(new EmptyBorder(0, 10, 10, 10));
        btnRow.add(btnCancel);
        btnRow.add(btnSave);

        dlg.add(form,   BorderLayout.CENTER);
        dlg.add(btnRow, BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    private void addFormRow(JPanel p, String label, JComponent field, int row) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(6, 0, 6, 10);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = row;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1;
        fc.insets = new Insets(6, 0, 6, 0);
        p.add(new JLabel(label), lc);
        p.add(field, fc);
    }

    private void styleButton(JButton btn, Color bg) {
        btn.setBackground(bg); btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false); btn.setBorderPainted(false);
    }
}
