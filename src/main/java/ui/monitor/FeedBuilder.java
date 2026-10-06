package ui.monitor;

import model.EventKind;
import model.TaskRunRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a flat list of events into the rows the feed shows. Pure logic, no
 * Swing — so the grouping rules (the part that decides whether the monitor
 * reads as calm or chaotic) can be tested directly.
 *
 * <p>Pipeline: event-level filter (range, category, level) → fold events that
 * share a run key into one run → collapse consecutive identical runs of the
 * same task into one bucket → sort newest first → flatten according to which
 * rows the operator has expanded.
 */
public final class FeedBuilder {

    private FeedBuilder() {}

    /** Result of one build. */
    public record Result(List<FeedRow> rows, List<MonitorEvent> matchedEvents,
                         Map<EventKind.Category, Integer> categoryCounts,
                         int errors, int warnings) {}

    // "3 file(s), 4.2 MB total in 12ms: ..." — the scheduler's detection summary line.
    private static final Pattern DETECTED = Pattern.compile("^(\\d+) file\\(s\\),\\s*(.+?) total");
    // How recently a run must have started to still count as "in progress" before its outcome row lands.
    private static final Duration FRESH_START = Duration.ofSeconds(15);

    public static Result build(List<MonitorEvent> all, EventFilter filter, boolean group, boolean collapseRepeats,
                               Set<String> runningTaskIds, Set<String> expandedKeys, LocalDateTime now) {

        Map<EventKind.Category, Integer> counts = new EnumMap<>(EventKind.Category.class);
        for (EventKind.Category c : EventKind.Category.values()) counts.put(c, 0);
        for (MonitorEvent e : all) {
            if (filter.acceptsIgnoringCategory(e)) counts.merge(e.kind().category(), 1, Integer::sum);
        }

        List<MonitorEvent> visible = new ArrayList<>();
        for (MonitorEvent e : all) if (filter.acceptsEvent(e)) visible.add(e);

        List<FeedRow> top = new ArrayList<>();
        List<MonitorEvent> matched = new ArrayList<>();

        if (!group) {
            for (MonitorEvent e : visible) {
                if (!filter.acceptsGroup(List.of(e))) continue;
                matched.add(e);
                top.add(eventRow(e, 0));
            }
        } else {
            // Full (unfiltered) membership per run — a run's state and search text come from ALL of
            // its events, so hiding debug children never makes a finished run look unfinished.
            Map<String, List<MonitorEvent>> allByRun = new HashMap<>();
            for (MonitorEvent e : all) {
                if (e.runKey() != null) allByRun.computeIfAbsent(e.runKey(), k -> new ArrayList<>()).add(e);
            }
            Map<String, List<MonitorEvent>> visibleByRun = new LinkedHashMap<>();
            List<MonitorEvent> singles = new ArrayList<>();
            for (MonitorEvent e : visible) {
                if (e.runKey() == null) singles.add(e);
                else visibleByRun.computeIfAbsent(e.runKey(), k -> new ArrayList<>()).add(e);
            }

            for (MonitorEvent e : singles) {
                if (!filter.acceptsGroup(List.of(e))) continue;
                matched.add(e);
                top.add(eventRow(e, 0));
            }
            for (Map.Entry<String, List<MonitorEvent>> en : visibleByRun.entrySet()) {
                List<MonitorEvent> vis = en.getValue();
                List<MonitorEvent> full = allByRun.getOrDefault(en.getKey(), vis);
                if (!filter.acceptsGroup(full)) continue;
                matched.addAll(vis);
                if (vis.size() == 1) top.add(eventRow(vis.get(0), 0));
                else top.add(runRow(en.getKey(), vis, full, runningTaskIds, now));
            }
        }

        top.sort(NEWEST_FIRST);
        if (group && collapseRepeats) top = collapse(top);
        matched.sort(CHRONOLOGICAL.reversed());

        int errors = 0, warnings = 0;
        for (MonitorEvent e : matched) {
            if (e.severity() == EventKind.Severity.ERROR) errors++;
            else if (e.severity() == EventKind.Severity.WARN) warnings++;
        }
        return new Result(flatten(top, expandedKeys), matched, counts, errors, warnings);
    }

    /**
     * Orders events of one moment by where they sit in a run's lifecycle. History timestamps have
     * one-second resolution and the two history tables have independent id sequences, so neither a
     * timestamp tie nor an id comparison can say that "Started" came before "Succeeded" — this
     * rank does.
     */
    static int rank(MonitorEvent e) {
        return switch (e.kind()) {
            case WATCH_TRIGGERED -> 0;
            case RUN_STARTED -> 1;
            case FILES_DETECTED -> 2;
            case RUN_SUCCEEDED, RUN_FAILED, RUN_SKIPPED -> 9;
            default -> 5;
        };
    }

