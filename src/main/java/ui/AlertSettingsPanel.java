package ui;

import model.EventKind;
import model.ScheduledTask;
import service.AlertConfig;
import service.AlertDispatcher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Teams alert settings: where cards go, which events produce one, and each task's own choice. */
class AlertSettingsPanel extends JPanel {

    private final File dataDir;
    private final Supplier<List<ScheduledTask>> tasks;
    private final service.RunHistoryService history;

    private final JCheckBox enabled = new JCheckBox("Send alerts to Teams");
    private final JPasswordField webhook = new JPasswordField(40);
    private final JLabel host = new JLabel(" ");
    private final JCheckBox recovery = new JCheckBox("Also send a short \"recovered\" card when a problem is over");
    private final JSpinner throttle = new JSpinner(new SpinnerNumberModel(15, 0, 1440, 5));
    private final JCheckBox serve = new JCheckBox("Open logs opens the report from this application (HTTPS), where it can also be downloaded");
    private final JSpinner reportPort = new JSpinner(new SpinnerNumberModel(8444, 1, 65535, 1));
    private final JTextField reportHost = new JTextField(30);
    private final JTextField reportDir = new JTextField(30), baseUrl = new JTextField(30);
    private final JSpinner keepDays = new JSpinner(new SpinnerNumberModel(30, 1, 3650, 1));
    private final Map<EventKind, JCheckBox> kindBoxes = new LinkedHashMap<>();
    private final JLabel result = new JLabel(" ");
    private final TaskModel model = new TaskModel();
    private AlertConfig current = AlertConfig.empty();

    {
        reportPort.setEditor(new JSpinner.NumberEditor(reportPort, "#"));
    }

    AlertSettingsPanel(File dataDir, Supplier<List<ScheduledTask>> tasks, service.RunHistoryService history) {
        this.dataDir = dataDir;
        this.history = history;
        this.tasks = tasks;
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Where to send", scroll(destination()));
        tabs.addTab("Which alerts", scroll(kinds()));
        tabs.addTab("Per task & monitor", perTask());
        add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = new JButton("Save");
        JButton test = new JButton("Send test card");
        save.addActionListener(e -> save());
        test.addActionListener(e -> test());
        bottom.add(save);
        bottom.add(test);
        bottom.add(result);
        add(bottom, BorderLayout.SOUTH);
        webhook.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { hostHint(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { hostHint(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { hostHint(); }
        });
        load();
    }

    /** A panel that follows the width of its scroll pane, so wrapped notes wrap instead of widening the page. */
    private static final class Tracking extends JPanel implements Scrollable {
        Tracking(LayoutManager l) { super(l); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JScrollPane scroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        s.setBorder(null);
        return s;
    }

    private static JTextArea note(String text) {
        JTextArea t = new JTextArea(text);
        t.setEditable(false); t.setOpaque(false); t.setLineWrap(true); t.setWrapStyleWord(true);
        t.setForeground(Color.GRAY);
        t.setBorder(null);
        return t;
    }

    private JPanel destination() {
        JPanel p = new Tracking(new GridBagLayout());
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST; c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        p.add(enabled, c);
        c.gridy++; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(note("In Teams, create a Workflow from \"Post to a channel when a webhook request is received\" and paste its web address here. "
                + "It is stored encrypted and never written to a log."), c);
        c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        row(p, c, "Webhook address", webhook);
        c.gridx = 1; c.gridy++; p.add(host, c);
        row(p, c, "Quiet period (minutes)", throttle);
        c.gridx = 1; c.gridy++; c.gridwidth = 1;
        p.add(note("The same problem on the same task is sent once, then held back for this long. The next card says how many were held back. 0 = send every one."), c);
        c.gridx = 0; c.gridwidth = 2; c.gridy++; p.add(recovery, c);
        c.gridwidth = 1;
        c.gridx = 0; c.gridwidth = 2; c.gridy++; p.add(serve, c);
        c.gridwidth = 1;
        row(p, c, "Port", reportPort);
        row(p, c, "Name of this PC", reportHost);
        c.gridx = 1; c.gridy++;
        p.add(note("The name people type to reach this PC, as it is known on your network (blank = " + hostName() + "). Open the port in the Windows firewall. "
                + "The page uses the same certificate as the socket link (API -> Socket link -> Certificate); browsers warn until it is trusted. "
                + "Each link carries a key that only works for its own report, so it can be shared in the channel but not guessed."), c);
        row(p, c, "Report folder", reportDir);
        row(p, c, "Report web address", baseUrl);
        c.gridx = 1; c.gridy++;
        p.add(note("A card cannot hold a file, so \"Open logs\" opens the report from this application, and the page has a Download button (the same file is also sent "
                + "inside the webhook request for a Teams workflow to save, see the README). A copy stays in the report folder (blank = data folder). "
                + "Report web address is only for when the reports are published somewhere else (a web server or SharePoint); it then replaces the link above."), c);
        row(p, c, "Keep reports (days)", keepDays);
        c.gridx = 0; c.gridy++; c.weighty = 1; p.add(new JLabel(), c);
        return p;
    }

    private static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "this PC's name"; }
    }


    private static void row(JPanel p, GridBagConstraints c, String label, JComponent field) {
        c.gridx = 0; c.gridy++; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, c);
    }

