package com.aresstack.spaceharvester.output.convert;

import java.util.List;
import java.util.Objects;

/**
 * Formatneutrales Abbild des Confluence-Storage-Formats einer Seite, aus dem die Converter Markdown, Word oder PDF
 * schreiben. Es hält nur, was diese Formate darstellen können; Bilder, Anhänge und Diagramme bleiben Verweise.
 */
public final class StorageDocument {

    private StorageDocument() {
    }

    /** Der reine Text von Blöcken, Absätze durch Leerzeichen getrennt; für Stellen, die keine Struktur tragen. */
    public static String text(List<Block> blocks) {
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            String part = switch (block) {
                case Heading heading -> inlineText(heading.content());
                case Paragraph paragraph -> inlineText(paragraph.content());
                case ListBlock list -> String.join(" ", list.items().stream().map(StorageDocument::text).toList());
                case Table table -> String.join(" ", table.rows().stream().flatMap(List::stream)
                        .map(StorageDocument::text).toList());
                case CodeBlock code -> code.code();
                case Panel panel -> panel.label() + ": " + text(panel.content());
                case Rule rule -> "";
                case Note note -> "[Export: " + note.text() + "]";
            };
            if (!part.isBlank()) {
                text.append(text.isEmpty() ? "" : " ").append(part.strip());
            }
        }
        return text.toString();
    }

    /** Der reine Text von Inline-Inhalt. */
    public static String inlineText(List<Inline> content) {
        StringBuilder text = new StringBuilder();
        for (Inline inline : content) {
            switch (inline) {
                case Text plain -> text.append(plain.text());
                case Link link -> text.append(link.text());
                case ImageRef image -> text.append("[Bild: ").append(image.alt()).append(']');
                case LineBreak lineBreak -> text.append(' ');
            }
        }
        return text.toString();
    }

    /** Ein Block im Fluss einer Seite. */
    public sealed interface Block permits Heading, Paragraph, ListBlock, Table, CodeBlock, Panel, Rule, Note {
    }

    /** Text im Fluss eines Blocks. */
    public sealed interface Inline permits Text, Link, ImageRef, LineBreak {
    }

    /** @param level 1 bis 6, relativ zur Seite */
    public record Heading(int level, List<Inline> content) implements Block {

        public Heading {
            content = List.copyOf(content);
        }
    }

    public record Paragraph(List<Inline> content) implements Block {

        public Paragraph {
            content = List.copyOf(content);
        }
    }

    /**
     * @param ordered nummeriert
     * @param items   je Eintrag seine Blöcke
     * @param checked bei Aufgabenlisten je Eintrag erledigt oder nicht, sonst {@code null}
     */
    public record ListBlock(boolean ordered, List<List<Block>> items, List<Boolean> checked) implements Block {

        public ListBlock {
            items = items.stream().map(List::copyOf).toList();
            checked = checked == null ? null : List.copyOf(checked);
        }
    }

    /**
     * @param rows      Zeilen mit Zellen aus Blöcken
     * @param headerRow ob die erste Zeile Spaltenköpfe enthält
     */
    public record Table(List<List<List<Block>>> rows, boolean headerRow) implements Block {

        public Table {
            rows = rows.stream().map(row -> row.stream().map(List::copyOf).toList()).toList();
        }
    }

    /** @param language Sprache aus dem Code-Makro oder leer */
    public record CodeBlock(String language, String code) implements Block {

        public CodeBlock {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(code, "code");
        }
    }

    /** Hervorgehobener Kasten wie die Makros info, note, warning, tip oder ein Zitat. */
    public record Panel(String label, List<Block> content) implements Block {

        public Panel {
            content = List.copyOf(content);
        }
    }

    public record Rule() implements Block {
    }

    /**
     * Hinweis an Stelle von Inhalt, den das Zielformat nicht trägt: ein Diagramm ohne Textquelle in der Payload, ein
     * nicht unterstütztes Makro. Er ist im Ergebnis als solcher erkennbar, nie stillschweigend weggelassen.
     */
    public record Note(String text) implements Block {
    }

    public record Text(String text, boolean bold, boolean italic, boolean code) implements Inline {

        public Text {
            Objects.requireNonNull(text, "text");
        }

        public static Text plain(String text) {
            return new Text(text, false, false, false);
        }
    }

    /** @param target Adresse; {@code #page-<id>} für eine Seite desselben Exports, {@code null} ohne Ziel */
    public record Link(String text, String target) implements Inline {

        public Link {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Verweis auf ein Bild; die Datei selbst gehört nicht zum Export.
     *
     * @param alt       Alternativtext oder Dateiname
     * @param reference {@code attachment:<Datei>} für einen Anhang der Seite oder die Adresse eines externen Bildes
     */
    public record ImageRef(String alt, String reference) implements Inline {
    }

    public record LineBreak() implements Inline {
    }
}
