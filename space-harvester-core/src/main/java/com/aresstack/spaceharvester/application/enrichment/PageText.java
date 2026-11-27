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
 * @param sectionsFromText ob die Abschnitte allein aus dem Text folgen (etwa Markdown-Überschriften,
 *                    {@link #markdownSections}); dann bestimmt der Text sie schon und sie zählen nicht eigens in
 *                    {@link #inputSha256()}. So ändert das Erkennen der Überschriften den Stand bestehender Ergebnisse
 *                    nicht.
 */
public record PageText(String schema, String text, List<Section> sections, boolean sectionsFromText) {

    public PageText(String schema, String text, List<Section> sections) {
        this(schema, text, sections, false);
    }

    /**
     * Die Abschnitte eines Markdown-Texts: je Überschrift {@code #} bis {@code ######} am Zeilenanfang einer, bis zur
     * nächsten Überschrift gleicher oder höherer Ebene.
     */
    public static List<Section> markdownSections(String markdown) {
        java.util.regex.Matcher heading = MARKDOWN_HEADING.matcher(markdown);
        List<int[]> found = new java.util.ArrayList<>();
        List<String> titles = new java.util.ArrayList<>();
        while (heading.find()) {
            found.add(new int[] {heading.start(), heading.group(1).length()});
            titles.add(heading.group(2).strip());
        }
        List<Section> sections = new java.util.ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int end = markdown.length();
            for (int next = i + 1; next < found.size(); next++) {
                if (found.get(next)[1] <= found.get(i)[1]) {
                    end = found.get(next)[0];
                    break;
                }
            }
            sections.add(new Section(titles.get(i), found.get(i)[1], found.get(i)[0], end));
        }
        return List.copyOf(sections);
    }

    private static final java.util.regex.Pattern MARKDOWN_HEADING =
            java.util.regex.Pattern.compile("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t#]*$");

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
        for (Section section : sectionsFromText ? List.<Section>of() : sections) {
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
