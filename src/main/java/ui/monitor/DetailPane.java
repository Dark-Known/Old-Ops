package ui.monitor;

import model.EventKind;
import model.TaskRunRecord;
import service.RunHistoryService;
import ui.AppTheme;
import ui.RunLogSummarizer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything about the selected row, in one place — so the feed itself can
 * stay a one-line-per-event list without cramming information into it.
 *
 * <p>Shows, depending on what is selected: when/how long/what triggered it,
 * the full message, why it failed and what to do about it, the files and
 * folders involved in a transfer, the timeline of a run, and (on demand) the
 * raw captured log. The potentially large log text is loaded from the history
 * database only for the selected row, on a background thread — the live feed
 * itself never reads it.
 */
public class DetailPane extends JPanel {

    private static final int MAX_FILES = 25;
    private static final int MAX_LOG_CHARS = 200_000;

    private final RunHistoryService history;
    private final Consumer<String> onlyTask;

    private final CardLayout cards = new CardLayout();
    private final JLabel title = new JLabel();
    private final JLabel subtitle = new JLabel();
    private final JEditorPane body = new JEditorPane();
    private final JTextArea logArea = new JTextArea();
    private final JScrollPane logScroll = new JScrollPane(logArea);
    private final JToggleButton logToggle = new JToggleButton("Show raw log");
    private final JButton copyBtn = new JButton("Copy");
    private final JButton onlyBtn = new JButton("Only this task");

