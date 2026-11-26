package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.settings.NumberSetting;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.betaview.http.HttpBetaViewServer;
import com.aresstack.spaceharvester.deck.files.sharepoint.windows.NetUseLogin;

import java.time.Duration;
import java.util.Map;

/**
 * Fristen des Command Deck außerhalb des Terminals, gemerkt in {@code app-state.json} unter {@code deck.timeouts.*};
 * Bereich „Mainframe“ der Einstellungen. Früher fest im Code (Angelo 26.11.2026: keine Magic Numbers). Die Fristen
 * des Terminals (Makros, Anmeldung, OpenTerm) bleiben bis zu einer Entscheidung unverändert. Gelesen wird bei jedem
 * Gebrauch, eine Änderung gilt ohne Neustart.
 */
public final class DeckTimeouts {

    static final String PREFIX = "deck.timeouts.";

    /** Die einstellbaren Fristen, in Sekunden. */
    public enum Value implements NumberSetting {
        FTP_CHAT("ftp-chat-seconds", "KI liest FTP-Datasets (Sekunden)",
                "So lange wartet ein Werkzeug der KI im FTP-Bereich (Datasets auflisten, Dataset lesen) höchstens auf "
                        + "den Host", 120, 1, 86_400),
        EAKTE_DOCUMENT("eakte-document-seconds", "KI liest E-Akte-Dokumente (Sekunden)",
                "So lange darf ein Schritt dauern, wenn die KI ein Dokument der E-Akte liest (Mappe lesen, Dokument "
                        + "lesen)", 120, 1, 86_400),
        BETAVIEW("betaview-seconds", "BetaView-Antwort (Sekunden)",
                "So lange wartet BetaView höchstens auf Verbindung und Antwort des Servers",
                HttpBetaViewServer.DEFAULT_TIMEOUT.toSeconds(), 1, 86_400),
        SHAREPOINT_LOGIN("sharepoint-login-seconds", "SharePoint-Anmeldung über „net use“ (Sekunden)",
                "So lange darf die Anmeldung an einer SharePoint-Bibliothek über WebDAV dauern",
                NetUseLogin.DEFAULT_TIMEOUT.toSeconds(), 1, 86_400);

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

    public DeckTimeouts(AppState state) {
        this.numbers = new DeckNumbers<>(state, PREFIX, Value.class);
    }

    /** Die Fristen der laufenden App. */
    public static DeckTimeouts shared() {
        return new DeckTimeouts(AppState.shared());
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
