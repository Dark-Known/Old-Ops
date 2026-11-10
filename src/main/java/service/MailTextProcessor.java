package service;

import util.AppConfig;
import util.AppSettings;
import util.MiniJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text handling for Outlook mail tasks, split out of {@link TransferService}: HTML/plain-text cleanup,
 * banner and signature stripping, LDM/MVT/PTM recognition, origin/destination extraction, the SITA header
 * block, station-code lookup and the persistent 5-digit message id. No SFTP or transfer state in here.
 */
final class MailTextProcessor {

    private static final DateTimeFormatter RCV_HEADER_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    // Second line of the raw message packet: a dot, a 7-letter SITA address
    // (3-letter station + 4-letter org code), a space, then a 6-digit
    // date-time group. Captures the 3-letter station code.
    private static final Pattern LDM_ORIGIN_SECOND_LINE =
            Pattern.compile("^\\.([A-Z]{3})[A-Z]{4}\\s+\\d{6}");

    // ─── SITA-style header block (LDM / MVT / PTM) ─────────────────────────
    // An isolated line of exactly 3 uppercase letters immediately followed by
    // a baggage (B) or cargo (C) distribution line starting with a dot.
    private static final Pattern LDM_ORIGIN_BEFORE_DISTRIBUTION =
            Pattern.compile("^([A-Z]{3})\\s*$\\R^(?:B|C)\\s+\\.", Pattern.MULTILINE);
    // First run of 3 capital letters right after a '-' on the same line.
    private static final Pattern LDM_DESTINATION_AFTER_DASH =
            Pattern.compile("-\\s*([A-Z]{3})");

    // ─── LDM origin / destination extraction ───────────────────────────────
    // Flight designator + departure date line, e.g. "SV753/06AUG HYDJED":
    // 2-3 char airline code + 1-4 digit flight number, "/", 2-digit day +
    // 3-letter month, then a run of 6 capital letters split into two 3-letter
    // station codes — origin (where these passengers boarded) then the
    // immediate/transit destination (where they disembark).
    private static final Pattern PTM_FLIGHT_ORIGIN_DEST =
            Pattern.compile("^[A-Z0-9]{2,3}\\d{1,4}/\\d{2}[A-Z]{3}\\s+([A-Z]{3})([A-Z]{3})", Pattern.MULTILINE);
    private static final Pattern[] BANNER_REMOVAL_PATTERNS = new Pattern[]{
            // Outlook's "You don't often get email from X. Learn why this is
            // important" safety tip — X varies per message, so match the fixed
            // wrapper text around it rather than a literal sender.
            Pattern.compile("(?is)you don't often get email from .*?learn why this is important\\.?"),
            // "** EXTERNAL EMAIL - Please verify the sender ... **" style banners.
            Pattern.compile("(?is)\\*\\*\\s*external email\\s*-.*?\\*\\*"),
            // Common Office 365 default external-sender banner text.
            Pattern.compile("(?is)this message originated from outside your organization\\.?\\s*"
                    + "(do not click links or open attachments unless you recognize the sender[^.\\n]*\\.)?"),
            // "[EXTERNAL]" tag some gateways prepend to the subject/body.
            Pattern.compile("(?i)^\\s*\\[external]\\s*", Pattern.MULTILINE),
            Pattern.compile("(?i)^caution:\\s*this email originated from outside.*$", Pattern.MULTILINE),
    };
    /**
     * Company convention for these mailboxes: the substantive message content
     * begins at a literal "LDM" / "MVT" / "PTM" marker in the body — whichever
     * one starts the actual message — and everything before it (banners,
     * greetings, etc.) is boilerplate that gets discarded. The marker found
     * also identifies the message type, which drives the =PRIORITY value and
     * whether/how origin+destination are extracted. If none of the three
     * markers are present, no marker is returned and the text is left as-is
     * (not an LDM/MVT/PTM message, so no SITA header is generated for it).
     */
    private static final String[] MESSAGE_TYPE_MARKERS = {"LDM", "MVT", "PTM"};
    // Lines matching any of these mark the start of a signature, footer/
    // disclaimer, or quoted reply history — everything from the first match
    // onward is dropped. Best-effort: real-world mail signature formats
    // vary too much to catch every case, but this covers the common ones
    // (Outlook/Gmail/mobile clients, RFC 3676 "-- " delimiter, disclaimers).
    private static final Pattern[] BODY_CUTOFF_PATTERNS = new Pattern[]{
            Pattern.compile("^-- ?$"),
            Pattern.compile("^_{5,}$"),
            Pattern.compile("(?i)^-{3,}\\s*original message\\s*-{3,}$"),
            Pattern.compile("(?i)^on .{5,120} wrote:\\s*$"),
            Pattern.compile("^from:\\s+\\S.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^>.*$"),
            Pattern.compile("(?i)^sent from my (iphone|android|mobile|samsung|ipad).*$"),
            Pattern.compile("(?i)^get outlook for (ios|android).*$"),
            Pattern.compile("(?i)^(confidentiality notice|disclaimer)\\b.*$"),
            // Common sign-off lines — "Thanks & Regards", "Best Regards,", etc.
            // Anchored to a line containing (at most) a short trailing name/
            // title so it doesn't accidentally match the phrase mid-sentence.
            Pattern.compile("(?i)^thanks\\s*(&|and)\\s*regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|warm|kind|many)\\s+regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(thank\\s*you|thanks)\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|sincerely|cheers|respectfully)\\s*,?\\s*$"),
            Pattern.compile("(?i)^this (e-?mail|message)( and any (attachments|files))?"
                    + " (is|are|contains|may contain).*confidential.*$"),
    };
    private final java.util.function.Supplier<File> dataDir;

