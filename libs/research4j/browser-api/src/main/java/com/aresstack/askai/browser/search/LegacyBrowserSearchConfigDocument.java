package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The versioned, immutable configuration document handed from the host to the Java-21 browser sidecar
 * ({@code --browser-config=<absolute-path>}) instead of dozens of CLI arguments. JSON with a fixed,
 * restricted shape (all settings values are strings in the canonical codec form):
 * <pre>
 * { "schemaVersion": 1, "settingsRevision": 3, "settingsDigest": "…",
 *   "settings": { "consent.enabled": "true", … } }
 * </pre>
 * Writer and parser are dependency-free so every module (Java 8 host, Java 8 runtime, Java 21 sidecar)
 * uses the SAME implementation. The parser is strict: a malformed document throws with a readable
 * message — a broken config never silently degrades to defaults.
 *
 * <p>PRECEDENCE (documented contract): built-in defaults &lt; this document &lt; explicit legacy CLI
 * overrides ({@code --search-url=} etc.), the latter being dev/test-only escape hatches. There is no
 * mixed state beyond that rule.</p>
 */
public final class LegacyBrowserSearchConfigDocument {

    /**
     * v1 = A2 field set; v2 = A3 mechanical-analysis fields added to the analysis section;
     * v3 = A4 layout-repair ticket-cache settings; v4 = the AI layout resolver ships productively
     * enabled (the stale disabled shipped default in frozen session profiles is lifted by the typed
     * snapshot migration); v5 = the primary-engine-plus-fallback-templates pair is replaced by an
     * ORDERED engine selection with an acquisition mode; v6 = the shipped challenge-text list no longer
     * carries the bare word "captcha" (an untouched old shipped list is lifted while decoding, see
     * {@link LegacyBrowserSearchSettingsCodec#liftStaleShippedDefaults}); v7 = the engine list is the engine
     * table (priority, languages and further languages per row; old {@code id:on:pages[:delay]} lists are
     * migrated while decoding with the stored acquisition mode, see
     * {@link LegacyBrowserSearchSettingsCodec#migrateEngineList}); v8 = priority groups with the "enough
     * results" setting {@code navigation.minimumUsableResults} (a document without it takes the central
     * default). Older documents decode cleanly:
     * missing keys fall back to {@link LegacyBrowserSearchDefaults}, which is the migration.
     */
    public static final int CURRENT_SCHEMA_VERSION = 8;

    public final int schemaVersion;
    public final long settingsRevision;
    /** Digest of the FULL source settings (provenance); the document may carry a subset. */
    public final String settingsDigest;
    /** The owning profile (session snapshot id); empty for a plain hand-off document. */
    public final String profileId;
    /** Creation instant of the owning profile; 0 for a plain hand-off document. */
    public final long createdAtEpochMillis;
    public final Map<String, String> values;

    public LegacyBrowserSearchConfigDocument(int schemaVersion, long settingsRevision,
                                             String settingsDigest, Map<String, String> values) {
        this(schemaVersion, settingsRevision, settingsDigest, "", 0L, values);
    }

    public LegacyBrowserSearchConfigDocument(int schemaVersion, long settingsRevision,
                                             String settingsDigest, String profileId,
                                             long createdAtEpochMillis, Map<String, String> values) {
        this.schemaVersion = schemaVersion;
        this.settingsRevision = settingsRevision;
        this.settingsDigest = settingsDigest;
        this.profileId = profileId == null ? "" : profileId;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    /**
     * The browser-near subset for the sidecar: AI prompts and reranker/model configuration stay in
     * the Java-8 research agent and are NEVER transferred into the browser process.
     */
    public static Map<String, String> sidecarSubset(Map<String, String> allValues) {
        Map<String, String> subset = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : allValues.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("aiLayoutResolver.") && !key.startsWith("reranker.")) {
                subset.put(key, entry.getValue());
            }
        }
        return subset;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n");
        sb.append("  \"settingsRevision\": ").append(settingsRevision).append(",\n");
        sb.append("  \"settingsDigest\": ").append(quote(settingsDigest)).append(",\n");
        if (!profileId.isEmpty()) {
            sb.append("  \"profileId\": ").append(quote(profileId)).append(",\n");
            sb.append("  \"createdAtEpochMillis\": ").append(createdAtEpochMillis).append(",\n");
        }
        sb.append("  \"settings\": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("\n    ").append(quote(entry.getKey())).append(": ")
              .append(quote(entry.getValue()));
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    /** Strict parse; throws {@link IllegalArgumentException} with a readable reason on any deviation. */
    public static LegacyBrowserSearchConfigDocument parse(String json) {
        Parser parser = new Parser(json);
        Map<String, Object> root = parser.parseObject();
        parser.expectEnd();
        Object schemaVersion = root.get("schemaVersion");
        Object revision = root.get("settingsRevision");
        Object digest = root.get("settingsDigest");
        Object settings = root.get("settings");
        if (!(schemaVersion instanceof Long)) {
            throw new IllegalArgumentException("browser config: schemaVersion missing or not a number");
        }
        if (((Long) schemaVersion).intValue() > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("browser config: schemaVersion " + schemaVersion
                    + " is newer than supported " + CURRENT_SCHEMA_VERSION);
        }
        if (!(settings instanceof Map)) {
            throw new IllegalArgumentException("browser config: settings object missing");
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) settings).entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("browser config: setting '" + entry.getKey()
                        + "' must be a string value");
            }
            values.put(String.valueOf(entry.getKey()), (String) entry.getValue());
        }
        Object profileId = root.get("profileId");
        Object createdAt = root.get("createdAtEpochMillis");
        return new LegacyBrowserSearchConfigDocument(
                ((Long) schemaVersion).intValue(),
                revision instanceof Long ? (Long) revision : 0L,
                digest instanceof String ? (String) digest : "",
                profileId instanceof String ? (String) profileId : "",
                createdAt instanceof Long ? (Long) createdAt : 0L,
                values);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** Minimal strict JSON parser for the restricted document shape (objects, strings, longs, bools). */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw fail("expected ',' or '}'");
                }
            }
        }

        private Object parseValue() {
            char c = peek();
            if (c == '{') {
                return parseObject();
            }
            if (c == '"') {
                return parseString();
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            int start = pos;
            while (pos < text.length() && "-+0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (pos == start) {
                throw fail("unexpected value");
            }
            try {
                return Long.valueOf(text.substring(start, pos));
            } catch (NumberFormatException ex) {
                throw fail("invalid number '" + text.substring(start, pos) + "'");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw fail("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw fail("unterminated escape");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw fail("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw fail("unknown escape '\\" + escape + "'");
                }
            }
        }

        void expectEnd() {
            skipWhitespace();
            if (pos < text.length()) {
                throw fail("trailing content");
            }
        }

        private void expect(char expected) {
            if (next() != expected) {
                pos--;
                throw fail("expected '" + expected + "'");
            }
        }

        private char next() {
            if (pos >= text.length()) {
                throw fail("unexpected end of document");
            }
            return text.charAt(pos++);
        }

        private char peek() {
            if (pos >= text.length()) {
                throw fail("unexpected end of document");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException(
                    "browser config: malformed JSON at offset " + pos + ": " + reason);
        }
    }
}
