package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.document.ExportGraphics;
import com.aresstack.spaceharvester.document.MarkdownExportWriter;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.CodeBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Heading;
import com.aresstack.spaceharvester.output.convert.StorageDocument.ImageRef;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Inline;
import com.aresstack.spaceharvester.output.convert.StorageDocument.LineBreak;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Link;
import com.aresstack.spaceharvester.output.convert.StorageDocument.ListBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Note;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Panel;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Paragraph;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Rule;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Table;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Text;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown (#30): alle Seiten eines Exports in einer Datei {@code pages.md}, linear in Baumreihenfolge, geschrieben mit
 * dem Markdown-Exporter des Enterprise AI Client (Modul {@code :document-export}) aus demselben Kapitelmodell wie Word
 * und PDF ({@link ExportChapters}): {@code #} ist der Space, Wurzelseiten {@code ##}, deren Kinder {@code ###} …; tiefer
 * als sechs Ebenen wird die Überschrift eine fette Zeile. Jede Seite beginnt mit einer Sprungmarke {@code page-<id>},
 * darunter Seiten-ID, Version und Adresse. Der Inhalt kommt aus dem Storage-Format; Verweise auf Seiten desselben
 * Exports zeigen auf deren Sprungmarke.
 *
 * <p>Bilder, die der Datenbestand abgelegt hat, stehen als {@code data:}-URI im Text; Mermaid als Codeblock mit seiner
 * Textquelle und darunter als gezeichnetes Bild; draw.io-Diagramme gezeichnet aus ihren abgelegten Quelldaten. Formatverluste: nicht abgelegte Bilder erscheinen als „Abbildung
 * nicht verfügbar“ mit Grund, externe zusätzlich mit ihrer Adresse; Gliffy-Diagramme
 * erscheinen als Hinweis mit ihrem Namen, PlantUML als Codeblock mit seiner Textquelle; Infokästen werden
 * Zitatblöcke; Zellen mit Listen oder mehreren Absätzen werden einzeilig; Farben, Ausrichtung und verbundene Zellen
 * entfallen. Nicht unterstützte Makros stehen als Hinweis im Text. Siehe {@code docs/CONVERTERS.md}.
 */
public final class MarkdownConverter implements ExportConverter {

    public static final String FORMAT = "markdown";
    public static final String VERSION = "markdown/6";

    /** Zeichen am Zeilenanfang, die Markdown sonst als Überschrift, Liste oder Trennlinie läse. */
    private static final Pattern LINE_START = Pattern.compile("^(\\s*\\d*)([#=+-]|(?<=\\d)[.)])");

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String mediaType() {
        return "text/markdown; charset=UTF-8";
    }

    @Override
    public String fileName() {
        return "pages.md";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        StorageParser parser = new StorageParser(PageLinks.of(input.headers(), input.spaceKey()));
        MarkdownExportWriter markdown = new MarkdownExportWriter(ExportGraphics.rendering());
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        String title = ExportChapters.title(input);
        writer.write(markdown.chapter(escape(title), null, ExportChapters.rootText(input, VERSION), List.of(), 1));
        try {
            input.forEachPage(page -> {
                try {
                    ExportChapters.Figures figures = ExportChapters.figures(page, parser, input.assets());
                    figures.notices().forEach(input::report);
                    writer.write(markdown.chapter(escape(ExportChapters.title(page)), PageLinks.anchor(page.pageId()),
                            figures.markdown(), figures.images(), ExportChapters.depth(page)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        writer.flush();
    }

    /** Blöcke, durch Leerzeilen getrennt; Überschriften im Inhalt liegen unter der Seitenüberschrift. */
    static String blocks(List<Block> blocks, int headingOffset) {
        StringBuilder text = new StringBuilder();
        Block previous = null;
        for (Block block : blocks) {
            String rendered = block(block, headingOffset);
            if (!rendered.isEmpty()) {
                if (previous instanceof ListBlock && block instanceof ListBlock) {
                    // Zwei Listen direkt hintereinander läse Markdown als eine; der Kommentar trennt sie.
                    text.append("<!-- -->\n\n");
                }
                text.append(rendered).append("\n\n");
                previous = block;
            }
        }
        return text.toString();
    }

    private static String block(Block block, int headingOffset) {
        return switch (block) {
            case Heading heading -> "#".repeat(Math.min(6, headingOffset + heading.level())) + " "
                    + inline(heading.content()).replace("\n", " ");
            case Paragraph paragraph -> paragraph(paragraph.content());
            case ListBlock list -> list(list, headingOffset);
            case Table table -> table(table);
            case CodeBlock code -> code(code);
            case Panel panel -> quote("**" + escape(panel.label()) + "**\n\n" + blocks(panel.content(), headingOffset)
                    .stripTrailing());
            case Rule rule -> "---";
            case Note note -> quote("*[Export: " + escape(note.text()) + "]*");
        };
    }

    private static String paragraph(List<Inline> content) {
        List<String> lines = new ArrayList<>();
        for (String line : inline(content).split("\n", -1)) {
            lines.add(LINE_START.matcher(line).replaceFirst("$1\\\\$2"));
        }
        return String.join("\\\n", lines);
    }

    private static String list(ListBlock list, int headingOffset) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < list.items().size(); i++) {
            String marker = list.ordered() ? (i + 1) + ". " : "- ";
            if (list.checked() != null) {
                marker += list.checked().get(i) ? "[x] " : "[ ] ";
            }
            String content = blocks(list.items().get(i), headingOffset).stripTrailing();
            String indent = " ".repeat(list.ordered() ? (i + 1 + ". ").length() : 2);
            String[] lines = content.isEmpty() ? new String[] {""} : content.split("\n", -1);
            text.append(marker).append(lines[0]).append('\n');
            for (int line = 1; line < lines.length; line++) {
                text.append(lines[line].isEmpty() ? "" : indent + lines[line]).append('\n');
            }
        }
        return text.toString().stripTrailing();
    }

    private static String table(Table table) {
        int columns = table.rows().stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        List<List<List<Block>>> rows = table.rows();
        int first = 0;
        if (table.headerRow()) {
            text.append(row(rows.getFirst(), columns));
            first = 1;
        } else {
            text.append(row(List.of(), columns));
        }
        text.append('|').append(" --- |".repeat(columns)).append('\n');
        for (int i = first; i < rows.size(); i++) {
            text.append(row(rows.get(i), columns));
        }
        return text.toString().stripTrailing();
    }

    private static String row(List<List<Block>> cells, int columns) {
        StringBuilder text = new StringBuilder("|");
        for (int i = 0; i < columns; i++) {
            String cell = i < cells.size() ? cell(cells.get(i)) : "";
            text.append(' ').append(cell).append(" |");
        }
        return text.append('\n').toString();
    }

    /** Eine Zelle einzeilig: Absätze und Zeilenumbrüche werden zu {@code <br>}. */
    private static String cell(List<Block> blocks) {
        List<String> parts = new ArrayList<>();
        for (Block block : blocks) {
            String part = switch (block) {
                case Paragraph paragraph -> inline(paragraph.content());
                case Heading heading -> "**" + inline(heading.content()) + "**";
                case CodeBlock code -> "`" + code.code().replace("`", "'").replace("\n", " ") + "`";
                case ListBlock list -> String.join("<br>", list.items().stream()
                        .map(item -> "• " + cell(item)).toList());
                case Panel panel -> escape(panel.label()) + ": " + cell(panel.content());
                case Note note -> "*[Export: " + escape(note.text()) + "]*";
                case Table nested -> "*[Export: verschachtelte Tabelle nicht übernommen]*";
                case Rule rule -> "";
            };
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        // Ein noch nicht maskierter senkrechter Strich, etwa in Code, beendete sonst die Zelle.
        return String.join("<br>", parts).replace("\n", "<br>").replaceAll("(?<!\\\\)\\|", "\\\\|");
    }

    private static String code(CodeBlock code) {
        String fence = "`".repeat(Math.max(3, longestTickRun(code.code()) + 1));
        String body = code.code().endsWith("\n") ? code.code() : code.code() + "\n";
        // Nur harmlose Zeichen: ein Backtick in der Sprache machte die Zeile zu keinem Zaun mehr (Seiteninhalt als HTML).
        return fence + code.language().replaceAll("[^A-Za-z0-9_+.#-]", "") + "\n" + body + fence;
    }

    private static int longestTickRun(String text) {
        int longest = 0;
        Matcher ticks = Pattern.compile("`+").matcher(text);
        while (ticks.find()) {
            longest = Math.max(longest, ticks.group().length());
        }
        return longest;
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            quoted.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        return quoted.toString().stripTrailing();
    }

    /** Inline-Inhalt; ein Zeilenumbruch erscheint als {@code \n} und wird vom Aufrufer gesetzt. */
    static String inline(List<Inline> content) {
        StringBuilder text = new StringBuilder();
        for (Inline inline : content) {
            switch (inline) {
                case Text plain -> text.append(styled(plain));
                case Link link -> {
                    String label = link.text().isEmpty() ? (link.target() == null ? "" : link.target()) : link.text();
                    text.append(link.target() == null || !safeTarget(link.target()) ? escape(label)
                            : "[" + escape(label) + "](" + destination(link.target()) + ")");
                }
                case ImageRef image -> {
                    String reference = image.reference();
                    if (reference.startsWith("attachment:") || reference.startsWith("drawio:")) {
                        text.append("![").append(escape(image.alt())).append("](").append(destination(reference))
                                .append(')');
                    } else if (safeTarget(reference)) {
                        // Externe Bilder nur als Verweis: ein eingebettetes Bild lädt der Betrachter ungefragt nach.
                        // Sie gelten als nicht verfügbar, wie Anhänge ohne Bilddaten.
                        text.append("*\\[Abbildung nicht verfügbar: ")
                                .append(escape(image.alt().isEmpty() ? "Bild" : image.alt())).append("\\]* ([Adresse](")
                                .append(destination(reference)).append("))");
                    } else {
                        text.append(escape(image.alt()));
                    }
                }
                case LineBreak lineBreak -> text.append('\n');
            }
        }
        return text.toString();
    }

    private static String styled(Text text) {
        String value = text.text();
        if (value.isBlank()) {
            return value.isEmpty() ? "" : " ";
        }
        String leading = value.substring(0, value.length() - value.stripLeading().length());
        String trailing = value.substring(value.stripTrailing().length());
        String core = value.strip();
        if (text.code()) {
            // Zaun länger als jede Backtick-Folge im Text, sonst endet der Code früher und der Rest wird HTML.
            String ticks = "`".repeat(longestTickRun(core) + 1);
            String pad = core.startsWith("`") || core.endsWith("`") || core.contains("``") ? " " : "";
            core = ticks + pad + core + pad + ticks;
        } else {
            core = escape(core);
        }
        if (text.italic()) {
            core = "*" + core + "*";
        }
        if (text.bold()) {
            core = "**" + core + "**";
        }
        return leading + core + trailing;
    }

    /** Nur Ziele ohne Ausführung: Seiten- und Anhangsverweise, http(s), mailto; javascript:, data: usw. werden Text. */
    static boolean safeTarget(String target) {
        if (target.startsWith("#page-") || target.startsWith("attachment:")) {
            return true;
        }
        Matcher scheme = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):").matcher(target.strip());
        if (!scheme.find()) {
            return false;
        }
        String name = scheme.group(1).toLowerCase(java.util.Locale.ROOT);
        return name.equals("http") || name.equals("https") || name.equals("mailto");
    }

    /** Linkziel; mit Leerzeichen oder Klammern in spitzen Klammern. */
    static String destination(String target) {
        if (target.isEmpty()) {
            return "<>";
        }
        return target.matches(".*[\\s()<>].*") ? "<" + target.replace("<", "%3C").replace(">", "%3E") + ">"
                : target;
    }

    static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if ("\\`*_[]<>|~".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
