package ui.monitor;

import java.util.*;

/**
 * Turns the audit text written when a task is created, edited or deleted
 * ("Created task "X" — Type: FILE_TRANSFER; Direction: INBOUND; ...") into something an operator
 * can read at a glance: a one-sentence description of what the task does, then grouped, plainly
 * worded settings (or, for an edit, a before/after table).
 *
 * <p>Pure text-in, HTML-out, so it can be tested without a window. The audit text itself is not
 * changed, so older events render the same way.
 */
public final class TaskChangeView {

    /**
     * Field key -> label used in the audit text. The task list screen writes the text with these.
     */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final String[] PREFIXES = {"Created task \"", "Edited task \"", "Deleted task \""};

    static {
        LABELS.put("taskType", "Type");
        LABELS.put("transferDirection", "Direction");
        LABELS.put("transferMode", "Transfer mode");
        LABELS.put("sourceCredential", "Source credential");
        LABELS.put("targetCredential", "Target credential");
        LABELS.put("targetUsername", "Target credential");
        LABELS.put("sourcePath", "Source path");
        LABELS.put("targetPath", "Target path");
        LABELS.put("additionalTargetPaths", "Additional target paths");
        LABELS.put("fileSelectionRules", "File selection rules");
        LABELS.put("watchOptions", "What is reported");
        LABELS.put("backupSourcePath", "Backup source path");
        LABELS.put("backupDestinationPath", "Backup destination path");
        LABELS.put("backupRetentionDays", "Backup retention (days)");
        LABELS.put("backupSourceUsername", "Backup source credential");
        LABELS.put("backupDestinationUsername", "Backup destination credential");
        LABELS.put("imapFolder", "IMAP folder");
        LABELS.put("mailSearchCriteria", "Mail search criteria");
        LABELS.put("mailFetchMode", "Mail fetch mode");
        LABELS.put("mailMailboxAddress", "Mailbox address");
        LABELS.put("mailTenantId", "Mail tenant ID");
        LABELS.put("mailClientId", "Mail client ID");
        LABELS.put("mailFetchScope", "Mail fetch scope");
        LABELS.put("mailMaxResults", "Mail max results");
        LABELS.put("mailMarkAsRead", "Mark mail as read");
        LABELS.put("mailMoveToFolderEnabled", "Move mail to folder");
        LABELS.put("mailMoveToFolderName", "Move-to folder name");
        LABELS.put("mailOutputFolder", "Mail output folder");
        LABELS.put("scheduleType", "Schedule type");
        LABELS.put("scheduledAt", "Scheduled at");
        LABELS.put("intervalMinutes", "Interval (minutes)");
        LABELS.put("intervalSeconds", "Interval (seconds)");
        LABELS.put("cronExpression", "Cron expression");
        LABELS.put("watcherEnabled", "Watcher enabled");
        LABELS.put("inboundWatcherPollIntervalMinutes", "Watcher fallback poll interval (min)");
        LABELS.put("inboundWatcherMaxAgeMinutes", "Watcher max file age (min)");
        LABELS.put("retryCount", "Retry count");
    }

    private TaskChangeView() {
    }

    public static boolean applies(String message) {
        if (message == null) return false;
        for (String p : PREFIXES) if (message.startsWith(p)) return true;
        return false;
    }

    /**
     * @param muted CSS class for secondary text, @param bad CSS class for removed values.
     */
    public static View render(String message, String muted) {
        Change kind = message.startsWith(PREFIXES[0]) ? Change.CREATED
                : message.startsWith(PREFIXES[1]) ? Change.EDITED : Change.DELETED;
        int q1 = message.indexOf('"');
        int q2 = message.indexOf('"', q1 + 1);
        String name = q2 > q1 ? message.substring(q1 + 1, q2) : "";
        String rest = q2 > 0 ? message.substring(q2 + 1) : "";
        int dash = rest.indexOf('—');
        rest = dash >= 0 ? rest.substring(dash + 1).trim() : rest.trim();
        if (rest.startsWith("final configuration:")) rest = rest.substring("final configuration:".length()).trim();
        if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1);

        List<Row> rows = parse(rest, kind == Change.EDITED);
        StringBuilder h = new StringBuilder();
        StringBuilder t = new StringBuilder();

