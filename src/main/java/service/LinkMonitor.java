package service;

import model.EventKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when a socket link reported by another server's application needs attention. No I/O and no clock of
 * its own, so every rule is unit-testable.
 *
 * <p>The application on the sending server pushes two kinds of message: <b>events</b> when the link changes state
 * ({@code connected}, {@code disconnected}, {@code stalled}, {@code recovered}, {@code reconnecting}) and a small
 * <b>heartbeat</b> carrying the current state. Alerts are raised on <i>transitions</i> (once), then repeated at a
 * set interval while the problem lasts. If nothing at all arrives for too long the monitor alerts that the source
 * stopped reporting: a crashed or unreachable server cannot announce its own disconnect.
 *
 * <p>Thread-safe: the HTTP ingest threads and the scheduler tick share one instance.
 */
public final class LinkMonitor {

    /** Where the link stands, as last reported. */
    public enum State { CONNECTED, CONNECTING, DISCONNECTED, STALLED }

    /**
     * Settings of one monitored source. The token itself is never stored, only its hash; after a token is regenerated
     * the previous hash stays valid until {@code prevValidUntil} (epoch millis) so the sender can be updated without a gap.
     */
    public record Source(String id, String label, boolean enabled,
                         String tokenHash, String prevTokenHash, long prevValidUntil, long tokenIssuedAt,
                         int heartbeatSeconds, int silentAfterSeconds, int repeatMinutes,
                         int flapCount, int flapWindowMinutes) {
        public Source {
            if (heartbeatSeconds < 5) heartbeatSeconds = 60;
            if (silentAfterSeconds < 10) silentAfterSeconds = heartbeatSeconds * 3;
            if (repeatMinutes < 0) repeatMinutes = 30;
            if (flapCount < 2) flapCount = 5;
            if (flapWindowMinutes < 1) flapWindowMinutes = 10;
            if (label == null || label.isBlank()) label = id;
            if (tokenHash == null) tokenHash = "";
            if (prevTokenHash == null) prevTokenHash = "";
        }

        /** True when this presented token is the current one, or the previous one still inside its grace period. */
        public boolean accepts(String presentedToken, long now) {
            if (!enabled) return false;
            if (LinkTokens.matches(presentedToken, tokenHash)) return true;
            return !prevTokenHash.isEmpty() && now < prevValidUntil && LinkTokens.matches(presentedToken, prevTokenHash);
        }
    }

    /** One incoming message, already authenticated and parsed. */
    public record Report(String type, String state, long seq, String instance, String reason,
                         int reconnectsLastHour) {}

    /** Something to tell the operator. */
    public record Alert(EventKind kind, String sourceId, String label, String summary, String details) {}

    /** What happened to a batch of reports. */
    public record Ingest(int accepted, int duplicates, List<Alert> alerts) {}

    private static final class Track {
        State state;                 // null until first contact
        long stateSince;
        long lastHeardAt;
        boolean silent;
        long silentSince;
        String instance;
        long lastSeq = -1;
        long badSince;               // when the current bad spell began
        long lastRepeatAt;           // last time we alerted about the current bad state / silence
        long lastFlapAlertAt = Long.MIN_VALUE;
        final Deque<Long> breaks = new ArrayDeque<>();
        String lastReason = "";
    }

    private final Map<String, Track> tracks = new HashMap<>();
    private final Map<String, Source> sources = new HashMap<>();

    public LinkMonitor(long nowMillis) { }

    /**
     * Replaces the configured sources (config reload). State of sources that stay is kept; a source that is new, or
     * newly enabled, starts counting its quiet time from {@code now} (not from when the monitor started), so adding
     * one in Settings does not raise an immediate "stopped reporting".
     */
    public synchronized void setSources(List<Source> list, long now) {
        sources.clear();
        for (Source s : list) {
            if (!s.enabled()) continue;
            sources.put(s.id(), s);
            // A source we have never heard from counts as quiet since the monitor started.
            tracks.computeIfAbsent(s.id(), k -> { Track t = new Track(); t.lastHeardAt = now; return t; });
        }
        tracks.keySet().retainAll(sources.keySet());
    }

