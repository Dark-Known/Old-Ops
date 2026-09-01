package service;

import util.MailFetchMode;
import util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Outlook mail via the Microsoft Graph REST API instead of raw IMAP.
 *
 * Why: many Microsoft 365 tenants block legacy protocols (IMAP/POP/SMTP AUTH)
 * tenant-wide via Conditional Access / authentication policies — regardless
 * of whether the client authenticates with Basic Auth or OAuth2. Graph is a
 * plain HTTPS REST API — the same path the Outlook web app and mobile apps
 * use — so it is unaffected by that policy.
 *
 * This service intentionally accepts the *same* task-level search criteria
 * string the app already collects via {@code SearchCriteriaPanel} (built
 * from IMAP RFC 3501 syntax) and translates the subset that has a reasonable
 * Graph equivalent into an OData {@code $filter}. Anything unrecognized is
 * logged and skipped rather than silently misapplied — Graph's query
 * language is not a superset of IMAP SEARCH, so a lossy best-effort mapping
 * is the honest option here.
 */
public class GraphMailService {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private final HttpClient http = HttpClient.newHttpClient();

    public static class MailMessage {
        public final String id;
        public final String subject;
        public final String from;
        public final String receivedDateTime;
        public final String bodyContent;
        public final String bodyType; // "html" or "text"
        public final String attachmentText;    // combined text of parseable text attachments, "" if none
        public final List<String> attachmentNames; // names of the attachments attachmentText was built from
        public final List<AttachmentFile> attachmentFiles; // ALL non-inline file attachments, raw bytes, for saving to disk

        MailMessage(String id, String subject, String from,
                    String receivedDateTime, String bodyContent, String bodyType,
                    String attachmentText, List<String> attachmentNames) {
            this(id, subject, from, receivedDateTime, bodyContent, bodyType,
                    attachmentText, attachmentNames, Collections.emptyList());
        }

        MailMessage(String id, String subject, String from,
                    String receivedDateTime, String bodyContent, String bodyType,
                    String attachmentText, List<String> attachmentNames,
                    List<AttachmentFile> attachmentFiles) {
            this.id = id;
            this.subject = subject;
            this.from = from;
            this.receivedDateTime = receivedDateTime;
            this.bodyContent = bodyContent;
            this.bodyType = bodyType;
            this.attachmentText = attachmentText != null ? attachmentText : "";
            this.attachmentNames = attachmentNames != null ? attachmentNames : Collections.emptyList();
            this.attachmentFiles = attachmentFiles != null ? attachmentFiles : Collections.emptyList();
        }
    }

    /** A single downloadable (non-inline, file-type) attachment's raw content. */
    public static class AttachmentFile {
        public final String name;
        public final String contentType;
        public final byte[] bytes;

        AttachmentFile(String name, String contentType, byte[] bytes) {
            this.name = name != null ? name : "attachment";
            this.contentType = contentType != null ? contentType : "";
            this.bytes = bytes != null ? bytes : new byte[0];
        }
    }

