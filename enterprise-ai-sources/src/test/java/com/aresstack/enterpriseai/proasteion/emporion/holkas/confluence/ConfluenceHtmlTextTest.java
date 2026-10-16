package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeStructuralBlock;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeStructuralBlock.Kind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfluenceHtmlTextTest {

    @Test
    public void headingsParagraphsAndInlineMarkup() {
        assertEquals("## Titel\n\nEin fetter Satz.\n\nZweiter Absatz.",
                ConfluenceHtmlText.toText("<h2>Titel</h2><p>Ein <b>fetter</b>   Satz.</p><p>Zweiter&nbsp;Absatz.</p>"));
    }

    @Test
    public void listsAreBulletedOrNumbered() {
        assertEquals("- eins\n- zwei\n\n1. erstens\n2. zweitens",
                ConfluenceHtmlText.toText("<ul><li>eins</li><li>zwei</li></ul><ol><li>erstens</li><li>zweitens</li></ol>"));
    }

    @Test
    public void tablesBecomeMarkdownTables() {
        assertEquals("| Name | Wert |\n| --- | --- |\n| A | 1 |",
                ConfluenceHtmlText.toText("<table><tbody><tr><th>Name</th><th>Wert</th></tr>"
                        + "<tr><td>A</td><td>1</td></tr></tbody></table>"));
    }

    @Test
    public void tableKeepsEmptyCellsSpansAndEscapedPipesInTheirColumns() {
        String html = "<table><tbody><tr><th>Name</th><th>Wert</th><th>Hinweis</th></tr>"
                + "<tr><td>A</td><td></td><td>x|y</td></tr>"
                + "<tr><td colspan=\"2\">B</td><td rowspan=\"2\">lang</td></tr>"
                + "<tr><td>C</td><td>3</td></tr></tbody></table>";

        assertEquals("| Name | Wert | Hinweis |\n| --- | --- | --- |\n| A | | x\\|y |\n| B | B | lang |\n| C | 3 | lang |",
                ConfluenceHtmlText.toText(html));
    }

    @Test
    public void rowspanZeroCoversTheRemainingRowsAndHugeSpansStayInsideTheTable() {
        String html = "<table><tr><th>A</th><th>B</th></tr>"
                + "<tr><td rowspan=\"0\">x</td><td>1</td></tr><tr><td>2</td></tr>"
                + "<tr><td rowspan=\"1000000000\" colspan=\"1000000000\">y</td></tr></table>";

        String text = ConfluenceHtmlText.toText(html);

        assertTrue(text, text.startsWith("| A | B |"));
        assertTrue(text, text.contains("\n| x | 1 |"));
        assertTrue(text, text.contains("\n| x | 2 |"));
        assertEquals(text, 5, text.split("\n").length);
    }

    @Test
    public void codeMacroBecomesFencedBlockWithLanguageAndKeepsIndentationAndBlankLines() {
        String html = "<p>Vorher</p><div class=\"code panel\"><div class=\"codeContent panelContent\">"
                + "<pre class=\"syntaxhighlighter-pre\" data-syntaxhighlighter-params=\"brush: java; gutter: false\">"
                + "class A {\n\n\n    int x;\n}\n</pre></div></div><p>Nachher</p>";
        assertEquals("Vorher\n\n```java\nclass A {\n\n\n    int x;\n}\n```\n\nNachher", ConfluenceHtmlText.toText(html));
    }

    @Test
    public void plainPreHasNoLanguage() {
        assertEquals("```\nls -l\n```", ConfluenceHtmlText.toText("<pre>ls -l</pre>"));
    }

    @Test
    public void scriptsImagesAndMacroChromeAreRemoved() {
        String html = "<p>Text<img src=\"x.png\" alt=\"Bild\"/></p><script>alert(1)</script><style>p{}</style>"
                + "<span class=\"aui-icon\">Icon</span><div style=\"display: none\">versteckt</div>"
                + "<span class=\"confluence-embedded-file-wrapper\"><img src=\"y\"/>Datei</span>";
        assertEquals("Text", ConfluenceHtmlText.toText(html));
    }

    @Test
    public void infoMacroContentIsKept() {
        assertEquals("Hinweis\n\nBitte beachten.",
                ConfluenceHtmlText.toText("<div class=\"confluence-information-macro\"><p class=\"title\">Hinweis</p>"
                        + "<div class=\"confluence-information-macro-body\"><p>Bitte beachten.</p></div></div>"));
    }

    @Test
    public void emptyAndNullInput() {
        assertEquals("", ConfluenceHtmlText.toText(null));
        assertEquals("", ConfluenceHtmlText.toText("<p>  </p>"));
    }

    @Test
    public void blocksKeepTablesQuotesAndCodeAsHardBoundaries() {
        List<KnowledgeStructuralBlock> blocks = ConfluenceHtmlText.toBlocks("Seite",
                "<h2>Teil</h2><p>Ein Satz.</p><table><tr><th>Name</th><th>Wert</th></tr><tr><td>A</td><td>1</td></tr>"
                        + "</table><blockquote><p>Zitat hier.</p></blockquote><ul><li>eins</li></ul>"
                        + "<pre>int x;\n  y();</pre>");
        assertEquals(new KnowledgeStructuralBlock(Kind.HEADING, "Seite", "Seite"), blocks.get(0));
        assertEquals(new KnowledgeStructuralBlock(Kind.HEADING, "Seite > Teil", "Teil"), blocks.get(1));
        assertEquals(new KnowledgeStructuralBlock(Kind.PARAGRAPH, "Seite > Teil", "Ein Satz."), blocks.get(2));
        assertEquals(new KnowledgeStructuralBlock(Kind.TABLE_ROW, "Seite > Teil", "Name | Wert"), blocks.get(3));
        assertEquals(new KnowledgeStructuralBlock(Kind.TABLE_ROW, "Seite > Teil", "A | 1"), blocks.get(4));
        assertEquals(new KnowledgeStructuralBlock(Kind.QUOTE, "Seite > Teil", "Zitat hier."), blocks.get(5));
        assertEquals(new KnowledgeStructuralBlock(Kind.LIST_ITEM, "Seite > Teil", "- eins"), blocks.get(6));
        assertEquals(new KnowledgeStructuralBlock(Kind.CODE, "Seite > Teil", "int x;\n  y();"), blocks.get(7));
        assertEquals(8, blocks.size());
    }
}
