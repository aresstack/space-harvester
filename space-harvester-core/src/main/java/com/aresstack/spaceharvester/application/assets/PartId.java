package com.aresstack.spaceharvester.application.assets;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Teilkennung einer enthaltenen Datei innerhalb ihres äußeren Behälters (ADR 0026, Punkt 5). Sie ist flach je äußerem
 * Behälter; die Verschachtelung steht nur am {@link EmbeddedEntry} (Elterneintrag, Tiefe). Anzeigename und Tikas
 * Namenspfad werden nie Teilkennung.
 *
 * <ul>
 *   <li>{@link Content}: Bytes vollständig gelesen, also SHA-256 samt laufender Nummer unter inhaltsgleichen Einträgen
 *       desselben äußeren Behälters. Übersteht neue Tika-Versionen und neue Stände des Behälters.</li>
 *   <li>{@link Position}: Bytes nicht vollständig gelesen (Grenze, Lesefehler), also der Zählerpfad in Lesereihenfolge,
 *       gültig nur für genau diesen Stand des Behälters (dessen SHA-256).</li>
 * </ul>
 */
public sealed interface PartId {

    /** Die Teilkennung als Zeichenkette, z. B. {@code sha256:<hex>:1} oder {@code pos:<hex>:/1/3}. */
    String value();

    /** Liest eine Teilkennung aus {@link #value()}. */
    static PartId parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] parts = value.split(":", 3);
        if (parts.length == 3 && Content.PREFIX.equals(parts[0])) {
            try {
                return new Content(parts[1], Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Keine Teilkennung", e);
            }
        }
        if (parts.length == 3 && Position.PREFIX.equals(parts[0])) {
            return new Position(parts[1], parts[2]);
        }
        throw new IllegalArgumentException("Keine Teilkennung");
    }

    /**
     * Inhaltsbasierte Teilkennung.
     *
     * @param sha256  SHA-256 der vollständig gelesenen Bytes, 64 Hexziffern
     * @param ordinal laufende Nummer unter inhaltsgleichen Einträgen in Lesereihenfolge über den
     *                ganzen äußeren Behälter (nicht je Elternteil), ab 1
     */
    record Content(String sha256, int ordinal) implements PartId {

        static final String PREFIX = "sha256";

        public Content {
            sha256 = hex(sha256, "sha256");
            if (ordinal < 1) {
                throw new IllegalArgumentException("Laufende Nummer ab 1");
            }
        }

        @Override
        public String value() {
            return PREFIX + ':' + sha256 + ':' + ordinal;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    /**
     * Teilkennung nach Position, für Einträge ohne vollständig gelesene Bytes.
     *
     * @param containerSha256 SHA-256 des äußeren Behälters, für dessen Stand die Position gilt
     * @param path            Zählerpfad in Lesereihenfolge wie {@code /1/3}, jede Stufe ab 1
     */
    record Position(String containerSha256, String path) implements PartId {

        static final String PREFIX = "pos";
        private static final Pattern PATH = Pattern.compile("(/[1-9][0-9]{0,8})+");

        public Position {
            containerSha256 = hex(containerSha256, "containerSha256");
            Objects.requireNonNull(path, "path");
            if (!PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("Zählerpfad wie /1/3 erwartet");
            }
        }

        /** Verschachtelungstiefe nach dem Pfad, ab 1. */
        public int depth() {
            return (int) path.chars().filter(c -> c == '/').count();
        }

        @Override
        public String value() {
            return PREFIX + ':' + containerSha256 + ':' + path;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    private static String hex(String value, String name) {
        Objects.requireNonNull(value, name);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.length() != 64 || !lower.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
            throw new IllegalArgumentException(name + " ist kein SHA-256");
        }
        return lower;
    }
}
