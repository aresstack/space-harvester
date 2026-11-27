package com.aresstack.askai.research.runtime.rerank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON reader/writer scoped to the {@code /api/rerank} dialect, following the
 * repo convention of a per-module private JSON helper. Parsing is strict: malformed input throws
 * {@link JsonParseException} rather than guessing.
 */
final class RerankJson {

    static final class JsonParseException extends RuntimeException {
        JsonParseException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private RerankJson(String src) {
        this.src = src;
    }

    // ------------------------------------------------------------------ writer

    static void appendString(StringBuilder sb, String value) {
        sb.append('"');
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
        sb.append('"');
    }

    // ------------------------------------------------------------------ reader

    static Object parse(String text) {
        if (text == null) {
            throw new JsonParseException("null input");
        }
        RerankJson parser = new RerankJson(text.trim());
        if (parser.src.isEmpty()) {
            throw new JsonParseException("empty input");
        }
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw new JsonParseException("trailing content at " + parser.pos);
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new JsonParseException("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't':
            case 'f': return readBoolean();
            case 'n': readLiteral("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException("expected object key at " + pos);
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw new JsonParseException("expected ',' or '}' at " + (pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<Object>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return array;
        }
        while (true) {
            array.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw new JsonParseException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new JsonParseException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw new JsonParseException("unterminated escape");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new JsonParseException("bad unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw new JsonParseException("bad unicode escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw new JsonParseException("bad escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Boolean readBoolean() {
        if (peek() == 't') {
            readLiteral("true");
            return Boolean.TRUE;
        }
        readLiteral("false");
        return Boolean.FALSE;
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (pos == start) {
            throw new JsonParseException("unexpected character at " + pos);
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new JsonParseException("invalid number at " + start);
        }
    }

    private void readLiteral(String literal) {
        if (!src.regionMatches(pos, literal, 0, literal.length())) {
            throw new JsonParseException("expected '" + literal + "' at " + pos);
        }
        pos += literal.length();
    }

    private void expect(char c) {
        if (next() != c) {
            throw new JsonParseException("expected '" + c + "' at " + (pos - 1));
        }
    }

    private char next() {
        if (pos >= src.length()) {
            throw new JsonParseException("unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
