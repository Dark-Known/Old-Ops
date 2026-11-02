package ui;

import service.LinkMonitorConfig;
import service.LinkSelfTest;
import service.LinkTls;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Two tabs of help for the person setting up the sending application: how to connect it (with the real address
 * filled in, ready to copy, and a test that runs from this PC), and how to check the certificate is genuine.
 */
final class LinkConnectPanel {

    private final File dataDir;
    private ApiPanel.View view;

    private final JTextArea connectText = mono();
    private final JTextArea testOut = mono();
    private final JComboBox<String> hostBox = new JComboBox<>();

    private final JTextArea certInfo = mono();
    private final JTextArea certHow = mono();
    private final JTextArea certTestOut = mono();
    private final JButton btnCopyFp = new JButton("Copy fingerprint");
    private final JButton btnExport = new JButton("Export certificate...");

    final JComponent connectTab;
    final JComponent certTab;

    LinkConnectPanel(File dataDir) {
        this.dataDir = dataDir;
        connectTab = buildConnect();
        certTab = buildCert();
    }

    private static JTextArea mono() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(new EmptyBorder(6, 8, 6, 8));
        return t;
    }

    private JComponent buildConnect() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        JScrollPane top = new JScrollPane(connectText);
        top.setPreferredSize(new Dimension(400, 280));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("Test from this PC using the name:"));
        hostBox.setEditable(true);
        bar.add(hostBox);
        JButton test = new JButton("Test now");
        test.setToolTipText("Checks the port, the certificate and GET /socketLink/health, the way the sender will");
        test.addActionListener(e -> runTest(testOut, (String) hostBox.getSelectedItem()));
        bar.add(test);
        JButton copy = new JButton("Copy instructions");
        copy.addActionListener(e -> clip(connectText.getText()));
        bar.add(copy);

        testOut.setText("\"Test now\" checks the port, certificate and health check from this PC.");
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(bar, BorderLayout.NORTH);
        bottom.add(new JScrollPane(testOut), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCert() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(certInfo, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
        btnCopyFp.addActionListener(e -> { if (view != null && view.cert() != null) clip(view.cert().sha256Fingerprint()); });
        btnExport.addActionListener(e -> export());
        JButton verify = new JButton("Verify endpoint now");
        verify.setToolTipText("Connects to the live endpoint and compares the certificate it presents with link-server.cer");
        verify.addActionListener(e -> runTest(certTestOut, "localhost"));
        buttons.add(btnCopyFp);
        buttons.add(btnExport);
        buttons.add(verify);
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);
        top.add(east, BorderLayout.EAST);

        JPanel center = new JPanel(new GridLayout(2, 1, 0, 6));
        center.add(new JScrollPane(certHow));
        certTestOut.setText("\"Verify endpoint now\" compares the live certificate with the one above.");
        center.add(new JScrollPane(certTestOut));

        top.setPreferredSize(new Dimension(400, 150));
        p.add(top, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    void update(ApiPanel.View v) {
        this.view = v;
        LinkMonitorConfig c = v.config();
        String scheme = c.tlsEnabled ? "https://" : "http://";
        String url = v.primaryUrl();
        List<String> hosts = new ArrayList<>(v.hostNames());
        Object keep = hostBox.getSelectedItem();
        hostBox.removeAllItems();
        hostBox.addItem("localhost");
        for (String h : hosts) if (!h.equals("localhost") && !h.startsWith("127.")) hostBox.addItem(h);
        hostBox.setSelectedItem(keep != null ? keep : "localhost");

        StringBuilder sb = new StringBuilder();
        sb.append("URL        ").append(url).append("/socketLink/events\n");
        sb.append("Header     Authorization: Bearer <token>   (add a source in Sources & tokens to get one)\n");
        sb.append("Body       {\"type\":\"heartbeat\",\"state\":\"connected\",\"seq\":1,\"instance\":\"app-start-time\"}\n");
        if (v.addresses().size() > 1) {
            sb.append("\nOther addresses that work:\n");
            for (int i = 1; i < v.addresses().size(); i++) sb.append("  ").append(v.addresses().get(i)).append("/socketLink/events\n");
        }
        sb.append("\nTest from server A\n");
        sb.append("  PowerShell  Invoke-RestMethod ").append(url).append("/socketLink/health\n");
        sb.append("  curl        curl ").append(c.tlsEnabled ? "--cacert link-server.cer " : "").append(url).append("/socketLink/health\n");
        sb.append("  Answer: ok\n");
        sb.append("\nSend a message\n");
        sb.append("  Invoke-RestMethod -Method Post -Uri ").append(url).append("/socketLink/events `\n");
        sb.append("    -Headers @{ Authorization = \"Bearer <token>\" } -ContentType application/json `\n");
        sb.append("    -Body '{\"type\":\"heartbeat\",\"state\":\"connected\"}'\n");
        sb.append("  Answer: {\"accepted\":1,\"duplicates\":0}\n");
        sb.append("\nSend a heartbeat with the current state about every minute, and an event on every change\n");
        sb.append("(connected, disconnected, reconnecting, stalled, recovered). Retry unsent messages.\n");
        connectText.setText(sb.toString());
        connectText.setCaretPosition(0);

        LinkTls.Material m = v.cert();
        StringBuilder ci = new StringBuilder();
        if (!c.tlsEnabled) {
            ci.append("HTTPS is switched OFF (tls.enabled=false in the settings file).\nRequests and tokens travel unencrypted; use this only on a private test network.");
            btnCopyFp.setEnabled(false);
            btnExport.setEnabled(false);
        } else if (m == null) {
            ci.append("No certificate yet.\nIt is created automatically by the background service when the first source is active.");
            btnCopyFp.setEnabled(false);
            btnExport.setEnabled(false);
        } else {
            ci.append("Certificate of this PC's link endpoint\n");
            ci.append("  Valid until:  ").append(new SimpleDateFormat("yyyy-MM-dd").format(m.notAfter())).append('\n');
            ci.append("  Covers names: ").append(String.join(", ", m.names())).append('\n');
            ci.append("  SHA-256 fingerprint:\n    ").append(m.sha256Fingerprint()).append('\n');
            ci.append("  File: ").append(m.certificate().getAbsolutePath());
            btnCopyFp.setEnabled(true);
            btnExport.setEnabled(true);
        }
        certInfo.setText(ci.toString());
        certInfo.setCaretPosition(0);

        String fp = m == null ? "<fingerprint>" : m.sha256Fingerprint();
        String fpNoColons = fp.replace(":", "");
        String host = hosts.isEmpty() ? "serverc" : hosts.get(0);
        StringBuilder how = new StringBuilder();
        how.append("Check the certificate on server A by its fingerprint (it must equal the one above):\n\n");
        how.append("Install it     certutil -dump link-server.cer     (Cert Hash(sha256) = fingerprint)\n");
        how.append("               certutil -addstore Root link-server.cer\n\n");
        how.append("Or pin it      no installation; the application compares the fingerprint itself. PowerShell check:\n");
        how.append("  $tcp = New-Object Net.Sockets.TcpClient('").append(host).append("', ").append(c.port).append(")\n");
        how.append("  $ssl = New-Object Net.Security.SslStream($tcp.GetStream(), $false, ({$true}))\n");
        how.append("  $ssl.AuthenticateAsClient('").append(host).append("')\n");
        how.append("  $c = New-Object Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)\n");
        how.append("  [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($c.RawData)).Replace('-','') -eq '")
           .append(fpNoColons).append("'\n\n");
        how.append("Other options: your own certificate (tls.keystore / tls.password), or HTTPS off (tls.enabled=false) on a private network.");
        certHow.setText(how.toString());
        certHow.setCaretPosition(0);
    }

    private void runTest(JTextArea out, String host) {
        ApiPanel.View v = view;
        if (v == null) return;
        String h = host == null || host.isBlank() ? "localhost" : host.trim();
        out.setText("Testing...");
        int port = v.config().port;
        boolean tls = v.config().tlsEnabled;
        File cer = LinkTls.certificateFile(dataDir);
        Async.latest(out, () -> LinkSelfTest.run(h, port, tls, cer), r -> {
            out.setText(String.join("\n", r.lines()) + "\n\n" + (r.ok() ? "RESULT: all checks passed." : "RESULT: there is a problem, see above."));
            out.setCaretPosition(0);
        }, e -> out.setText("Test failed: " + e));
    }

    private void export() {
        File cer = LinkTls.certificateFile(dataDir);
        if (!cer.isFile()) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("link-server.cer"));
        if (fc.showSaveDialog(connectTab) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(cer.toPath(), fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(connectTab, "Saved. Copy it to the sending server and compare the fingerprint before trusting it.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(connectTab, "Could not save: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void clip(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }
}