    /** Oldest first: by second, then lifecycle stage, then id. */
    static final Comparator<MonitorEvent> CHRONOLOGICAL =
            Comparator.comparing((MonitorEvent e) -> e.time().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                    .thenComparingInt(FeedBuilder::rank)
                    .thenComparingLong(MonitorEvent::id);

    private static final Comparator<FeedRow> NEWEST_FIRST =
            Comparator.comparing((FeedRow r) -> r.time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).reversed()
                    .thenComparing((FeedRow r) -> rank(r.head), Comparator.reverseOrder())
                    .thenComparing(r -> r.head.id(), Comparator.reverseOrder());

    // ── Row construction ────────────────────────────────────────────

    static FeedRow eventRow(MonitorEvent e, int depth) {
        FeedRow.Outcome outcome = outcomeOf(e);
        String label = e.isOutcome() ? "Outcome" : e.kind().label();
        String files = e.kind() == EventKind.FILES_DETECTED ? filesText(e.summary()) : null;
        long dur = e.kind() == EventKind.RUN_STARTED || e.durationMs() <= 0 ? -1 : e.durationMs();
        return new FeedRow(FeedRow.Type.EVENT, depth, "e:" + e.key(), e, List.of(e), List.of(), 1, e.time(), e.start(),
                outcome, label, e.summary(), e.trigger(), dur, files, false, false);
    }

    private static FeedRow runRow(String runKey, List<MonitorEvent> visible, List<MonitorEvent> full,
                                  Set<String> runningTaskIds, LocalDateTime now) {
        List<MonitorEvent> vis = new ArrayList<>(visible);
        vis.sort(CHRONOLOGICAL);
        List<MonitorEvent> fullSorted = new ArrayList<>(full);
        fullSorted.sort(CHRONOLOGICAL);

        MonitorEvent outcomeEv = null, started = null, filesEv = null;
        EventKind.Severity worst = EventKind.Severity.DEBUG;
        String trigger = null;
        LocalDateTime first = null;
        for (MonitorEvent e : fullSorted) {
            if (e.isOutcome()) outcomeEv = e;
            if (e.kind() == EventKind.RUN_STARTED && started == null) started = e;
            if (e.kind() == EventKind.FILES_DETECTED && filesEv == null) filesEv = e;
            if (e.severity().compareTo(worst) > 0) worst = e.severity();
            if (trigger == null && e.trigger() != null) trigger = e.trigger();
            if (first == null || e.start().isBefore(first)) first = e.start();
        }
        MonitorEvent latest = fullSorted.get(fullSorted.size() - 1);
        MonitorEvent head = outcomeEv != null ? outcomeEv : latest;

        FeedRow.Outcome outcome;
        String summary;
        long dur;
        if (outcomeEv != null) {
            outcome = outcomeOf(outcomeEv);
            summary = outcomeEv.summary();
            dur = Math.max(0, outcomeEv.durationMs());
        } else if (started != null) {
            boolean live = runningTaskIds.contains(started.taskId())
                    || Duration.between(started.start(), now).compareTo(FRESH_START) < 0;
            if (live) {
                outcome = FeedRow.Outcome.RUNNING;
                summary = filesEv != null ? "In progress \u2014 " + filesEv.summary() : "In progress";
                dur = Math.max(0, Duration.between(started.start(), now).toMillis());
            } else {
                outcome = FeedRow.Outcome.WARNING;
                summary = "No outcome recorded \u2014 the process may have stopped mid-run";
                dur = -1;
            }
        } else {
            outcome = fromSeverity(worst);
            summary = latest.summary();
            dur = -1;
        }

        LocalDateTime time = outcomeEv != null ? outcomeEv.time() : latest.time();
        return new FeedRow(FeedRow.Type.RUN, 0, "r:" + runKey, head, vis, List.of(), vis.size(), time,
                first != null ? first : time, outcome, "Run", summary, trigger, dur,
                filesEv != null ? filesText(filesEv.summary()) : null, true, false);
    }

    // ── Collapsing identical consecutive runs ───────────────────────

    /**
     * Folds consecutive, identical, uneventful runs of the same task into one
     * bucket. "Identical" means same task, same outcome and the exact same
     * summary text; "uneventful" means the run touched no files — a run that
     * actually moved data is never hidden inside a bucket. A different row for
     * the same task in between (a failure after successes, a watcher change)
     * ends the current bucket.
     */
    private static List<FeedRow> collapse(List<FeedRow> sortedNewestFirst) {
        // Each element is either a plain FeedRow, or the (still growing) member list of a bucket.
        List<Object> out = new ArrayList<>();
        Map<String, List<FeedRow>> open = new HashMap<>();   // taskId -> member list of that task's open bucket
        Map<String, String> openSig = new HashMap<>();       // taskId -> signature of that open bucket

        for (FeedRow row : sortedNewestFirst) {
            boolean eligible = row.type == FeedRow.Type.RUN && row.files == null
                    && row.outcome != FeedRow.Outcome.RUNNING && row.outcome != FeedRow.Outcome.WARNING;
            String task = row.taskId;
            if (!eligible) {
                if (task != null) { open.remove(task); openSig.remove(task); }   // anything else ends the streak
                out.add(row);
                continue;
            }
            String sig = row.outcome + "|" + row.summary;
            List<FeedRow> members = open.get(task);
            if (members != null && sig.equals(openSig.get(task))) {
                members.add(row);
            } else {
                members = new ArrayList<>();
                members.add(row);
                open.put(task, members);
                openSig.put(task, sig);
                out.add(members);
            }
        }

        List<FeedRow> result = new ArrayList<>(out.size());
        for (Object o : out) {
            if (o instanceof FeedRow r) {
                result.add(r);
            } else {
                @SuppressWarnings("unchecked") List<FeedRow> members = (List<FeedRow>) o;
                result.add(members.size() == 1 ? members.get(0) : bucket(members));   // a lone run stays a run
            }
        }
        return result;
    }

    private static FeedRow bucket(List<FeedRow> members) {
        FeedRow newest = members.get(0);
        FeedRow oldest = members.get(members.size() - 1);
        List<MonitorEvent> events = new ArrayList<>();
        long sum = 0; int timed = 0;
        Map<String, Integer> triggers = new HashMap<>();
        for (FeedRow m : members) {
            events.addAll(m.events);
            if (m.durationMs >= 0) { sum += m.durationMs; timed++; }
            if (m.trigger != null) triggers.merge(m.trigger, 1, Integer::sum);
        }
        String trigger = triggers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new FeedRow(FeedRow.Type.BUCKET, 0, "b:" + newest.key, newest.head, events, List.copyOf(members),
                members.size(), newest.time, oldest.firstTime, newest.outcome, "Run \u00d7" + members.size(),
                newest.summary, trigger, timed > 0 ? sum / timed : -1, null, true, false);
    }

    // ── Flattening ──────────────────────────────────────────────────

    private static List<FeedRow> flatten(List<FeedRow> top, Set<String> expanded) {
        List<FeedRow> out = new ArrayList<>(top.size());
        for (FeedRow r : top) addWithChildren(out, r, 0, expanded);
        return out;
    }

    private static void addWithChildren(List<FeedRow> out, FeedRow row, int depth, Set<String> expanded) {
        boolean open = row.expandable && expanded.contains(row.key);
        out.add(row.at(depth, open));
        if (!open) return;
        if (row.type == FeedRow.Type.BUCKET) {
            for (FeedRow m : row.members) addWithChildren(out, m, depth + 1, expanded);
        } else if (row.type == FeedRow.Type.RUN) {
            for (MonitorEvent e : row.events) out.add(eventRow(e, depth + 1));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    static FeedRow.Outcome outcomeOf(MonitorEvent e) {
        if (e.isOutcome()) {
            return switch (e.status()) {
                case SUCCESS -> FeedRow.Outcome.SUCCEEDED;
                case FAILED  -> FeedRow.Outcome.FAILED;
                case SKIPPED -> FeedRow.Outcome.SKIPPED;
            };
        }
        return fromSeverity(e.severity());
    }

    private static FeedRow.Outcome fromSeverity(EventKind.Severity s) {
        return switch (s) {
            case ERROR -> FeedRow.Outcome.ERROR;
            case WARN  -> FeedRow.Outcome.WARNING;
            case INFO  -> FeedRow.Outcome.INFO;
            case DEBUG -> FeedRow.Outcome.DEBUG;
        };
    }

    /** "3 file(s), 4.2 MB total in 12ms: ..." -> "3 \u00b7 4.2 MB"; null if it doesn't match. */
    static String filesText(String summary) {
        if (summary == null) return null;
        Matcher m = DETECTED.matcher(summary);
        return m.find() ? m.group(1) + " \u00b7 " + m.group(2) : null;
    }
}
