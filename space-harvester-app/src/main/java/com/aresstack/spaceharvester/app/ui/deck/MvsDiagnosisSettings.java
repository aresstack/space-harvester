package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.settings.NumberSetting;
import com.aresstack.spaceharvester.app.ui.AppState;

import java.util.Map;

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

    private final DeckNumbers<Value> numbers;

    public MvsDiagnosisSettings(AppState state) {
        this.numbers = new DeckNumbers<>(state, PREFIX, Value.class);
    }

    /** Die Werte der laufenden App. */
    public static MvsDiagnosisSettings shared() {
        return new MvsDiagnosisSettings(AppState.shared());
    }

    /** Ein Wert; fehlt er oder ist er ungültig, gilt die Vorgabe. */
    public long get(Value value) {
        return numbers.get(value);
    }

    public int number(Value value) {
        return (int) get(value);
    }

    /** Alle Werte, mit Vorgaben aufgefüllt. */
    public Map<Value, Long> values() {
        return numbers.values();
    }

    /** Speichert die Werte; eine Vorgabe wird nicht gemerkt. */
    public void save(Map<Value, Long> values) {
        numbers.save(values);
    }
}