        String headline = switch (kind) {
            case CREATED -> "New task created";
            case EDITED -> "Task settings changed";
            case DELETED -> "Task deleted — its last settings are kept here for reference";
        };
        h.append("<h4>").append(headline.toUpperCase(Locale.ROOT)).append("</h4>");
        t.append(headline).append(": ").append(name).append('\n');

        if (kind == Change.EDITED) {
            if (rows.isEmpty()) {
                h.append("<span class='").append(muted).append("'>Saved without changing any setting.</span>");
                t.append("No setting changed.\n");
            } else {
                h.append("<table cellspacing='0'><tr><td width='150' class='").append(muted).append("'>Setting</td><td class='")
                        .append(muted).append("'>Before</td><td></td><td class='").append(muted).append("'>After</td></tr>");
                t.append("Changes:\n");
                for (Row r : rows) {
                    String o = friendly(r.label, r.oldValue), n = friendly(r.label, r.value);
                    h.append("<tr><td><b>").append(esc(r.label)).append("</b></td><td>").append(esc(o))
                            .append("</td><td class='").append(muted).append("'>→</td><td><b>").append(esc(n)).append("</b></td></tr>");
                    t.append("  ").append(r.label).append(": ").append(o).append(" -> ").append(n).append('\n');
                }
                h.append("</table>");
            }
            return new View(h.toString(), t.toString());
        }

        Map<String, String> v = new LinkedHashMap<>();
        for (Row r : rows) v.put(r.label, r.value);

        String sentence = sentence(v);
        if (sentence != null) {
            h.append("<div><b>In short:</b> ").append(esc(sentence)).append("</div>");
            t.append("In short: ").append(sentence).append('\n');
        }