    private JPanel kinds() {
        JPanel p = new Tracking(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton problems = new JButton("Problems only");
        JButton none = new JButton("Clear all");
        problems.addActionListener(e -> kindBoxes.forEach((k, b) -> b.setSelected(AlertConfig.PROBLEMS.contains(k))));
        none.addActionListener(e -> kindBoxes.values().forEach(b -> b.setSelected(false)));
        presets.add(new JLabel("Presets:"));
        presets.add(problems);
        presets.add(none);
        presets.setAlignmentX(0);
        p.add(presets);
        p.add(Box.createVerticalStrut(6));
        JTextArea n = note("These are the defaults for every task. Anything ticked here sends a card; a task can use its own list or send nothing (Per task tab).");
        n.setAlignmentX(0);
        p.add(n);
        for (EventKind k : kindChoices()) {
            JCheckBox b = new JCheckBox(k.label());
            b.setAlignmentX(0);
            kindBoxes.put(k, b);
            p.add(b);
        }
        return p;
    }

    /** Events that can sensibly alert: every problem, plus the other warnings and errors. */
    private static List<EventKind> kindChoices() {
        List<EventKind> out = new ArrayList<>();
        for (EventKind k : EventKind.values()) {
            if (AlertConfig.NEVER.contains(k) || AlertConfig.RECOVERIES.contains(k)) continue;
            if (AlertConfig.PROBLEMS.contains(k) || k.defaultSeverity() == EventKind.Severity.WARN || k.defaultSeverity() == EventKind.Severity.ERROR
                    || k == EventKind.FILES_DETECTED || k == EventKind.FILES_CHANGED) out.add(k);   // "Watch a folder" tasks alert on these
        }
        return out;
    }

    private JPanel perTask() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTable table = new JTable(model);
        table.setRowHeight(26);
        JComboBox<String> modes = new JComboBox<>(new String[] {"Use defaults", "Send nothing", "Own list"});
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(modes));
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        JButton edit = new JButton("Choose alerts for the selected row...");
        edit.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) chooseKinds(r);
        });
        p.add(note("Everything that can be monitored is listed: the background service, the socket link API (and each link source), the Outlook mail API, system health, and every task. Each follows the defaults unless you change it here: send nothing, or send its own list of alerts."), BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
        b.add(edit);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    private void chooseKinds(int row) {
        String id = model.ids.get(row);
        String area = id.startsWith("@") ? id : model.names.get(row).startsWith("Socket link: ") ? AlertConfig.SOCKET : null;
        List<EventKind> choices = new ArrayList<>();
        for (EventKind k : kindChoices()) if (java.util.Objects.equals(AlertConfig.areaOf(k), area)) choices.add(k);
        JPanel box = new JPanel(new GridLayout(0, 1));
        Map<EventKind, JCheckBox> boxes = new LinkedHashMap<>();
        Set<EventKind> have = model.kinds.get(row);
        for (EventKind k : choices) {
            JCheckBox cb = new JCheckBox(k.label(), have.contains(k));
            boxes.put(k, cb);
            box.add(cb);
        }
        JScrollPane sp = new JScrollPane(box);
        sp.setPreferredSize(new Dimension(360, 380));
        if (JOptionPane.showConfirmDialog(this, sp, "Alerts for " + model.names.get(row), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Set<EventKind> now = EnumSet.noneOf(EventKind.class);
        boxes.forEach((k, cb) -> { if (cb.isSelected()) now.add(k); });
        model.kinds.set(row, now);
        model.modes.set(row, AlertConfig.Mode.CUSTOM);
        model.fireTableRowsUpdated(row, row);
    }

    private void hostHint() {
        String url = new String(webhook.getPassword()).trim();
        String h = "";
        try { h = url.isEmpty() ? "" : java.net.URI.create(url).getHost(); } catch (Exception ignored) { }
        host.setText(h == null || h.isEmpty() ? (url.isEmpty() ? " " : "Not a valid web address") : "Sends to " + h);
        host.setForeground(h == null || h.isEmpty() ? new Color(0xB3261E) : Color.GRAY);
    }

    private void load() {
        Async.latest(this, () -> new Object[] {AlertConfig.load(dataDir), tasks.get(), service.LinkMonitorConfig.load(dataDir)}, o -> {
            current = (AlertConfig) o[0];
            @SuppressWarnings("unchecked") List<ScheduledTask> list = (List<ScheduledTask>) o[1];
            enabled.setSelected(current.enabled);
            webhook.setText(current.webhookUrl);
            recovery.setSelected(current.recoveryCards);
            throttle.setValue(current.throttleMinutes);
            reportDir.setText(current.reportDir);
            baseUrl.setText(current.reportBaseUrl);
            keepDays.setValue(current.reportKeepDays);
            serve.setSelected(current.reportServer);
            reportPort.setValue(current.reportPort);
            reportHost.setText(current.reportHost);
            kindBoxes.forEach((k, b) -> b.setSelected(current.kinds.contains(k)));
            List<String[]> src = new ArrayList<>();
            for (var x : ((service.LinkMonitorConfig) o[2]).sources) src.add(new String[] {x.id(), x.label()});
            model.fill(list, src, current);
            hostHint();
        }, e -> result.setText("Could not read the alert settings: " + e));
    }

    private AlertConfig collect() {
        Set<EventKind> picked = EnumSet.noneOf(EventKind.class);
        kindBoxes.forEach((k, b) -> { if (b.isSelected()) picked.add(k); });
        AlertConfig c = current.with(enabled.isSelected(), new String(webhook.getPassword()).trim(), picked, recovery.isSelected(),
                ((Number) throttle.getValue()).intValue(), reportDir.getText(), baseUrl.getText(), ((Number) keepDays.getValue()).intValue())
                .withReportServer(serve.isSelected(), ((Number) reportPort.getValue()).intValue(), reportHost.getText());
        for (int i = 0; i < model.ids.size(); i++) {
            c = c.withTask(model.ids.get(i), new AlertConfig.TaskRule(model.modes.get(i), model.kinds.get(i)));
        }
        return c;
    }

    private void save() {
        AlertConfig c = collect();
        try {
            c.save(dataDir);
            current = c;
            result.setForeground(new Color(0x2E7D32));
            result.setText("Saved. The service picks the change up on the next alert.");
        } catch (Exception e) {
            result.setForeground(new Color(0xB3261E));
            result.setText("Could not save: " + e.getMessage());
        }
    }

    private void test() {
        AlertConfig c = collect();
        result.setForeground(Color.GRAY);
        result.setText("Sending...");
        Async.latest(this, () -> AlertDispatcher.sendTest(dataDir, c, null, history), r -> {
            boolean ok = r.ok();
            result.setForeground(ok ? new Color(0x2E7D32) : new Color(0xB3261E));
            result.setText(ok ? "Test card delivered (" + r.status() + "). Check the Teams channel."
                    : "Not delivered: " + (r.status() == 0 ? r.error() : "HTTP " + r.status() + " " + r.body()));
        }, e -> result.setText("Test failed: " + e));
    }

    private static final class TaskModel extends AbstractTableModel {
        final List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        final List<AlertConfig.Mode> modes = new ArrayList<>();
        final List<Set<EventKind>> kinds = new ArrayList<>();
        private static final String[] LABEL = {"Use defaults", "Send nothing", "Own list"};

        void fill(List<ScheduledTask> list, List<String[]> sources, AlertConfig cfg) {
            ids.clear(); names.clear(); modes.clear(); kinds.clear();
            String[][] monitors = {{AlertConfig.SERVICE, "Monitor: Background service"}, {AlertConfig.SOCKET, "Monitor: Socket link API"},
                    {AlertConfig.OUTLOOK, "Monitor: Outlook mail API"}, {AlertConfig.SYSTEM, "Monitor: System and health"}};
            for (String[] m : monitors) {
                AlertConfig.TaskRule r = cfg.ruleFor(m[0]);
                ids.add(m[0]); names.add(m[1]); modes.add(r.mode());
                kinds.add(r.kinds().isEmpty() ? defaultsFor(m[0]) : EnumSet.copyOf(r.kinds()));
            }
            for (String[] src : sources) {
                AlertConfig.TaskRule r = cfg.ruleFor(src[0]);
                ids.add(src[0]); names.add("Socket link: " + src[1]); modes.add(r.mode());
                kinds.add(r.kinds().isEmpty() ? defaultsFor(AlertConfig.SOCKET) : EnumSet.copyOf(r.kinds()));
            }
            for (ScheduledTask t : list) {
                AlertConfig.TaskRule r = cfg.ruleFor(t.getId());
                ids.add(t.getId()); names.add(t.getName()); modes.add(r.mode());
                kinds.add(r.kinds().isEmpty() ? defaultsFor(null) : EnumSet.copyOf(r.kinds()));
            }
            fireTableDataChanged();
        }
        private static Set<EventKind> defaultsFor(String area) {
            Set<EventKind> out = EnumSet.noneOf(EventKind.class);
            for (EventKind k : AlertConfig.PROBLEMS) if (java.util.Objects.equals(AlertConfig.areaOf(k), area)) out.add(k);
            return out;
        }
        @Override public int getRowCount() { return ids.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return new String[] {"Task / monitor", "Alerts", "Sends"}[c]; }
        @Override public boolean isCellEditable(int r, int c) { return c == 1; }
        @Override public Object getValueAt(int r, int c) {
            return switch (c) {
                case 0 -> names.get(r);
                case 1 -> LABEL[modes.get(r).ordinal()];
                default -> switch (modes.get(r)) {
                    case INHERIT -> "the default list";
                    case OFF -> "nothing";
                    case CUSTOM -> kinds.get(r).size() + " alert type(s)";
                };
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            for (int i = 0; i < LABEL.length; i++) if (LABEL[i].equals(v)) modes.set(r, AlertConfig.Mode.values()[i]);
            fireTableRowsUpdated(r, r);
        }
    }
}