    /**
     * @param folder          e.g. "INBOX" (mapped to Graph's well-known "inbox" folder);
     *                        other names are resolved via a display-name lookup
     * @param searchCriteria  IMAP-style criteria string from the task, best-effort
     *                        translated to a Graph $filter (see class docs)
     * @param mode            controls how much of each message body is fetched
     * @param maxResults      upper bound on how many matching messages to return —
     *                        pass 1 for "latest only"; for "entire folder" this is a
     *                        safety cap, not an invitation to fetch unbounded mail —
     *                        results beyond this are paginated via @odata.nextLink
     *                        until either the cap or the last page is reached
     * @param afterEpochMillis  when > 0, only returns messages with receivedDateTime
     *                        strictly after this instant (watcher mode); pass 0 to disable
     */
    public List<MailMessage> fetchMessages(String accessToken, String folder,
                                           String searchCriteria, MailFetchMode mode,
                                           int maxResults, long afterEpochMillis,
                                           Consumer<String> logLine)
            throws IOException, InterruptedException {

        String folderSegment = resolveFolderSegment(accessToken, folder, logLine);
        String filter = mapSearchCriteriaToFilter(searchCriteria, logLine);

        if (afterEpochMillis > 0) {
            String iso = java.time.Instant.ofEpochMilli(afterEpochMillis).toString();
            String epochClause = "receivedDateTime gt " + iso;
            filter = (filter == null || filter.isEmpty()) ? epochClause : filter + " and " + epochClause;
        }

        // Graph pages ~10 messages by default and caps a single page at 999;
        // request pages sized to what's still needed, up to that cap.
        int pageSize = Math.min(Math.max(maxResults, 1), 999);
        String baseUrl = GRAPH_BASE + "/me/mailFolders/" + folderSegment + "/messages"
                + "?$top=" + pageSize
                + "&$select=id,subject,from,receivedDateTime";
        String filterParam = (filter != null && !filter.isEmpty()) ? "&$filter=" + urlEnc(filter) : "";

        // NOTE: Microsoft Graph rejects some $filter + $orderby combinations on the
        // /messages collection (a documented quirk, not something this code can
        // predict in advance without calling the API). Try with $orderby first
        // since it gives cleanest "newest first" ordering; if Graph rejects the
        // combination, retry once without it and log that ordering may be off.
        String nextUrl = baseUrl + "&$orderby=" + urlEnc("receivedDateTime desc") + filterParam;
        List<Map<String, Object>> items = new ArrayList<>();
        boolean orderByDropped = false;

        while (nextUrl != null && items.size() < maxResults) {
            Map<String, Object> page;
            try {
                page = graphGetJson(accessToken, nextUrl);
            } catch (IOException ex) {
                if (!orderByDropped && nextUrl.contains("$orderby=")) {
                    logLine.accept("[WARN] Graph rejected $orderby combined with the current filter — "
                            + "retrying without it (" + ex.getMessage() + ")");
                    orderByDropped = true;
                    nextUrl = baseUrl + filterParam;
                    continue;
                }
                throw ex;
            }
            List<Object> pageItems = MiniJson.getArray(page, "value");
            if (pageItems != null) {
                for (Object o : pageItems) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    items.add(m);
                    if (items.size() >= maxResults) break;
                }
            }
            nextUrl = (items.size() < maxResults)
                    ? MiniJson.getString(page, "@odata.nextLink", null)
                    : null;
        }

        if (items.isEmpty()) {
            logLine.accept("[INFO] No messages matched criteria '" + searchCriteria
                    + "' in folder '" + folder + "'.");
            return Collections.emptyList();
        }
        logLine.accept("[INFO] Fetched " + items.size() + " message(s) (cap=" + maxResults + ").");

