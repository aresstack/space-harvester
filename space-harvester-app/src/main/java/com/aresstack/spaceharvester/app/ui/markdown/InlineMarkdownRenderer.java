package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.HtmlEntity;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.Escaping;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Cursor;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wandelt Inline-Knoten von Flexmark in StyledDocument-Attribute (aus askai-java8 {@code InlineMarkdownRenderer}). */
final class InlineMarkdownRenderer {

    private final MarkdownTheme theme;
    private final DesktopLinkOpener linkOpener;

    InlineMarkdownRenderer(MarkdownTheme theme, DesktopLinkOpener linkOpener) {
        this.theme = theme;
        this.linkOpener = linkOpener;
    }

    WrappingTextPane render(Node container, Font font) {
        WrappingTextPane pane = pane(font);
        renderChildren(container, pane.getStyledDocument(), attributes(font));
        trimTrailingBlank(pane.getStyledDocument());
        installLinkHandling(pane);
        return pane;
    }

    /**
     * Entfernt Umbrüche und Leerzeichen am Ende: nach einer Liste ({@code </ul>}) oder einem {@code <br>} am Ende
     * einer Tabellenzelle zeigte die Textfläche sonst eine leere Zeile, und die Tabellenzeile war eine Zeile zu hoch.
     */
    private static void trimTrailingBlank(StyledDocument document) {
        try {
            int end = document.getLength();
            String text = document.getText(0, end);
            int keep = end;
            while (keep > 0 && Character.isWhitespace(text.charAt(keep - 1))) {
                keep--;
            }
            if (keep < end) {
                document.remove(keep, end - keep);
            }
        } catch (BadLocationException ignored) {
            // Ohne lesbaren Text bleibt das Dokument, wie es ist.
        }
    }

    /** Unformatierter Text (unbekannte Blöcke wie HTML), umbrochen wie ein Absatz; nie als HTML gedeutet. */
    WrappingTextPane plain(String text, Font font) {
        WrappingTextPane pane = pane(font);
        append(pane.getStyledDocument(), text == null ? "" : text, attributes(font));
        return pane;
    }

    private WrappingTextPane pane(Font font) {
        WrappingTextPane pane = new WrappingTextPane();
        pane.setFont(font);
        pane.setForeground(theme.getForeground());
        // Markierung aus der Blasenfarbe statt des Look-and-Feel-Blaus; die Schrift bleibt lesbar.
        pane.setSelectionColor(theme.getQuoteBorder());
        pane.setSelectedTextColor(theme.getForeground());
        return pane;
    }

    private void renderChildren(Node parent, StyledDocument document, SimpleAttributeSet inherited) {
        // Inline-HTML öffnet und schließt über Geschwisterknoten hinweg („<b>“, Text, „</b>“): der Zustand gilt
        // daher für die Folgeknoten desselben Elternknotens.
        InlineHtml html = new InlineHtml(document, inherited);
        Node node = parent.getFirstChild();
        while (node != null) {
            if (node instanceof HtmlInline) {
                html.apply(node.getChars().toString());
            } else {
                html.flushBreak();
                renderNode(node, document, html.current());
            }
            node = node.getNext();
        }
    }

