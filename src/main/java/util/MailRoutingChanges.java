package util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rules for editing the mail-routing list and describing what an edit changed (for the activity log). */
public final class MailRoutingChanges {

    private MailRoutingChanges() { }

    /** Adds {@code key -> folder}, replacing any rule with the same key (case-insensitive) instead of duplicating it. */
    public static void upsert(List<MailRoutingRule> rules, String key, String folder) {
        rules.removeIf(r -> key.equalsIgnoreCase(r.getKey()));
        rules.add(new MailRoutingRule(key, folder));
    }

    /**
     * A "what actually changed" description for a save: every key whose folder was added, removed or
     * changed, instead of a bare rule count. Keys match case-insensitively, as {@link #upsert} does.
     */
    public static String describe(List<MailRoutingRule> before, List<MailRoutingRule> after) {
        Map<String, String> oldByKey = new LinkedHashMap<>();
        for (MailRoutingRule r : before) oldByKey.put(r.getKey().toLowerCase(), r.getFolder());
        Map<String, String> newByKey = new LinkedHashMap<>();
        for (MailRoutingRule r : after) newByKey.put(r.getKey().toLowerCase(), r.getFolder());

        List<String> changes = new ArrayList<>();
        for (MailRoutingRule r : after) {
            String key = r.getKey().toLowerCase();
            if (!oldByKey.containsKey(key)) {
                changes.add("added \"" + r.getKey() + "\" → \"" + r.getFolder() + "\"");
            } else if (!oldByKey.get(key).equals(r.getFolder())) {
                changes.add("\"" + r.getKey() + "\": \"" + oldByKey.get(key) + "\" → \"" + r.getFolder() + "\"");
            }
        }
        for (MailRoutingRule r : before) {
            if (!newByKey.containsKey(r.getKey().toLowerCase())) {
                changes.add("removed \"" + r.getKey() + "\" (was → \"" + r.getFolder() + "\")");
            }
        }
        return changes.isEmpty() ? "no changes (re-saved as-is)" : String.join("; ", changes);
    }
}
