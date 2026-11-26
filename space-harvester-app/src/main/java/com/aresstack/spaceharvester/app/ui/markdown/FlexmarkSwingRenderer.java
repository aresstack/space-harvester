package com.aresstack.spaceharvester.app.ui.markdown;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Locale;

/** Bildet Flexmark-Blockknoten auf native Swing-Komponenten ab (aus askai-java8 {@code FlexmarkSwingRenderer}). */
final class FlexmarkSwingRenderer {

    private final Parser parser;
    private final MarkdownTheme theme;
    private final InlineMarkdownRenderer inlineRenderer;
    private final DesktopLinkOpener linkOpener;
    private final MermaidImageRenderer mermaidImageRenderer;
    private final MermaidImageRenderer svgImageRenderer = new SvgImageRenderer();
    private final MermaidImageRenderer drawioImageRenderer = new DrawioImageRenderer(svgImageRenderer);

    FlexmarkSwingRenderer(MarkdownTheme theme, DesktopLinkOpener linkOpener,
                          MermaidImageRenderer mermaidImageRenderer) {
        this.theme = theme;
        this.inlineRenderer = new InlineMarkdownRenderer(theme, linkOpener);
        this.linkOpener = linkOpener;
        this.mermaidImageRenderer = mermaidImageRenderer;
        this.parser = MarkdownParsing.parser();
    }

    JPanel render(String markdown, boolean renderMermaid) {
        // Sonderleerzeichen hinter Blockmarken ersetzt BlockMarkerSpacing Zeichen für Zeichen: Die Länge bleibt,
        // die Quellpositionen gelten weiter für den übergebenen Text.
        String source = BlockMarkerSpacing.normalize(markdown == null ? "" : markdown);
        String fenced = SvgMarkdownBlocks.fence(source);
        Node document = parser.parse(fenced);
        // Nur wenn der geparste Text der übergebene ist, stimmen die Quellpositionen der Blöcke mit ihm überein.
        tagSourceOffsets = fenced.equals(source);
        try {
            return renderContainer(document, renderMermaid, true);
        } finally {
            tagSourceOffsets = false;
        }
    }

    /** Ob die Blöcke der obersten Ebene gerade ihre Quellposition bekommen ({@link MarkdownMessageView#SOURCE_START}). */
    private boolean tagSourceOffsets;

    private JPanel renderContainer(Node container, boolean renderMermaid, boolean allowUnwrap) {
        JPanel panel = verticalPanel();
        Node child = container.getFirstChild();
        while (child != null) {
            JComponent component = renderBlock(child, renderMermaid, allowUnwrap);
            if (component != null) {
                if (tagSourceOffsets && container.getParent() == null) {
                    component.putClientProperty(MarkdownMessageView.SOURCE_START,
                            Integer.valueOf(child.getStartOffset()));
                }
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(component);
                panel.add(Box.createVerticalStrut(blockGap(child)));
            }
            child = child.getNext();
        }
        return panel;
    }

    private JComponent renderBlock(Node node, boolean renderMermaid, boolean allowUnwrap) {
        if (node instanceof HtmlCommentBlock) {
            // HTML-Kommentare bleiben im Markdown-Quelltext, zeichnen aber keine sichtbare Komponente. Es wird kein
            // HTML gerendert oder ausgeführt; nur Kommentare werden verborgen, jeder andere unbekannte Knoten
            // behält den allgemeinen Rückfall unten.
            return null;
        }
        if (node instanceof Heading) {
            return renderHeading((Heading) node);
        }
        if (node instanceof Paragraph) {
            return inlineRenderer.render(node, theme.getBodyFont());
        }
        if (node instanceof FencedCodeBlock) {
            return renderFencedCode((FencedCodeBlock) node, renderMermaid, allowUnwrap);
        }
        if (node instanceof IndentedCodeBlock) {
            String code = ((IndentedCodeBlock) node).getContentChars().toString();
            return code.trim().isEmpty() ? null : new CodeBlockPanel("", code, theme);
        }
        if (node instanceof BulletList) {
            return renderList(node, false, 1, renderMermaid, allowUnwrap);
        }
        if (node instanceof OrderedList) {
            return renderList(node, true, ((OrderedList) node).getStartNumber(), renderMermaid, allowUnwrap);
        }
        if (node instanceof BlockQuote) {
            return renderQuote(node, renderMermaid, allowUnwrap);
        }
        if (node instanceof TableBlock) {
            return new MarkdownTablePanel((TableBlock) node, theme, inlineRenderer, linkOpener);
        }
        if (node instanceof ThematicBreak) {
            return new MarkdownRule(theme.getSeparatorColor());
        }
        if (node.hasChildren()) {
            return renderContainer(node, renderMermaid, allowUnwrap);
        }
        // Unbekannte Blöcke (etwa HTML) stammen aus der Modellantwort: als Text zeigen, nie als Swing-HTML deuten,
        // und wie einen Absatz umbrechen statt als einzeiliges Label rechts abzuschneiden.
        return inlineRenderer.plain(node.getChars().toString().trim(), theme.getBodyFont());
    }

