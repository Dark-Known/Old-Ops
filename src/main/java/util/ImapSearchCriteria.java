package util;

/**
 * IMAP Search Criteria definitions per RFC 3501.
 * Categorizes all standard IMAP SEARCH criteria by type.
 */
public class ImapSearchCriteria {

    /**
     * Simple flag-based criteria (no arguments required)
     */
    public enum SimpleCriteria {
        ALL("ALL", "All messages"),
        UNSEEN("UNSEEN", "Unread messages"),
        SEEN("SEEN", "Read messages"),
        ANSWERED("ANSWERED", "Replied messages"),
        UNANSWERED("UNANSWERED", "Unreplied messages"),
        DELETED("DELETED", "Marked for deletion"),
        UNDELETED("UNDELETED", "Not marked for deletion"),
        DRAFT("DRAFT", "Draft messages"),
        UNDRAFT("UNDRAFT", "Not draft messages"),
        FLAGGED("FLAGGED", "Flagged messages"),
        UNFLAGGED("UNFLAGGED", "Unflagged messages"),
        RECENT("RECENT", "Recently received"),
        NEW("NEW", "Recent and unseen"),
        OLD("OLD", "Not recently received");

        private final String criterion;
        private final String description;

        SimpleCriteria(String criterion, String description) {
            this.criterion = criterion;
            this.description = description;
        }

        public String getCriterion() { return criterion; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return description + " (" + criterion + ")"; }
    }

    /**
     * Text-based criteria requiring string arguments
     */
    public enum TextCriteria {
        FROM("FROM", "From address"),
        TO("TO", "To address"),
        CC("CC", "CC address"),
        BCC("BCC", "BCC address"),
        SUBJECT("SUBJECT", "Subject line"),
        BODY("BODY", "Message body"),
        TEXT("TEXT", "Headers and body");

        private final String criterion;
        private final String description;

        TextCriteria(String criterion, String description) {
            this.criterion = criterion;
            this.description = description;
        }

        public String getCriterion() { return criterion; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return description + " (" + criterion + ")"; }
    }

    /**
     * Date-based criteria requiring date arguments
     */
    public enum DateCriteria {
        BEFORE("BEFORE", "Before date (DD-MMM-YYYY)"),
        SINCE("SINCE", "Since date (DD-MMM-YYYY)"),
        ON("ON", "On date (DD-MMM-YYYY)"),
        SENTBEFORE("SENTBEFORE", "Sent before date (DD-MMM-YYYY)"),
        SENTSINCE("SENTSINCE", "Sent since date (DD-MMM-YYYY)"),
        SENTON("SENTON", "Sent on date (DD-MMM-YYYY)");

        private final String criterion;
        private final String description;

        DateCriteria(String criterion, String description) {
            this.criterion = criterion;
            this.description = description;
        }

        public String getCriterion() { return criterion; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return description; }
    }

    /**
     * Size-based criteria requiring numeric arguments (in bytes)
     */
    public enum SizeCriteria {
        LARGER("LARGER", "Larger than (bytes)"),
        SMALLER("SMALLER", "Smaller than (bytes)");

        private final String criterion;
        private final String description;

        SizeCriteria(String criterion, String description) {
            this.criterion = criterion;
            this.description = description;
        }

        public String getCriterion() { return criterion; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return description; }
    }

    /**
     * Advanced criteria requiring specific arguments
     */
    public enum AdvancedCriteria {
        HEADER("HEADER", "Custom header"),
        KEYWORD("KEYWORD", "Message keyword"),
        UNKEYWORD("UNKEYWORD", "Without keyword"),
        UID("UID", "Unique ID range");

        private final String criterion;
        private final String description;

        AdvancedCriteria(String criterion, String description) {
            this.criterion = criterion;
            this.description = description;
        }

        public String getCriterion() { return criterion; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return description + " (" + criterion + ")"; }
    }

    /**
     * Helper class for building IMAP search criteria strings
     */
    public static class Builder {
        private StringBuilder sb = new StringBuilder();

        /**
         * Add a simple criterion
         */
        public Builder add(SimpleCriteria criteria) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(criteria.getCriterion());
            return this;
        }

        /**
         * Add a text-based criterion
         */
        public Builder add(TextCriteria criteria, String value) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(criteria.getCriterion()).append(" \"").append(escapeQuotes(value)).append("\"");
            return this;
        }

        /**
         * Add a date-based criterion
         */
        public Builder add(DateCriteria criteria, String date) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(criteria.getCriterion()).append(" ").append(date);
            return this;
        }

        /**
         * Add a size-based criterion
         */
        public Builder add(SizeCriteria criteria, int bytes) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(criteria.getCriterion()).append(" ").append(bytes);
            return this;
        }

        /**
         * Add an advanced criterion
         */
        public Builder add(AdvancedCriteria criteria, String... args) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(criteria.getCriterion());
            for (String arg : args) {
                sb.append(" ").append(arg);
            }
            return this;
        }

        /**
         * Append custom IMAP criterion string
         */
        public Builder addCustom(String criterion) {
            if (criterion != null && !criterion.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(criterion);
            }
            return this;
        }

        /**
         * Get the built criterion string
         */
        public String build() {
            String result = sb.toString().trim();
            return result.isEmpty() ? "ALL" : result;
        }

        /**
         * Clear the builder
         */
        public Builder clear() {
            sb = new StringBuilder();
            return this;
        }

        /**
         * Get current criteria count
         */
        public int size() {
            return sb.toString().trim().split(" ").length;
        }

        private String escapeQuotes(String value) {
            return value != null ? value.replace("\"", "\\\"") : "";
        }

        @Override
        public String toString() {
            return build();
        }
    }
}
