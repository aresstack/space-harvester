package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechText;
import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlEntity;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.Escaping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Der gesprochene Text einer Antwort oder Seite aus derselben Deutung des Markdowns wie die Anzeige
 * ({@link MarkdownParsing}): gesprochen wird nur, was als Text zu sehen ist. Code- und Diagrammblöcke, HTML-Kommentare,
 * Tags, nackte URLs und Linkziele fallen weg, Entities werden zu ihren Zeichen, Escapes zu dem Zeichen dahinter.
 * Überschriften, Listenpunkte und Tabellenzeilen enden als Satz, damit die Stimme an ihren Grenzen absetzt; eine
 * Tabellenzeile wird als „Spalte: Wert, Spalte: Wert.“ gesprochen statt als Wortkette. Absätze bleiben durch Leerzeilen
 * getrennt, eine Überschrift bleibt beim Absatz darunter.
 *
 * <p>Unverändert aus dem Enterprise AI Client ({@code proasteion/exedra/ui/markdown/MarkdownSpeech}) für das Vorlesen
 * im Chat (Angelo 23.11.2026: „das war ja schon implementiert, das wollen wir nicht neu machen“).
 */
public final class MarkdownSpeech {

    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?…:;]$");
    private static final Pattern URL_LIKE = Pattern.compile("(?i)^(https?://|www\\.|mailto:)\\S*$");
    private static final Pattern HTML_SKIPPED = Pattern.compile("(?is)<(script|style)\\b.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");

    private static final Parser PARSER = MarkdownParsing.parser();

    private MarkdownSpeech() {
    }

    /** Markdown → gesprochener Text; nie {@code null}. */
    public static String spokenText(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        Node document = MarkdownParsing.parseComplete(PARSER, markdown.replace("\r\n", "\n").replace('\r', '\n'));
        Blocks blocks = new Blocks();
        blocks(document, blocks);
        return SpeechText.speakableCharacters(blocks.text());
    }

    private static void blocks(Node container, Blocks out) {
        for (Node node = container.getFirstChild(); node != null; node = node.getNext()) {
            block(node, out);
        }
    }

    private static void block(Node node, Blocks out) {
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof HtmlCommentBlock
                || node instanceof Reference) {
            return; // code, diagrams, comments and link definitions are not text to read
        }
        if (node instanceof ThematicBreak) {
            out.endBlock();
            return;
        }
        if (node instanceof Heading) {
            out.heading(inline(node));
            return;
        }
        if (node instanceof Paragraph) {
            out.line(inline(node), false);
            if (!out.inGroup()) {
                out.endBlock();
            }
            return;
        }
        if (node instanceof HtmlBlock) {
            out.line(html(node.getChars().toString()), false);
            if (!out.inGroup()) {
                out.endBlock();
            }
            return;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            list(node, out);
            return;
        }
        if (node instanceof TableBlock) {
            table((TableBlock) node, out);
            return;
        }
        if (node instanceof BlockQuote || node.hasChildren()) {
            blocks(node, out);
        }
    }

    /** A list is one block, every item one sentence; nested lists continue the same block. */
    private static void list(Node list, Blocks out) {
        boolean outer = !out.inGroup();
        out.beginGroup();
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph) {
                    out.line(inline(child), true);
                } else {
                    block(child, out);
                }
            }
        }
        if (outer) {
            out.endGroup();
        }
    }

    /** A table is one block; every body row is one sentence naming its column headers. */
    private static void table(TableBlock table, Blocks out) {
        List<String> headers = new ArrayList<String>();
        List<List<String>> rows = new ArrayList<List<String>>();
        collectRows(table, headers, rows);
        boolean outer = !out.inGroup();
        out.beginGroup();
        if (rows.isEmpty()) {
            out.line(join(headers), true);
        }
        for (List<String> row : rows) {
            StringBuilder sentence = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                if (cell.isEmpty()) {
                    continue;
                }
                if (sentence.length() > 0) {
                    sentence.append(", ");
                }
                String header = i < headers.size() ? headers.get(i) : "";
                if (!header.isEmpty() && !header.equals(cell)) {
                    sentence.append(header).append(": ");
                }
                sentence.append(cell);
            }
            out.line(sentence.toString(), true);
        }
        if (outer) {
            out.endGroup();
        }
    }

    private static void collectRows(Node node, List<String> headers, List<List<String>> rows) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableSeparator) {
                continue; // the |---| line is layout, not a row
            }
            if (child instanceof TableRow) {
                List<String> cells = new ArrayList<String>();
                for (Node cell = child.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) {
                        cells.add(inline(cell));
                    }
                }
                if (child.getParent() instanceof TableHead) {
                    if (headers.isEmpty()) {
                        headers.addAll(cells);
                    }
                } else {
                    rows.add(cells);
                }
            } else if (child.hasChildren()) {
                collectRows(child, headers, rows);
            }
        }
    }

    private static String join(List<String> cells) {
        StringBuilder text = new StringBuilder();
        for (String cell : cells) {
            if (!cell.isEmpty()) {
                text.append(text.length() > 0 ? ", " : "").append(cell);
            }
        }
        return text.toString();
    }

    /** The visible text of an inline container, on one line. */
    private static String inline(Node container) {
        StringBuilder text = new StringBuilder();
        inline(container, text);
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static void inline(Node parent, StringBuilder text) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text) {
                text.append(node.getChars().unescape());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                text.append(' ');
            } else if (node instanceof HtmlEntity) {
                text.append(Escaping.unescapeString(node.getChars().toString()));
            } else if (node instanceof HtmlInline || node instanceof HtmlInlineComment) {
                text.append(' '); // tags are markup, the text between them is a sibling and stays
            } else if (node instanceof Code) {
                text.append(((Code) node).getText());
            } else if (node instanceof AutoLink) {
                text.append(' '); // a bare address is no sentence
            } else if (node instanceof Link) {
                StringBuilder label = new StringBuilder();
                inline(node, label);
                String shown = label.toString().trim();
                if (!URL_LIKE.matcher(shown).matches()) {
                    text.append(label);
                }
            } else if (node.hasChildren()) {
                inline(node, text);
            } else {
                text.append(node.getChars().unescape());
            }
        }
    }

    /** The text of a raw HTML block: tags, comments, scripts and styles drop, entities become their characters. */
    private static String html(String source) {
        String text = HTML_SKIPPED.matcher(source).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        return Escaping.unescapeString(text).replaceAll("\\s+", " ").trim();
    }

    /** Paragraph blocks of lines; a heading waits for the block below it. */
    private static final class Blocks {

        private final StringBuilder text = new StringBuilder();
        private final List<String> lines = new ArrayList<String>();
        private String heading;
        private int groups;

        boolean inGroup() {
            return groups > 0;
        }

        void beginGroup() {
            groups++;
        }

        void endGroup() {
            groups = 0;
            endBlock();
        }

        void heading(String line) {
            if (heading != null) {
                lines.add(heading); // zwei Überschriften hintereinander: die erste bleibt gesprochen (#1258)
                heading = null;
            }
            endBlock();
            if (!line.isEmpty()) {
                heading = sentence(line);
            }
        }

        void line(String line, boolean asSentence) {
            if (line.isEmpty()) {
                return;
            }
            if (heading != null) {
                lines.add(heading);
                heading = null;
            }
            lines.add(asSentence ? sentence(line) : line);
        }

        void endBlock() {
            if (lines.isEmpty()) {
                return;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            for (int i = 0; i < lines.size(); i++) {
                text.append(i == 0 ? "" : "\n").append(lines.get(i));
            }
            lines.clear();
        }

        String text() {
            if (heading != null) {
                lines.add(heading);
                heading = null;
            }
            endBlock();
            return text.toString();
        }

        private static String sentence(String line) {
            return SENTENCE_END.matcher(line).matches() ? line : line + ".";
        }
    }
}