    private FeedRow current;
    private String currentText = "";
    private String currentLog = "";
    private int seq;
    private final Map<String, TaskRunRecord> recordCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TaskRunRecord> e) { return size() > 60; }
    };

    public DetailPane(RunHistoryService history, Consumer<String> onlyTask) {
        this.history = history;
        this.onlyTask = onlyTask;
        setLayout(cards);

        JLabel hint = new JLabel("<html><div style='text-align:center;width:200px'><b>Select an event</b><br>"
                + "to see its full details, the files involved, and the timeline of the run.</div></html>",
                SwingConstants.CENTER);
        hint.setForeground(MonitorStyle.muted());
        add(hint, "empty");
        add(buildContent(), "content");
        cards.show(this, "empty");
    }

    private JComponent buildContent() {
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        subtitle.setForeground(MonitorStyle.muted());
        subtitle.setFont(subtitle.getFont().deriveFont(11.5f));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(new EmptyBorder(0, 0, 8, 0));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(title);
        head.add(Box.createVerticalStrut(2));
        head.add(subtitle);

        body.setEditorKit(new HTMLEditorKit());
        body.setEditable(false);
        body.setOpaque(false);
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.FALSE);
        body.setBorder(new EmptyBorder(0, 0, 0, 0));

        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logScroll.setPreferredSize(new Dimension(100, 180));
        logScroll.setVisible(false);

        // Must track the viewport's width, otherwise the HTML body lays out at its natural (unwrapped)
        // width and long messages are clipped instead of wrapping.
        JPanel scrollBody = new WidthTrackingPanel(new BorderLayout(0, 8));
        scrollBody.setOpaque(false);
        scrollBody.add(body, BorderLayout.NORTH);
        scrollBody.add(logScroll, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(scrollBody);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        logToggle.addActionListener(e -> {
            logScroll.setVisible(logToggle.isSelected());
            logToggle.setText(logToggle.isSelected() ? "Hide raw log" : "Show raw log");
            revalidate();
        });
        copyBtn.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(currentText + (currentLog.isEmpty() ? "" : "\n\n--- Raw log ---\n" + currentLog)), null));
        onlyBtn.addActionListener(e -> { if (current != null && current.taskId != null) onlyTask.accept(current.taskId); });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        actions.add(copyBtn);
        actions.add(onlyBtn);
        actions.add(logToggle);

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        p.add(head, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(actions, BorderLayout.SOUTH);
        return p;
    }

    /** Shows {@code row}, or the empty hint for null. Safe to call with the same row repeatedly. */
    public void setRow(FeedRow row) {
        current = row;
        final int mySeq = ++seq;
        if (row == null) { cards.show(this, "empty"); return; }
        cards.show(this, "content");

        title.setText(row.taskName);
        Color[] c = MonitorStyle.pill(row.outcome);
        subtitle.setText("<html><span style='color:" + MonitorStyle.hex(MonitorStyle.onSurface(c[0])) + "'><b>" + row.outcome.label()
                + "</b></span>  \u00b7  " + MonitorStyle.htmlEscape(headline(row)) + "</html>");
        onlyBtn.setVisible(row.taskId != null && row.head.kind().category() != EventKind.Category.SYSTEM);

        // Render immediately from what the feed already knows, then enrich once the log arrives.
        render(row, Map.of());

        List<MonitorEvent> need = new ArrayList<>();
        for (MonitorEvent e : row.events) {
            if (e.isOutcome() || e.kind() == EventKind.FILES_DETECTED || row.type == FeedRow.Type.EVENT) need.add(e);
        }
        if (row.type == FeedRow.Type.BUCKET || need.isEmpty()) { applyLog(row, Map.of()); return; }

        Map<String, TaskRunRecord> have = new HashMap<>();
        List<MonitorEvent> missing = new ArrayList<>();
        for (MonitorEvent e : need) {
            TaskRunRecord r = recordCache.get(e.key());
            if (r != null) have.put(e.key(), r); else missing.add(e);
        }
        if (missing.isEmpty()) { applyLog(row, have); return; }

        new SwingWorker<Map<String, TaskRunRecord>, Void>() {
            @Override protected Map<String, TaskRunRecord> doInBackground() {
                Map<String, TaskRunRecord> out = new HashMap<>();
                for (MonitorEvent e : missing) {
                    TaskRunRecord r = e.table() == MonitorEvent.Table.RUN ? history.getRunById(e.id())
                            : history.getActivityEventById(e.id());
                    if (r != null) out.put(e.key(), r);
                }
                return out;
            }
            @Override protected void done() {
                if (mySeq != seq) return;     // the selection moved on while this was loading
                try {
                    Map<String, TaskRunRecord> got = get();
                    recordCache.putAll(got);
                    Map<String, TaskRunRecord> all = new HashMap<>(have);
                    all.putAll(got);
                    applyLog(row, all);
                } catch (Exception ex) {
                    applyLog(row, have);
                }
            }
        }.execute();
    }

    private void applyLog(FeedRow row, Map<String, TaskRunRecord> records) {
        render(row, records);
        StringBuilder log = new StringBuilder();
        for (MonitorEvent e : row.events) {
            TaskRunRecord r = records.get(e.key());
            if (r == null || r.getDetails() == null || r.getDetails().isBlank()) continue;
            if (r.getDetails().strip().equals(r.getReason() != null ? r.getReason().strip() : "")) continue; // nothing beyond the summary
            if (log.length() > 0) log.append("\n\n");
            if (row.events.size() > 1) log.append("\u2500\u2500 ").append(e.kind().label()).append("  ").append(MonitorStyle.time(e.time())).append(" \u2500\u2500\n");
            log.append(r.getDetails().strip());
        }
        currentLog = log.length() > MAX_LOG_CHARS ? log.substring(0, MAX_LOG_CHARS) + "\n\u2026 (truncated)" : log.toString();
        logArea.setText(currentLog);
        logArea.setCaretPosition(0);
        logToggle.setVisible(!currentLog.isEmpty());
        if (currentLog.isEmpty()) { logToggle.setSelected(false); logToggle.setText("Show raw log"); logScroll.setVisible(false); }
    }

    private static String headline(FeedRow row) {
        return switch (row.type) {
            case BUCKET -> row.count + " identical runs";
            case RUN -> "Run \u00b7 " + row.count + " events";
            case EVENT -> row.head.kind().category().label() + " \u00b7 " + row.head.kind().label();
        };
    }

    // ── Rendering ───────────────────────────────────────────────────

    private void render(FeedRow row, Map<String, TaskRunRecord> records) {
        String fg = MonitorStyle.hex(MonitorStyle.fg());
        String muted = MonitorStyle.hex(MonitorStyle.muted());
        StyleSheet css = ((HTMLEditorKit) body.getEditorKit()).getStyleSheet();
        css.addRule("body { font-family: sans-serif; font-size: 11pt; color: " + fg + "; margin: 0; }");
        css.addRule("td { vertical-align: top; padding: 1px 6px 1px 0; }");
        css.addRule("h4 { margin: 10px 0 3px 0; font-size: 10pt; color: " + muted + "; }");
        css.addRule(".m { color: " + muted + "; }");
        css.addRule(".bad { color: " + MonitorStyle.hex(MonitorStyle.onSurface(AppTheme.FAILED_FG)) + "; }");
        css.addRule(".warn { color: " + MonitorStyle.hex(MonitorStyle.onSurface(AppTheme.SKIPPED_FG)) + "; }");

        StringBuilder h = new StringBuilder("<html><body>");
        StringBuilder t = new StringBuilder();           // plain-text twin for Copy
        t.append(row.taskName).append("  [").append(row.outcome.label()).append("]  ").append(headline(row)).append('\n');

        // --- Facts ---
        h.append("<table cellspacing='0'>");
        fact(h, t, "When", MonitorStyle.fullTime(row.time));
        if (row.type != FeedRow.Type.EVENT && row.firstTime != null && !row.firstTime.equals(row.time)) {
            fact(h, t, row.type == FeedRow.Type.BUCKET ? "Since" : "Began", MonitorStyle.fullTime(row.firstTime));
        }
        if (row.durationMs >= 0) {
            fact(h, t, row.type == FeedRow.Type.BUCKET ? "Average time" : row.outcome == FeedRow.Outcome.RUNNING ? "Elapsed" : "Took",
                    MonitorStyle.duration(row.durationMs));
        }
        if (row.trigger != null) fact(h, t, "Triggered by", triggerExplain(row.trigger));
        if (row.taskType != null) fact(h, t, "Task type", row.taskType.replace('_', ' ').toLowerCase(Locale.ROOT));
        if (row.head.runKey() != null) fact(h, t, "Run ID", row.head.runKey());
        h.append("</table>");

        // --- Message ---
        String message = fullMessage(row, records);
        if (!message.isBlank() && row.type != FeedRow.Type.BUCKET) {
            boolean bad = row.outcome == FeedRow.Outcome.FAILED || row.outcome == FeedRow.Outcome.ERROR;
            h.append("<h4>").append(bad ? "WHAT WENT WRONG" : "SUMMARY").append("</h4><div class='").append(bad ? "bad" : "")
                    .append("'>").append(MonitorStyle.htmlEscape(message).replace("\n", "<br>")).append("</div>");
            t.append('\n').append(message).append('\n');
        }

        // --- Failure guidance ---
        MonitorEvent failed = failedEvent(row);
        if (failed != null && failed.failureCategory() != null) {
            h.append("<h4>WHAT TO DO</h4>");
            String cat = categoryLabel(failed.failureCategory());
            String retry = failed.retryable() ? "usually clears up on retry" : "needs a fix before retrying";
            h.append("<b>").append(cat).append("</b> <span class='m'>\u2014 ").append(retry).append("</span>");
            t.append("\nCategory: ").append(cat).append(" (").append(retry).append(")\n");
            if (failed.suggestedAction() != null && !failed.suggestedAction().isBlank()) {
                h.append("<br>").append(MonitorStyle.htmlEscape(failed.suggestedAction()));
                t.append("Suggested: ").append(failed.suggestedAction()).append('\n');
            }
            h.append("<br><span class='m'>This stays in the Health Feed until the task is re-run or edited.</span>");
        }

        // --- Transfer details (from the run log) ---
        if (row.type != FeedRow.Type.BUCKET) transferSection(h, t, row, records);

        // --- Timeline of a run ---
        if (row.type == FeedRow.Type.RUN) timeline(h, t, row);
        if (row.type == FeedRow.Type.BUCKET) bucketSection(h, t, row);

        h.append("</body></html>");
        currentText = t.toString();
        body.setText(h.toString());
        body.setCaretPosition(0);
    }

    private static void fact(StringBuilder h, StringBuilder t, String k, String v) {
        h.append("<tr><td class='m'>").append(k).append("</td><td>").append(MonitorStyle.htmlEscape(v)).append("</td></tr>");
        t.append(k).append(": ").append(v).append('\n');
    }

    private static String triggerExplain(String trig) {
        return switch (trig) {
            case "SCHEDULE" -> "its schedule";
            case "WATCHER" -> "the watcher (a file changed)";
            case "MANUAL" -> "a manual run (Run Now)";
            case "RETRY" -> "an automatic retry";
            default -> trig;
        };
    }

    private static MonitorEvent failedEvent(FeedRow row) {
        for (MonitorEvent e : row.events) if (e.kind() == EventKind.RUN_FAILED) return e;
        return row.head.kind() == EventKind.RUN_FAILED ? row.head : null;
    }

    /** The untruncated message of the row's headline event, when the record has been loaded. */
    private static String fullMessage(FeedRow row, Map<String, TaskRunRecord> records) {
        TaskRunRecord r = records.get(row.head.key());
        String s = r != null && r.getReason() != null && !r.getReason().isBlank() ? r.getReason() : row.summary;
        return s == null ? "" : s.strip();
    }

    private void transferSection(StringBuilder h, StringBuilder t, FeedRow row, Map<String, TaskRunRecord> records) {
        // Detection: the files a scan found.
        for (MonitorEvent e : row.events) {
            if (e.kind() != EventKind.FILES_DETECTED) continue;
            TaskRunRecord r = records.get(e.key());
            List<String> found = r != null ? parseDetected(r.getDetails()) : List.of();
            if (found.isEmpty()) continue;
            h.append("<h4>FILES FOUND BY THE SCAN</h4>");
            t.append("\nFiles found:\n");
            listFiles(h, t, found);
            break;
        }
        // Outcome: parsed transfer summary.
        MonitorEvent outcome = null;
        for (MonitorEvent e : row.events) if (e.isOutcome()) outcome = e;
        if (outcome == null || !"FILE_TRANSFER".equals(outcome.taskType())) return;
        TaskRunRecord r = records.get(outcome.key());
        if (r == null) return;
        RunLogSummarizer.FileTransferSummary s = RunLogSummarizer.parse(r.getDetails());
        if (s == null) return;

        h.append("<h4>TRANSFER</h4><table cellspacing='0'>");
        t.append("\nTransfer:\n");
        if (s.direction() != null) fact(h, t, "Direction", s.direction().equals("INBOUND") ? "Inbound (remote \u2192 local)" : s.direction().equals("OUTBOUND") ? "Outbound (local \u2192 remote)" : s.direction());
        if (s.sourcePath() != null) fact(h, t, "From", s.sourcePath());
        if (s.destPath() != null) fact(h, t, "To", s.destPath());
        fact(h, t, "Files", String.valueOf(s.fileCount()));
        if (s.totalBytesFormatted() != null) fact(h, t, "Total size", s.totalBytesFormatted());
        if (s.batchCount() > 0) fact(h, t, "Batches", String.valueOf(s.batchCount()));
        if (s.sessionCount() > 0) fact(h, t, "SFTP/WinSCP sessions", String.valueOf(s.sessionCount()));
        if (s.workerThreads() > 0) fact(h, t, "Worker threads", String.valueOf(s.workerThreads()));
        h.append("</table>");
        if (!s.files().isEmpty() && !hasDetectedList(row)) {
            h.append("<h4>FILES</h4>");
            List<String> names = new ArrayList<>();
            for (RunLogSummarizer.FileEntry f : s.files()) names.add(f.name() + (f.sizeFormatted() != null ? "  \u2014  " + f.sizeFormatted() : ""));
            listFiles(h, t, names);
        }
    }

    private static boolean hasDetectedList(FeedRow row) {
        for (MonitorEvent e : row.events) if (e.kind() == EventKind.FILES_DETECTED) return true;
        return false;
    }

    private static void listFiles(StringBuilder h, StringBuilder t, List<String> files) {
        int shown = Math.min(files.size(), MAX_FILES);
        for (int i = 0; i < shown; i++) {
            h.append("\u2022 ").append(MonitorStyle.htmlEscape(files.get(i))).append("<br>");
            t.append("  - ").append(files.get(i)).append('\n');
        }
        if (files.size() > shown) {
            h.append("<span class='m'>\u2026 and ").append(files.size() - shown).append(" more</span>");
            t.append("  ... and ").append(files.size() - shown).append(" more\n");
        }
    }

    /** Lines like "[INFO]   name | lastModified=... | size=123" written by the detection event. */
    private static List<String> parseDetected(String details) {
        List<String> out = new ArrayList<>();
        if (details == null) return out;
        for (String line : details.split("\\R")) {
            String l = line.trim();
            if (!l.startsWith("[INFO]") || !l.contains("| size=")) continue;
            String rest = l.substring("[INFO]".length()).trim();
            int bar = rest.indexOf('|');
            String name = bar > 0 ? rest.substring(0, bar).trim() : rest;
            String sizePart = rest.substring(rest.indexOf("size=") + 5).trim();
            try { out.add(name + "  \u2014  " + RunLogSummarizer.formatBytes(Long.parseLong(sizePart))); }
            catch (NumberFormatException nfe) { out.add(name); }
        }
        return out;
    }

    private void timeline(StringBuilder h, StringBuilder t, FeedRow row) {
        h.append("<h4>TIMELINE</h4><table cellspacing='0'>");
        t.append("\nTimeline:\n");
        java.time.LocalDateTime t0 = row.firstTime;
        for (MonitorEvent e : row.events) {
            long offset = t0 != null ? Duration.between(t0, e.time()).toMillis() : 0;
            String when = MonitorStyle.time(e.time());
            String off = offset > 0 ? "+" + MonitorStyle.duration(offset) : "";
            String cls = e.severity() == EventKind.Severity.ERROR ? "bad" : e.severity() == EventKind.Severity.WARN ? "warn" : "";
            h.append("<tr><td class='m'>").append(when).append("</td><td class='m'>").append(off).append("</td><td class='")
                    .append(cls).append("'><b>").append(MonitorStyle.htmlEscape(e.isOutcome() ? "Outcome" : e.kind().label()))
                    .append("</b> \u2014 ").append(MonitorStyle.htmlEscape(e.summary())).append("</td></tr>");
            t.append("  ").append(when).append("  ").append(off).append("  ").append(e.kind().label()).append(": ").append(e.summary()).append('\n');
        }
        h.append("</table>");
    }

    private void bucketSection(StringBuilder h, StringBuilder t, FeedRow row) {
        h.append("<h4>").append(row.count).append(" IDENTICAL RUNS</h4>");
        h.append("<span class='m'>Every run ended the same way: </span>").append(MonitorStyle.htmlEscape(row.summary)).append("<br><br>");
        t.append('\n').append(row.count).append(" identical runs: ").append(row.summary).append('\n');
        int shown = Math.min(row.members.size(), 12);
        for (int i = 0; i < shown; i++) {
            FeedRow m = row.members.get(i);
            h.append("<span class='m'>").append(MonitorStyle.time(m.time)).append("</span>")
                    .append(m.durationMs >= 0 ? "  \u00b7  " + MonitorStyle.duration(m.durationMs) : "")
                    .append(m.trigger != null ? "  \u00b7  " + MonitorStyle.triggerLabel(m.trigger) : "").append("<br>");
            t.append("  ").append(MonitorStyle.time(m.time)).append('\n');
        }
        if (row.members.size() > shown) h.append("<span class='m'>\u2026 and ").append(row.members.size() - shown).append(" more \u2014 expand the row to see each one</span>");
        h.append("<br><br><span class='m'>Repeated runs with nothing to do are grouped to keep the feed readable. "
                + "Turn this off in Options \u2192 Collapse identical repeated runs.</span>");
    }

    private static String categoryLabel(TaskRunRecord.FailureCategory c) {
        return switch (c) {
            case NETWORK -> "Network";
            case AUTH -> "Authentication";
            case PERMISSION -> "Permission";
            case DISK_SPACE -> "Disk space";
            case CONFIG -> "Configuration";
            case TIMEOUT -> "Timeout";
            case ORPHANED -> "Orphaned (process stopped mid-run)";
            case UNKNOWN -> "Unclassified";
        };
    }
}
