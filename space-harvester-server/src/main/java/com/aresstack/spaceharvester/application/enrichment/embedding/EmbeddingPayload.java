package com.aresstack.spaceharvester.application.enrichment.embedding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Das Ergebnis von {@link EmbeddingProcessor} gelesen: {@code {"model","dimensions","chunks":[{"charStart","charEnd",
 * "section","vector":[…]}]}}. Für die Ablage der Vektoren (#89), die das JSON nicht selbst deuten soll.
 *
 * @param model      Modellkennung
 * @param dimensions Länge jedes Vektors
 * @param chunks     Stücke in Textreihenfolge
 */
public record EmbeddingPayload(String model, int dimensions, List<Chunk> chunks) {

    public EmbeddingPayload {
        Objects.requireNonNull(model, "model");
        chunks = List.copyOf(chunks);
        for (Chunk chunk : chunks) {
            if (chunk.vector().length != dimensions) {
                throw new IllegalArgumentException("Vektor hat nicht die Dimension " + dimensions);
            }
        }
    }

    /**
     * Ein eingebettetes Stück.
     *
     * @param charStart erstes Zeichen in der Textprojektion
     * @param charEnd   Ende (exklusiv)
     * @param vector    Vektor des Modells
     */
    public record Chunk(int charStart, int charEnd, float[] vector) {
    }

    /**
     * Liest das Ergebnis.
     *
     * @throws IllegalArgumentException wenn es nicht dem Format von {@link EmbeddingProcessor} entspricht
     */
    public static EmbeddingPayload parse(String json) {
        Object root = new Parser(Objects.requireNonNull(json, "json")).document();
        Map<?, ?> object = as(root, Map.class, "Ergebnis");
        String model = as(object.get("model"), String.class, "model");
        int dimensions = integer(object.get("dimensions"), "dimensions");
        List<Chunk> chunks = new ArrayList<>();
        for (Object item : as(object.get("chunks"), List.class, "chunks")) {
            Map<?, ?> chunk = as(item, Map.class, "chunk");
            List<?> values = as(chunk.get("vector"), List.class, "vector");
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) as(values.get(i), Double.class, "vector").doubleValue();
                if (!Float.isFinite(vector[i])) {
                    throw new IllegalArgumentException("vector enthält keinen endlichen Wert");
                }
            }
            chunks.add(new Chunk(integer(chunk.get("charStart"), "charStart"), integer(chunk.get("charEnd"),
                    "charEnd"), vector));
        }
        return new EmbeddingPayload(model, dimensions, chunks);
    }

    private static int integer(Object value, String name) {
        double number = as(value, Double.class, name);
        if (number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " ist keine natürliche Zahl");
        }
        return (int) number;
    }

    private static <T> T as(Object value, Class<T> type, String name) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(name + " fehlt oder hat den falschen Typ");
        }
        return type.cast(value);
    }

    /** Genug JSON für dieses Format: Objekte, Listen, Zeichenketten, Zahlen, true, false, null. */
    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value();
            skipSpace();
            if (at != text.length()) {
                throw error();
            }
            return value;
        }

        private Object value() {
            skipSpace();
            if (at >= text.length()) {
                throw error();
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skipSpace();
            if (peek('}')) {
                at++;
                return map;
            }
            while (true) {
                skipSpace();
                if (!peek('"')) {
                    throw error();
                }
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
                if (peek(',')) {
                    at++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            skipSpace();
            if (peek(']')) {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                skipSpace();
                if (peek(',')) {
                    at++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw error();
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error();
                }
            }
            throw error();
        }

        private Double number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            try {
                return Double.valueOf(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error();
            }
            at += word.length();
            return value;
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error();
            }
            at++;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("Kein gültiges Embedding-Ergebnis (Position " + at + ")");
        }
    }
}
