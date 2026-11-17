package service;

/** Reads and checks values typed into the settings screen, without a window. */
public final class SettingsInput {

    private SettingsInput() { }

    /**
     * The "batch size override" in bytes: blank means 0 (no override), a negative number is treated as 0.
     *
     * @return the value, or {@code null} if the text is not a whole number
     */
    public static Long parseBatchMaxBytes(String text) {
        String t = text == null ? "" : text.trim();
        try {
            long parsed = t.isEmpty() ? 0 : Long.parseLong(t);
            return Math.max(parsed, 0);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }
}
