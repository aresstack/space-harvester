package com.aresstack.spaceharvester.app.settings;

import java.util.Locale;

/**
 * Woher die App den API-Key des KI-Dienstes bezieht ({@code space-harvester-app.ai-access},
 * {@code space-harvester-app.ai-keepass-entry}); wie „Zugang“ im Enterprise AI Client. Gilt nur für die Desktop-App:
 * Der Core erhält allein den aufgelösten Key. In der Datei stehen nur Bezugsart und Eintragstitel, nie das Geheimnis.
 *
 * @param mode  gewählte Bezugsart oder {@code null}, solange nichts gewählt ist
 * @param entry Titel des KeePass-Eintrags oder {@code null}
 */
public record AiAccess(Mode mode, String entry) {

    /** Nichts gewählt: Die App nutzt einen Key im Tresor, die Oberfläche schlägt KeePass vor. */
    public static final AiAccess NONE = new AiAccess(null, null);
    /** Der Standardeintrag wie im Enterprise AI Client, damit beide denselben Eintrag lesen. */
    public static final String DEFAULT_ENTRY = "Enterprise AI API";

    public enum Mode {
        /** Basisadresse und API-Key aus einem KeePass-Eintrag (KeePassRPC). */
        KEEPASS("keepass"),
        /** API-Key im Tresor dieses Windows-Kontos (DPAPI) bzw. nur für die Sitzung. */
        MANUAL("manual");

        private final String key;

        Mode(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Der Modus zu {@code keepass} oder {@code manual}, sonst {@code null}. */
        public static Mode parse(String text) {
            if (text == null) {
                return null;
            }
            for (Mode mode : values()) {
                if (mode.key.equals(text.strip().toLowerCase(Locale.ROOT))) {
                    return mode;
                }
            }
            return null;
        }
    }

    public AiAccess {
        entry = entry == null || entry.isBlank() ? null : entry.strip();
    }

    /** Ob der Key aus KeePass kommt: KeePass gewählt und ein Eintrag benannt. */
    public boolean fromKeePass() {
        return mode == Mode.KEEPASS && entry != null;
    }
}
