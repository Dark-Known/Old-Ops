package service;

import model.EventKind;
import model.TaskRunRecord;

import java.time.LocalDateTime;

/**
 * Turns the Outlook mail call log and the service command log into events, so they can alert like everything else:
 * the mail API after {@link #FAIL_AFTER} failed calls in a row (and again when it works), and any command to the
 * background service that failed. Runs inside the service; the events show in the Event Monitor.
 */
public final class MonitorEvents {

    static final int FAIL_AFTER = 3;

    private final RunHistoryService history;
    private int graphFailures;
    private boolean graphFailing;
    private String lastGraphError = "";

    public MonitorEvents(RunHistoryService history) { this.history = history; }

    public void start() {
        GraphCallLog.setListener(this::onGraph);
        DaemonCallLog.setListener(this::onDaemon);
    }

    public void close() {
        GraphCallLog.setListener(null);
        DaemonCallLog.setListener(null);
    }

    synchronized void onGraph(GraphCallLog.Entry e) {
        boolean ok = e.ok();
        boolean failed = GraphCallLog.failed(e);
        LocalDateTime now = LocalDateTime.now();
        if (ok) {
            graphFailures = 0;
            if (graphFailing) {
                graphFailing = false;
                history.recordEvent(EventKind.GRAPH_RECOVERED, AlertConfig.OUTLOOK, "Outlook mail", "OUTLOOK", TaskRunRecord.Status.SUCCESS,
                        "Outlook mail API is working again", "Last call: " + GraphCallLog.describe(e), now, now, null, null);
            }
            return;
        }
        if (!failed) return;
        lastGraphError = GraphCallLog.describe(e) + " -> " + (e.status() == 0 ? "no answer" : "HTTP " + e.status())
                + (e.note().isEmpty() ? "" : " " + e.note());
        if (++graphFailures >= FAIL_AFTER && !graphFailing) {
            graphFailing = true;
            history.recordEvent(EventKind.GRAPH_FAILING, AlertConfig.OUTLOOK, "Outlook mail", "OUTLOOK", TaskRunRecord.Status.FAILED,
                    "Outlook mail API failed " + graphFailures + " calls in a row", "Latest: " + lastGraphError
                    + (e.mailbox().isEmpty() ? "" : "\nMailbox: " + e.mailbox()), now, now, null, null);
        }
    }

    void onDaemon(DaemonCallLog.Entry e) {
        if (e.action().isEmpty() || e.ok() || e.status() == 202) return;
        LocalDateTime now = LocalDateTime.now();
        history.recordEvent(EventKind.SERVICE_COMMAND_FAILED, AlertConfig.SERVICE, "Background service", "SERVICE", TaskRunRecord.Status.FAILED,
                "Service command " + e.action() + " failed (" + (e.status() == 0 ? "no answer" : "HTTP " + e.status()) + ")",
                DaemonCallLog.describe(e) + "\n" + e.response(), now, now, null, e.requestedBy());
    }
}