        List<MailMessage> results = new ArrayList<>();
        // Oldest-first among the fetched batch, mirroring the previous IMAP FETCH ordering.
        for (int i = items.size() - 1; i >= 0; i--) {
            Map<String, Object> item = items.get(i);

            String id       = MiniJson.getString(item, "id", "");
            String subject  = MiniJson.getString(item, "subject", "");
            String received = MiniJson.getString(item, "receivedDateTime", "");

            String from = "";
            Map<String, Object> fromObj = MiniJson.getObject(item, "from");
            if (fromObj != null) {
                Map<String, Object> addr = MiniJson.getObject(fromObj, "emailAddress");
                if (addr != null) from = MiniJson.getString(addr, "address", "");
            }

            String bodyContent;
            String bodyType = "text";
            String attachmentText = "";
            List<String> attachmentNames = Collections.emptyList();
            List<AttachmentFile> attachmentFiles = Collections.emptyList();

            if (mode == MailFetchMode.FULL_MESSAGE) {
                // Raw RFC 2822 MIME — closest analog to the old IMAP BODY[] fetch.
                // Attachments are already embedded in the MIME itself, so no
                // separate attachment fetch/preference logic applies here.
                bodyContent = graphGetRaw(accessToken, GRAPH_BASE + "/me/messages/" + id + "/$value");
            } else {
                Map<String, Object> full = graphGetJson(accessToken,
                        GRAPH_BASE + "/me/messages/" + id + "?$select=body");
                Map<String, Object> body = MiniJson.getObject(full, "body");
                if (body != null) {
                    bodyContent = MiniJson.getString(body, "content", "");
                    bodyType    = MiniJson.getString(body, "contentType", "text");
                } else {
                    bodyContent = "";
                }

                AttachmentBundle bundle = fetchTextAttachments(accessToken, id, logLine);
                attachmentText = bundle.text;
                attachmentNames = bundle.names;
                attachmentFiles = bundle.files;
            }

            results.add(new MailMessage(id, subject, from, received, bodyContent, bodyType,
                    attachmentText, attachmentNames, attachmentFiles));
        }
        return results;
    }

    // ─── Attachments ─────────────────────────────────────────────────────────

    private static class AttachmentBundle {
        final String text;
        final List<String> names;
        final List<AttachmentFile> files;
        AttachmentBundle(String text, List<String> names, List<AttachmentFile> files) {
            this.text = text;
            this.names = names;
            this.files = files;
        }
    }

    /**
     * Fetches a message's non-inline, file-type attachments: returns both
     * the combined text of the ones that are actually parseable as text
     * (text/plain, text/csv, or a .txt/.csv/.log/.rcv filename — Graph's
     * contentType is sometimes a generic octet-stream for these, so the
     * filename is checked too) AND the raw bytes of every non-inline file
     * attachment (text or binary) so callers can save them to disk as-is.
     *
     * Fetches the plain /attachments collection with NO $select and NO
     * type-cast path segment. Combining an OData cast (e.g.
     * ".../microsoft.graph.fileAttachment") with $select is unreliable on
     * this endpoint — Graph sometimes still validates the $select list
     * against the base "attachment" type (which has no contentBytes) and
     * rejects the whole request with a 400:
     *   "Could not find a property named 'contentBytes' on type
     *    'microsoft.graph.attachment'"
     * even though the cast should have narrowed the type first. Fetching
     * the untouched collection sidesteps the bug: Graph returns each
     * attachment with all of its real (derived-type) properties — including
     * contentBytes for file attachments — and an "@odata.type" field that's
     * used here to distinguish file attachments from item/reference ones
     * client-side instead.
     */
    private AttachmentBundle fetchTextAttachments(String accessToken, String messageId, Consumer<String> logLine)
            throws IOException, InterruptedException {
        Map<String, Object> resp;
        try {
            resp = graphGetJson(accessToken, GRAPH_BASE + "/me/messages/" + messageId + "/attachments");
        } catch (IOException ex) {
            logLine.accept("[WARN] Could not fetch attachments for message " + messageId + ": " + ex.getMessage());
            return new AttachmentBundle("", Collections.emptyList(), Collections.emptyList());
        }

        List<Object> arr = MiniJson.getArray(resp, "value");
        if (arr == null || arr.isEmpty()) return new AttachmentBundle("", Collections.emptyList(), Collections.emptyList());

        StringBuilder combined = new StringBuilder();
        List<String> textNames = new ArrayList<>();
        List<AttachmentFile> files = new ArrayList<>();
        for (Object o : arr) {
            @SuppressWarnings("unchecked")
            Map<String, Object> att = (Map<String, Object>) o;

            if (MiniJson.getBoolean(att, "isInline", false)) continue; // embedded images etc., not real attachments

            // Only file attachments carry contentBytes; item attachments
            // (forwarded emails) and reference attachments (e.g. a
            // SharePoint/OneDrive link) don't — skip those, there is
            // nothing to download.
            String odataType = MiniJson.getString(att, "@odata.type", "");
            String name = MiniJson.getString(att, "name", "(unnamed)");
            if (!"#microsoft.graph.fileAttachment".equals(odataType)) {
                logLine.accept("[INFO] Skipping non-file attachment '" + name + "' (" + odataType
                        + ") — no downloadable content.");
                continue;
            }

            String contentType = MiniJson.getString(att, "contentType", "");
            String b64 = MiniJson.getString(att, "contentBytes", null);
            if (b64 == null || b64.isEmpty()) {
                logLine.accept("[WARN] Attachment '" + name + "' has no contentBytes — skipped.");
                continue;
            }

            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException ex) {
                logLine.accept("[WARN] Could not decode attachment '" + name + "' — skipped.");
                continue;
            }

            files.add(new AttachmentFile(name, contentType, raw));

            if (isTextAttachment(name, contentType)) {
                String text = new String(raw, StandardCharsets.UTF_8);
                if (combined.length() > 0) combined.append("\n\n");
                combined.append(text);
                textNames.add(name);
            }
        }
        return new AttachmentBundle(combined.toString(), textNames, files);
    }

    private boolean isTextAttachment(String name, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".log") || lower.endsWith(".rcv");
    }

    // ─── Folder resolution ──────────────────────────────────────────────────

    private String resolveFolderSegment(String accessToken, String folder, Consumer<String> logLine)
            throws IOException, InterruptedException {
        if (folder == null || folder.trim().isEmpty() || folder.equalsIgnoreCase("INBOX")) return "inbox";
        switch (folder.trim().toUpperCase(Locale.ROOT)) {
            case "SENT":
            case "SENTITEMS":    return "sentitems";
            case "DRAFTS":       return "drafts";
            case "DELETED":
            case "TRASH":        return "deleteditems";
            case "JUNK":
            case "SPAM":         return "junkemail";
            case "ARCHIVE":      return "archive";
            default: {
                String url = GRAPH_BASE + "/me/mailFolders?$filter="
                        + urlEnc("displayName eq '" + folder.trim().replace("'", "''") + "'");
                Map<String, Object> resp = graphGetJson(accessToken, url);
                List<Object> arr = MiniJson.getArray(resp, "value");
                if (arr != null && !arr.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> first = (Map<String, Object>) arr.get(0);
                    return MiniJson.getString(first, "id", "inbox");
                }
                // Folder doesn't exist yet — create it (top-level, under the mailbox
                // root) rather than silently misfiling the message into Inbox. This
                // makes the mail-routing table in Settings genuinely "create on first
                // use": an operator can type a brand-new folder name and it Just Works
                // the first time a message needs to go there, same as if they'd
                // created it by hand in Outlook beforehand.
                try {
                    String createBody = "{\"displayName\":\"" + MiniJson.escapeString(folder.trim()) + "\"}";
                    String created = graphPostJson(accessToken, GRAPH_BASE + "/me/mailFolders", createBody);
                    Map<String, Object> createdObj = MiniJson.parseObject(created);
                    String newId = MiniJson.getString(createdObj, "id", null);
                    if (newId != null) {
                        logLine.accept("[INFO] Created Outlook folder '" + folder.trim() + "' (didn't exist yet).");
                        return newId;
                    }
                } catch (Exception e) {
                    logLine.accept("[WARN] Could not create Outlook folder '" + folder
                            + "' (" + e.getMessage() + ") — defaulting to Inbox.");
                    return "inbox";
                }
                logLine.accept("[WARN] Folder '" + folder + "' not found and could not be created — defaulting to Inbox.");
                return "inbox";
            }
        }
    }

    // ─── Search criteria translation (IMAP-string -> Graph $filter) ─────────

    private static final DateTimeFormatter IMAP_DATE_FMT = new DateTimeFormatterBuilder()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private static final Pattern TEXT_CRITERION =
            Pattern.compile("(FROM|TO|CC|BCC|SUBJECT|BODY|TEXT)\\s+\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_CRITERION =
            Pattern.compile("(SINCE|BEFORE|ON|SENTSINCE|SENTBEFORE|SENTON)\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_CRITERION =
            Pattern.compile("(LARGER|SMALLER)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEYWORD_CRITERION =
            Pattern.compile("(KEYWORD|UNKEYWORD)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_CRITERION =
            Pattern.compile("HEADER\\s+\\S+\\s+\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern UID_CRITERION =
            Pattern.compile("UID\\s+\\S+", Pattern.CASE_INSENSITIVE);

    // Simple (no-argument) IMAP flags that map directly to a Graph $filter
    // clause. NEW has no true Graph equivalent (it means "\Recent and
    // \Unseen", and Graph has no session-based \Recent concept) — approximated
    // as unread, which is the closest useful behavior, not an exact match.
    private static final Map<String, String> SIMPLE_CRITERIA_FILTERS = new LinkedHashMap<>();
    static {
        SIMPLE_CRITERIA_FILTERS.put("UNSEEN", "isRead eq false");
        SIMPLE_CRITERIA_FILTERS.put("SEEN", "isRead eq true");
        SIMPLE_CRITERIA_FILTERS.put("DRAFT", "isDraft eq true");
        SIMPLE_CRITERIA_FILTERS.put("UNDRAFT", "isDraft eq false");
        SIMPLE_CRITERIA_FILTERS.put("FLAGGED", "flag/flagStatus eq 'flagged'");
        SIMPLE_CRITERIA_FILTERS.put("UNFLAGGED", "flag/flagStatus eq 'notFlagged'");
        SIMPLE_CRITERIA_FILTERS.put("NEW", "isRead eq false");
    }

    // Simple flags with genuinely no Graph mail API equivalent — flagged with
    // a specific reason rather than falling through to the generic
    // "unrecognized portion" warning, so it's clear this isn't a typo/gap in
    // parsing but an actual capability Graph doesn't expose.
    private static final Map<String, String> SIMPLE_CRITERIA_UNSUPPORTED = new LinkedHashMap<>();
    static {
        SIMPLE_CRITERIA_UNSUPPORTED.put("ANSWERED", "Graph has no \\Answered-equivalent property on messages");
        SIMPLE_CRITERIA_UNSUPPORTED.put("UNANSWERED", "Graph has no \\Answered-equivalent property on messages");
        SIMPLE_CRITERIA_UNSUPPORTED.put("DELETED", "Graph has no per-message deleted flag — deletion just moves the message to Deleted Items");
        SIMPLE_CRITERIA_UNSUPPORTED.put("UNDELETED", "Graph has no per-message deleted flag — deletion just moves the message to Deleted Items");
        SIMPLE_CRITERIA_UNSUPPORTED.put("RECENT", "Graph has no session-based \\Recent concept");
        SIMPLE_CRITERIA_UNSUPPORTED.put("OLD", "Graph has no session-based \\Recent concept");
    }

    /**
     * Best-effort mapping of the app's IMAP-style search criteria string to a
     * Graph OData $filter. Every token in util.ImapSearchCriteria is handled
     * one of three ways: mapped to a real Graph filter clause, explicitly
     * flagged as unsupported (with the specific reason), or — only for
     * genuinely unknown text — logged as an unrecognized portion. A wrong
     * filter is worse than no filter, so nothing here is guessed at.
     */
    private String mapSearchCriteriaToFilter(String criteria, Consumer<String> logLine) {
        if (criteria == null || criteria.trim().isEmpty()) return null;
        String c = criteria.trim();

        if (c.equalsIgnoreCase("ALL")) return null;

        List<String> clauses = new ArrayList<>();
        String remaining = c;

        for (Map.Entry<String, String> e : SIMPLE_CRITERIA_FILTERS.entrySet()) {
            if (containsToken(remaining, e.getKey())) {
                clauses.add(e.getValue());
                remaining = stripToken(remaining, e.getKey());
            }
        }
        for (Map.Entry<String, String> e : SIMPLE_CRITERIA_UNSUPPORTED.entrySet()) {
            if (containsToken(remaining, e.getKey())) {
                logLine.accept("[WARN] Search criterion '" + e.getKey() + "' skipped — " + e.getValue() + ".");
                remaining = stripToken(remaining, e.getKey());
            }
        }

        Matcher tm = TEXT_CRITERION.matcher(remaining);
        while (tm.find()) {
            String field = tm.group(1).toUpperCase(Locale.ROOT);
            String value = tm.group(2).replace("'", "''");
            switch (field) {
                case "SUBJECT": clauses.add("contains(subject,'" + value + "')"); break;
                case "FROM":    clauses.add("from/emailAddress/address eq '" + value + "'"); break;
                case "TO":      clauses.add("toRecipients/any(r:r/emailAddress/address eq '" + value + "')"); break;
                case "CC":      clauses.add("ccRecipients/any(r:r/emailAddress/address eq '" + value + "')"); break;
                case "BCC":     clauses.add("bccRecipients/any(r:r/emailAddress/address eq '" + value + "')"); break;
                // Graph's $filter has no contains() support for message body —
                // only a handful of properties (subject among them) are
                // filterable that way; body text needs $search, which isn't
                // wired up here. Skip with a note rather than send a filter
                // Graph will reject and fail the whole request over.
                case "BODY":
                case "TEXT":
                    logLine.accept("[WARN] Search criterion '" + field
                            + "' skipped — Graph's $filter doesn't support body text search (would need $search, not implemented).");
                    break;
            }
            remaining = remaining.replace(tm.group(), "");
        }

        Matcher dm = DATE_CRITERION.matcher(remaining);
        while (dm.find()) {
            String op = dm.group(1).toUpperCase(Locale.ROOT);
            String isoDate = java.time.LocalDate.parse(dm.group(2), IMAP_DATE_FMT) + "T00:00:00Z";
            String prop = op.startsWith("SENT") ? "sentDateTime" : "receivedDateTime";
            switch (op) {
                case "SINCE":
                case "SENTSINCE":
                    clauses.add(prop + " ge " + isoDate); break;
                case "BEFORE":
                case "SENTBEFORE":
                    clauses.add(prop + " lt " + isoDate); break;
                case "ON":
                case "SENTON":
                    clauses.add(prop + " ge " + isoDate
                        + " and " + prop + " lt " + isoDate.replace("T00:00:00Z", "T23:59:59Z"));
                    break;
            }
            remaining = remaining.replace(dm.group(), "");
        }

        Matcher km = KEYWORD_CRITERION.matcher(remaining);
        while (km.find()) {
            boolean negate = km.group(1).equalsIgnoreCase("UNKEYWORD");
            String value = km.group(2).replace("'", "''");
            // Graph's "categories" feature is the closest analog to IMAP
            // custom keyword flags.
            clauses.add((negate ? "not " : "") + "categories/any(c:c eq '" + value + "')");
            remaining = remaining.replace(km.group(), "");
        }

        Matcher sm = SIZE_CRITERION.matcher(remaining);
        while (sm.find()) {
            logLine.accept("[WARN] Search criterion '" + sm.group(1).toUpperCase(Locale.ROOT)
                    + "' skipped — Graph's message resource has no filterable size property.");
            remaining = remaining.replace(sm.group(), "");
        }

        Matcher hm = HEADER_CRITERION.matcher(remaining);
        while (hm.find()) {
            logLine.accept("[WARN] Search criterion 'HEADER' skipped — Graph's $filter has no arbitrary-header search.");
            remaining = remaining.replace(hm.group(), "");
        }

        Matcher um = UID_CRITERION.matcher(remaining);
        while (um.find()) {
            logLine.accept("[WARN] Search criterion 'UID' skipped — IMAP UIDs don't correspond to anything in Graph's id scheme.");
            remaining = remaining.replace(um.group(), "");
        }

        remaining = remaining.trim();
        if (!remaining.isEmpty()) {
            logLine.accept("[WARN] Unrecognized portion of search criteria ignored: '" + remaining + "'");
        }

        if (clauses.isEmpty()) return null;
        return String.join(" and ", clauses);
    }

    private boolean containsToken(String s, String token) {
        return Pattern.compile("(?i)(^|\\s)" + token + "(\\s|$)").matcher(s).find();
    }

    private String stripToken(String s, String token) {
        return Pattern.compile("(?i)(^|\\s)" + token + "(\\s|$)").matcher(s).replaceFirst(" ").trim();
    }

    // ─── Post-processing: mark as read / move to another folder ─────────────

    /** Marks a single message as read. Call before {@link #moveMessage} if doing both — move changes the message ID. */
    public void markAsRead(String accessToken, String messageId) throws IOException, InterruptedException {
        graphPatch(accessToken, GRAPH_BASE + "/me/messages/" + messageId, "{\"isRead\":true}");
    }

    /**
     * Moves a message into another folder. By default Graph's move endpoint
     * returns a *new* message resource with a different ID in the destination
     * folder, invalidating the original ID immediately — this is what caused
     * intermittent "ErrorItemNotFound" 404s on subsequent per-message calls
     * (e.g. the attachment fetch in {@link #fetchTextAttachments}) when a
     * message was moved/re-indexed between this app's own calls. All requests
     * in this class now send {@code Prefer: IdType="ImmutableId"}, which keeps
     * a message's ID stable across folder moves, so IDs captured earlier in a
     * run remain valid afterward. Marking as read before moving is still the
     * safer order regardless.
     */
    public void moveMessage(String accessToken, String messageId, String destinationFolder,
                            Consumer<String> logLine) throws IOException, InterruptedException {
        String destId = resolveFolderSegment(accessToken, destinationFolder, logLine);
        String body = "{\"destinationId\":\"" + MiniJson.escapeString(destId) + "\"}";
        graphPost(accessToken, GRAPH_BASE + "/me/messages/" + messageId + "/move", body);
    }

    // ─── HTTP helpers ────────────────────────────────────────────────────────

    private Map<String, Object> graphGetJson(String accessToken, String url)
            throws IOException, InterruptedException {
        return MiniJson.parseObject(graphGetRaw(accessToken, url));
    }

    private String graphGetRaw(String accessToken, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Prefer", "IdType=\"ImmutableId\"")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Graph API call failed (" + resp.statusCode() + ") for " + url
                    + ": " + resp.body());
        }
        return resp.body();
    }

    private void graphPatch(String accessToken, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Prefer", "IdType=\"ImmutableId\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Graph PATCH failed (" + resp.statusCode() + ") for " + url
                    + ": " + resp.body());
        }
    }

    private void graphPost(String accessToken, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Prefer", "IdType=\"ImmutableId\"")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Graph POST failed (" + resp.statusCode() + ") for " + url
                    + ": " + resp.body());
        }
    }

    /** Like {@link #graphPost}, but returns the response body — needed when the caller
     *  wants something back from the created/updated resource (e.g. a new folder's id). */
    private String graphPostJson(String accessToken, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Prefer", "IdType=\"ImmutableId\"")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Graph POST failed (" + resp.statusCode() + ") for " + url
                    + ": " + resp.body());
        }
        return resp.body();
    }

    private String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