    // ─── PTM origin / destination extraction ───────────────────────────────
    private final Object msgIdLock = new Object();
    private Map<String, Object> stationCodeCache;

    // ─── Station code → full SITA address lookup (app-config.xml / JSON) ──
    private long stationCodeCacheFileMtime = -1;
    MailTextProcessor(java.util.function.Supplier<File> dataDir) {
        this.dataDir = dataDir;
    }
    // (defaultStationAddress is no longer cached here — AppSettings.getDefaultStationAddress()
    // already does its own cheap mtime-checked caching and stays live-editable.)

    /**
     * Removes only leading spaces/tabs from a single line, leaving the rest of the line untouched.
     */
    static String stripLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(i);
    }

    /**
     * Builds the fixed-format routing header prepended to LDM/MVT/PTM
     * message content:
     * =HEADER
     * RCV,<yyyy/MM/dd HH:mm>
     * =PRIORITY
     * QU (LDM/MVT) or QN (PTM)
     * =DESTINATION TYPE B
     * STX,<destination SITA address>
     * =ORIGIN
     * <origin SITA address>
     * =MSGID
     * <5-digit sequential id>
     * =SMI
     * The template is identical across all three message types — only the
     * field values differ. Missing origin/destination addresses (extraction
     * or lookup failure) are left blank rather than guessed; a WARN is
     * already logged by the caller in that case.
     */
    String buildSitaHeaderBlock(String messageType, String originAddress,
                                String destinationAddress, String msgId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=HEADER\n");
        sb.append("RCV,").append(LocalDateTime.now().format(RCV_HEADER_DATE_FMT)).append("\n");
        sb.append("=PRIORITY\n");
        sb.append("PTM".equals(messageType) ? "QN" : "QU").append("\n");
        sb.append("=DESTINATION TYPE B\n");
        sb.append("STX,").append(destinationAddress != null ? destinationAddress : "").append("\n");
        sb.append("=ORIGIN\n");
        sb.append(originAddress != null ? originAddress : "").append("\n");
        sb.append("=MSGID\n");
        sb.append(msgId).append("\n");
        sb.append("=SMI\n");
        return sb.toString();
    }

    /**
     * Origin station code for an LDM message. Tries the "second line of the
     * packet" SITA-address convention first (against the untouched, pre-
     * truncation body, since that addressing line precedes the LDM marker);
     * falls back to the "isolated line before a B/C distribution line"
     * convention (against the LDM-truncated body) if the first doesn't match.
     */
    String extractLdmOriginCode(String fullBodyText, String ldmBody) {
        if (fullBodyText != null) {
            String[] lines = fullBodyText.split("\n", -1);
            if (lines.length > 1) {
                Matcher m = LDM_ORIGIN_SECOND_LINE.matcher(lines[1].trim());
                if (m.find()) return m.group(1);
            }
        }
        if (ldmBody != null) {
            Matcher m = LDM_ORIGIN_BEFORE_DISTRIBUTION.matcher(ldmBody);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Destination Type B station code for an LDM message: only the first
     * line containing a '-' is checked (per spec) — if that line's dash
     * isn't immediately followed by 3 capital letters, extraction fails
     * rather than scanning further lines.
     */
    String extractLdmDestinationCode(String ldmBody) {
        if (ldmBody == null) return null;
        for (String line : ldmBody.split("\n", -1)) {
            int dash = line.indexOf('-');
            if (dash < 0) continue;
            Matcher m = LDM_DESTINATION_AFTER_DASH.matcher(line.substring(dash));
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

    // ─── Sequential 5-digit message id ──────────────────────────────────────

    /**
     * Returns {origin, destination} station codes for a PTM message, or {null, null} if not found.
     */
    String[] extractPtmOriginDestination(String ptmBody) {
        if (ptmBody != null) {
            Matcher m = PTM_FLIGHT_ORIGIN_DEST.matcher(ptmBody);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
        }
        return new String[]{null, null};
    }

    /**
     * Loads and caches the station-code → 7-character SITA address map from
     * the JSON file whose path is configured in app-config.xml under
     * <sitaMessaging><stationCodesFile>. The cache is invalidated whenever
     * the file's lastModified timestamp changes, so editing the station
     * codes JSON (by hand, or via a future dedicated editor) takes effect on
     * the next message processed — no restart needed, same as the other
     * live settings in {@link AppSettings}. Missing config/file/JSON just
     * yields an empty map (best-effort — lookups then fail through to the
     * configured default rather than the app crashing).
     */
    synchronized Map<String, Object> loadStationCodes() {
        String path = readAppConfigValue("stationCodesFile");
        if (path == null || path.isEmpty()) return stationCodeCache != null ? stationCodeCache : Collections.emptyMap();
        File f = new File(path);
        long mtime = f.exists() ? f.lastModified() : 0L;
        if (stationCodeCache != null && mtime == stationCodeCacheFileMtime) return stationCodeCache;

        Map<String, Object> loaded = new HashMap<>();
        try {
            if (f.exists()) {
                String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(json);
                if (parsed != null) loaded.putAll(parsed);
            }
        } catch (Exception ignored) {
            // best-effort; invalid JSON just means codes won't expand this read
        }
        stationCodeCache = loaded;
        stationCodeCacheFileMtime = mtime;
        return stationCodeCache;
    }

    /**
     * Reads a single top-level-named element's text from app-config.xml.
     */
    String readAppConfigValue(String tagName) {
        // Delegates to util.AppConfig, which resolves the file next to the
        // running JAR as a fallback when it's not found relative to the
        // process's working directory — a plain new File("app-config.xml")
        // here silently returned null (and therefore no station-code lookup
        // and no default fallback) whenever the process wasn't launched with
        // the install directory as its working directory.
        return AppConfig.readValue(tagName);
    }

    String getDefaultStationAddress() {
        return AppSettings.getDefaultStationAddress();
    }

    /**
     * Resolves a station code to its full SITA address via the JSON lookup,
     * falling back to the <sitaMessaging><defaultStationAddress> configured
     * in app-config.xml when the code couldn't be extracted from the message
     * at all, or couldn't be found in the lookup — so =ORIGIN and
     * =DESTINATION TYPE B are never left blank as long as a default is set.
     */
    String resolveStationAddress(String code, String messageType, String fieldName, Consumer<String> logLine) {
        if (code != null) {
            Object v = loadStationCodes().get(code);
            if (v != null) return v.toString();
            logLine.accept("[WARN] " + messageType + ": no SITA address configured for station code '" + code
                    + "' (needed for =" + fieldName + ") — check stationCodesFile in app-config.xml.");
        }
        String fallback = getDefaultStationAddress();
        if (fallback != null && !fallback.isEmpty()) {
            logLine.accept("[INFO] " + messageType + ": using configured default SITA address '" + fallback
                    + "' for =" + fieldName + ".");
            return fallback;
        }
        return null;
    }

    /**
     * Persistent, sequential 5-digit =MSGID, shared across all LDM/MVT/PTM
     * messages regardless of task. Stored as a plain counter file in the
     * app's data directory so it survives restarts; wraps from 99999 back to
     * 00001. Best-effort: if the counter file can't be read/written, a value
     * is still returned for the current message rather than failing the run.
     */
    String nextMessageId() {
        synchronized (msgIdLock) {
            File counterFile = new File(dataDir.get(), "msgid-counter.txt");
            int next = 1;
            try {
                if (counterFile.exists()) {
                    String s = Files.readString(counterFile.toPath(), StandardCharsets.UTF_8).trim();
                    int current = Integer.parseInt(s);
                    next = (current % 99999) + 1;
                }
            } catch (Exception ignored) {
                next = 1;
            }
            try {
                Files.writeString(counterFile.toPath(), String.valueOf(next), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best-effort persistence; still return a value for this run
            }
            return String.format("%05d", next);
        }
    }

    /**
     * Drops every blank/whitespace-only line so the .RCV file has no gaps
     * between lines of actual content — HTML paragraph/line breaks and the
     * earlier cleanup passes leave behind blank lines (collapsed to at most
     * one), which this removes entirely. Also strips any leading
     * whitespace (spaces/tabs) from the start of each remaining line —
     * HTML-to-plain-text conversion and quoted/indented content upstream
     * can leave lines indented, which this normalizes back to column 0.
     * Trailing whitespace is left alone (not part of this request).
     *
     * <p>Only used for BODY_ONLY/HEADERS_AND_BODY content — never applied
     * to FULL_MESSAGE (raw MIME), where leading whitespace is sometimes
     * structurally significant (RFC 2822 header folding).
     */
    String removeBlankLines(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String stripped = stripLeadingWhitespace(line);
            if (sb.length() > 0) sb.append("\n");
            sb.append(stripped);
        }
        return sb.toString();
    }

    /**
     * Converts a Graph message body to plain text. Graph returns bodyType
     * "html" for the vast majority of real-world mail — writing that raw
     * markup into a .RCV file isn't usable as "body text", so block-level
     * tags are turned into line breaks, all remaining tags are stripped, and
     * the handful of entities that actually show up in mail are decoded.
     * bodyType "text" is returned as-is.
     */
    String toPlainText(String content, String bodyType) {
        if (content == null) return "";
        if (!"html".equalsIgnoreCase(bodyType)) return content;

        String html = content;
        html = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", "");
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        html = html.replaceAll("(?i)<(p|div|li)[^>]*>", "\n");
        html = html.replaceAll("(?s)<[^>]+>", "");
        html = html.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        html = html.replaceAll("[ \\t]+\\n", "\n");
        html = html.replaceAll("\\n{3,}", "\n\n");
        return html.trim();
    }

    /**
     * Strips automated banners that mail gateways/clients inject into the
     * body — Outlook's "first contact" safety tip and generic external-sender
     * warnings — since they're not part of the actual message. Applied
     * wherever the banner appears in the text, not just at the start, since
     * some gateways insert it inline rather than as a strict prefix.
     */
    String removeAutomatedBanners(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        for (Pattern p : BANNER_REMOVAL_PATTERNS) {
            result = p.matcher(result).replaceAll("");
        }
        return result.replaceAll("\\n{3,}", "\n\n").trim();
    }

    MarkerMatch findMessageTypeMarker(String text) {
        if (text == null) return null;
        MarkerMatch best = null;
        for (String type : MESSAGE_TYPE_MARKERS) {
            int idx = text.indexOf(type);
            if (idx >= 0 && (best == null || idx < best.index)) {
                best = new MarkerMatch(type, idx);
            }
        }
        return best;
    }

    /**
     * Classifies a message for automatic post-processing folder routing:
     * checks Subject, attachment text, and body TOGETHER, case-insensitively,
     * against every configured rule's key (see Settings → Message Routing),
     * in the order those rules are listed — so if a message could match more
     * than one key, whichever rule is listed first wins. The "Others"
     * fallback rule's key is never matched against (it's the catch-all, not
     * a marker to search for).
     * <p>
     * Deliberately separate from findMessageTypeMarker (used for the SITA
     * header): that one is a strict, single-source, case-sensitive match
     * against the structured message content, because getting the header
     * wrong has real consequences. This one is a looser "does this message
     * relate to this classification at all" check across everywhere the
     * marker could appear, since misrouting a message to the wrong folder
     * because of a stray lowercase letter or because the word only appeared
     * in the subject is the actual reported problem.
     *
     * @return the matching rule's key (e.g. "LDM", "PTM", or any custom key
     * configured in Settings), or {@code null} if nothing matched
     * (routes to the "Others" folder).
     */
    String classifyForFolderRouting(GraphMailService.MailMessage m) {
        String subject = m.subject != null ? m.subject : "";
        String attachment = m.attachmentText != null ? m.attachmentText : "";
        String body = toPlainText(m.bodyContent, m.bodyType);

        String combined = (subject + "\n" + attachment + "\n" + body).toUpperCase(Locale.ROOT);
        for (util.MailRoutingRule rule : AppSettings.getMailRoutingRules()) {
            if (rule.isOthers()) continue;
            String key = rule.getKey();
            if (key != null && !key.trim().isEmpty() && combined.contains(key.trim().toUpperCase(Locale.ROOT))) {
                return key.trim();
            }
        }
        return null;
    }

    /**
     * Resolves the destination folder name for automatic post-processing
     * move: looks up the folder configured for this classification (LDM,
     * PTM, or any custom key added in Settings), falling back to the
     * "Others" rule's folder if nothing matches. Folder names come from the
     * live app-settings database (see {@link AppSettings#getMailRoutingRules()})
     * so a change made in the Settings panel takes effect on the very next
     * message processed — no restart needed.
     */
    String resolveMoveFolderName(String messageType) {
        return AppSettings.resolveRoutingFolder(messageType);
    }

    String stripSignatureAndQuotedContent(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        int cutoff = lines.length;

        outer:
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            for (Pattern p : BODY_CUTOFF_PATTERNS) {
                if (!p.matcher(line).matches()) continue;
                // A bare "From:" line is ambiguous on its own (could be body text
                // someone typed) — only treat it as a quoted-reply header block
                // when a Sent:/Date: line follows shortly after, matching the
                // Outlook "From: / Sent: / To: / Subject:" quote-block pattern.
                if (line.toLowerCase().startsWith("from:")) {
                    boolean looksLikeQuoteBlock = false;
                    for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                        String next = lines[j].trim().toLowerCase();
                        if (next.startsWith("sent:") || next.startsWith("date:")) {
                            looksLikeQuoteBlock = true;
                            break;
                        }
                    }
                    if (!looksLikeQuoteBlock) continue;
                }
                cutoff = i;
                break outer;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cutoff; i++) {
            sb.append(lines[i]);
            if (i < cutoff - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    static class MarkerMatch {
        final String type;
        final int index;

        MarkerMatch(String type, int index) {
            this.type = type;
            this.index = index;
        }
    }

}
