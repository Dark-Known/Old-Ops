package util;

/**
 * One row of the mail-routing table in Settings: a classification key (the
 * marker text a message is checked for — "LDM", "PTM", or any custom word)
 * paired with the Outlook folder name messages of that type get moved into.
 *
 * <p>Replaces the old fixed three text fields (LDM/PTM/Others mailbox
 * folder) with an arbitrary-length, user-editable list, stored as JSON in
 * {@link AppSettings} under {@link AppSettings#KEY_MAIL_ROUTING_RULES}.
 *
 * <p>The special key {@link #OTHERS_KEY} ("Others", case-insensitive) is
 * the fallback bucket: any message that doesn't match another rule's key is
 * routed there. There must always be exactly one such rule — the settings
 * UI and {@link AppSettings#getMailRoutingRules()} both enforce this.
 */
public final class MailRoutingRule {

    /** Case-insensitive fallback key — the bucket for anything that doesn't match another rule. */
    public static final String OTHERS_KEY = "Others";

    private String key;
    private String folder;

    public MailRoutingRule() {}

    public MailRoutingRule(String key, String folder) {
        this.key = key;
        this.folder = folder;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public boolean isOthers() {
        return key != null && key.trim().equalsIgnoreCase(OTHERS_KEY);
    }

    @Override
    public String toString() {
        return key + " → " + folder;
    }
}
