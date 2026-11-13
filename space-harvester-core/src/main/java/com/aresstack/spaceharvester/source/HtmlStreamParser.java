package com.aresstack.spaceharvester.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractEncodingDetectorParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.JSoupParser;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;
import org.jsoup.parser.Tag;
import org.jsoup.parser.TagSet;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * HTML als Strom (ADR 0026, AP10, von AT freigegeben): wie Tikas {@link JSoupParser}, aber ohne das ganze Dokument als
 * DOM zu halten. jsoups {@link StreamParser} (die Version, die Tika mitbringt) liefert jedes Element, sobald es
 * geschlossen ist; abgegeben wird alles in Dokumentreihenfolge an dieselbe Kette wie bei Tika (Tikas
 * {@code HtmlHandler} hinter {@code XHTMLDowngradeHandler}, mit {@link DefaultHtmlMapper}), danach wird es aus dem
 * Baum entfernt. Damit gelten Abbildung der Elemente, Titel und Kopfdaten, Verweise und Schutz vor Skripten genau wie
 * bei Tika, und der Speicher hängt nicht mehr an der Dateigröße.
 *
 * <p>Abgegeben wird am Ende eines Blocks ({@link #FLUSH}), nicht jedes Inline-Elements: Bis dahin darf jsoup den Block
 * noch umbauen (etwa verschachtelte Formatierungen). Was jsoup erst nach einem Block vor ihn setzt (Text, der in einer
 * kaputten Tabelle steht und vor die Tabelle wandert), steht hier hinter dem schon abgegebenen Anfang; der Text bleibt
 * erhalten.
 *
 * <p>Abweichung von Tika: {@code data:}-Inhalte werden nicht als enthaltene Dateien gelesen, sie ergeben keinen Text
 * (Vorgabe AT). Eingebettete Skripte ergeben wie bei Tika keinen Text.
 */
public final class HtmlStreamParser extends AbstractEncodingDetectorParser {

    private static final long serialVersionUID = 1L;

    private static final Set<MediaType> SUPPORTED = Set.of(MediaType.TEXT_HTML, MediaType.application("xhtml+xml"),
            MediaType.application("vnd.wap.xhtml+xml"), MediaType.application("x-asp"));

    /** Blöcke, an deren Ende abgegeben wird. */
    static final Set<String> FLUSH = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "tr",
            "td", "th", "pre", "blockquote", "table", "thead", "tbody", "tfoot", "caption", "ul", "ol", "dl",
            "section", "article", "main", "header", "footer", "nav", "aside", "form", "address", "figure",
            "figcaption", "fieldset", "details", "summary", "hr", "head", "title", "body");

    private static final TagSet SELF_CLOSEABLE = selfCloseable();
    private static final Constructor<?> HTML_HANDLER;
    private static final Constructor<?> DOWNGRADE;

    static {
        try {
            Class<?> handler = Class.forName("org.apache.tika.parser.html.HtmlHandler");
            HTML_HANDLER = handler.getConstructor(HtmlMapper.class, ContentHandler.class, Metadata.class,
                    ParseContext.class, boolean.class);
            HTML_HANDLER.setAccessible(true);
            Class<?> downgrade = Class.forName("org.apache.tika.parser.html.XHTMLDowngradeHandler");
            DOWNGRADE = downgrade.getConstructor(ContentHandler.class);
            DOWNGRADE.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Mit Tikas Standard-Erkennung des Zeichensatzes. */
    public HtmlStreamParser() {
        super();
    }

    /** Mit der Erkennung des Zeichensatzes aus der Tika-Konfiguration, wie JSoupParser. */
    public HtmlStreamParser(org.apache.tika.detect.EncodingDetector encodingDetector) {
        super(encodingDetector);
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        // Zeichensatz und Kopfdaten wie JSoupParser.
        Charset detected = getEncodingDetector(context).detect(stream, metadata);
        Charset charset = detected == null ? JSoupParser.DEFAULT_CHARSET : detected;
        String type = metadata.get(Metadata.CONTENT_TYPE);
        MediaType withCharset = null;
        if (type == null || type.startsWith("text/html")) {
            withCharset = new MediaType(MediaType.TEXT_HTML, charset);
        } else {
            for (MediaType supported : SUPPORTED) {
                if (type.startsWith(supported.toString())) {
                    withCharset = new MediaType(supported, charset);
                    break;
                }
            }
        }
        if (withCharset != null) {
            metadata.set(Metadata.CONTENT_TYPE, withCharset.toString());
        }
        metadata.set(Metadata.CONTENT_ENCODING, charset.name());
        HtmlMapper mapper = context.get(HtmlMapper.class, new DefaultHtmlMapper());
        ContentHandler target = chain(mapper, handler, metadata, context);
        target.startDocument();
        try (Reader reader = new BufferedReader(new InputStreamReader(CloseShieldInputStream.wrap(stream), charset));
             StreamParser parser = new StreamParser(Parser.htmlParser().tagSet(SELF_CLOSEABLE))) {
            parser.parse(skipBom(reader), "");
            Emitter emitter = new Emitter(target, parser.document());
            Iterator<Element> elements = parser.iterator();
            while (elements.hasNext()) {
                Element element = elements.next();
                if (FLUSH.contains(element.normalName())) {
                    emitter.through(element);
                }
            }
            emitter.rest();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        target.endDocument();
    }

    private static ContentHandler chain(HtmlMapper mapper, ContentHandler handler, Metadata metadata,
                                        ParseContext context) throws TikaException {
        try {
            Object html = HTML_HANDLER.newInstance(mapper, handler, metadata, context, false);
            return (ContentHandler) DOWNGRADE.newInstance(html);
        } catch (ReflectiveOperationException e) {
            throw new TikaException("HTML-Verarbeitung von Tika nicht verfügbar", e);
        }
    }

    /** Wie {@code Jsoup.parse}: ein Byte-Order-Mark gehört nicht zum Text. */
    private static Reader skipBom(Reader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
        return reader;
    }

    private static TagSet selfCloseable() {
        // Wie JSoupParser: dieselbe Liste aus Tika, damit leere Elemente gleich gelesen werden.
        TagSet tags = TagSet.Html();
        try (InputStream in = JSoupParser.class.getResourceAsStream("self-closeable-tags.txt")) {
            if (in == null) {
                return tags;
            }
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    tags.valueOf(line.trim(), Parser.NamespaceHtml).set(Tag.SelfClose);
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return tags;
    }

    /**
     * Gibt in Dokumentreihenfolge ab: {@code head} beim Betreten, {@code tail} beim Verlassen, wie Tikas Durchlauf
     * über den ganzen Baum ({@code NodeTraversor.filter}). Abgegebenes verlässt den Baum.
     */
    private static final class Emitter {

        private final ContentHandler target;
        /** Betretene, noch nicht verlassene Knoten, außen zuerst. */
        private final List<Node> open = new ArrayList<>();

        Emitter(ContentHandler target, Node document) throws SAXException {
            this.target = target;
            head(document);
            open.add(document);
        }

        /** Alles bis einschließlich {@code element}, das jsoup geschlossen hat. */
        void through(Element element) throws SAXException {
            List<Node> path = new ArrayList<>();
            for (Node node = element; node != null; node = node.parentNode()) {
                path.addFirst(node);
            }
            if (path.isEmpty() || path.getFirst() != open.getFirst()) {
                // Nicht (mehr) im Dokument, etwa schon abgegeben.
                return;
            }
            int common = 1;
            while (common < open.size() && common < path.size() && open.get(common) == path.get(common)) {
                common++;
            }
            if (common < open.size()) {
                // Betretene Knoten, die nicht mehr auf dem Weg liegen, hat jsoup geschlossen (etwa ein span um
                // Blöcke): ihr Rest, dann verlassen.
                close(common);
            }
            for (int level = common; level < path.size(); level++) {
                Node parent = path.get(level - 1);
                Node next = path.get(level);
                emitBefore(parent, next);
                if (level == path.size() - 1) {
                    traverse(next);
                    next.remove();
                } else {
                    head(next);
                    open.add(next);
                }
            }
        }

        /** Am Ende: alles, was noch im Baum steht, dann die offenen Knoten verlassen. */
        void rest() throws SAXException {
            for (int level = open.size() - 1; level >= 0; level--) {
                Node node = open.get(level);
                while (node.childNodeSize() > 0) {
                    Node child = node.childNode(0);
                    traverse(child);
                    child.remove();
                }
                tail(node);
            }
            open.clear();
        }

        private void close(int from) throws SAXException {
            for (int level = open.size() - 1; level >= from; level--) {
                Node node = open.remove(level);
                while (node.childNodeSize() > 0) {
                    Node child = node.childNode(0);
                    traverse(child);
                    child.remove();
                }
                tail(node);
                node.remove();
            }
        }

        /** Gibt die Kinder von {@code parent} vor {@code next} ab und entfernt sie. */
        private void emitBefore(Node parent, Node next) throws SAXException {
            while (parent.childNodeSize() > 0 && parent.childNode(0) != next) {
                Node child = parent.childNode(0);
                traverse(child);
                child.remove();
            }
        }

        private void traverse(Node node) throws SAXException {
            head(node);
            for (int i = 0; i < node.childNodeSize(); i++) {
                traverse(node.childNode(i));
            }
            tail(node);
        }

        private void head(Node node) throws SAXException {
            if (node instanceof TextNode text) {
                char[] chars = text.getWholeText().toCharArray();
                target.characters(chars, 0, chars.length);
            } else if (node instanceof DataNode data) {
                char[] chars = data.getWholeData().toCharArray();
                target.characters(chars, 0, chars.length);
            } else {
                AttributesImpl attributes = new AttributesImpl();
                for (Attribute attribute : node.attributes()) {
                    // data:-Inhalte ergeben keinen Text und keine enthaltene Datei (Vorgabe AT).
                    if (!attribute.getValue().stripLeading().regionMatches(true, 0, "data:", 0, 5)) {
                        attributes.addAttribute("", attribute.getKey(), attribute.getKey(), "",
                                attribute.getValue());
                    }
                }
                target.startElement("", node.nodeName(), node.nodeName(), attributes);
            }
        }

        private void tail(Node node) throws SAXException {
            if (!(node instanceof TextNode) && !(node instanceof DataNode)) {
                target.endElement("", node.nodeName(), node.nodeName());
            }
        }
    }
}
