package util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * The project intentionally avoids adding a JSON library dependency (e.g.
 * org.json / Jackson / Gson) so the build does not need to reach Maven
 * Central for anything beyond what it already pulls in. This parser only
 * needs to handle the shapes returned by Microsoft's OAuth2 token endpoint
 * and the Microsoft Graph API — plain objects, arrays, strings, numbers,
 * booleans, and null — which is a small, well-defined subset of JSON.
 *
 * Parsed objects come back as:
 *   object -> LinkedHashMap<String, Object>
 *   array  -> List<Object>
 *   string -> String
 *   number -> Long (no fraction/exponent) or Double
 *   true/false -> Boolean
 *   null   -> null
 */
public final class MiniJson {

    private MiniJson() {}

    // ─── Parsing ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = new Parser(json).parseValue();
        if (!(result instanceof Map)) {
            throw new IllegalArgumentException("JSON root is not an object: " + json);
        }
        return (Map<String, Object>) result;
    }

    public static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; this.pos = 0; }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw err("Unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expectChar('{');
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expectChar(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char n = nextChar();
                if (n == ',') continue;
                if (n == '}') break;
                throw err("Expected ',' or '}' in object");
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expectChar('[');
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWs();
                char n = nextChar();
                if (n == ',') continue;
                if (n == ']') break;
                throw err("Expected ',' or ']' in array");
            }
            return list;
        }

        private String parseString() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw err("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (pos >= s.length()) throw err("Unterminated escape");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw err("Bad \\u escape");
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default: throw err("Bad escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String numStr = s.substring(start, pos);
            if (numStr.isEmpty() || numStr.equals("-")) throw err("Invalid number");
            return isDouble ? (Object) Double.parseDouble(numStr) : (Object) Long.parseLong(numStr);
        }

        private void expect(String literal) {
            if (pos + literal.length() > s.length() || !s.startsWith(literal, pos)) {
                throw err("Expected literal '" + literal + "'");
            }
            pos += literal.length();
        }

        private void expectChar(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw err("Expected '" + c + "'");
            }
            pos++;
        }

        private char nextChar() {
            skipWs();
            if (pos >= s.length()) throw err("Unexpected end of input");
            return s.charAt(pos++);
        }

        private char peek() {
            skipWs();
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private RuntimeException err(String msg) {
            int ctxStart = Math.max(0, pos - 20);
            int ctxEnd = Math.min(s.length(), pos + 20);
            return new IllegalArgumentException(msg + " at pos " + pos
                    + " near: ..." + s.substring(ctxStart, ctxEnd) + "...");
        }
    }

    // ─── Writing (only what's needed: flat string-keyed objects) ─────────────

    /** Serializes a simple Map<String,String> into a compact JSON object. Null values become JSON null. */
    public static String writeObject(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            if (e.getValue() == null) {
                sb.append("null");
            } else {
                sb.append('"').append(escape(e.getValue())).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** Serializes a list of flat Map<String,String> objects into a compact JSON array. */
    public static String writeArrayOfObjects(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Map<String, String> row : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append(writeObject(row));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String s) {
        return escapeString(s);
    }

    /** Public escaping helper for hand-built JSON request bodies (e.g. small PATCH/POST payloads). */
    public static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ─── Convenience accessors ─────────────────────────────────────────────

    public static String getString(Map<String, Object> obj, String key, String def) {
        Object v = obj.get(key);
        return v != null ? String.valueOf(v) : def;
    }

    public static int getInt(Map<String, Object> obj, String key, int def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public static boolean getBoolean(Map<String, Object> obj, String key, boolean def) {
        Object v = obj.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getArray(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }
}