    private void renderNode(Node node, StyledDocument document, SimpleAttributeSet inherited) {
        if (node instanceof Text) {
            // Backslash-Escapes (\[, \*) sind Markdown-Syntax, kein Text: gezeigt wird das Zeichen dahinter.
            append(document, node.getChars().unescape(), inherited);
            return;
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            append(document, "\n", inherited);
            return;
        }
        if (node instanceof HtmlEntity) {
            append(document, Escaping.unescapeString(node.getChars().toString()), inherited);
            return;
        }
        if (node instanceof Code) {
            append(document, ((Code) node).getText().toString(), codeAttributes(inherited));
            return;
        }
        if (node instanceof StrongEmphasis) {
            SimpleAttributeSet bold = copy(inherited);
            StyleConstants.setBold(bold, true);
            renderChildren(node, document, bold);
            return;
        }
        if (node instanceof Emphasis) {
            SimpleAttributeSet italic = copy(inherited);
            StyleConstants.setItalic(italic, true);
            renderChildren(node, document, italic);
            return;
        }
        if (node instanceof Strikethrough) {
            SimpleAttributeSet strike = copy(inherited);
            StyleConstants.setStrikeThrough(strike, true);
            renderChildren(node, document, strike);
            return;
        }
        if (node instanceof Link) {
            Link link = (Link) node;
            renderLink(link, link.getUrl().toString(), document, inherited);
            return;
        }
        if (node instanceof AutoLink) {
            AutoLink link = (AutoLink) node;
            renderStandaloneLink(link.getText().toString(), link.getUrl().toString(), document, inherited);
            return;
        }
        if (node instanceof MailLink) {
            MailLink link = (MailLink) node;
            String address = link.getText().toString();
            renderStandaloneLink(address, "mailto:" + address, document, inherited);
            return;
        }
        if (node instanceof com.vladsch.flexmark.ast.LinkRef) {
            com.vladsch.flexmark.ast.LinkRef ref = (com.vladsch.flexmark.ast.LinkRef) node;
            com.vladsch.flexmark.ast.Reference target = ref.isDefined() && ref.getDocument() != null
                    ? ref.getReferenceNode(ref.getDocument()) : null;
            if (target == null) {
                // Space Harvester: Quellenmarken wie „[3]“ ohne Linkdefinition bleiben mit Klammern stehen; sonst
                // verlöre der Chat die Nummer der Quelle zur bloßen Zahl.
                append(document, node.getChars().toString(), inherited);
            } else {
                // „[Text][ref]“ mit Definition ist ein Link wie ein Inline-Link (wie MarkdownDocxRenderer.reference).
                renderLink(ref, target.getUrl().toString(), document, inherited);
            }
            return;
        }
        if (node.hasChildren()) {
            renderChildren(node, document, inherited);
            return;
        }
        append(document, node.getChars().toString(), inherited);
    }

    private void renderLink(Node link, String url, StyledDocument document, SimpleAttributeSet inherited) {
        SimpleAttributeSet linkAttributes = linkAttributes(inherited, url);
        if (link.hasChildren()) {
            renderChildren(link, document, linkAttributes);
        } else {
            append(document, url, linkAttributes);
        }
    }

    private void renderStandaloneLink(String text, String url, StyledDocument document,
                                      SimpleAttributeSet inherited) {
        append(document, text, linkAttributes(inherited, url));
    }

    private SimpleAttributeSet codeAttributes(SimpleAttributeSet inherited) {
        SimpleAttributeSet code = copy(inherited);
        StyleConstants.setFontFamily(code, theme.getCodeFont().getFamily());
        StyleConstants.setBackground(code, theme.getCodeBackground());
        return code;
    }

    private SimpleAttributeSet linkAttributes(SimpleAttributeSet inherited, String url) {
        SimpleAttributeSet attributes = copy(inherited);
        StyleConstants.setForeground(attributes, theme.getLinkForeground());
        StyleConstants.setUnderline(attributes, true);
        attributes.addAttribute(LinkMouseListener.HREF_ATTRIBUTE, url);
        return attributes;
    }

