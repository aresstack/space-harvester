package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.NumberSetting;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Einstellbare Zahlen in {@code app-state.json} unter einem Präfix (etwa {@code deck.…}, {@code app.…}): lesen mit
 * Vorgabe, wenn der Wert fehlt oder ungültig ist, und speichern ohne Vorgaben. Gemeinsame Grundlage etwa von
 * {@code MvsDiagnosisSettings}, {@code DeckTimeouts} und {@link AppTimeouts}; gelesen wird bei jedem Gebrauch, eine
 * Änderung gilt ohne Neustart.
 *
 * @param <V> die Aufzählung der Werte
 */
public final class StateNumbers<V extends Enum<V> & NumberSetting> {

    private final AppState state;
    private final String prefix;
    private final Class<V> type;

    public StateNumbers(AppState state, String prefix, Class<V> type) {
        this.state = Objects.requireNonNull(state, "state");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Ein Wert; fehlt er oder ist er ungültig, gilt die Vorgabe. */
    public long get(V value) {
        String text = state.get(prefix + value.key());
        if (text == null) {
            return value.defaultValue();
        }
        try {
            long chosen = Long.parseLong(text.strip());
            return value.accepts(chosen) ? chosen : value.defaultValue();
        } catch (NumberFormatException e) {
            return value.defaultValue();
        }
    }

    /** Alle Werte, mit Vorgaben aufgefüllt. */
    public Map<V, Long> values() {
        Map<V, Long> values = new EnumMap<>(type);
        for (V value : type.getEnumConstants()) {
            values.put(value, get(value));
        }
        return values;
    }

    /** Speichert die Werte; eine Vorgabe wird nicht gemerkt. */
    public void save(Map<V, Long> values) {
        Map<String, String> changes = new LinkedHashMap<>();
        for (V value : type.getEnumConstants()) {
            long chosen = values.getOrDefault(value, value.defaultValue());
            if (!value.accepts(chosen)) {
                throw new IllegalArgumentException(value.label() + " muss zwischen " + value.min() + " und "
                        + value.max() + " liegen");
            }
            changes.put(prefix + value.key(), chosen == value.defaultValue() ? null : Long.toString(chosen));
        }
        state.putAll(changes);
    }
}
