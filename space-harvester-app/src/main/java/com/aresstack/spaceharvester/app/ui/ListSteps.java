package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.NumberSetting;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import java.util.Map;

/**
 * Wie viele Einträge eine blätternde Liste je Schritt lädt („Weitere laden“), gemerkt in {@code app-state.json}
 * unter {@code app.lists.*}; Bereich „Allgemein“ der Einstellungen. Früher fest im Code (Angelo 26.11.2026: keine
 * Magic Numbers). Gelesen wird bei jedem Gebrauch, eine Änderung gilt ohne Neustart.
 */
public final class ListSteps {

    static final String PREFIX = "app.lists.";

    /** Die einstellbaren Schritte. */
    public enum Value implements NumberSetting {
        // Obergrenze ist der größte Stapel, den der Core je Abfrage von Begriffen liefert.
        TERMS("terms-step", "Begriffe, Wörter und Fundstellen je Schritt",
                "So viele Einträge laden der Reiter „Begriffe“, die Wortsuche und die Textausschnitte der "
                        + "Fundstellen auf einmal; „Weitere laden“ holt die nächsten", 120, 1, TermQueries.MAX_LIMIT);

        private final String key;
        private final String label;
        private final String hint;
        private final long defaultValue;
        private final long min;
        private final long max;

        Value(String key, String label, String hint, long defaultValue, long min, long max) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String hint() {
            return hint;
        }

        @Override
        public long defaultValue() {
            return defaultValue;
        }

        @Override
        public long min() {
            return min;
        }

        @Override
        public long max() {
            return max;
        }
    }

    private final StateNumbers<Value> numbers;

    public ListSteps(AppState state) {
        this.numbers = new StateNumbers<>(state, PREFIX, Value.class);
    }

    /** Die Schritte der laufenden App. */
    public static ListSteps shared() {
        return new ListSteps(AppState.shared());
    }

    /** Ein Schritt; fehlt er oder ist er ungültig, gilt die Vorgabe. */
    public int get(Value value) {
        return Math.toIntExact(numbers.get(value));
    }

    /** Alle Schritte, mit Vorgaben aufgefüllt. */
    public Map<Value, Long> values() {
        return numbers.values();
    }

    /** Speichert die Schritte; eine Vorgabe wird nicht gemerkt. */
    public void save(Map<Value, Long> values) {
        numbers.save(values);
    }
}