    private JComponent renderHeading(Heading heading) {
        int level = Math.max(1, Math.min(6, heading.getLevel()));
        int bodySize = theme.getBodyFont().getSize();
        int size = Math.max(bodySize + 1, bodySize + (7 - level) * 2);
        Font font = theme.getBodyFont().deriveFont(Font.BOLD, size);
        return inlineRenderer.render(heading, font);
    }

    private JComponent renderFencedCode(FencedCodeBlock block, boolean renderMermaid, boolean allowUnwrap) {
        String language = block.getInfo().toString().trim();
        String code = block.getContentChars().toString();
        if ("mermaid".equalsIgnoreCase(language) && renderMermaid) {
            return new MermaidDiagramPanel(code, theme, mermaidImageRenderer);
        }
        // draw.io-XML (```drawio, oder ```xml/nackt mit <mxfile/<mxGraphModel als Wurzel) über drawio-java, wie in Seiten
        // und Exporten; während des Streamings bleibt es ein Codeblock.
        if (renderMermaid && DrawioImageRenderer.isDrawioBlock(language, code)) {
            return MermaidDiagramPanel.drawio(code, theme, drawioImageRenderer);
        }
        // Rohes SVG (```svg, oder ```xml/```html mit <svg als Wurzel, oder nacktes <svg> — siehe SvgMarkdownBlocks)
        // geht über denselben Bildweg wie Mermaid; während des Streamings bleibt es ein Codeblock.
        if (renderMermaid && isSvgBlock(language, code)) {
            return MermaidDiagramPanel.svg(code, theme, svgImageRenderer);
        }
        // Ein Modell packt einen echten Mermaid-Zaun manchmal in einen zusätzlichen ```markdown-Block. Dieser eine
        // Behälter wird ausgepackt (höchstens eine Ebene, nur bei vollständiger Antwort), damit das Diagramm
        // erscheint — ein gewöhnlicher Codeblock (java/json/…) oder ein Doku-Ausschnitt ohne vollständigen inneren
        // Mermaid-Zaun wird nie umgedeutet.
        if (allowUnwrap && renderMermaid && isContainerLanguage(language) && containsMermaidFence(code)) {
            return renderContainer(parser.parse(code), renderMermaid, false);
        }
        if (code.trim().isEmpty()) {
            return null; // für einen leeren Zaun keinen großen leeren Kasten zeichnen
        }
        return new CodeBlockPanel(language, code, theme);
    }

    private static boolean isSvgBlock(String language, String code) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("svg")) {
            return code.toLowerCase(Locale.ROOT).contains("<svg");
        }
        return (normalized.equals("xml") || normalized.equals("html") || normalized.isEmpty())
                && SvgImageRenderer.looksLikeSvg(code);
    }

    private static boolean isContainerLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("markdown") || normalized.equals("md")
                || normalized.equals("commonmark") || normalized.equals("gfm");
    }

    /** @return {@code true}, wenn der Zauninhalt als Markdown geparst mindestens einen direkten Mermaid-Zaun ergibt */
    private boolean containsMermaidFence(String content) {
        Node document = parser.parse(content == null ? "" : content);
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock
                    && "mermaid".equalsIgnoreCase(((FencedCodeBlock) child).getInfo().toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private JComponent renderList(Node list, boolean ordered, int startNumber, boolean renderMermaid,
                                 boolean allowUnwrap) {
        JPanel listPanel = verticalPanel();
        listPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        Node child = list.getFirstChild();
        int number = startNumber;
        while (child != null) {
            if (child instanceof ListItem) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                JLabel marker = new JLabel(ordered ? number + "." : "•");
                marker.setFont(theme.getBodyFont());
                marker.setForeground(theme.getForeground());
                marker.setVerticalAlignment(JLabel.TOP);
                row.add(marker, BorderLayout.WEST);
                row.add(renderContainer(child, renderMermaid, allowUnwrap), BorderLayout.CENTER);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(row);
                listPanel.add(Box.createVerticalStrut(2));
                number++;
            }
            child = child.getNext();
        }
        return listPanel;
    }

    private JComponent renderQuote(Node quote, boolean renderMermaid, boolean allowUnwrap) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, theme.getQuoteBorder()),
                BorderFactory.createEmptyBorder(1, 10, 1, 0)));
        wrapper.add(renderContainer(quote, renderMermaid, allowUnwrap), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel verticalPanel() {
        // Ein deterministischer vertikaler Stapel (kein BoxLayout): Jeder Block wird beim Layout bei der echten
        // Breite gemessen, sodass umbrechende Absätze schon im ersten Durchlauf stimmen. Siehe MarkdownStackPanel.
        return new MarkdownStackPanel();
    }

    private int blockGap(Node node) {
        if (node instanceof Heading) {
            return 6;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof BlockQuote) {
            return 10;
        }
        return 5;
    }
}
