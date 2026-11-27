package com.aresstack.spaceharvester.app.chat.artifact;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Elemente einer Grafik (ADR 0045, Abschnitt 6): Jedes sichtbare Element bekommt eine feste Kennung
 * ({@code e1}, {@code e2}, …), damit die KI und der Benutzer genau eines meinen und ändern können, ohne das ganze SVG
 * neu zu schreiben. Reine Logik auf dem XML des SVG, ohne Oberfläche; scheitert das Lesen, bleibt das SVG, wie es ist.
 */
public final class SvgElements {

    /** Die Elemente, die gezeichnet werden und eine Kennung bekommen; Textteile ({@code tspan}) gehören zum Text. */
    private static final Set<String> SHAPES = Set.of("g", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "path", "text", "image", "use", "foreignObject", "a");
    /** Bereiche mit Bausteinen, die selbst nicht gezeichnet werden (Verläufe, Muster, Symbole …). */
    private static final Set<String> HIDDEN = Set.of("defs", "style", "title", "desc", "metadata", "clipPath", "mask",
            "marker", "pattern", "linearGradient", "radialGradient", "symbol", "filter");
    private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("g", "Gruppe"),
            Map.entry("rect", "Rechteck"), Map.entry("circle", "Kreis"), Map.entry("ellipse", "Ellipse"),
            Map.entry("line", "Linie"), Map.entry("polyline", "Linienzug"), Map.entry("polygon", "Vieleck"),
            Map.entry("path", "Pfad"), Map.entry("text", "Text"), Map.entry("image", "Bild"),
            Map.entry("use", "Verwendung"), Map.entry("foreignObject", "Einbettung"), Map.entry("a", "Link"));
    /** Die Merkmale, die eine Zeile der Liste nennt, in dieser Reihenfolge. */
    private static final List<String> SHOWN = List.of("x", "y", "width", "height", "cx", "cy", "r", "rx", "ry", "x1",
            "y1", "x2", "y2", "fill", "stroke", "transform");
    private static final Pattern OWN_ID = Pattern.compile("e(\\d{1,6})");
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    /** So viele Zeichen eines Textes oder Merkmals stehen in einer Zeile der Liste. */
    private static final int LABEL_CHARS = 60;

    private static final System.Logger LOG = System.getLogger(SvgElements.class.getName());

    private SvgElements() {
    }

    /** Ein Element der Grafik: Kennung, Art, Tiefe in Gruppen und eine kurze Beschreibung. */
    public record Item(String id, String tag, int depth, String label) {

        /** „e3 Rechteck · x=10 y=20 fill=#4a90d9“, eingerückt nach Tiefe. */
        public String line() {
            return "  ".repeat(depth) + id + " " + NAMES.getOrDefault(tag, tag) + (label.isEmpty() ? "" : " · " + label);
        }
    }

    /** Ein Fehler beim Ändern, als Text für das Modell. */
    public static final class SvgException extends RuntimeException {
        public SvgException(String message) {
            super(message);
        }
    }

    /** Das SVG mit einer Kennung an jedem sichtbaren Element, das noch keine hat; vorhandene bleiben. */
    public static String withIds(String svg) {
        Document document = parse(svg);
        if (document == null) {
            return svg;
        }
        assignIds(document);
        String written = write(document);
        return written == null ? svg : written;
    }

    /** Die sichtbaren Elemente mit Kennung in Dokumentreihenfolge; leer, wenn das SVG nicht lesbar ist. */
    public static List<Item> list(String svg) {
        Document document = parse(svg);
        List<Item> items = new ArrayList<>();
        if (document != null) {
            collect(document.getDocumentElement(), 0, items);
        }
        return items;
    }

    /** Die Liste als Text für das Modell, eine Zeile je Element. */
    public static String listing(String svg) {
        List<Item> items = list(svg);
        if (items.isEmpty()) {
            return "(keine Elemente mit Kennung)";
        }
        StringBuilder b = new StringBuilder();
        for (Item item : items) {
            b.append(item.line()).append('\n');
        }
        return b.toString().stripTrailing();
    }

    /** Ein Element als SVG-Text, etwa um es dem Modell zu zeigen. */
    public static String element(String svg, String id) {
        Document document = parse(svg);
        Element element = document == null ? null : byId(document, id);
        if (element == null) {
            throw new SvgException("Element '" + id + "' gibt es in dieser Grafik nicht. Mit grafik_elemente nachsehen.");
        }
        return write(element);
    }

    /**
     * Ersetzt ein Element durch {@code fragment} (ein oder mehrere SVG-Elemente); leer löscht es. Das erste neue
     * Element ohne Kennung übernimmt die alte, damit Bezüge darauf gültig bleiben; weitere bekommen neue.
     */
    public static String replace(String svg, String id, String fragment) {
        Document document = parse(svg);
        if (document == null) {
            throw new SvgException("Die Grafik ist kein lesbares SVG; nur grafik_ersetzen hilft.");
        }
        Element target = byId(document, id);
        if (target == null) {
            throw new SvgException("Element '" + id + "' gibt es in dieser Grafik nicht. Mit grafik_elemente nachsehen.");
        }
        Node parent = target.getParentNode();
        String content = fragment == null ? "" : fragment.strip();
        if (!content.isEmpty()) {
            Document wrapper = parse("<svg xmlns=\"" + SVG_NS + "\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                    + content + "</svg>");
            if (wrapper == null) {
                throw new SvgException("'svg' ist kein gültiges SVG-Element (etwa <rect …/> oder <g>…</g>).");
            }
            boolean first = true;
            NodeList children = wrapper.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node imported = document.importNode(children.item(i), true);
                if (first && imported instanceof Element element && element.getAttribute("id").isEmpty()) {
                    element.setAttribute("id", target.getAttribute("id"));
                    first = false;
                } else if (imported instanceof Element) {
                    first = false;
                }
                parent.insertBefore(imported, target);
            }
        }
        parent.removeChild(target);
        assignIds(document);
        String written = write(document);
        if (written == null) {
            throw new SvgException("Die Grafik konnte nicht geschrieben werden.");
        }
        return written;
    }

    // ------------------------------------------------------------------ Innen

    private static void assignIds(Document document) {
        int next = 1;
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Matcher match = OWN_ID.matcher(((Element) all.item(i)).getAttribute("id"));
            if (match.matches()) {
                next = Math.max(next, Integer.parseInt(match.group(1)) + 1);
            }
        }
        List<Element> missing = new ArrayList<>();
        visible(document.getDocumentElement(), missing);
        for (Element element : missing) {
            if (element.getAttribute("id").isEmpty()) {
                element.setAttribute("id", "e" + next++);
            }
        }
    }

    private static void visible(Element parent, List<Element> out) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String tag = local(child);
                if (HIDDEN.contains(tag)) {
                    continue;
                }
                if (SHAPES.contains(tag)) {
                    out.add(child);
                }
                if (!tag.equals("text")) {
                    visible(child, out);
                }
            }
        }
    }

    private static void collect(Element parent, int depth, List<Item> out) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String tag = local(child);
                if (HIDDEN.contains(tag)) {
                    continue;
                }
                boolean shown = SHAPES.contains(tag) && !child.getAttribute("id").isEmpty();
                if (shown) {
                    out.add(new Item(child.getAttribute("id"), tag, depth, label(child, tag)));
                }
                if (!tag.equals("text")) {
                    collect(child, shown ? depth + 1 : depth, out);
                }
            }
        }
    }

    private static String label(Element element, String tag) {
        List<String> parts = new ArrayList<>();
        if (tag.equals("text")) {
            String text = element.getTextContent().replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                parts.add("„" + cut(text) + "“");
            }
        }
        for (String attribute : SHOWN) {
            String value = element.getAttribute(attribute);
            if (!value.isEmpty()) {
                parts.add(attribute + "=" + cut(value));
            }
        }
        return String.join(" ", parts);
    }

    private static String cut(String text) {
        return text.length() <= LABEL_CHARS ? text : text.substring(0, LABEL_CHARS - 1) + "…";
    }

    private static Element byId(Document document, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.getAttribute("id").equals(id.strip())) {
                return element;
            }
        }
        return null;
    }

    private static String local(Element element) {
        String name = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** Liest das SVG ohne DTD und ohne externe Bezüge; {@code null}, wenn es kein lesbares XML ist. */
    private static Document parse(String svg) {
        if (svg == null || svg.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document document = builder.parse(new InputSource(new StringReader(svg.strip())));
            return local(document.getDocumentElement()).toLowerCase(Locale.ROOT).equals("svg") ? document : null;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "SVG nicht lesbar: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String write(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "SVG nicht geschrieben: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
