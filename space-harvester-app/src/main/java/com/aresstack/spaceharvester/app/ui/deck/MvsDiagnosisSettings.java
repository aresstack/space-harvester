package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.settings.NumberSetting;
import com.aresstack.spaceharvester.app.ui.AppState;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Die Werte der MVS-Binärdiagnose und der Hex-Ansicht in FTP-Datasets, gemerkt in {@code app-state.json} unter
 * {@code deck.ftp.diagnosis.*} wie die übrigen Deck-Einstellungen; Bereich „Mainframe“ der Einstellungen. Früher
 * fest im Code (8 MiB, 4096 und 65536 Bytes, 120 Sekunden); Angelo 26.11.2026: keine Magic Numbers. Gelesen wird
 * bei jedem Gebrauch, eine Änderung gilt ohne Neustart.
 */
public final class MvsDiagnosisSettings {

    static final String PREFIX = "deck.ftp.diagnosis.";

    /** Die einstellbaren Werte. */
    public enum Value implements NumberSetting {
        SAMPLE_BYTES("sample-bytes", "Stichprobe im Speicher (Bytes)",
                "So viele Bytes vom Anfang des binären Transfers hält die Diagnose und die Hex-Ansicht; Länge und "
                        + "SHA-256 gelten immer für den ganzen Transfer", 8L * 1024 * 1024, 1, Integer.MAX_VALUE),
        READ_BYTES("read-bytes", "Bytes je Leseaufruf",
                "So viele Bytes liest die KI höchstens mit einem Aufruf; die Hex-Ansicht zeigt und blättert so viele",
                4096, 1, Integer.MAX_VALUE),
        BUDGET_BYTES("budget-bytes", "Bytes je Freigabe",
                "So viele Bytes darf die KI nach einer Freigabe insgesamt lesen; danach ist eine neue Freigabe "
                        + "nötig", 65536, 1, Integer.MAX_VALUE),
        TIMEOUT_SECONDS("timeout-seconds", "Frist für den Transfer (Sekunden)",
                "So lange wartet ein Diagnoseaufruf höchstens auf den binären Transfer", 120, 1, 86_400);

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

    private final AppState state;

    public MvsDiagnosisSettings(AppState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Die Werte der laufenden App. */
    public static MvsDiagnosisSettings shared() {
        return new MvsDiagnosisSettings(AppState.shared());
    }

    /** Ein Wert; fehlt er oder ist er ungültig, gilt die Vorgabe. */
    public long get(Value value) {
        String text = state.get(PREFIX + value.key());
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

    public int number(Value value) {
        return (int) get(value);
    }

    public Duration seconds(Value value) {
        return Duration.ofSeconds(get(value));
    }

    /** Alle Werte, mit Vorgaben aufgefüllt. */
    public Map<Value, Long> values() {
        Map<Value, Long> values = new EnumMap<>(Value.class);
        for (Value value : Value.values()) {
            values.put(value, get(value));
        }
        return values;
    }

    /** Speichert die Werte; eine Vorgabe wird nicht gemerkt. */
    public void save(Map<Value, Long> values) {
        Map<String, String> changes = new LinkedHashMap<>();
        for (Value value : Value.values()) {
            long chosen = values.getOrDefault(value, value.defaultValue());
            if (!value.accepts(chosen)) {
                throw new IllegalArgumentException(value.label() + " muss zwischen " + value.min() + " und "
                        + value.max() + " liegen");
            }
            changes.put(PREFIX + value.key(), chosen == value.defaultValue() ? null : Long.toString(chosen));
        }
        state.putAll(changes);
    }
}