    private SimpleAttributeSet attributes(Font font) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, font.getFamily());
        StyleConstants.setFontSize(attributes, font.getSize());
        StyleConstants.setBold(attributes, font.isBold());
        StyleConstants.setForeground(attributes, theme.getForeground());
        return attributes;
    }

    private static SimpleAttributeSet copy(SimpleAttributeSet source) {
        return new SimpleAttributeSet(source);
    }

    private static void append(StyledDocument document, String text, SimpleAttributeSet attributes) {
        try {
            document.insertString(document.getLength(), text, attributes);
        } catch (BadLocationException ignored) {
            // Die übrigen Knoten weiter rendern, wenn eine Einfügung scheitert.
        }
    }

    private static final Pattern TAG = Pattern.compile("^<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9]*)([^>]*)>$", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile(
            "\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    /**
     * Deutet die harmlosen Inline-Tags, die Modelle vor allem in Tabellenzellen schreiben (dort kennt GFM keine
     * Markdown-Listen): {@code <br>}, {@code <p>}, {@code <ul>/<ol>/<li>}, {@code <b>/<strong>}, {@code <i>/<em>},
     * {@code <s>/<del>}, {@code <code>} und {@code <a href>}. Es wird nichts ausgeführt; jedes andere Tag fällt weg,
     * statt als Quelltext zu erscheinen. Geöffnet werden Links weiter nur über {@link DesktopLinkOpener}.
     */
    private final class InlineHtml {

        private final StyledDocument document;
        private final SimpleAttributeSet base;
        private final Deque<Frame> open = new ArrayDeque<Frame>();
        private boolean pendingBreak;

        InlineHtml(StyledDocument document, SimpleAttributeSet base) {
            this.document = document;
            this.base = base;
        }

        SimpleAttributeSet current() {
            return open.isEmpty() ? base : open.peek().attributes;
        }

        void flushBreak() {
            if (pendingBreak) {
                pendingBreak = false;
                startLine();
            }
        }

        void apply(String raw) {
            Matcher tag = TAG.matcher(raw.trim());
            if (!tag.matches()) {
                return; // Kommentar, Deklaration oder Bruchstück: nicht anzeigen
            }
            boolean closing = !tag.group(1).isEmpty();
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            if (name.equals("br")) {
                pendingBreak = false;
                append(document, "\n", current());
                return;
            }
            if (closing) {
                close(name);
                return;
            }
            if (name.equals("p")) {
                pendingBreak = true;
            } else if (name.equals("ul") || name.equals("ol")) {
                pendingBreak = true;
                open.push(new Frame(name, current(), name.equals("ol") ? 0 : -1));
            } else if (name.equals("li")) {
                pendingBreak = false;
                startLine();
                Frame list = innermostList();
                String marker = "\u2022 ";
                if (list != null && list.counter >= 0) {
                    list.counter++;
                    marker = list.counter + ". ";
                }
                append(document, indent(listDepth()) + marker, current());
            } else if (name.equals("b") || name.equals("strong")) {
                SimpleAttributeSet bold = copy(current());
                StyleConstants.setBold(bold, true);
                open.push(new Frame(name, bold, -1));
            } else if (name.equals("i") || name.equals("em")) {
                SimpleAttributeSet italic = copy(current());
                StyleConstants.setItalic(italic, true);
                open.push(new Frame(name, italic, -1));
            } else if (name.equals("s") || name.equals("del") || name.equals("strike")) {
                SimpleAttributeSet strike = copy(current());
                StyleConstants.setStrikeThrough(strike, true);
                open.push(new Frame(name, strike, -1));
            } else if (name.equals("code")) {
                open.push(new Frame(name, codeAttributes(current()), -1));
            } else if (name.equals("a")) {
                Matcher href = HREF.matcher(tag.group(3));
                String url = null;
                if (href.find()) {
                    url = href.group(1) != null ? href.group(1) : href.group(2) != null ? href.group(2) : href.group(3);
                    url = Escaping.unescapeString(url).trim();
                }
                open.push(new Frame(name, url == null || url.isEmpty() ? current() : linkAttributes(current(), url),
                        -1));
            }
        }

        private void close(String name) {
            if (name.equals("p")) {
                pendingBreak = true;
                return;
            }
            boolean present = false;
            for (Frame frame : open) {
                if (frame.tag.equals(name)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return;
            }
            Frame frame;
            do {
                frame = open.pop();
            } while (!frame.tag.equals(name));
            if (name.equals("ul") || name.equals("ol")) {
                pendingBreak = true;
            }
        }

        private Frame innermostList() {
            for (Frame frame : open) {
                if (frame.tag.equals("ul") || frame.tag.equals("ol")) {
                    return frame;
                }
            }
            return null;
        }

        private int listDepth() {
            int depth = 0;
            Iterator<Frame> frames = open.iterator();
            while (frames.hasNext()) {
                String tag = frames.next().tag;
                if (tag.equals("ul") || tag.equals("ol")) {
                    depth++;
                }
            }
            return depth;
        }

        private String indent(int depth) {
            StringBuilder indent = new StringBuilder();
            for (int level = 1; level < depth; level++) {
                indent.append("    ");
            }
            return indent.toString();
        }

        /** Beginnt eine neue Zeile, sofern das Dokument nicht leer ist oder schon am Zeilenanfang steht. */
        private void startLine() {
            int length = document.getLength();
            if (length == 0) {
                return;
            }
            try {
                if (!"\n".equals(document.getText(length - 1, 1))) {
                    append(document, "\n", base);
                }
            } catch (BadLocationException ignored) {
                // Ohne lesbares Ende keinen Umbruch einfügen.
            }
        }
    }

    private static final class Frame {
        final String tag;
        final SimpleAttributeSet attributes;
        int counter;

        Frame(String tag, SimpleAttributeSet attributes, int counter) {
            this.tag = tag;
            this.attributes = attributes;
            this.counter = counter;
        }
    }

    private void installLinkHandling(final JTextPane pane) {
        Cursors.set(pane, Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        LinkMouseListener listener = new LinkMouseListener(pane, linkOpener);
        pane.addMouseListener(listener);
        pane.addMouseMotionListener(listener);
    }
}
