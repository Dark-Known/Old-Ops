package ui;

import model.ScheduledTask;
import service.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The "API" page: every API the tool exposes or calls (socket link reports, Outlook mail, the background service) in one place. A summary strip on top,
 * then Activity (every request and response), Sources (tokens and thresholds), Connect a sender and Certificate.
 */
public class ApiPanel extends JPanel {

    private static final Color GOOD = new Color(0x2E7D32), BAD = new Color(0xB3261E), WARN = new Color(0xB26A00), IDLE = new Color(0x8A8F98);
    private final File dataDir;
    private final Item socket = new Item("Socket link", "Reports from another server");
    private final Item outlook = new Item("Outlook mail", "Graph calls and sign-in");
    private final Item service = new Item("Background service", "Commands and status");
    private final Item teams = new Item("Teams alerts", "Cards sent to a Teams channel");
    private final DefaultListModel<Item> items = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(items);
    private final CardLayout cards = new CardLayout();
    private final JPanel detail = new JPanel(cards);
    private final JLabel socketBadge = badge(), outlookBadge = badge(), serviceBadge = badge(), teamsBadge = badge();
    private final LinkOverviewPanel socketOverview = new LinkOverviewPanel(LinkOverviewPanel.Section.SOCKET);
    private final LinkOverviewPanel outlookOverview = new LinkOverviewPanel(LinkOverviewPanel.Section.OUTLOOK);
    private final LinkOverviewPanel serviceOverview = new LinkOverviewPanel(LinkOverviewPanel.Section.SERVICE);
    private final LinkConnectPanel connect;
    private javax.swing.Timer timer;
    public ApiPanel(File dataDir, Supplier<List<ScheduledTask>> tasks, service.RunHistoryService history) {
        this.dataDir = dataDir;
        this.connect = new LinkConnectPanel(dataDir);
        setLayout(new BorderLayout(10, 0));
        setBorder(new EmptyBorder(12, 14, 12, 14));

        items.addElement(socket);
        items.addElement(outlook);
        items.addElement(service);
        items.addElement(teams);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(64);
        list.setCellRenderer(new ItemRenderer());
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0)
                cards.show(detail, new String[]{"socket", "outlook", "service", "teams"}[i]);
        });
        JScrollPane left = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        left.setPreferredSize(new Dimension(240, 10));
        left.setBorder(BorderFactory.createLineBorder(border()));

        JTabbedPane socketTabs = new JTabbedPane();
        socketTabs.addTab("Overview", socketOverview);
        socketTabs.addTab("Activity", new LinkActivityPanel(dataDir, LinkActivityPanel.LINK));
        socketTabs.addTab("Sources & tokens", new LinkMonitorPanel(dataDir));
        socketTabs.addTab("Connect a sender", connect.connectTab);
        socketTabs.addTab("Certificate", connect.certTab);
        detail.add(page("Socket link monitor",
                "Another server's application reports the state of a socket connection to this PC.", socketBadge, socketTabs), "socket");

        JTabbedPane outlookTabs = new JTabbedPane();
        outlookTabs.addTab("Overview", outlookOverview);
        outlookTabs.addTab("Activity", new LinkActivityPanel(dataDir, LinkActivityPanel.GRAPH, LinkActivityPanel.TOKEN));
        detail.add(page("Outlook mail (Microsoft Graph)",
                "Every call this tool makes to read and move mail, and its sign-in requests.", outlookBadge, outlookTabs), "outlook");

        JTabbedPane serviceTabs = new JTabbedPane();
        serviceTabs.addTab("Overview", serviceOverview);
        serviceTabs.addTab("Activity", new LinkActivityPanel(dataDir, LinkActivityPanel.DAEMON));
        detail.add(page("Background service",
                "Commands the window sends to the service (Run now, Cancel...), status polls and the command queue.", serviceBadge, serviceTabs), "service");

        JTabbedPane teamsTabs = new JTabbedPane();
        teamsTabs.addTab("Settings", new AlertSettingsPanel(dataDir, tasks, history));
        teamsTabs.addTab("Activity", new LinkActivityPanel(dataDir, LinkActivityPanel.ALERT));
        detail.add(page("Teams alerts",
                "Problems posted to Teams as a card with an offline log report.", teamsBadge, teamsTabs), "teams");

        add(left, BorderLayout.WEST);
        add(detail, BorderLayout.CENTER);
        list.setSelectedIndex(0);
    }

    private static Color border() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : Color.LIGHT_GRAY;
    }

    private static JLabel badge() {
        JLabel l = new JLabel(" ");
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }

    /**
     * A detail page: title, one line saying what it is, this API's own status badge, then its tabs.
     */
    private static JPanel page(String title, String description, JLabel badge, JTabbedPane tabs) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        JPanel head = new JPanel(new BorderLayout());
        JPanel texts = new JPanel(new GridLayout(2, 1));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 16f));
        JLabel d = new JLabel(description);
        d.setForeground(Color.GRAY);
        texts.add(t);
        texts.add(d);
        head.add(texts, BorderLayout.CENTER);
        head.add(badge, BorderLayout.EAST);
        head.setBorder(new EmptyBorder(0, 4, 0, 4));
        p.add(head, BorderLayout.NORTH);
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private static void set(Item it, JLabel badge, String text, Color c) {
        it.status = text;
        it.color = c;
        badge.setText(text);
        badge.setForeground(c);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        reload();
        if (timer == null) {
            timer = new javax.swing.Timer(4000, e -> reload());
            timer.start();
        }
    }

    @Override
    public void removeNotify() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        super.removeNotify();
    }

    private void reload() {
        Async.latest(this, () -> {
            String local;
            try {
                local = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                local = "this-pc";
            }
            return new View(LinkMonitorConfig.load(dataDir), LinkStatusFile.read(dataDir), LinkTls.describe(dataDir),
                    LinkActivityLog.readRecent(dataDir, 1000), GraphCallLog.readRecent(dataDir, 1000),
                    DaemonCallLog.readRecent(dataDir, 1000), AlertCallLog.readRecent(dataDir, 1000), AlertConfig.load(dataDir), System.currentTimeMillis(), local);
        }, this::apply, e -> System.err.println("API panel could not be refreshed: " + e));
    }

    private void apply(View v) {
        long hour = v.now() - 3_600_000L;

        // Socket link: listening state and link health
        int n = 0;
        for (var s : v.config().sources) if (s.enabled()) n++;
        if (!v.config().enabled()) {
            set(socket, socketBadge, "Not set up", IDLE);
        } else if (!v.live()) {
            set(socket, socketBadge, "Service not running", BAD);
        } else if (!v.status().listening()) {
            set(socket, socketBadge, "Not listening", BAD);
        } else if (v.status().problems() > 0) {
            set(socket, socketBadge, v.status().problems() + " of " + n + " links down", BAD);
        } else {
            set(socket, socketBadge, "Listening :" + v.status().port() + (n > 0 ? "  |  " + n + " healthy" : ""), GOOD);
        }
        if (!v.config().enabled()) socket.status = "No sources yet";
        else if (v.live() && v.status().listening() && v.status().problems() == 0)
            socket.status = n + (n == 1 ? " link healthy" : " links healthy");

        // Outlook: failures in the last hour
        int calls = 0, failed = 0;
        for (GraphCallLog.Entry e : v.graph())
            if (e.time() >= hour) {
                calls++;
                if (GraphCallLog.failed(e)) failed++;
            }
        if (v.graph().isEmpty()) set(outlook, outlookBadge, "No calls yet", IDLE);
        else if (failed > 0) set(outlook, outlookBadge, failed + " of " + calls + " calls failed (last hour)", BAD);
        else set(outlook, outlookBadge, calls + " calls in the last hour, OK", GOOD);

        // Background service: commands that failed in the last hour
        int cmds = 0, cmdFail = 0;
        for (DaemonCallLog.Entry e : v.daemon()) {
            if (e.action().isEmpty() || e.time() < hour || e.status() == 202) continue;
            cmds++;
            if (!e.ok()) cmdFail++;
        }
        if (v.daemon().isEmpty()) set(service, serviceBadge, "No traffic yet", IDLE);
        else if (cmdFail > 0) set(service, serviceBadge, cmdFail + " of " + cmds + " commands failed", WARN);
        else set(service, serviceBadge, cmds + " commands in the last hour, OK", GOOD);

        // Teams alerts: configuration, then deliveries in the last day
        AlertConfig ac = v.alertConfig();
        long day = v.now() - 86_400_000L;
        int sent = 0, notSent = 0;
        for (AlertCallLog.Entry e : v.alerts())
            if (e.time() >= day && (e.ok() || e.attempt() >= 3)) {
                if (e.ok()) sent++;
                else notSent++;
            }
        if (!ac.ready()) set(teams, teamsBadge, ac.enabled ? "No webhook address" : "Off", IDLE);
        else if (notSent > 0)
            set(teams, teamsBadge, notSent + (notSent == 1 ? " alert" : " alerts") + " not delivered (24 h)", BAD);
        else set(teams, teamsBadge, "On  |  " + sent + " sent (24 h)  |  " + ac.webhookHost(), GOOD);

        list.repaint();
        socketOverview.update(v);
        outlookOverview.update(v);
        serviceOverview.update(v);
        connect.update(v);
    }

    /**
     * Everything the tabs need, read together off the UI thread.
     */
    record View(LinkMonitorConfig config, LinkStatusFile.Status status, LinkTls.Material cert,
                List<LinkActivityLog.Entry> recent, List<GraphCallLog.Entry> graph, List<DaemonCallLog.Entry> daemon,
                List<AlertCallLog.Entry> alerts, AlertConfig alertConfig, long now, String localName) {

        boolean live() {
            return status != null && status.fresh(now);
        }

        List<String> addresses() {
            String scheme = config.tlsEnabled ? "https://" : "http://";
            Set<String> out = new LinkedHashSet<>();
            if (status != null) {
                for (String a : status.addresses()) {
                    if (!a.contains("//localhost") && !a.contains("//127.")) out.add(a);
                }
            }
            if (out.isEmpty()) {
                if (!config.tlsHostnames.isEmpty())
                    for (String h : config.tlsHostnames) out.add(scheme + h + ":" + config.port);
                out.add(scheme + localName + ":" + config.port);
            }
            return new ArrayList<>(out);
        }

        String primaryUrl() {
            return addresses().get(0);
        }

        List<String> hostNames() {
            List<String> out = new ArrayList<>();
            for (String a : addresses()) {
                String s = a.substring(a.indexOf("//") + 2);
                int c = s.lastIndexOf(':');
                out.add(c > 0 ? s.substring(0, c) : s);
            }
            return out;
        }

        long countSince(long since, boolean problemsOnly) {
            long n = 0;
            for (LinkActivityLog.Entry e : recent) {
                if (e.time() < since || e.path().endsWith("/health")) continue;
                if (!problemsOnly || !e.ok()) n++;
            }
            return n;
        }
    }

    /**
     * One entry of the list on the left.
     */
    private static final class Item {
        final String title, description;
        String status = "";
        Color color = IDLE;

        Item(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static final class ItemRenderer extends JPanel implements ListCellRenderer<Item> {
        private final JLabel title = new JLabel(), desc = new JLabel(), status = new JLabel();
        private final JPanel bar = new JPanel();

        ItemRenderer() {
            setLayout(new BorderLayout(8, 0));
            JPanel text = new JPanel(new GridLayout(3, 1));
            text.setOpaque(false);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
            desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 11f));
            desc.setForeground(Color.GRAY);
            status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
            text.add(title);
            text.add(desc);
            text.add(status);
            bar.setPreferredSize(new Dimension(6, 10));
            add(bar, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            setBorder(new EmptyBorder(4, 0, 4, 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item it, int i, boolean sel, boolean focus) {
            title.setText(it.title);
            desc.setText(it.description);
            status.setText(it.status.isEmpty() ? " " : it.status);
            status.setForeground(it.color);
            bar.setBackground(it.color);
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            title.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            setOpaque(true);
            return this;
        }
    }
}
