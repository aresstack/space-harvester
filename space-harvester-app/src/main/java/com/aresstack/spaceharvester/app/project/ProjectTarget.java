package com.aresstack.spaceharvester.app.project;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Das Ziel eines Projekts (ADR 0043, Nachtrag F6, „Ziel des Projekts“): wohin ein Klick auf den Projektnamen unten in
 * der Ecke führt. Die Art steht mit dem Wert, sie wird nie aus dem Inhalt erraten: Der Benutzer wählt sie im Dialog.
 *
 * @param kind  lokaler Ordner oder Datei, oder Website
 * @param value der Pfad bzw. die Adresse; eine Website hat immer {@code http://} oder {@code https://}
 */
public record ProjectTarget(Kind kind, String value) {

    /** Die Art des Ziels; in der Datei als Schlüssel. */
    public enum Kind {
        PATH("pfad"),
        WEBSITE("website");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Die Art zum Schlüssel; {@code null}, wenn unbekannt. */
        public static Kind of(String key) {
            for (Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public ProjectTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    /**
     * Ein Ziel aus der Eingabe des Benutzers; {@code null} bei leerer Eingabe oder einer Website, die keine
     * http(s)-Adresse ist. Einer Website ohne Schema wird {@code https://} vorangestellt.
     */
    public static ProjectTarget of(Kind kind, String input) {
        if (kind == null || input == null || input.isBlank()) {
            return null;
        }
        String value = input.strip();
        if (kind == Kind.PATH) {
            return new ProjectTarget(kind, value);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.matches("^[a-z][a-z0-9+.-]*:.*") && !lower.matches("^[^/:]+:\\d+.*")) {
                return null;
            }
            value = "https://" + value;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() == null || uri.getHost().isBlank() ? null : new ProjectTarget(kind, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isWebsite() {
        return kind == Kind.WEBSITE;
    }
}
