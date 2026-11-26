package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.NumberSetting;

import java.time.Duration;
import java.util.Map;

/**
 * Fristen der App außerhalb von Core und Command Deck, gemerkt in {@code app-state.json} unter {@code app.timeouts.*};
 * Bereich „Allgemein“ der Einstellungen. Früher fest im Code (Angelo 26.11.2026: keine Magic Numbers). Gelesen wird
 * bei jedem Gebrauch, eine Änderung gilt ohne Neustart.
 */
public final class AppTimeouts {

    static final String PREFIX = "app.timeouts.";

    /** Die einstellbaren Fristen, in Sekunden. */
    public enum Value implements NumberSetting {
        DOWNLOAD_CONNECT("download-connect-seconds", "Modell-Download: Verbindung (Sekunden)",
                "So lange darf der Aufbau einer Verbindung beim Laden von Modellen (Parser, Hugging Face) dauern",
                30, 1, 86_400),
        DOWNLOAD_RESPONSE("download-response-seconds", "Modell-Download: Antwort (Sekunden)",
                "So lange wartet ein Modell-Download höchstens auf die Antwortköpfe des Servers; ein hängender "
                        + "Datenstrom endet über „Abbrechen“", 60, 1, 86_400),
        FIREFOX_START("firefox-start-seconds", "Firefox-Start für den Chat (Sekunden)",
                "So lange darf der Start von Firefox dauern, wenn der Chat Webseiten liest", 30, 1, 86_400),
        DIAGRAM_LABELS("diagram-labels-seconds", "Beschriftung von Code-Diagrammen (Sekunden)",
                "So lange wartet die Beschriftung eines Code-Diagramms höchstens auf das Modell", 30, 1, 86_400),
        AUDIO_SILENCE("audio-silence-seconds", "Transkription: ohne Ton (Sekunden)",
                "So lange darf VLC beim Transkribieren keine Tondaten liefern, bevor der Lauf abbricht", 60, 1,
                86_400);

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

    public AppTimeouts(AppState state) {
        this.numbers = new StateNumbers<>(state, PREFIX, Value.class);
    }

    /** Die Fristen der laufenden App. */
    public static AppTimeouts shared() {
        return new AppTimeouts(AppState.shared());
    }

    /** Eine Frist; fehlt sie oder ist sie ungültig, gilt die Vorgabe. */
    public Duration get(Value value) {
        return Duration.ofSeconds(numbers.get(value));
    }

    /** Alle Fristen in Sekunden, mit Vorgaben aufgefüllt. */
    public Map<Value, Long> values() {
        return numbers.values();
    }

    /** Speichert die Fristen; eine Vorgabe wird nicht gemerkt. */
    public void save(Map<Value, Long> values) {
        numbers.save(values);
    }
}
