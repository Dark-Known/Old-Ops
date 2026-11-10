package ui;

import service.DaemonCallLog;
import service.GraphCallLog;
import service.LinkActivityLog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One feed of everything that crossed the wire: requests from the sending applications and calls to the Outlook mail
 * API, newest first, with the full detail of the selected line below. Read from the two log files the background
 * service writes, so it does not depend on the Event Monitor.
 */
final class LinkActivityPanel extends JPanel {

    static final String LINK = "Link request";
    static final String GRAPH = "Outlook API";
    static final String TOKEN = "Sign-in";
    static final String DAEMON = "Service";
    static final String ALERT = "Teams";

    private static final int LOAD = 1000;
    private static final String[] COLUMNS = {"Time", "Type", "Who / mailbox", "What", "Status", "Time taken", "Answer"};

    /** One line of the feed, whatever it came from. */
    record Row(long time, String type, String who, String what, int status, long millis, String answer,
               String detail, boolean ok, boolean muted) {}

    private final File dataDir;
    private List<Row> all = new ArrayList<>();
    private List<Row> shown = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<String> type = new JComboBox<>();
    private final JComboBox<String> outcome = new JComboBox<>(new String[] {
            "All outcomes", "Problems only", "Hide health checks"});
    private final JTextField search = new JTextField(16);
    private final JCheckBox pause = new JCheckBox("Pause");
    private final JLabel count = new JLabel(" ");
    private final JTextArea detail = new JTextArea();
    private final SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat whenLong = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private long stamp = Long.MIN_VALUE;
    private javax.swing.Timer timer;
    private JSplitPane split;

    private final java.util.Set<String> only;

