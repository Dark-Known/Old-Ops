package model;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which files of a folder a file-transfer task should take: a set of conditions on the file
 * (name, extension, size, age) and a "pick" step (all matches, or only the newest/oldest/largest/
 * smallest N). It is layered on top of the task's {@link ScheduledTask.TransferMode} and does
 * nothing at all while {@link #isActive()} is false, so tasks saved before this existed behave
 * exactly as they did.
 *
 * <h3>How a file is judged</h3>
 * <ol>
 *   <li>Every <b>exclude</b> condition that matches rejects the file, no matter what else matches.</li>
 *   <li>The <b>include</b> conditions must match: all of them ({@link MatchMode#ALL}) or at least
 *       one ({@link MatchMode#ANY}). With no include conditions every file passes this step.</li>
 *   <li>The size / age / temp-file options must hold too.</li>
 * </ol>
 * Name conditions look at the file name only (never the folder part of a path).
 *
 * <h3>Date placeholders</h3>
 * A condition's text may contain {@code {today}}, {@code {yesterday}} or {@code {tomorrow}},
 * optionally with a {@link DateTimeFormatter} pattern — {@code {today:yyyyMMdd}} (the default
 * pattern), {@code {yesterday:dd-MMM-yyyy}} — replaced by that date when the task runs. That is
 * how "the file with today's date in its name" is expressed.
 *
 * <p>The whole thing is stored on the task as one small line-based text value
 * ({@link #toText()} / {@link #fromText(String)}), so a new option never needs a new database column.
 */
public final class FileSelection {

    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\{(today|yesterday|tomorrow)(?::([^}]+))?}", Pattern.CASE_INSENSITIVE);
    private final List<Rule> rules = new ArrayList<>();
    private MatchMode matchMode = MatchMode.ALL;
    private long minSizeBytes;            // 0 = no minimum
    private long maxSizeBytes;            // 0 = no maximum
    private long modifiedWithinMinutes;   // 0 = off: only files changed in the last N minutes

    // ── The settings ─────────────────────────────────────────────────
    private long settledSeconds;          // 0 = off: skip files changed in the last N seconds (still being written)
    private boolean modifiedToday;        // only files changed since local midnight
    private boolean skipTempFiles;        // ignore hidden/temporary/partial-download files
    private Pick pick = Pick.BY_MODE;
    private int pickCount = 1;
    private boolean keepTies;             // include further files tied with the last picked one
    private boolean failIfNoMatch;        // nothing matched = failed run instead of a skipped one
    private SentPolicy sentPolicy = SentPolicy.ALWAYS;
    private AfterAction afterAction = AfterAction.KEEP;
    private boolean alertNoFiles = false;      // raise an alert when a run finds no file to send/receive
    private int silenceMinutes = 0;            // alert when no file has arrived for this long (0 = off); works with watchers
    private int alertRepeatMinutes = 60;       // do not repeat the alert for the same task within this time (0 = every run)
    private String afterFolder = "";      // target folder for AfterAction.MOVE (remote for downloads, local for uploads)

    private static boolean matches(Rule r, Pattern p, String expandedValue, String name) {
        boolean ic = r.ignoreCase;
        switch (r.op) {
            case ENDS_WITH:
                return ic ? name.toLowerCase(Locale.ROOT).endsWith(expandedValue.toLowerCase(Locale.ROOT))
                        : name.endsWith(expandedValue);
            case STARTS_WITH:
                return ic ? name.toLowerCase(Locale.ROOT).startsWith(expandedValue.toLowerCase(Locale.ROOT))
                        : name.startsWith(expandedValue);
            case CONTAINS:
                return ic ? name.toLowerCase(Locale.ROOT).contains(expandedValue.toLowerCase(Locale.ROOT))
                        : name.contains(expandedValue);
            case EQUALS:
                return ic ? name.equalsIgnoreCase(expandedValue) : name.equals(expandedValue);
            case REGEX:
                return p != null && p.matcher(name).find();
            case WILDCARD: {
                if (p == null) return false;
                // Several wildcards may be given, separated by ';' — a file matching any of them matches.
                return p.matcher(name).matches();
            }
            case EXTENSION_IN: {
                int dot = name.lastIndexOf('.');
                String ext = dot >= 0 ? name.substring(dot + 1) : "";
                for (String e : expandedValue.split("[,;\\s]+")) {
                    String want = e.startsWith(".") ? e.substring(1) : e;
                    if (want.isEmpty()) continue;
                    if (ic ? ext.equalsIgnoreCase(want) : ext.equals(want)) return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    /**
     * {@code *.csv;report_??.txt} → one regex that accepts a name matching either pattern, whole name.
     */
    static String wildcardToRegex(String wildcards) {
        StringBuilder out = new StringBuilder();
        for (String w : wildcards.split(";")) {
            String t = w.trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('|');
            out.append("(?:");
            StringBuilder lit = new StringBuilder();
            for (char c : t.toCharArray()) {
                if (c == '*' || c == '?') {
                    if (lit.length() > 0) {
                        out.append(Pattern.quote(lit.toString()));
                        lit.setLength(0);
                    }
                    out.append(c == '*' ? ".*" : ".");
                } else {
                    lit.append(c);
                }
            }
            if (lit.length() > 0) out.append(Pattern.quote(lit.toString()));
            out.append(')');
        }
        return out.length() == 0 ? "(?!)" : out.toString(); // no pattern at all matches nothing
    }

    /**
     * Replaces {today}/{yesterday}/{tomorrow}[:pattern] with that date, formatted.
     */
    static String expandDates(String text, long nowMillis, ZoneId zone) {
        if (text == null || text.indexOf('{') < 0) return text == null ? "" : text;
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone);
        Matcher m = DATE_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            LocalDate d = switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "yesterday" -> today.minusDays(1);
                case "tomorrow" -> today.plusDays(1);
                default -> today;
            };
            String fmt = m.group(2) != null ? m.group(2) : "yyyyMMdd";
            m.appendReplacement(sb, Matcher.quoteReplacement(DateTimeFormatter.ofPattern(fmt, Locale.ENGLISH).format(d)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * The file name part of a name that may be a full local or remote path.
     */
    public static String baseName(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /**
     * Hidden files, editor/office lock files and the leftovers of unfinished downloads or copies.
     */
    static boolean isTempName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".") || n.startsWith("~$")) return true;
        if (n.equals("thumbs.db") || n.equals("desktop.ini")) return true;
        for (String suffix : new String[]{".tmp", ".temp", ".part", ".partial", ".crdownload", ".filepart",
                ".lck", ".lock", ".swp", ".swx", "~"}) {
            if (n.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String humanSize(long b) {
        if (b >= 1L << 30 && b % (1L << 30) == 0) return (b >> 30) + " GB";
        if (b >= 1L << 20 && b % (1L << 20) == 0) return (b >> 20) + " MB";
        if (b >= 1L << 10 && b % (1L << 10) == 0) return (b >> 10) + " KB";
        return b + " B";
    }

    /**
     * Parses {@link #toText()} output. Null, blank or damaged text gives "no rules" — never an exception.
     */
    public static FileSelection fromText(String text) {
        FileSelection s = new FileSelection();
        if (text == null || text.isBlank()) return s;
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "match":
                        s.setMatchMode(MatchMode.valueOf(v));
                        break;
                    case "pick":
                        s.setPick(Pick.valueOf(v));
                        break;
                    case "count":
                        s.setPickCount(Integer.parseInt(v));
                        break;
                    case "ties":
                        s.setKeepTies("1".equals(v));
                        break;
                    case "failIfNoMatch":
                        s.setFailIfNoMatch("1".equals(v));
                        break;
                    case "minSize":
                        s.setMinSizeBytes(Long.parseLong(v));
                        break;
                    case "maxSize":
                        s.setMaxSizeBytes(Long.parseLong(v));
                        break;
                    case "withinMin":
                        s.setModifiedWithinMinutes(Long.parseLong(v));
                        break;
                    case "settledSec":
                        s.setSettledSeconds(Long.parseLong(v));
                        break;
                    case "today":
                        s.setModifiedToday("1".equals(v));
                        break;
                    case "skipTemp":
                        s.setSkipTempFiles("1".equals(v));
                        break;
                    case "sent":
                        s.setSentPolicy(SentPolicy.valueOf(v));
                        break;
                    case "after":
                        s.setAfterAction(AfterAction.valueOf(v));
                        break;
                    case "alert":
                        s.setAlertNoFiles("1".equals(v));
                        break;
                    case "silence":
                        s.setSilenceMinutes(Integer.parseInt(v));
                        break;
                    case "alertEvery":
                        s.setAlertRepeatMinutes(Integer.parseInt(v));
                        break;
                    case "afterFolder":
                        s.setAfterFolder(URLDecoder.decode(v, StandardCharsets.UTF_8));
                        break;
                    case "rule": {
                        String[] f = v.split("\\|", 4);
                        if (f.length == 4) {
                            s.addRule(new Rule("X".equals(f[0]), Op.valueOf(f[1]), URLDecoder.decode(f[3], StandardCharsets.UTF_8),
                                    "1".equals(f[2])));
                        }
                        break;
                    }
                    default:
                        break; // written by a newer build — ignore
                }
            } catch (IllegalArgumentException ignored) {
                // an unknown enum name or a bad number: keep the default for that one setting
            }
        }
        return s;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public MatchMode getMatchMode() {
        return matchMode;
    }

    public FileSelection setMatchMode(MatchMode m) {
        matchMode = m == null ? MatchMode.ALL : m;
        return this;
    }

    public long getMinSizeBytes() {
        return minSizeBytes;
    }

    public FileSelection setMinSizeBytes(long v) {
        minSizeBytes = Math.max(0, v);
        return this;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public FileSelection setMaxSizeBytes(long v) {
        maxSizeBytes = Math.max(0, v);
        return this;
    }

    public long getModifiedWithinMinutes() {
        return modifiedWithinMinutes;
    }

    public FileSelection setModifiedWithinMinutes(long v) {
        modifiedWithinMinutes = Math.max(0, v);
        return this;
    }

    public long getSettledSeconds() {
        return settledSeconds;
    }

    public FileSelection setSettledSeconds(long v) {
        settledSeconds = Math.max(0, v);
        return this;
    }

    public boolean isModifiedToday() {
        return modifiedToday;
    }

    public FileSelection setModifiedToday(boolean v) {
        modifiedToday = v;
        return this;
    }

    public boolean isSkipTempFiles() {
        return skipTempFiles;
    }

    public FileSelection setSkipTempFiles(boolean v) {
        skipTempFiles = v;
        return this;
    }

    public Pick getPick() {
        return pick;
    }

    public FileSelection setPick(Pick p) {
        pick = p == null ? Pick.BY_MODE : p;
        return this;
    }

    public int getPickCount() {
        return pickCount;
    }

    public FileSelection setPickCount(int v) {
        pickCount = Math.max(1, v);
        return this;
    }

    public boolean isKeepTies() {
        return keepTies;
    }

    public FileSelection setKeepTies(boolean v) {
        keepTies = v;
        return this;
    }

    public boolean isFailIfNoMatch() {
        return failIfNoMatch;
    }

    public FileSelection setFailIfNoMatch(boolean v) {
        failIfNoMatch = v;
        return this;
    }

    public SentPolicy getSentPolicy() {
        return sentPolicy;
    }

    public FileSelection setSentPolicy(SentPolicy p) {
        sentPolicy = p == null ? SentPolicy.ALWAYS : p;
        return this;
    }

    public AfterAction getAfterAction() {
        return afterAction;
    }

    public FileSelection setAfterAction(AfterAction a) {
        afterAction = a == null ? AfterAction.KEEP : a;
        return this;
    }

    public String getAfterFolder() {
        return afterFolder;
    }

    public FileSelection setAfterFolder(String f) {
        afterFolder = f == null ? "" : f.trim();
        return this;
    }

    public FileSelection addRule(Rule r) {
        if (r != null) rules.add(r);
        return this;
    }

    public int getSilenceMinutes() {
        return silenceMinutes;
    }

    public FileSelection setSilenceMinutes(int m) {
        silenceMinutes = Math.max(0, m);
        return this;
    }

    public boolean isAlertNoFiles() {
        return alertNoFiles;
    }

    public FileSelection setAlertNoFiles(boolean b) {
        alertNoFiles = b;
        return this;
    }

    public int getAlertRepeatMinutes() {
        return alertRepeatMinutes;
    }

    public FileSelection setAlertRepeatMinutes(int m) {
        alertRepeatMinutes = Math.max(0, m);
        return this;
    }

    /**
     * True when files sent in earlier runs are remembered and skipped (needs the sent-files ledger).
     */
    public boolean usesLedger() {
        return sentPolicy != SentPolicy.ALWAYS;
    }

    /**
     * True when originals are deleted or moved after a successful transfer.
     */
    public boolean hasAfterAction() {
        return afterAction != AfterAction.KEEP;
    }

    // ── Validation ───────────────────────────────────────────────────

    /**
     * True when any condition narrows which files qualify (the pick step is not counted).
     */
    public boolean hasFilters() {
        return !rules.isEmpty() || minSizeBytes > 0 || maxSizeBytes > 0 || modifiedWithinMinutes > 0
                || settledSeconds > 0 || modifiedToday || skipTempFiles;
    }

    // ── Matching ─────────────────────────────────────────────────────

    /**
     * True when the file list is changed compared with the plain Transfer Mode behaviour (conditions,
     * pick, or skipping already-sent files). Cleaning up originals afterwards is separate: see
     * {@link #hasAfterAction()}.
     */
    public boolean isActive() {
        return hasFilters() || pick != Pick.BY_MODE || usesLedger();
    }

    /**
     * Problems that would stop the rules from working, in plain words; empty when they are fine.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (Rule r : rules) {
            n++;
            if (r.value.trim().isEmpty()) {
                problems.add("Condition " + n + " (" + r.op.label() + ") has no text.");
                continue;
            }
            try {
                String v = expandDates(r.value, Instant.now().toEpochMilli(), ZoneId.systemDefault());
                if (r.op == Op.REGEX) Pattern.compile(v);
            } catch (PatternSyntaxException e) {
                problems.add("Condition " + n + " is not a valid regular expression: " + e.getDescription());
            } catch (IllegalArgumentException e) {
                problems.add("Condition " + n + " has an invalid date placeholder: " + e.getMessage());
            }
        }
        if (alertRepeatMinutes < 0) problems.add("The alert repeat time cannot be negative.");
        if (afterAction == AfterAction.MOVE && afterFolder.isBlank()) {
            problems.add("\"Move the original to a folder\" needs a folder.");
        }
        if (minSizeBytes > 0 && maxSizeBytes > 0 && minSizeBytes > maxSizeBytes) {
            problems.add("Minimum size is larger than maximum size — no file could ever match.");
        }
        return problems;
    }

    public Filter filter() {
        return filter(System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public Filter filter(long nowMillis, ZoneId zone) {
        return new Filter(nowMillis, zone);
    }

    /**
     * Only the conditions, no pick step — used where every new matching file is wanted (watchers).
     */
    public <T> List<T> filterOnly(Collection<T> items, Function<T, String> nameOf,
                                  ToLongFunction<T> sizeOf, ToLongFunction<T> mtimeOf) {
        Filter f = filter();
        List<T> out = new ArrayList<>();
        for (T t : items) if (f.test(nameOf.apply(t), sizeOf.applyAsLong(t), mtimeOf.applyAsLong(t))) out.add(t);
        return out;
    }

    /**
     * Conditions, then the pick step. {@code mode} only matters while the pick is {@link Pick#BY_MODE}.
     */
    public <T> List<T> select(Collection<T> items, Function<T, String> nameOf,
                              ToLongFunction<T> sizeOf, ToLongFunction<T> mtimeOf,
                              ScheduledTask.TransferMode mode) {
        List<T> matched = filterOnly(items, nameOf, sizeOf, mtimeOf);
        Pick effective = pick;
        int count = pickCount;
        boolean ties = keepTies;
        if (pick == Pick.BY_MODE) {
            // The classic behaviour of the modes: ENTIRE_FOLDER takes everything, LATEST_ONLY takes
            // the newest timestamp including every file that shares it.
            effective = mode == ScheduledTask.TransferMode.LATEST_ONLY ? Pick.NEWEST : Pick.ALL;
            count = 1;
            ties = true;
        }
        if (effective == Pick.ALL || matched.size() <= 0) return matched;

        final ToLongFunction<T> key = switch (effective) {
            case NEWEST, OLDEST -> mtimeOf;
            default -> sizeOf;
        };
        final boolean descending = effective == Pick.NEWEST || effective == Pick.LARGEST;
        List<T> sorted = new ArrayList<>(matched);
        Comparator<T> byKey = Comparator.comparingLong(key);
        if (descending) byKey = byKey.reversed();
        sorted.sort(byKey.thenComparing(t -> baseName(nameOf.apply(t)).toLowerCase(Locale.ROOT)));

        int take = Math.min(count, sorted.size());
        List<T> out = new ArrayList<>(sorted.subList(0, take));
        if (ties) {
            long cutoff = key.applyAsLong(sorted.get(take - 1));
            for (int i = take; i < sorted.size() && key.applyAsLong(sorted.get(i)) == cutoff; i++)
                out.add(sorted.get(i));
        }
        return out;
    }

    /**
     * One readable line, e.g. {@code name ends with ".csv" AND NOT name contains "tmp" · settled 30s · newest 1}.
     */
    public String describe() {
        if (!isActive() && !hasAfterAction() && !alertNoFiles && silenceMinutes == 0)
            return "no file selection rules (Transfer Mode only)";
        List<String> parts = new ArrayList<>();
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        for (Rule r : rules) (r.exclude ? exc : inc).add(r.toString().replaceFirst("^NOT ", ""));
        if (!inc.isEmpty()) parts.add(String.join(matchMode == MatchMode.ALL ? " AND " : " OR ", inc));
        for (String e : exc) parts.add("NOT " + e);
        if (minSizeBytes > 0) parts.add("size ≥ " + humanSize(minSizeBytes));
        if (maxSizeBytes > 0) parts.add("size ≤ " + humanSize(maxSizeBytes));
        if (modifiedWithinMinutes > 0) parts.add("changed in the last " + modifiedWithinMinutes + " min");
        if (settledSeconds > 0) parts.add("unchanged for " + settledSeconds + "s");
        if (modifiedToday) parts.add("changed today");
        if (skipTempFiles) parts.add("no hidden/temp files");
        if (pick != Pick.BY_MODE) {
            parts.add(pick == Pick.ALL ? "all matches" : pick.name().toLowerCase(Locale.ROOT) + " " + pickCount
                    + (keepTies ? " (+ties)" : ""));
        }
        if (sentPolicy == SentPolicy.UNCHANGED) parts.add("skip files already sent (unchanged)");
        if (sentPolicy == SentPolicy.ANY_NAME) parts.add("skip names already sent");
        if (failIfNoMatch && isActive()) parts.add("fail if nothing matches");
        if (afterAction == AfterAction.DELETE) parts.add("then delete the original");
        if (afterAction == AfterAction.MOVE) parts.add("then move the original to " + afterFolder);
        if (silenceMinutes > 0) parts.add("alert if no file for " + silenceMinutes + " min");
        if (alertNoFiles)
            parts.add("alert if no files" + (alertRepeatMinutes > 0 ? " (at most every " + alertRepeatMinutes + " min)" : ""));
        return String.join(" · ", parts);
    }

    /**
     * Serialized form kept in the task's {@code file_selection} column; empty string when there are no rules.
     */
    public String toText() {
        if (!isActive() && !failIfNoMatch && !hasAfterAction() && !alertNoFiles && silenceMinutes == 0) return "";
        StringBuilder sb = new StringBuilder("v=1\n");
        sb.append("match=").append(matchMode.name()).append('\n');
        sb.append("pick=").append(pick.name()).append('\n');
        sb.append("count=").append(pickCount).append('\n');
        sb.append("ties=").append(keepTies ? 1 : 0).append('\n');
        sb.append("failIfNoMatch=").append(failIfNoMatch ? 1 : 0).append('\n');
        sb.append("minSize=").append(minSizeBytes).append('\n');
        sb.append("maxSize=").append(maxSizeBytes).append('\n');
        sb.append("withinMin=").append(modifiedWithinMinutes).append('\n');
        sb.append("settledSec=").append(settledSeconds).append('\n');
        sb.append("today=").append(modifiedToday ? 1 : 0).append('\n');
        sb.append("skipTemp=").append(skipTempFiles ? 1 : 0).append('\n');
        sb.append("sent=").append(sentPolicy.name()).append('\n');
        sb.append("after=").append(afterAction.name()).append('\n');
        sb.append("alert=").append(alertNoFiles ? 1 : 0).append('\n');
        sb.append("silence=").append(silenceMinutes).append('\n');
        sb.append("alertEvery=").append(alertRepeatMinutes).append('\n');
        sb.append("afterFolder=").append(URLEncoder.encode(afterFolder, StandardCharsets.UTF_8)).append('\n');
        for (Rule r : rules) {
            sb.append("rule=").append(r.exclude ? 'X' : 'I').append('|').append(r.op.name()).append('|')
                    .append(r.ignoreCase ? 1 : 0).append('|')
                    .append(URLEncoder.encode(r.value, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    /**
     * What a name condition compares the file name with.
     */
    public enum Op {
        ENDS_WITH("ends with"),
        STARTS_WITH("starts with"),
        CONTAINS("contains"),
        EQUALS("is exactly"),
        WILDCARD("matches wildcard"),
        REGEX("matches regex"),
        EXTENSION_IN("extension is one of");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Whether a file needs to satisfy all or just one of the include conditions.
     */
    public enum MatchMode {
        ALL("all include conditions"), ANY("any include condition");
        private final String label;

        MatchMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Which of the matching files are transferred.
     */
    public enum Pick {
        BY_MODE("As the Transfer Mode says"),
        ALL("All matching files"),
        NEWEST("Newest N files"),
        OLDEST("Oldest N files"),
        LARGEST("Largest N files"),
        SMALLEST("Smallest N files");
        private final String label;

        Pick(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ── Description (for logs and the change history) ────────────────

    /**
     * Whether a file that was already sent in an earlier run is sent again.
     */
    public enum SentPolicy {
        ALWAYS("Send again every run"),
        UNCHANGED("Skip files already sent (unchanged)"),
        ANY_NAME("Skip any file name already sent");
        private final String label;

        SentPolicy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * What happens to the original file once the whole transfer has succeeded.
     */
    public enum AfterAction {
        KEEP("Leave the original"),
        DELETE("Delete the original"),
        MOVE("Move the original to a folder");
        private final String label;

        AfterAction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ── Storage ──────────────────────────────────────────────────────

    /**
     * One name condition.
     */
    public static final class Rule {
        public final boolean exclude;
        public final Op op;
        public final String value;
        public final boolean ignoreCase;

        public Rule(boolean exclude, Op op, String value, boolean ignoreCase) {
            this.exclude = exclude;
            this.op = op;
            this.value = value == null ? "" : value;
            this.ignoreCase = ignoreCase;
        }

        @Override
        public String toString() {
            return (exclude ? "NOT " : "") + "name " + op.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " \"" + value + "\"" + (ignoreCase ? "" : " (case-sensitive)");
        }
    }

    /**
     * The conditions compiled once for a batch of files, evaluated against one clock reading.
     */
    public final class Filter {
        private final long now;
        private final long startOfToday;
        private final List<Rule> excludes = new ArrayList<>();
        private final List<Rule> includes = new ArrayList<>();
        private final List<Pattern> excludePatterns = new ArrayList<>();
        private final List<Pattern> includePatterns = new ArrayList<>();
        private final List<String> excludeValues = new ArrayList<>();
        private final List<String> includeValues = new ArrayList<>();

        private Filter(long nowMillis, ZoneId zone) {
            this.now = nowMillis;
            this.startOfToday = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
                    .atStartOfDay(zone).toInstant().toEpochMilli();
            for (Rule r : rules) {
                String v = expandDates(r.value, nowMillis, zone);
                Pattern p = null;
                if (r.op == Op.REGEX || r.op == Op.WILDCARD) {
                    try {
                        p = r.op == Op.REGEX
                                ? Pattern.compile(v, r.ignoreCase ? Pattern.CASE_INSENSITIVE : 0)
                                : Pattern.compile(wildcardToRegex(v), r.ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
                    } catch (PatternSyntaxException e) {
                        p = null; // an unusable pattern never matches (validate() reports it to the user)
                    }
                }
                if (r.exclude) {
                    excludes.add(r);
                    excludePatterns.add(p);
                    excludeValues.add(v);
                } else {
                    includes.add(r);
                    includePatterns.add(p);
                    includeValues.add(v);
                }
            }
        }

        /**
         * Whether a file with this name, size (bytes) and modified time (epoch ms) qualifies.
         */
        public boolean test(String name, long size, long modifiedMillis) {
            String n = baseName(name);
            for (int i = 0; i < excludes.size(); i++) {
                if (matches(excludes.get(i), excludePatterns.get(i), excludeValues.get(i), n)) return false;
            }
            if (!includes.isEmpty()) {
                boolean any = false, all = true;
                for (int i = 0; i < includes.size(); i++) {
                    boolean m = matches(includes.get(i), includePatterns.get(i), includeValues.get(i), n);
                    any |= m;
                    all &= m;
                }
                if (matchMode == MatchMode.ALL ? !all : !any) return false;
            }
            if (skipTempFiles && isTempName(n)) return false;
            if (minSizeBytes > 0 && size < minSizeBytes) return false;
            if (maxSizeBytes > 0 && size > maxSizeBytes) return false;
            if (modifiedWithinMinutes > 0 && modifiedMillis < now - modifiedWithinMinutes * 60_000L) return false;
            if (settledSeconds > 0 && modifiedMillis > now - settledSeconds * 1_000L) return false;
            if (modifiedToday && modifiedMillis < startOfToday) return false;
            return true;
        }
    }
}
