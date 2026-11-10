package service;

import model.EventKind;
import util.MiniJson;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the message a Teams workflow ("When a Teams webhook request is received") posts to a channel or chat: an
 * Adaptive Card with a coloured header, the key facts, the reason, what to do about it and an "Open logs" button.
 * Plain JSON text; nothing here talks to the network.
 */
public final class TeamsCard {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TeamsCard() {
    }

    public static String build(Alert a) {
        String style = a.recovery() ? "good" : a.kind() != null && a.kind().defaultSeverity() == EventKind.Severity.ERROR ? "attention"
                : a.kind() != null && a.kind().defaultSeverity() == EventKind.Severity.WARN ? "warning" : "accent";
        String icon = a.recovery() ? "✅" : style.equals("attention") ? "❌" : style.equals("warning") ? "⚠️" : "ℹ️";

        List<String> body = new ArrayList<>();
        body.add("{\"type\":\"Container\",\"style\":\"" + style + "\",\"bleed\":true,\"items\":[{\"type\":\"ColumnSet\",\"columns\":["
                + "{\"type\":\"Column\",\"width\":\"auto\",\"items\":[{\"type\":\"TextBlock\",\"text\":" + q(icon) + ",\"size\":\"ExtraLarge\"}]},"
                + "{\"type\":\"Column\",\"width\":\"stretch\",\"verticalContentAlignment\":\"Center\",\"items\":["
                + "{\"type\":\"TextBlock\",\"text\":" + q(a.title()) + ",\"weight\":\"Bolder\",\"size\":\"Large\",\"wrap\":true},"
                + "{\"type\":\"TextBlock\",\"text\":" + q("Ops Transfer Tool on " + nz(a.host()) + "  |  " + TS.format(a.raisedAt()))
                + ",\"isSubtle\":true,\"spacing\":\"None\",\"size\":\"Small\",\"wrap\":true}]}]}]}");

        if (!nz(a.message()).isEmpty()) {
            body.add("{\"type\":\"TextBlock\",\"text\":" + q(clip(a.message(), 600)) + ",\"wrap\":true,\"spacing\":\"Medium\"}");
        }

        List<String> facts = new ArrayList<>();
        if (!nz(a.taskName()).isEmpty()) facts.add(fact("Task", a.taskName()));
        if (!nz(a.taskType()).isEmpty()) facts.add(fact("Type", a.taskType()));
        if (a.kind() != null) facts.add(fact("Event", a.kind().label()));
        if (!nz(a.status()).isEmpty()) facts.add(fact("Result", a.status()));
        if (a.startedAt() != null) facts.add(fact("Started", TS.format(a.startedAt())));
        if (a.durationMs() > 0) facts.add(fact("Duration", duration(a.durationMs())));
        if (!nz(a.trigger()).isEmpty()) facts.add(fact("Triggered by", a.trigger()));
        if (a.suppressed() > 0)
            facts.add(fact("Repeats held back", a.suppressed() + " similar alert" + (a.suppressed() == 1 ? "" : "s")
                    + " in the last interval"));
        if (!facts.isEmpty())
            body.add("{\"type\":\"FactSet\",\"facts\":[" + String.join(",", facts) + "],\"spacing\":\"Medium\"}");

        String det = nz(a.details()).trim();
        if (!det.isEmpty() && !det.equals(nz(a.message()).trim())) {
            body.add("{\"type\":\"Container\",\"style\":\"emphasis\",\"spacing\":\"Medium\",\"items\":["
                    + "{\"type\":\"TextBlock\",\"text\":\"Details\",\"weight\":\"Bolder\",\"size\":\"Small\"},"
                    + "{\"type\":\"TextBlock\",\"text\":" + q(clip(det, 700)) + ",\"wrap\":true,\"fontType\":\"Monospace\",\"size\":\"Small\"}]}");
        }
        if (!nz(a.suggestedAction()).isEmpty() && !a.recovery()) {
            body.add("{\"type\":\"TextBlock\",\"text\":" + q("What to do: " + clip(a.suggestedAction(), 400))
                    + ",\"wrap\":true,\"spacing\":\"Medium\",\"weight\":\"Bolder\"}");
        }
        if (!nz(a.reportPath()).isEmpty()) {
            body.add("{\"type\":\"TextBlock\",\"text\":" + q("Full report (open in a browser): " + a.reportPath())
                    + ",\"wrap\":true,\"isSubtle\":true,\"size\":\"Small\",\"spacing\":\"Medium\"}");
        }

        String actions = nz(a.reportUrl()).isEmpty() ? "" :
                ",\"actions\":[{\"type\":\"Action.OpenUrl\",\"title\":\"Open logs\",\"url\":" + q(a.reportUrl()) + "}]";
        return "{\"type\":\"message\",\"attachments\":[{\"contentType\":\"application/vnd.microsoft.card.adaptive\",\"contentUrl\":null,"
                + "\"content\":{\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\",\"type\":\"AdaptiveCard\","
                + "\"version\":\"1.4\",\"msteams\":{\"width\":\"Full\"},\"body\":[" + String.join(",", body) + "]" + actions + "}}]}";
    }

    private static String fact(String title, String value) {
        return "{\"title\":" + q(title) + ",\"value\":" + q(value) + "}";
    }

    private static String q(String s) {
        return "\"" + MiniJson.escapeString(s == null ? "" : s) + "\"";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    static String duration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 120) return s + " s";
        long m = s / 60;
        if (m < 120) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    /**
     * Everything one alert says.
     */
    public record Alert(EventKind kind, boolean recovery, String taskId, String taskName, String taskType,
                        String status,
                        String message, String details, String suggestedAction, LocalDateTime startedAt,
                        LocalDateTime endedAt,
                        long durationMs, String trigger, String runKey, String host, String reportUrl,
                        String reportPath,
                        int suppressed, LocalDateTime raisedAt) {

        public String title() {
            String what = kind == null ? "Alert" : kind.label();
            return (recovery ? "Recovered: " : "") + what + (taskName == null || taskName.isBlank() ? "" : " - " + taskName);
        }
    }
}
