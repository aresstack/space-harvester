package com.aresstack.enterpriseai.astu.source;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Einstellungen einer Quelle als Schlüssel und Text, ohne Präfix {@code source.<n>.} und ohne {@code type}
 * und {@code enabled}. Welche Schlüssel es gibt, bestimmt der Adapter ({@link KnowledgeSourceType#fields()});
 * Kern und Oberfläche reichen die Werte nur durch. Leere Werte zählen als nicht gesetzt. Enthält nie Secrets, nur
 * Verweise darauf; {@link #toString()} nennt trotzdem nur die Schlüssel.
 */
public final class SourceSettings {


    private final Map<String, String> values;

    private SourceSettings(Map<String, String> values) {
        this.values = values;
    }

    public static SourceSettings empty() {
        return new SourceSettings(Collections.<String, String>emptyMap());
    }

    /** Übernimmt die nicht leeren Werte in der Reihenfolge der Map (getrimmt). */
    public static SourceSettings of(Map<String, String> values) {
        Map<String, String> copy = new LinkedHashMap<String, String>();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                put(copy, entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? empty() : new SourceSettings(Collections.unmodifiableMap(copy));
    }

    /** Der Wert oder leer. */
    public String get(String key) {
        String value = values.get(key);
        return value == null ? "" : value;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Eine Kopie mit dem Wert; ein leerer Wert entfernt den Schlüssel. */
    public SourceSettings with(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<String, String>(values);
        copy.remove(key);
        put(copy, key, value);
        return copy.isEmpty() ? empty() : new SourceSettings(Collections.unmodifiableMap(copy));
    }

    /** Alle gesetzten Werte in ihrer Reihenfolge (unveränderlich). */
    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) {
            target.put(key.trim(), trimmed);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceSettings && values.equals(((SourceSettings) other).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "SourceSettings" + values.keySet();
    }
}
