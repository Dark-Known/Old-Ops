package ui;

import service.ConnectionTestService;

import javax.swing.*;
import java.awt.*;

/**
 * Small modal popup that tests a set of target credentials (host / username
 * / password) over SFTP and shows the live result — spinner while in
 * progress, then a green check or red cross with a short reason.
 *
 * <p>Used from the New/Edit Task dialog's Target Credentials tab and from
 * the Credential Manager's Add/Edit Credential dialog, so both places where
 * a person types in a host/username/password can verify it before it's
 * saved.
 */
public final class TestConnectionDialog {

    private TestConnectionDialog() {}

    /**
     * Shows the dialog and blocks (it's modal) until the user closes it.
     *
     * @return the test result, or {@code null} if the dialog was closed
     *         before the background test finished (shouldn't normally
     *         happen since Close is disabled until then).
     */
    public static ConnectionTestService.Result show(Component parent, String host, String username, String password) {
        Window ownerWindow = SwingUtilities.getWindowAncestor(parent);
        Frame ownerFrame = ownerWindow instanceof Frame ? (Frame) ownerWindow : null;

        JDialog dlg = new JDialog(ownerFrame, "Test Connection", true);
        dlg.setResizable(false);
        dlg.setLayout(new BorderLayout(10, 10));
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JLabel icon = new JLabel(VectorIcons.hourglass(new Color(0x757575), 30), SwingConstants.CENTER);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel status = new JLabel("Testing connection to " + host + " ...", SwingConstants.CENTER);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 13f));
        status.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel detail = new JLabel(" ", SwingConstants.CENTER);
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
        detail.setForeground(new Color(0x757575));
        detail.setAlignmentX(Component.CENTER_ALIGNMENT);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setAlignmentX(Component.CENTER_ALIGNMENT);
        bar.setMaximumSize(new Dimension(280, 8));
        bar.setPreferredSize(new Dimension(280, 8));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(18, 20, 6, 20));
        center.add(icon);
        center.add(Box.createVerticalStrut(8));
        center.add(status);
        center.add(Box.createVerticalStrut(4));
        center.add(detail);
        center.add(Box.createVerticalStrut(10));
        center.add(bar);

        JButton btnClose = new JButton("Close");
        btnClose.setEnabled(false);
        btnClose.addActionListener(e -> dlg.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        south.add(btnClose);

        dlg.add(center, BorderLayout.CENTER);
        dlg.add(south, BorderLayout.SOUTH);
        dlg.setSize(380, 210);
        dlg.setLocationRelativeTo(parent);

        final ConnectionTestService.Result[] holder = new ConnectionTestService.Result[1];

        new SwingWorker<ConnectionTestService.Result, Void>() {
            @Override protected ConnectionTestService.Result doInBackground() {
                return ConnectionTestService.testSftp(host, username, password);
            }
            @Override protected void done() {
                ConnectionTestService.Result r;
                try {
                    r = get();
                } catch (Exception ex) {
                    r = new ConnectionTestService.Result(false, "Unexpected error: " + ex.getMessage(), 0);
                }
                holder[0] = r;
                bar.setIndeterminate(false);
                bar.setValue(100);

                if (r.success) {
                    icon.setIcon(VectorIcons.check(AppTheme.EARTH_MOSS, 30));
                    status.setText("Connection successful");
                    status.setForeground(AppTheme.EARTH_MOSS);
                    bar.setForeground(AppTheme.EARTH_MOSS);
                } else {
                    icon.setIcon(VectorIcons.cross(AppTheme.EARTH_RUST, 30));
                    status.setText("Connection failed");
                    status.setForeground(AppTheme.EARTH_RUST);
                    bar.setForeground(AppTheme.EARTH_RUST);
                }
                detail.setText("<html><div style='width:300px;text-align:center'>"
                        + escape(r.message) + "<br><span style='color:#9E9E9E'>(" + r.elapsedMs + " ms)</span></div></html>");
                btnClose.setEnabled(true);
                dlg.getRootPane().setDefaultButton(btnClose);
            }
        }.execute();

        dlg.setVisible(true); // blocks (modal) until Close is clicked
        return holder[0];
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
