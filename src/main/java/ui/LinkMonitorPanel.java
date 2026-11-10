package ui;

import service.LinkMonitor;
import service.LinkMonitorConfig;
import service.LinkStatusFile;
import service.LinkTls;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Settings section for the socket link monitor: the servers whose applications report their socket status to this
 * machine. End users create a source here, get its access token once, and paste the ready-made settings into the
 * sending application; nothing is edited by hand. The Daemon serves the endpoint and picks changes up within a minute.
 */
public class LinkMonitorPanel extends JPanel {

    private static final String[] COLUMNS = {"Name", "Status", "Last heard", "Enabled", "Heartbeat", "Silent after", "Token issued", "Previous token"};

    private final File dataDir;
    private final SourceTableModel model = new SourceTableModel();
    private final JTable table = new JTable(model);
    private final JTextArea lblEndpoint = new JTextArea();
    private final JLabel lblCert = new JLabel();
    private final JButton btnEdit = new JButton("Edit...");
    private final JButton btnRegen = new JButton("New token...");
    private final JButton btnDelete = new JButton("Delete");
    private final JButton btnExport = new JButton("Export certificate...");
    private LinkMonitorConfig config = LinkMonitorConfig.empty();
    private LinkStatusFile.Status status;

    // ── Loading ───────────────────────────────────────────────────────────
    private javax.swing.Timer refreshTimer;

    public LinkMonitorPanel(File dataDir) {
        this.dataDir = dataDir;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
        info.setOpaque(false);
        JPanel endpointRow = new JPanel(new BorderLayout(8, 0));
        endpointRow.setOpaque(false);
        lblEndpoint.setEditable(false);
        lblEndpoint.setFocusable(false);
        lblEndpoint.setOpaque(false);
        lblEndpoint.setLineWrap(true);
        lblEndpoint.setWrapStyleWord(false);
        lblEndpoint.setFont(new JLabel().getFont());
        endpointRow.add(lblEndpoint, BorderLayout.CENTER);
        JButton btnEndpoint = new JButton("Port & names...");
        btnEndpoint.setToolTipText("Change the port, or add the host names / addresses server A will use to reach this PC");
        btnEndpoint.addActionListener(e -> editEndpoint());
        endpointRow.add(btnEndpoint, BorderLayout.EAST);
        JPanel certRow = new JPanel(new BorderLayout(8, 0));
        certRow.setOpaque(false);
        certRow.add(lblCert, BorderLayout.CENTER);
        btnExport.setToolTipText("Copy the certificate to a file you can install as trusted on the reporting server");
        btnExport.addActionListener(e -> exportCertificate());
        certRow.add(btnExport, BorderLayout.EAST);
        info.add(endpointRow);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(info, BorderLayout.CENTER);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int[] widths = {190, 170, 80, 60, 80, 80, 120, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(560, 130));

        JButton btnAdd = new JButton("Add source...");
        btnAdd.addActionListener(e -> addSource());
        btnEdit.addActionListener(e -> editSelected());
        btnRegen.setToolTipText("Issue a new access token for this source");
        btnRegen.addActionListener(e -> regenerate());
        btnDelete.addActionListener(e -> deleteSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(btnAdd);
        buttons.add(btnEdit);
        buttons.add(btnRegen);
        buttons.add(btnDelete);

        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        reload();
    }

    private static void addRow(JPanel p, String label, JComponent field, int row) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(4, 0, 4, 10);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(4, 0, 4, 0);
        p.add(new JLabel(label), l);
        p.add(field, f);
    }