    public static State parseState(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase()) {
            case "connected": case "up": case "established": case "recovered": return State.CONNECTED;
            case "connecting": case "reconnecting": return State.CONNECTING;
            case "disconnected": case "down": case "closed": return State.DISCONNECTED;
            case "stalled": case "stale": return State.STALLED;
            default: return null;
        }
    }

    /** The state a message implies: an event type names it directly, a heartbeat carries it in {@code state}. */
    static State impliedState(Report r) {
        String t = r.type() == null ? "heartbeat" : r.type().trim().toLowerCase();
        if (t.equals("heartbeat") || t.equals("status")) return parseState(r.state());
        State s = parseState(t);
        return s != null ? s : parseState(r.state());
    }

    public synchronized Ingest ingest(String sourceId, List<Report> reports, long now) {
        Source src = sources.get(sourceId);
        Track tr = tracks.get(sourceId);
        if (src == null || tr == null) return new Ingest(0, 0, List.of());
        List<Alert> out = new ArrayList<>();
        int accepted = 0, dup = 0;
        for (Report r : reports) {
            // A restarted application starts counting again: a new instance id resets the duplicate filter.
            if (r.instance() != null && !r.instance().equals(tr.instance)) { tr.instance = r.instance(); tr.lastSeq = -1; }
            if (r.seq() >= 0) {
                if (r.seq() <= tr.lastSeq) {
                    dup++;
                    // A repeated or out-of-order message adds no news, but it proves the sender is alive.
                    touch(src, tr, now, out);
                    continue;
                }
                tr.lastSeq = r.seq();
            }
            accepted++;
            apply(src, tr, r, now, out);
        }
        return new Ingest(accepted, dup, out);
    }

    /** Something arrived from the source: it is not silent any more. */
    private void touch(Source src, Track tr, long now, List<Alert> out) {
        tr.lastHeardAt = now;
        if (tr.silent) {
            tr.silent = false;
            out.add(alert(EventKind.LINK_UP, src, "Reporting again",
                    "Reports from " + src.label() + " resumed after " + minutes(now - tr.silentSince) + "."));
        }
    }

    private void apply(Source src, Track tr, Report r, long now, List<Alert> out) {
        touch(src, tr, now, out);
        if (r.reason() != null && !r.reason().isBlank()) tr.lastReason = r.reason();

        State next = impliedState(r);
        if (next == null) return;
        State prev = tr.state;
        if (prev == next) {
            // Same state again (heartbeat). Flapping can also show up as a counter the heartbeat carries.
            maybeFlapFromCounter(src, tr, r, now, out);
            return;
        }
        boolean wasBad = prev == State.DISCONNECTED || prev == State.STALLED;
        // "Reconnecting" while already down is progress, not recovery: stay down until it is really connected.
        if (next == State.CONNECTING && wasBad) return;
        tr.state = next;
        tr.stateSince = now;
        boolean nowBad = next == State.DISCONNECTED || next == State.STALLED;

        if (nowBad && !wasBad) {
            tr.lastRepeatAt = now;
            tr.badSince = now;
            tr.breaks.addLast(now);
            trimBreaks(src, tr, now);
            String why = tr.lastReason.isEmpty() ? "" : " Reason: " + tr.lastReason + ".";
            if (next == State.STALLED) {
                out.add(alert(EventKind.LINK_STALLED, src, "Connected but no data or acknowledgement",
                        "The socket of " + src.label() + " is open but stalled: no data or acknowledgement from the other end." + why));
            } else {
                out.add(alert(EventKind.LINK_DOWN, src, "Disconnected",
                        "The socket of " + src.label() + " is disconnected." + why));
            }
            maybeFlap(src, tr, now, out);
        } else if (nowBad) {
            // DISCONNECTED <-> STALLED while already bad: no new alert, the reminder covers it.
        } else if (next == State.CONNECTED && wasBad) {
            out.add(alert(EventKind.LINK_UP, src, "Connected again",
                    "The socket of " + src.label() + " is connected again after " + minutes(now - tr.badSince) + "."));
        }
    }

    private void maybeFlapFromCounter(Source src, Track tr, Report r, long now, List<Alert> out) {
        if (r.reconnectsLastHour() >= src.flapCount() * 2 && flapDue(src, tr, now)) {
            tr.lastFlapAlertAt = now;
            out.add(alert(EventKind.LINK_FLAPPING, src, "Reconnecting repeatedly",
                    src.label() + " reported " + r.reconnectsLastHour() + " reconnects in the last hour."));
        }
    }

    private void maybeFlap(Source src, Track tr, long now, List<Alert> out) {
        if (tr.breaks.size() >= src.flapCount() && flapDue(src, tr, now)) {
            tr.lastFlapAlertAt = now;
            out.add(alert(EventKind.LINK_FLAPPING, src, "Dropping repeatedly",
                    "The socket of " + src.label() + " dropped " + tr.breaks.size() + " times in the last "
                            + src.flapWindowMinutes() + " minutes."));
        }
    }

    private boolean flapDue(Source src, Track tr, long now) {
        long every = Math.max(1, src.repeatMinutes()) * 60_000L;
        return tr.lastFlapAlertAt == Long.MIN_VALUE || now - tr.lastFlapAlertAt >= every;
    }

    private void trimBreaks(Source src, Track tr, long now) {
        long cutoff = now - src.flapWindowMinutes() * 60_000L;
        while (!tr.breaks.isEmpty() && tr.breaks.peekFirst() < cutoff) tr.breaks.removeFirst();
    }

    /** Called periodically: raises "stopped reporting" and the repeat reminders. */
    public synchronized List<Alert> tick(long now) {
        List<Alert> out = new ArrayList<>();
        for (Source src : sources.values()) {
            Track tr = tracks.get(src.id());
            if (tr == null) continue;
            long quiet = now - tr.lastHeardAt;
            if (!tr.silent && quiet > src.silentAfterSeconds() * 1000L) {
                tr.silent = true;
                tr.silentSince = tr.lastHeardAt;
                tr.lastRepeatAt = now;
                out.add(alert(EventKind.LINK_SILENT, src, "Stopped reporting",
                        "Nothing received from " + src.label() + " for " + minutes(quiet)
                                + ". Its server or application may be down, or it cannot reach this server."));
                continue;
            }
            boolean bad = tr.silent || tr.state == State.DISCONNECTED || tr.state == State.STALLED;
            if (bad && src.repeatMinutes() > 0 && now - tr.lastRepeatAt >= src.repeatMinutes() * 60_000L) {
                tr.lastRepeatAt = now;
                if (tr.silent) {
                    out.add(alert(EventKind.LINK_SILENT, src, "Still not reporting",
                            "Still nothing from " + src.label() + " (" + minutes(now - tr.silentSince) + " so far)."));
                } else {
                    out.add(alert(tr.state == State.STALLED ? EventKind.LINK_STALLED : EventKind.LINK_DOWN, src, "Still unhealthy",
                            "The socket of " + src.label() + " has been " + tr.state.name().toLowerCase()
                                    + " for " + minutes(now - tr.stateSince) + "."));
                }
            }
        }
        return out;
    }

    /** What a status display needs to know about one source. */
    public record Snapshot(String id, String label, State state, long stateSince, long lastHeardAt, boolean silent) {}

    public synchronized List<Snapshot> snapshots() {
        List<Snapshot> out = new ArrayList<>();
        for (String id : sourceIds()) {
            Track t = tracks.get(id);
            if (t == null) continue;
            out.add(new Snapshot(id, sources.get(id).label(), t.state, t.stateSince, t.lastHeardAt, t.silent));
        }
        return out;
    }

    /** Current state of a source, for status displays and tests; null when unknown. */
    public synchronized State stateOf(String sourceId) {
        Track t = tracks.get(sourceId);
        return t == null ? null : t.state;
    }

    public synchronized boolean isSilent(String sourceId) {
        Track t = tracks.get(sourceId);
        return t != null && t.silent;
    }

    public synchronized List<String> sourceIds() {
        List<String> ids = new ArrayList<>(sources.keySet());
        Collections.sort(ids);
        return ids;
    }

    private static Alert alert(EventKind kind, Source s, String headline, String details) {
        return new Alert(kind, s.id(), s.label(), s.label() + ": " + headline, details);
    }

    static String minutes(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 90) return sec + " s";
        long min = sec / 60;
        if (min < 120) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