    /** @param types which kinds of traffic this feed shows (LINK, GRAPH, TOKEN, DAEMON) */
    LinkActivityPanel(File dataDir, String... types) {
        this.dataDir = dataDir;
        this.only = new java.util.HashSet<>(java.util.Arrays.asList(types));
        type.addItem("All");
        for (String t : types) type.addItem(t);
        setLayout(new BorderLayout(6, 6));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        if (only.size() > 1) bar.add(type);
        bar.add(outcome);
        bar.add(new JLabel("Search"));
        bar.add(search);
        bar.add(pause);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> load(true));
        bar.add(refresh);
        JButton copy = new JButton("Copy details");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(detail.getText()), null));
        bar.add(copy);
        JButton openFolder = new JButton("Open folder");
        openFolder.setToolTipText("The logs folder with " + LinkActivityLog.FILE_NAME + " and " + GraphCallLog.FILE_NAME);
        openFolder.addActionListener(e -> {
            try { Desktop.getDesktop().open(service.LogFiles.dir(dataDir)); } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, service.LogFiles.dir(dataDir).getAbsolutePath());
            }
        });
        bar.add(openFolder);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.CENTER);
        count.setBorder(new EmptyBorder(0, 8, 2, 0));
        north.add(count, BorderLayout.SOUTH);

        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowVerticalLines(false);
        int[] w = {150, 100, 150, 250, 55, 80, 210};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        table.getColumnModel().getColumn(0).setMinWidth(145);
        table.getColumnModel().getColumn(5).setMinWidth(80);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel && r < shown.size()) {
                    Row row = shown.get(r);
                    comp.setForeground(!row.ok() ? new Color(0xB3261E) : row.muted() ? Color.GRAY : t.getForeground());
                }
                return comp;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });

        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detail.setLineWrap(true);
        detail.setBorder(new EmptyBorder(4, 6, 4, 6));
        detail.setText("Select a line to see it in full.");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.55);
        split.setBorder(null);
        this.split = split;

        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        type.addActionListener(e -> applyFilter());
        outcome.addActionListener(e -> applyFilter());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
    }

    @Override public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> { if (split != null && split.getHeight() > 0) split.setDividerLocation(0.55); });
        load(true);
        if (timer == null) {
            timer = new javax.swing.Timer(3000, e -> { if (!pause.isSelected()) load(false); });
            timer.start();
        }
    }

    @Override public void removeNotify() {
        if (timer != null) { timer.stop(); timer = null; }
        super.removeNotify();
    }

    private void load(boolean force) {
        long st = stampOf(LinkActivityLog.FILE_NAME) * 31 + stampOf(GraphCallLog.FILE_NAME);
        if (!force && st == stamp) return;
        stamp = st;
        Async.latest(this, () -> rows(dataDir, LOAD), list -> { all = list; applyFilter(); },
                e -> System.err.println("Link activity could not be read: " + e));
    }

    private long stampOf(String name) {
        File f = service.LogFiles.file(dataDir, name);
        return f.isFile() ? f.lastModified() * 31 + f.length() : -1;
    }

    /** Both logs merged, newest first. */
    static List<Row> rows(File dir, int limit) {
        List<Row> out = new ArrayList<>();
        SimpleDateFormat longFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        for (LinkActivityLog.Entry e : LinkActivityLog.readRecent(dir, limit)) {
            boolean health = e.path().endsWith("/health");
            StringBuilder d = new StringBuilder();
            d.append("Received:  ").append(longFmt.format(new Date(e.time()))).append('\n');
            d.append("From:      ").append(e.remote()).append('\n');
            d.append("Source:    ").append(e.label().isEmpty() ? "(token not recognised)" : e.label()).append('\n');
            d.append("Token:     ").append(e.tokenHint().isEmpty() ? "(none sent)" : e.tokenHint()).append('\n');
            d.append("\nREQUEST\n").append(e.method()).append(' ').append(e.path()).append('\n');
            d.append(e.request().isEmpty() ? "(no body)" : pretty(e.request())).append('\n');
            d.append("\nRESPONSE\n").append(e.status()).append('\n').append(e.response()).append('\n');
            String why = LinkActivityLog.explain(e.status());
            if (!why.isEmpty()) d.append('\n').append(why).append('\n');
            String hint = LinkActivityLog.hint(e);
            if (!hint.isEmpty()) d.append("\nNote: ").append(hint).append('\n');
            out.add(new Row(e.time(), LINK, e.remote() + (e.label().isEmpty() ? "" : "  " + e.label()),
                    LinkActivityLog.summarize(e), e.status(), -1, e.response().replace('\n', ' '), d.toString(), e.ok(), health));
        }
        for (GraphCallLog.Entry e : GraphCallLog.readRecent(dir, limit)) {
            StringBuilder d = new StringBuilder();
            d.append("Time:      ").append(longFmt.format(new Date(e.time()))).append('\n');
            d.append("What:      ").append(GraphCallLog.describe(e)).append('\n');
            d.append("Mailbox:   ").append(e.mailbox().isEmpty() ? "-" : e.mailbox()).append('\n');
            d.append("Attempt:   ").append(e.attempt()).append('\n');
            d.append("Duration:  ").append(e.millis()).append(" ms\n");
            if (e.bytes() >= 0) d.append("Size:      ").append(e.bytes()).append(" bytes\n");
            if (!e.requestId().isEmpty()) d.append("Request id: ").append(e.requestId()).append("   (quote this to Microsoft support)\n");
            d.append("\nREQUEST\n").append(e.method()).append(' ').append(e.target()).append('\n');
            d.append("\nRESPONSE\n").append(e.status() == 0 ? "(no answer)" : String.valueOf(e.status())).append('\n');
            if (!e.note().isEmpty()) d.append(e.note()).append('\n');
            String why = GraphCallLog.explain(e);
            if (!why.isEmpty()) d.append('\n').append(why).append('\n');
            d.append("\nNo access token or message content is stored in this log.\n");
            out.add(new Row(e.time(), e.isToken() ? TOKEN : GRAPH, e.mailbox(), GraphCallLog.describe(e), e.status(), e.millis(),
                    e.note().isEmpty() ? (e.ok() ? "OK" : "") : e.note(), d.toString(), e.ok(), false));
        }
        for (DaemonCallLog.Entry e : DaemonCallLog.readRecent(dir, limit)) {
            StringBuilder d = new StringBuilder();
            boolean poll = e.path().startsWith("/status");
            d.append("Time:      ").append(longFmt.format(new Date(e.time()))).append('\n');
            d.append("What:      ").append(DaemonCallLog.describe(e)).append('\n');
            d.append("Channel:   ").append(e.channel().equals("queue") ? "database command queue (fallback)" : "local control endpoint (127.0.0.1)").append('\n');
            if (!e.requestedBy().isEmpty()) d.append("Sent by:   ").append(e.requestedBy()).append('\n');
            if (!e.taskId().isEmpty()) d.append("Task id:   ").append(e.taskId()).append('\n');
            if (e.millis() > 0) d.append("Duration:  ").append(e.millis()).append(" ms\n");
            d.append("\nREQUEST\n").append(e.method()).append(' ').append(e.path());
            if (!e.action().isEmpty()) d.append("\naction=").append(e.action()).append(e.taskId().isEmpty() ? "" : "&taskId=" + e.taskId());
            d.append("\n\nRESPONSE\n").append(e.status() == 0 ? "(failed)" : String.valueOf(e.status())).append('\n').append(e.response()).append('\n');
            String why = DaemonCallLog.explain(e);
            if (!why.isEmpty()) d.append('\n').append(why).append('\n');
            d.append("\nThe control token is never logged.\n");
            out.add(new Row(e.time(), DAEMON, e.requestedBy().isEmpty() ? "service" : e.requestedBy(), DaemonCallLog.describe(e),
                    e.status(), e.millis() > 0 ? e.millis() : -1, e.response(), d.toString(), e.ok() || e.status() == 202, poll));
        }
        for (service.AlertCallLog.Entry e : service.AlertCallLog.readRecent(dir, limit)) {
            StringBuilder d = new StringBuilder();
            d.append("Time:      ").append(longFmt.format(new Date(e.time()))).append('\n');
            d.append("Task:      ").append(e.taskName().isEmpty() ? "-" : e.taskName()).append('\n');
            d.append("Alert:     ").append(e.kind()).append("  ").append(e.title()).append('\n');
            d.append("Webhook:   ").append(e.host()).append("  (the secret part of the address is never logged)\n");
            d.append("Attempt:   ").append(e.attempt()).append('\n');
            d.append("Duration:  ").append(e.millis()).append(" ms\n");
            if (!e.reportPath().isEmpty()) d.append("Report:    ").append(e.reportPath()).append('\n');
            d.append("\nRESPONSE\n").append(e.status() == 0 ? "(no answer)" : String.valueOf(e.status())).append('\n').append(e.response()).append('\n');
            d.append("\nCARD SENT\n").append(e.payload().isEmpty() ? "(not stored)" : pretty(e.payload())).append('\n');
            out.add(new Row(e.time(), ALERT, e.taskName().isEmpty() ? "-" : e.taskName(), e.kind() + ": " + e.title(), e.status(), e.millis(),
                    e.response().replace('\n', ' '), d.toString(), e.ok(), false));
        }
        out.sort(Comparator.comparingLong(Row::time).reversed());
        return out;
    }

    private void applyFilter() {
        Row selected = table.getSelectedRow() >= 0 && table.getSelectedRow() < shown.size() ? shown.get(table.getSelectedRow()) : null;
        String wanted = type.getSelectedIndex() <= 0 ? null : (String) type.getSelectedItem();
        int o = outcome.getSelectedIndex();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        int bad = 0;
        for (Row r : all) {
            if (!only.contains(r.type())) continue;
            if (wanted != null && !r.type().equals(wanted)) continue;
            if (o == 1 && r.ok()) continue;
            if (o == 2 && r.muted()) continue;
            if (!q.isEmpty() && !(r.who() + " " + r.what() + " " + r.answer() + " " + r.status() + " " + r.type()
                    + " " + r.detail()).toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(r);
            if (!r.ok()) bad++;
        }
        shown = out;
        model.fireTableDataChanged();
        if (selected != null) {
            for (int i = 0; i < shown.size(); i++) if (shown.get(i) == selected) { table.setRowSelectionInterval(i, i); break; }
        }
        count.setText(all.stream().noneMatch(r -> only.contains(r.type())) ? "Nothing recorded yet." : shown.size() + " shown   |   " + bad + " with problems");
    }

    private void showDetail() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= shown.size()) { detail.setText("Select a line to see it in full."); return; }
        detail.setText(shown.get(r).detail());
        detail.setCaretPosition(0);
    }

    /** Light indentation of JSON for reading. */
    static String pretty(String json) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"': inStr = true; sb.append(c); break;
                case '{': case '[': sb.append(c).append('\n'); depth++; indent(sb, depth); break;
                case '}': case ']': sb.append('\n'); depth = Math.max(0, depth - 1); indent(sb, depth); sb.append(c); break;
                case ',': sb.append(",\n"); indent(sb, depth); break;
                case ':': sb.append(": "); break;
                case ' ': case '\n': case '\r': case '\t': break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int d) { for (int i = 0; i < d; i++) sb.append("  "); }

    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row row = shown.get(r);
            switch (c) {
                case 0: return when.format(new Date(row.time()));
                case 1: return row.type();
                case 2: return row.who();
                case 3: return row.what();
                case 4: return row.status() == 0 ? "-" : String.valueOf(row.status());
                case 5: return row.millis() < 0 ? "" : row.millis() + " ms";
                default: return row.answer().length() > 90 ? row.answer().substring(0, 90) + "..." : row.answer();
            }
        }
    }
}