    private static String ago(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 90) return sec + " s ago";
        if (sec < 5400) return (sec / 60) + " min ago";
        return (sec / 3600) + " h ago";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (refreshTimer == null) {
            refreshTimer = new javax.swing.Timer(5000, e -> reload());
            refreshTimer.start();
        }
    }

    @Override
    public void removeNotify() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
        super.removeNotify();
    }

    /**
     * Re-reads the settings and the certificate file (off the UI thread), then redraws.
     */
    public void reload() {
        Async.latest(this, () -> new Loaded(LinkMonitorConfig.load(dataDir), LinkTls.describe(dataDir), LinkStatusFile.read(dataDir)),
                this::apply, e -> System.err.println("Socket link settings could not be read: " + e));
    }

    private void apply(Loaded l) {
        config = l.config();
        status = l.status();
        String selected = selectedId();
        model.fireTableDataChanged();
        if (selected != null) {
            for (int i = 0; i < config.sources.size(); i++) {
                if (config.sources.get(i).id().equals(selected)) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        lblEndpoint.setText("Address for the sender: " + addressList()
                + (config.tlsEnabled ? "" : "  (HTTPS is off)"));
        lblEndpoint.setForeground(UIManager.getColor("Label.foreground"));
        if (!config.tlsEnabled) {
            lblCert.setText("<html><b>Certificate:</b> not used</html>");
            btnExport.setEnabled(false);
        } else if (l.cert() == null) {
            lblCert.setText("<html><b>Certificate:</b> created automatically when the first source is active "
                    + "(background service running)</html>");
            btnExport.setEnabled(false);
        } else {
            lblCert.setText("<html><b>Certificate:</b> valid until " + new SimpleDateFormat("yyyy-MM-dd").format(l.cert().notAfter())
                    + " &nbsp; SHA-256 " + l.cert().sha256Fingerprint().substring(0, 23) + "...</html>");
            lblCert.setToolTipText("SHA-256 fingerprint: " + l.cert().sha256Fingerprint());
            btnExport.setEnabled(true);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean has = selectedId() != null;
        btnEdit.setEnabled(has);
        btnRegen.setEnabled(has);
        btnDelete.setEnabled(has);
    }

    private String selectedId() {
        int row = table.getSelectedRow();
        return row >= 0 && row < config.sources.size() ? config.sources.get(row).id() : null;
    }

    // ── Actions ───────────────────────────────────────────────────────────

    /**
     * Every name or address the sender can use, as full URLs (loopback left out: it only works on this PC).
     */
    private String addressList() {
        String scheme = config.tlsEnabled ? "https://" : "http://";
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (status != null) {
            for (String a : status.addresses()) {
                if (!a.contains("//localhost") && !a.contains("//127.")) out.add(a);
            }
        }
        if (out.isEmpty()) {
            out.add(scheme + preferredHost() + ":" + config.port);
            for (String h : config.tlsHostnames) out.add(scheme + h + ":" + config.port);
        }
        return String.join("  or  ", out);
    }

    private String preferredHost() {
        if (!config.tlsHostnames.isEmpty()) return config.tlsHostnames.get(0);
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "this-pc";
        }
    }

    private void addSource() {
        SourceForm form = new SourceForm(null);
        if (!form.show("Add source")) return;
        LinkMonitorConfig current = LinkMonitorConfig.load(dataDir);
        LinkMonitorConfig.Issued issued = current.addSource(form.name(), form.heartbeat(), form.silent(), form.remind(),
                form.flapCount(), form.flapWindow(), System.currentTimeMillis());
        if (!save(issued.config())) return;
        showToken(issued.config().source(issued.sourceId()), issued.token(), "Source added");
    }

    private void editSelected() {
        String id = selectedId();
        if (id == null) return;
        LinkMonitorConfig current = LinkMonitorConfig.load(dataDir);
        LinkMonitor.Source s = current.source(id);
        if (s == null) {
            reload();
            return;
        }
        SourceForm form = new SourceForm(s);
        if (!form.show("Edit source")) return;
        save(current.updateSource(id, form.name(), form.enabled(), form.heartbeat(), form.silent(), form.remind(),
                form.flapCount(), form.flapWindow()));
    }

    private void regenerate() {
        String id = selectedId();
        if (id == null) return;
        LinkMonitorConfig current = LinkMonitorConfig.load(dataDir);
        LinkMonitor.Source s = current.source(id);
        if (s == null) {
            reload();
            return;
        }
        Object[] options = {"Keep the old token for 24 hours", "Stop the old token now", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "<html><div style='width:420px'>Issue a new access token for <b>" + escape(s.label()) + "</b>?<br><br>"
                        + "The reporting application must be updated with the new token. Keeping the old token working "
                        + "for 24 hours lets you do that without a gap in monitoring. Stop it now if the old token may "
                        + "have leaked.</div></html>",
                "New token", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice != 0 && choice != 1) return;
        LinkMonitorConfig.Issued issued = current.regenerateToken(id,
                choice == 0 ? LinkMonitorConfig.DEFAULT_GRACE_MILLIS : 0, System.currentTimeMillis());
        if (!save(issued.config())) return;
        showToken(issued.config().source(id), issued.token(), "New token issued");
    }

    private void deleteSelected() {
        String id = selectedId();
        if (id == null) return;
        LinkMonitorConfig current = LinkMonitorConfig.load(dataDir);
        LinkMonitor.Source s = current.source(id);
        if (s == null) {
            reload();
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "<html><div style='width:380px'>Delete <b>" + escape(s.label()) + "</b>? Its token stops working at once and "
                        + "the application on that server will get \"unauthorised\" until it is given a new source.</div></html>",
                "Delete source", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) save(current.removeSource(id));
    }

    private void editEndpoint() {
        LinkMonitorConfig current = LinkMonitorConfig.load(dataDir);
        JSpinner port = new JSpinner(new SpinnerNumberModel(current.port, 1, 65535, 1));
        JTextField names = new JTextField(String.join(", ", current.tlsHostnames), 28);
        JPanel p = new JPanel(new GridBagLayout());
        addRow(p, "Port:", port, 0);
        addRow(p, "Extra names / addresses:", names, 1);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(8, 0, 0, 0);
        p.add(new JLabel("<html><div style='width:360px'><i>The certificate is valid for this PC's name and addresses. If the "
                + "reporting server reaches this PC by another name (for example a DNS alias), add it here. Changing the "
                + "names creates a new certificate, which the reporting server then has to trust again. Open the port "
                + "in Windows Firewall for the reporting server.</i></div></html>"), c);
        if (JOptionPane.showConfirmDialog(this, p, "Endpoint", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        List<String> list = new ArrayList<>();
        for (String n : names.getText().split("[,;\\s]+")) if (!n.isBlank()) list.add(n.trim());
        save(current.withEndpoint((Integer) port.getValue(), list));
    }

    private void exportCertificate() {
        File cer = LinkTls.certificateFile(dataDir);
        if (!cer.isFile()) {
            reload();
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save the certificate for the reporting server");
        fc.setSelectedFile(new File(cer.getName()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(cer.toPath(), fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(this, "Saved. Install it as a trusted certificate on the reporting server "
                    + "(see the Socket link API document).", "Certificate saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not save the certificate: " + e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Source dialog ─────────────────────────────────────────────────────

    private boolean save(LinkMonitorConfig next) {
        try {
            next.save(dataDir);
            reload();
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not save the settings: " + e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Shows the token ONCE, with the ready-to-paste settings for the reporting application.
     */
    private void showToken(LinkMonitor.Source s, String token, String title) {
        String url = (config.tlsEnabled ? "https://" : "http://") + preferredHost() + ":" + config.port;
        String block = "LINK_MONITOR_URL=" + url + "\n"
                + "LINK_MONITOR_TOKEN=" + token + "\n"
                + "LINK_HEARTBEAT_SECS=" + s.heartbeatSeconds() + "\n";
        JTextArea area = new JTextArea(block, 4, 56);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        JButton copyToken = new JButton("Copy token");
        copyToken.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(token), null));
        JButton copyAll = new JButton("Copy all");
        copyAll.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(block), null));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(copyToken);
        row.add(copyAll);

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html><div style='width:520px'><b>" + escape(s.label()) + "</b><br>This token is shown <b>only once</b> "
                + "(only its hash is stored). Put these settings in the reporting application. If you lose the token, use "
                + "\"New token\".</div></html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        p.add(row, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, p, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private record Loaded(LinkMonitorConfig config, LinkTls.Material cert, LinkStatusFile.Status status) {
    }

    private final class SourceForm {
        private final JTextField name = new JTextField(26);
        private final JCheckBox enabled = new JCheckBox("Monitor this source");
        private final JSpinner heartbeat = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
        private final JSpinner silent = new JSpinner(new SpinnerNumberModel(180, 10, 86400, 10));
        private final JSpinner remind = new JSpinner(new SpinnerNumberModel(30, 0, 1440, 5));
        private final JSpinner flapCount = new JSpinner(new SpinnerNumberModel(5, 2, 100, 1));
        private final JSpinner flapWindow = new JSpinner(new SpinnerNumberModel(10, 1, 1440, 1));
        private final boolean editing;

        SourceForm(LinkMonitor.Source s) {
            editing = s != null;
            if (s != null) {
                name.setText(s.label());
                enabled.setSelected(s.enabled());
                heartbeat.setValue(s.heartbeatSeconds());
                silent.setValue(s.silentAfterSeconds());
                remind.setValue(s.repeatMinutes());
                flapCount.setValue(s.flapCount());
                flapWindow.setValue(s.flapWindowMinutes());
            } else {
                enabled.setSelected(true);
            }
            heartbeat.addChangeListener(e -> {
                if (!editing) silent.setValue(((Integer) heartbeat.getValue()) * 3);
            });
        }

        boolean show(String title) {
            JPanel p = new JPanel(new GridBagLayout());
            addRow(p, "Name:", name, 0);
            name.setToolTipText("What this connection is, e.g. \"Server A to Server B order feed\"");
            addRow(p, "Heartbeat every (seconds):", heartbeat, 1);
            addRow(p, "Alert if nothing for (seconds):", silent, 2);
            addRow(p, "Remind while unhealthy (minutes, 0 = never):", remind, 3);
            addRow(p, "Alert for repeated drops: drops", flapCount, 4);
            addRow(p, "within (minutes):", flapWindow, 5);
            if (editing) addRow(p, "", enabled, 6);
            while (true) {
                if (JOptionPane.showConfirmDialog(LinkMonitorPanel.this, p, title, JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return false;
                if (!name.getText().isBlank()) return true;
                JOptionPane.showMessageDialog(LinkMonitorPanel.this, "Give the source a name.");
            }
        }

        String name() {
            return name.getText().trim();
        }

        boolean enabled() {
            return enabled.isSelected();
        }

        int heartbeat() {
            return (Integer) heartbeat.getValue();
        }

        int silent() {
            return (Integer) silent.getValue();
        }

        int remind() {
            return (Integer) remind.getValue();
        }

        int flapCount() {
            return (Integer) flapCount.getValue();
        }

        int flapWindow() {
            return (Integer) flapWindow.getValue();
        }
    }

    // ── Table ─────────────────────────────────────────────────────────────

    private final class SourceTableModel extends AbstractTableModel {
        private final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        @Override
        public int getRowCount() {
            return config.sources.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        private String statusText(LinkMonitor.Source s, long now) {
            if (!s.enabled()) return "Disabled";
            if (status == null || !status.fresh(now)) return "Unknown (service not running)";
            LinkStatusFile.Source live = status.find(s.id());
            if (live == null) return "Starting...";
            if (live.silent()) return "Silent (stopped reporting)";
            if (live.state() == null) return "No report yet";
            switch (live.state()) {
                case "CONNECTED":
                    return "Connected";
                case "CONNECTING":
                    return "Reconnecting";
                case "DISCONNECTED":
                    return "Disconnected";
                case "STALLED":
                    return "Stalled";
                default:
                    return live.state();
            }
        }

        @Override
        public Object getValueAt(int r, int c) {
            LinkMonitor.Source s = config.sources.get(r);
            long now = System.currentTimeMillis();
            switch (c) {
                case 0:
                    return s.label();
                case 1:
                    return statusText(s, now);
                case 2: {
                    LinkStatusFile.Source live = status == null ? null : status.find(s.id());
                    return live == null || live.lastHeardAt() <= 0 ? "" : ago(now - live.lastHeardAt());
                }
                case 3:
                    return s.enabled() ? "Yes" : "No";
                case 4:
                    return s.heartbeatSeconds() + " s";
                case 5:
                    return s.silentAfterSeconds() + " s";
                case 6:
                    return s.tokenIssuedAt() > 0 ? day.format(new Date(s.tokenIssuedAt())) : "";
                default:
                    if (s.prevTokenHash().isEmpty()) return "";
                    return s.prevValidUntil() > now ? "valid until " + day.format(new Date(s.prevValidUntil())) : "expired";
            }
        }
    }
}
