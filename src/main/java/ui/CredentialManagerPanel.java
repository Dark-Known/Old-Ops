package ui;

import model.Credential;
import service.XmlStorageService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.UUID;

/**
 * Displays all stored server credentials (see {@link service.CredentialDbService},
 * a small SQLite database at {@code <dataDir>/credentials.db}).
 * Passwords are shown and stored as plain text.
 *
 * Admins can add / edit / delete credentials here directly.
 * The ops team can also manage them implicitly via the Task Dialog.
 */
public class CredentialManagerPanel extends JPanel {

    private final XmlStorageService storage;
    private DefaultTableModel tableModel;
    private JTable table;

    public CredentialManagerPanel(XmlStorageService storage) {
        this.storage = storage;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();
    }

    private void buildUI() {
        // ── Info banner ───────────────────────────────────────────────────────
        JLabel banner = new JLabel(
            "<html><b>Server Credentials</b> — stored in <code>credentials.db</code>"
            + " in the data directory.<br>"
            + "<span style='color:gray'>Passwords are plain text. Each Task looks up the"
            + " matching credential by username at run time. \"Tasks Using\" counts how"
            + " many tasks currently reference each username.</span></html>");
        banner.setBorder(new EmptyBorder(0, 0, 6, 0));

        // ── Table ─────────────────────────────────────────────────────────────
        String[] cols = {"Username", "Host", "OS Type", "Display Name", "Tasks Using"};
        tableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
            public Class<?> getColumnClass(int c) { return c == 4 ? Integer.class : String.class; }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(26);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            { setHorizontalAlignment(CENTER); }
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                java.awt.Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                int count = value instanceof Integer ? (Integer) value : 0;
                if (!isSelected) c.setForeground(count > 0 ? new Color(0x1565C0) : new Color(0x9E9E9E));
                return c;
            }
        });
        JScrollPane scroll = new JScrollPane(table);

        // ── Buttons ───────────────────────────────────────────────────────────
        JButton btnAdd     = new JButton("Add Credential");
        JButton btnEdit    = new JButton("Edit");
        JButton btnDelete  = new JButton("Delete");
        JButton btnRefresh = new JButton("Refresh");

        styleButton(btnAdd,    new Color(0x2E7D32));
        styleButton(btnDelete, new Color(0xC62828));

        btnAdd.addActionListener(e -> showDialog(null));
        btnEdit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(this, "Select a credential to edit."); return; }
            int modelRow = table.convertRowIndexToModel(row);
            String username = (String) tableModel.getValueAt(modelRow, 0);
            Credential c = storage.loadCredentialByUsername(username);
            if (c != null) showDialog(c);
        });
        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(this, "Select a credential to delete."); return; }
            int modelRow = table.convertRowIndexToModel(row);
            String username = (String) tableModel.getValueAt(modelRow, 0);
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
        tableModel.setRowCount(0);
        List<Credential> creds = storage.loadAllCredentials();
        for (Credential c : creds) {
            tableModel.addRow(new Object[]{
                c.getUsername(),
                c.getHost(),
                c.getOsType(),
                c.getName(),
                storage.countTasksUsingCredential(c.getUsername())
            });
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
            tfUser.setBackground(new Color(0xF0F0F0));
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

        JButton btnSave   = new JButton("Save");
        JButton btnCancel = new JButton("Cancel");
        styleButton(btnSave, new Color(0x1565C0));
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
            storage.saveCredential(c);
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
        btn.setFocusPainted(false); btn.setOpaque(true); btn.setBorderPainted(false);
    }
}
