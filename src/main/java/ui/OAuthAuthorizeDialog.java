package ui;

import service.OAuth2TokenService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.function.Consumer;

/**
 * One-time interactive OAuth2 "device code" sign-in for a mailbox.
 *
 * Runs {@link OAuth2TokenService#enroll} on a background thread (it blocks
 * while polling Microsoft's token endpoint until the person finishes signing
 * in elsewhere), and shows the "open this URL, enter this code" instructions
 * Microsoft returns. On success the refresh token is cached to disk — every
 * scheduled run after that authenticates silently, with no further UI.
 */
public class OAuthAuthorizeDialog extends JDialog {

    private static final String GRAPH_MAIL_SCOPE =
            "https://graph.microsoft.com/Mail.ReadWrite offline_access";

    private final JTextArea taInstructions;
    private final JLabel lblStatus;
    private final JButton btnCopyCode;
    private final JButton btnClose;
    private String currentCode = null;

    public OAuthAuthorizeDialog(Frame parent, String mailbox, String tenantId, String clientId,
                                File dataDir, Consumer<Boolean> onFinished) {
        super(parent, "Authorize Mailbox", true);
        setSize(480, 320);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(15, 15, 15, 15));

        JLabel title = new JLabel("<html><b>Authorizing " + escapeHtml(mailbox) + "</b></html>");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

        lblStatus = new JLabel("Requesting a sign-in code from Microsoft\u2026");
        lblStatus.setForeground(new Color(0x555555));

        taInstructions = new JTextArea(6, 30);
        taInstructions.setEditable(false);
        taInstructions.setLineWrap(true);
        taInstructions.setWrapStyleWord(true);
        taInstructions.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        taInstructions.setBackground(new Color(0xF5F5F5));
        taInstructions.setBorder(new EmptyBorder(10, 10, 10, 10));
        taInstructions.setText("Please wait\u2026");

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(title);
        top.add(Box.createRigidArea(new Dimension(0, 8)));
        top.add(lblStatus);

        content.add(top, BorderLayout.NORTH);
        content.add(new JScrollPane(taInstructions), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCopyCode = new JButton("Copy Code");
        btnCopyCode.setEnabled(false);
        btnCopyCode.addActionListener(e -> {
            if (currentCode != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(currentCode), null);
                lblStatus.setText("Code copied to clipboard. Waiting for sign-in to complete\u2026");
            }
        });
        btnClose = new JButton("Cancel");
        btnClose.addActionListener(e -> dispose());
        buttons.add(btnCopyCode);
        buttons.add(btnClose);
        content.add(buttons, BorderLayout.SOUTH);

        add(content, BorderLayout.CENTER);

        startEnrollment(mailbox, tenantId, clientId, dataDir, onFinished);
    }

    private void startEnrollment(String mailbox, String tenantId, String clientId,
                                 File dataDir, Consumer<Boolean> onFinished) {
        // Shared dataDir-based path (same as TransferService) — NOT the
        // per-user-home default, which would make a mailbox authorized here
        // invisible to the Daemon (it runs as SYSTEM, a different account
        // with its own separate home directory; see app-config.xml
        // <runAsSystem>).
        OAuth2TokenService oauthService = new OAuth2TokenService(OAuth2TokenService.sharedTokenDir(dataDir));

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            private volatile boolean succeeded = false;

            @Override
            protected Void doInBackground() {
                try {
                    oauthService.enroll(mailbox, tenantId, clientId, GRAPH_MAIL_SCOPE, this::publishPrompt);
                    succeeded = true;
                } catch (Exception ex) {
                    publish("ERROR:" + ex.getMessage());
                }
                return null;
            }

            private void publishPrompt(String message) {
                publish("PROMPT:" + message);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                String last = chunks.get(chunks.size() - 1);
                if (last.startsWith("PROMPT:")) {
                    String message = last.substring("PROMPT:".length());
                    taInstructions.setText(message);
                    lblStatus.setText("Waiting for you to sign in\u2026");
                    currentCode = extractCode(message);
                    btnCopyCode.setEnabled(currentCode != null);
                } else if (last.startsWith("ERROR:")) {
                    String err = last.substring("ERROR:".length());
                    lblStatus.setText("Authorization failed.");
                    taInstructions.setText("Error: " + err
                            + "\n\nCommon causes:\n"
                            + " - The Client ID or Tenant ID is wrong\n"
                            + " - \"Allow public client flows\" is not enabled on the Azure AD app\n"
                            + " - Your tenant blocks user consent for self-registered apps\n"
                            + " - The device code expired before sign-in was completed \u2014 try again");
                    btnCopyCode.setEnabled(false);
                }
            }

            @Override
            protected void done() {
                if (succeeded) {
                    lblStatus.setText("Authorized successfully.");
                    taInstructions.setText("Mailbox " + mailbox + " is now authorized.\n\n"
                            + "Scheduled runs will authenticate automatically from now on \u2014 "
                            + "no further sign-in is needed unless you revoke access.");
                    btnCopyCode.setEnabled(false);
                    btnClose.setText("Done");
                    if (onFinished != null) onFinished.accept(true);
                } else {
                    if (onFinished != null) onFinished.accept(false);
                }
            }
        };
        worker.execute();
    }

    /** Pulls the short alphanumeric sign-in code out of Microsoft's device-code prompt text. */
    private String extractCode(String message) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\b([A-Z0-9]{4,}-?[A-Z0-9]{4,})\\b").matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