        for (Section s : sections(v)) {
            if (s.rows.isEmpty()) continue;
            h.append("<h4>").append(s.title.toUpperCase(Locale.ROOT)).append("</h4><table cellspacing='0'>");
            t.append('\n').append(s.title).append(":\n");
            for (String[] kv : s.rows) {
                h.append("<tr><td class='").append(muted).append("' width='210'>").append(esc(kv[0])).append("</td><td>")
                        .append(esc(kv[1])).append("</td></tr>");
                t.append("  ").append(kv[0]).append(": ").append(kv[1]).append('\n');
            }
            h.append("</table>");
        }
        return new View(h.toString(), t.toString());
    }

    /**
     * "Label: value; Label: value" with known labels only, so a ';' inside a value does not split it.
     */
    static List<Row> parse(String s, boolean edit) {
        List<Row> rows = new ArrayList<>();
        if (s == null || s.isBlank() || s.startsWith("no fields") || s.startsWith("no fields actually changed"))
            return rows;
        Set<String> labels = new HashSet<>(LABELS.values());
        String label = null;
        StringBuilder value = new StringBuilder();
        for (String part : s.split("; ")) {
            String found = null;
            for (String l : labels) {
                if (part.startsWith(l + ": ") && (found == null || l.length() > found.length())) found = l;
            }
            if (found != null) {
                if (label != null) rows.add(row(label, value.toString(), edit));
                label = found;
                value = new StringBuilder(part.substring(found.length() + 2));
            } else if (label != null) {
                value.append("; ").append(part);
            }
        }
        if (label != null) rows.add(row(label, value.toString(), edit));
        return rows;
    }

    private static Row row(String label, String value, boolean edit) {
        if (!edit) return new Row(label, null, value);
        int arrow = value.indexOf(" → ");
        if (arrow < 0) return new Row(label, "", value);
        return new Row(label, value.substring(0, arrow), value.substring(arrow + 3));
    }

    private static String friendly(String label, String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.equals("(not set)")) return "not set";
        return switch (label) {
            case "Type" -> switch (v) {
                case "FILE_TRANSFER" -> "File transfer (SFTP)";
                case "OUTLOOK_MAIL" -> "Mail download (Outlook / Microsoft 365)";
                case "BACKUP" -> "Backup";
                case "FOLDER_WATCH" -> "Watch a folder (no transfer)";
                default -> v;
            };
            case "Direction" -> switch (v) {
                case "INBOUND" -> "Download — from the remote server to this PC";
                case "OUTBOUND" -> "Upload — from this PC to the remote server";
                default -> v;
            };
            case "Transfer mode" -> switch (v) {
                case "LATEST_ONLY" -> "Only the newest file";
                case "ENTIRE_FOLDER" -> "Every file in the folder";
                case "SPECIFIC_FILE" -> "One named file";
                default -> v;
            };
            case "Schedule type" -> switch (v) {
                case "RUN_NOW" -> "Run once, immediately";
                case "ONCE" -> "Run once, at a set time";
                case "DAILY" -> "Every day";
                case "WEEKLY" -> "Every week";
                case "INTERVAL_MINUTES" -> "On a repeating interval";
                case "INTERVAL_SECONDS" -> "On a repeating interval";
                default -> v;
            };
            case "Mail fetch mode" -> switch (v) {
                case "BODY_ONLY" -> "Message text only";
                case "HEADERS_AND_BODY" -> "Subject/sender details and text";
                case "FULL_MESSAGE" -> "Complete message (with attachments)";
                default -> v;
            };
            case "Mail fetch scope" -> switch (v) {
                case "LATEST_ONLY" -> "Only the newest matching message";
                case "ALL_MATCHING" -> "All matching messages";
                default -> v;
            };
            case "Interval (minutes)" -> v.equals("1") ? "1 minute" : v + " minutes";
            case "Interval (seconds)" -> v.equals("1") ? "1 second" : v + " seconds";
            case "Retry count" -> v.equals("1") ? "1 retry after a failure" : v + " retries after a failure";
            case "Backup retention (days)" -> v + " days";
            case "Watcher fallback poll interval (min)", "Watcher max file age (min)" -> v + " minutes";
            case "Scheduled at" -> v.replace('T', ' ');
            default -> v;
        };
    }

    // ── parsing ────────────────────────────────────────────────────

    private static void add(List<String[]> rows, Map<String, String> v, String label, String shownLabel) {
        String val = v.get(label);
        if (val == null || val.isBlank()) return;
        rows.add(new String[]{shownLabel, friendly(label, val)});
    }

    private static List<Section> sections(Map<String, String> v) {
        List<Section> out = new ArrayList<>();
        String type = v.getOrDefault("Type", "");
        boolean inbound = "INBOUND".equals(v.get("Direction"));

        List<String[]> what = new ArrayList<>();
        add(what, v, "Type", "Kind of task");
        add(what, v, "Direction", "Direction");
        add(what, v, "Transfer mode", "Which files");
        out.add(new Section("What it does", what));

        if (type.equals("FILE_TRANSFER")) {
            List<String[]> where = new ArrayList<>();
            // The task stores the local side as "source" and the server side as "target" for both directions.
            add(where, v, "Target credential", "Remote server (login)");
            add(where, v, "Target path", inbound ? "Files are taken from (remote folder)" : "Files are sent to (remote folder)");
            add(where, v, "Source path", inbound ? "Files are saved to (this PC)" : "Files are taken from (this PC)");
            add(where, v, "Additional target paths", inbound ? "Also copied to (this PC)" : "Also sent to (remote folders)");
            add(where, v, "Source credential", "Source login");
            out.add(new Section("Where", where));
        } else if (type.equals("FOLDER_WATCH")) {
            List<String[]> where = new ArrayList<>();
            add(where, v, "Backup source path", "Folder watched");
            add(where, v, "Backup source credential", "Server login (blank = this PC or a network share)");
            add(where, v, "What is reported", "Reports");
            out.add(new Section("Where", where));
        } else if (type.equals("BACKUP")) {
            List<String[]> where = new ArrayList<>();
            add(where, v, "Backup source path", "Back up from");
            add(where, v, "Backup source credential", "Source login");
            add(where, v, "Backup destination path", "Back up to");
            add(where, v, "Backup destination credential", "Destination login");
            add(where, v, "Backup retention (days)", "Keep backups for");
            out.add(new Section("Where", where));
        } else if (type.equals("OUTLOOK_MAIL")) {
            List<String[]> mail = new ArrayList<>();
            add(mail, v, "Mailbox address", "Mailbox");
            add(mail, v, "IMAP folder", "Folder");
            add(mail, v, "Mail search criteria", "Which messages");
            add(mail, v, "Mail fetch scope", "How many");
            add(mail, v, "Mail max results", "Up to (messages per run)");
            add(mail, v, "Mail fetch mode", "What is saved");
            add(mail, v, "Mail output folder", "Saved to (this PC)");
            add(mail, v, "Mark mail as read", "Mark as read afterwards");
            add(mail, v, "Move mail to folder", "Move to another mailbox folder");
            add(mail, v, "Move-to folder name", "Move-to folder");
            add(mail, v, "Mail tenant ID", "Microsoft tenant ID");
            add(mail, v, "Mail client ID", "App (client) ID");
            out.add(new Section("Mailbox", mail));
        }

        List<String[]> when = new ArrayList<>();
        add(when, v, "Schedule type", "Runs");
        add(when, v, "Scheduled at", "Starting at");
        add(when, v, "Interval (minutes)", "Repeats every");
        add(when, v, "Interval (seconds)", "Repeats every");
        add(when, v, "Cron expression", "Cron expression");
        out.add(new Section("When", when));

        List<String[]> watcher = new ArrayList<>();
        add(watcher, v, "Watcher enabled", "Watch for new files");
        add(watcher, v, "Watcher fallback poll interval (min)", "Also checks every");
        add(watcher, v, "Watcher max file age (min)", "Ignores files older than");
        out.add(new Section("Watching", watcher));

        List<String[]> rel = new ArrayList<>();
        add(rel, v, "Retry count", "If it fails");
        out.add(new Section("Reliability", rel));
        return out;
    }

    // ── wording ────────────────────────────────────────────────────

    /**
     * One plain sentence describing the task, or null when there is not enough information.
     */
    static String sentence(Map<String, String> v) {
        String type = v.getOrDefault("Type", "");
        String when = whenPhrase(v);
        boolean watch = "Yes".equals(v.get("Watcher enabled"));
        String s;
        switch (type) {
            case "FILE_TRANSFER" -> {
                boolean inbound = "INBOUND".equals(v.get("Direction"));
                String which = switch (v.getOrDefault("Transfer mode", "")) {
                    case "LATEST_ONLY" -> "the newest file";
                    case "SPECIFIC_FILE" -> "one named file";
                    case "ENTIRE_FOLDER" -> "every file";
                    default -> "files";
                };
                String server = v.getOrDefault("Target credential", "the remote server");
                String remote = v.get("Target path");
                String local = v.get("Source path");
                s = inbound
                        ? "Downloads " + which + " from " + server + (remote != null ? " (" + remote + ")" : "")
                        + (local != null ? " to " + local : "")
                        : "Uploads " + which + (local != null ? " from " + local : "") + " to " + server
                        + (remote != null ? " (" + remote + ")" : "");
            }
            case "FOLDER_WATCH" ->
                    s = "Watches " + v.getOrDefault("Backup source path", "a folder") + " for new and changed files (nothing is transferred)";
            case "BACKUP" -> s = "Backs up " + v.getOrDefault("Backup source path", "files")
                    + (v.get("Backup destination path") != null ? " to " + v.get("Backup destination path") : "");
            case "OUTLOOK_MAIL" -> s = "Reads mail from " + v.getOrDefault("Mailbox address", "a mailbox")
                    + (v.get("Mail output folder") != null ? " and saves it to " + v.get("Mail output folder") : "");
            default -> {
                return null;
            }
        }
        if (watch) s += ", as soon as new files appear";
        if (when != null) s += ", " + when;
        return s + ".";
    }

    private static String whenPhrase(Map<String, String> v) {
        String type = v.get("Schedule type");
        if (type == null) return null;
        return switch (type) {
            case "RUN_NOW" -> "once, right away";
            case "ONCE" ->
                    v.get("Scheduled at") != null ? "once at " + friendly("Scheduled at", v.get("Scheduled at")) : "once";
            case "DAILY" -> "every day";
            case "WEEKLY" -> "every week";
            case "INTERVAL_MINUTES" ->
                    v.get("Interval (minutes)") != null ? "every " + friendly("Interval (minutes)", v.get("Interval (minutes)")) : null;
            case "INTERVAL_SECONDS" ->
                    v.get("Interval (seconds)") != null ? "every " + friendly("Interval (seconds)", v.get("Interval (seconds)")) : null;
            default -> null;
        };
    }

    private static String esc(String s) {
        return MonitorStyle.htmlEscape(s);
    }

    public enum Change {CREATED, EDITED, DELETED}

    /**
     * HTML for the details pane plus a plain-text twin for "Copy".
     */
    public record View(String html, String text) {
    }

    private record Row(String label, String oldValue, String value) {
    }

    private record Section(String title, List<String[]> rows) {
    }
}
