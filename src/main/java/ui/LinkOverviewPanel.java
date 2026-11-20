package ui;

import service.DaemonCallLog;
import service.GraphCallLog;
import service.LinkActivityLog;
import service.LinkMonitor;
import service.LinkStatusFile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Overview cards for one API: the socket links and endpoint, the Outlook mail API, or the background service. */
final class LinkOverviewPanel extends JPanel {

    private static final Color GOOD = new Color(0x2E7D32), BAD = new Color(0xB3261E), WARN = new Color(0xB26A00), IDLE = new Color(0x8A8F98);

    enum Section { SOCKET, OUTLOOK, SERVICE }

    private final JPanel body = new JPanel();
    private final Section section;

    LinkOverviewPanel(Section section) {
        this.section = section;
        setLayout(new BorderLayout());
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
    }

    void update(ApiPanel.View v) {
        body.removeAll();
        long since = v.now() - 3_600_000L;

        if (section == Section.SOCKET) {
        body.add(heading("Socket links"));
        boolean any = false;
        for (LinkMonitor.Source s : v.config().sources) {
            if (!s.enabled()) continue;
            any = true;
            LinkStatusFile.Source live = v.live() ? v.status().find(s.id()) : null;
            Color c; String state; String line;
            if (!v.live()) {
                c = IDLE; state = "Unknown"; line = "The background service is not reporting, so the state cannot be shown.";
            } else if (live == null) {
                c = IDLE; state = "Starting"; line = "Waiting for the service to pick up this source.";
            } else if (live.silent()) {
                c = BAD; state = "Silent";
                line = "Nothing received for " + ago(v.now() - live.lastHeardAt()).replace(" ago", "") + " (limit " + s.silentAfterSeconds() + " s).";
            } else if (live.state() == null) {
                c = IDLE; state = "No report yet"; line = "Nothing has been received from this source.";
            } else {
                switch (live.state()) {
                    case "CONNECTED": c = GOOD; state = "Connected"; break;
                    case "CONNECTING": c = WARN; state = "Reconnecting"; break;
                    case "STALLED": c = BAD; state = "Stalled"; break;
                    default: c = BAD; state = "Disconnected";
                }
                line = "In this state for " + ago(v.now() - live.stateSince()).replace(" ago", "")
                        + "   |   last heard " + ago(v.now() - live.lastHeardAt());
            }
            body.add(card(c, s.label(), state, line));
            body.add(Box.createVerticalStrut(6));
        }
        if (!any) {
            body.add(card(IDLE, "No sources yet", "", "Add one under Sources & tokens and connect the sending application (Connect a sender)."));
            body.add(Box.createVerticalStrut(6));
        }

        }

        if (section == Section.OUTLOOK) {
        body.add(heading("Last hour"));
        int calls = 0, failed = 0, throttled = 0, tokenFail = 0, tokens = 0;
        long totalMs = 0;
        GraphCallLog.Entry lastOk = null, lastBad = null;
        for (GraphCallLog.Entry e : v.graph()) {
            if (e.isToken()) {
                if (e.time() >= since) { tokens++; if (GraphCallLog.failed(e)) tokenFail++; }
            } else if (e.time() >= since) {
                calls++;
                totalMs += e.millis();
                if (e.status() == 429) throttled++;
                if (GraphCallLog.failed(e)) failed++;
            }
            if (e.ok()) { if (lastOk == null || e.time() > lastOk.time()) lastOk = e; }
            else if (GraphCallLog.failed(e) && (lastBad == null || e.time() > lastBad.time())) lastBad = e;
        }
        Color gc = v.graph().isEmpty() ? IDLE : (failed + tokenFail > 0 && lastBad != null && (lastOk == null || lastBad.time() > lastOk.time())) ? BAD
                : (failed + tokenFail > 0 ? WARN : GOOD);
        String gstate = v.graph().isEmpty() ? "No calls yet" : failed + tokenFail == 0 ? "Healthy" : failed + tokenFail + " failed";
        StringBuilder g = new StringBuilder();
        g.append(calls).append(" mail API calls");
        if (calls > 0) g.append(" (avg ").append(totalMs / calls).append(" ms)");
        g.append("   |   ").append(tokens).append(" sign-in requests");
        if (throttled > 0) g.append("   |   ").append(throttled).append(" throttled (429)");
        if (lastOk != null) g.append("\nLast success: ").append(GraphCallLog.describe(lastOk)).append(", ").append(ago(v.now() - lastOk.time()));
        if (lastBad != null) g.append("\nLast problem: ").append(GraphCallLog.describe(lastBad)).append(" -> ")
                .append(lastBad.status() == 0 ? "no answer" : lastBad.status()).append(", ").append(ago(v.now() - lastBad.time()))
                .append(lastBad.note().isEmpty() ? "" : "\n              " + clip(lastBad.note(), 160));
        body.add(card(gc, "Microsoft Graph (Outlook)", gstate, g.toString()));
        }

        if (section == Section.SERVICE) {
        body.add(heading("Last hour"));
        int cmds = 0, cmdFail = 0, queued = 0;
        DaemonCallLog.Entry lastCmd = null, lastCmdBad = null;
        for (DaemonCallLog.Entry e : v.daemon()) {
            if (e.action().isEmpty() || e.time() < since) continue;
            if (e.status() == 202) { queued++; continue; }
            cmds++;
            if (!e.ok()) { cmdFail++; if (lastCmdBad == null || e.time() > lastCmdBad.time()) lastCmdBad = e; }
            if (lastCmd == null || e.time() > lastCmd.time()) lastCmd = e;
        }
        StringBuilder dm = new StringBuilder();
        dm.append(cmds).append(" commands run, ").append(queued).append(" queued while the service was unreachable");
        if (lastCmd != null) dm.append("\nLast command: ").append(DaemonCallLog.describe(lastCmd)).append(", ").append(ago(v.now() - lastCmd.time()));
        if (lastCmdBad != null) dm.append("\nLast failure: ").append(DaemonCallLog.describe(lastCmdBad)).append(" -> ")
                .append(lastCmdBad.status()).append(", ").append(clip(lastCmdBad.response(), 140));
        Color dc = v.daemon().isEmpty() ? IDLE : cmdFail > 0 ? WARN : GOOD;
        String ds = v.daemon().isEmpty() ? "No traffic yet" : cmdFail > 0 ? cmdFail + " failed" : "Healthy";
        body.add(card(dc, "Control endpoint and command queue", ds, dm.toString()));
        }

        if (section == Section.SOCKET) {
        body.add(Box.createVerticalStrut(14));
        body.add(heading("This PC's endpoint"));
        StringBuilder ep = new StringBuilder();
        ep.append(v.primaryUrl());
        if (v.config().tlsEnabled && v.cert() != null) {
            ep.append("\nCertificate valid until ").append(new SimpleDateFormat("yyyy-MM-dd").format(v.cert().notAfter()));
        } else if (!v.config().tlsEnabled) {
            ep.append("\nHTTPS is off");
        }
        long hour = v.countSince(since, false);
        ep.append("\n").append(hour).append(" requests in the last hour, ").append(v.countSince(since, true)).append(" turned away");
        Color ec = !v.config().enabled() ? IDLE : !v.live() || !v.status().listening() ? BAD : GOOD;
        String es = !v.config().enabled() ? "Not active" : !v.live() ? "Service not running" : !v.status().listening() ? "Not listening" : "Listening on port " + v.status().port();
        body.add(card(ec, "Link endpoint", es, ep.toString()));
        }
        body.add(Box.createVerticalGlue());
        body.revalidate();
        body.repaint();
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setBorder(new EmptyBorder(2, 2, 6, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent card(Color accent, String title, String state, String text) {
        JPanel card = new JPanel(new BorderLayout(10, 0));
        Color border = UIManager.getColor("Component.borderColor") != null ? UIManager.getColor("Component.borderColor") : Color.LIGHT_GRAY;
        card.setBorder(BorderFactory.createLineBorder(border, 1, true));
        JPanel bar = new JPanel();
        bar.setBackground(accent);
        bar.setPreferredSize(new Dimension(6, 10));
        card.add(bar, BorderLayout.WEST);

        JPanel inner = new JPanel(new BorderLayout(0, 2));
        inner.setBorder(new EmptyBorder(8, 2, 8, 10));
        JPanel top = new JPanel(new BorderLayout());
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 14f));
        top.add(t, BorderLayout.WEST);
        if (!state.isEmpty()) {
            JLabel s = new JLabel(state);
            s.setFont(s.getFont().deriveFont(Font.BOLD, 13f));
            s.setForeground(accent);
            top.add(s, BorderLayout.EAST);
        }
        inner.add(top, BorderLayout.NORTH);
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setFocusable(false);
        ta.setOpaque(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(ta.getFont().deriveFont(Font.PLAIN, 12f));
        inner.add(ta, BorderLayout.CENTER);
        card.add(inner, BorderLayout.CENTER);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 40));
        return card;
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }

    private static String ago(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 90) return sec + " s ago";
        if (sec < 5400) return (sec / 60) + " min ago";
        if (sec < 172800) return (sec / 3600) + " h ago";
        return (sec / 86400) + " days ago";
    }
}
