package com.aresstack.spaceharvester.application.enrichment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Sichere interne Textprojektion einer Seite: sichtbarer Fließtext ohne Markup, ohne Makroinhalte und ohne aufgelöste
 * Verweise. Eingabe aller Prozessoren; nie eine Ausgabe der API.
 *
 * @param schema      Projektionsregeln, z. B. {@code confluence-storage-text/v1}; ändern sich die Regeln, ändert sich
 *                    das Schema und damit {@link #inputSha256()}
 * @param text        Text mit {@code \n} zwischen Blöcken
 * @param sections    Abschnitte in Textreihenfolge, je Überschrift einer
 */
public record PageText(String schema, String text, List<Section> sections) {

    public PageText {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(text, "text");
        sections = List.copyOf(sections);
        for (Section section : sections) {
            if (section.end() > text.length()) {
                throw new IllegalArgumentException("Abschnitt reicht über das Textende");
            }
        }
    }

    /**
     * {@code sha256:<hex>} über Schema, Abschnitte und Text: die Eingabe, gegen die ein Ergebnis berechnet wurde. Wird
     * nur eine Überschrift umgestuft, ändert sich der Hash also auch bei gleichem Text.
     */
    public String inputSha256() {
        StringBuilder input = new StringBuilder(schema).append('\n');
        for (Section section : sections) {
            // Überschriften stehen im Text; Ebene und Grenzen genügen, um die Gliederung festzulegen.
            input.append(section.level()).append(',').append(section.start()).append(',').append(section.end())
                    .append(';');
        }
        input.append('\n').append(text);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Der Abschnitt, in dem {@code offset} liegt, oder {@code null} vor der ersten Überschrift. */
    public Section sectionAt(int offset) {
        Section found = null;
        for (Section section : sections) {
            if (section.start() <= offset) {
                found = section;
            }
        }
        return found;
    }

    /**
     * Eine Überschrift und der Text bis zur nächsten.
     *
     * @param heading Text der Überschrift
     * @param level   1 bis 6
     * @param start   Zeichenposition der Überschrift im Text
     * @param end     Position nach dem Abschnitt
     */
    public record Section(String heading, int level, int start, int end) {

        public Section {
            Objects.requireNonNull(heading, "heading");
            if (level < 1 || level > 6 || start < 0 || end < start) {
                throw new IllegalArgumentException("Ungültiger Abschnitt");
            }
        }
    }
}
