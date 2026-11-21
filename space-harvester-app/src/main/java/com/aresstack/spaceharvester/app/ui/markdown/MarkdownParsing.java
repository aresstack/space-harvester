package com.aresstack.spaceharvester.app.ui.markdown;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.Arrays;

/**
 * Die eine Deutung einer Modellantwort als Markdown, für Anzeige ({@link FlexmarkSwingRenderer}) und Vorlesen
 * (MarkdownSpeech im EAC): derselbe Parser mit Tabellen, Durchstreichen und Autolinks und dieselben Vorstufen
 * (äußere Markdown-Umhüllung, Sonderleerzeichen hinter Blockmarken, nacktes SVG).
 */
final class MarkdownParsing {

    private MarkdownParsing() {
    }

    /** Ein neuer Parser mit den Erweiterungen der Anzeige. */
    static Parser parser() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create()));
        return Parser.builder(options).build();
    }

    /** Die Vorstufen vor dem Parsen, wie die Anzeige sie für eine fertige Antwort anwendet. */
    static String prepared(String markdown, boolean complete) {
        String normalized = MarkdownResponseNormalizer.normalize(markdown == null ? "" : markdown, complete);
        return SvgMarkdownBlocks.fence(BlockMarkerSpacing.normalize(normalized));
    }

    /** Parst eine fertige Antwort so, wie die Anzeige sie deutet. */
    static Node parseComplete(Parser parser, String markdown) {
        return parser.parse(prepared(markdown, true));
    }
}
