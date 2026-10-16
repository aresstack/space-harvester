package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.mxgraph.io.mxCodec;
import com.mxgraph.canvas.mxICanvas;
import com.mxgraph.canvas.mxSvgCanvas;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxDomUtils;
import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxConnectionConstraint;
import com.mxgraph.view.mxGraph;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Zeichnet ein draw.io-Diagramm ({@code mxfile}-XML aus {@code /rest/drawio/1.0/diagram/crud/…}) ohne Browser mit
 * JGraphX als SVG; das SVG rastert der Lesebereich über denselben Batik-Weg wie Mermaid.
 *
 * <p>draw.io ist JGraphX um Jahre voraus. Darum wird das Modell vorher auf das gebracht, was JGraphX kennt, und
 * alles andere gemeldet statt geraten: Stile mit unbekannter Form ({@code mxgraph.*}-Schablonen, {@code note},
 * Blockpfeile …), Bilder, die nicht eingebettet sind, und nicht lesbare Diagrammdaten machen das Diagramm
 * „nicht vollständig darstellbar“ ({@link Unsupported}); dann zeigt der Client die PNG-Vorschau des Plugins.
 * Angeglichen werden (Heuristik): HTML-Beschriftungen werden zu Text mit Zeilenumbrüchen, die draw.io-Farbwörter
 * {@code default}, {@code light-dark(a,b)} und {@code inherit}, die Kurznamen {@code rect}, {@code filledEdge},
 * {@code flexArrow}, {@code umlActor}, {@code note} (als Rechteck) und die benannten Grundstile ({@code text}, {@code ellipse} …) sowie die
 * Standardfarben von draw.io (weiß gefüllt, schwarz umrandet) statt der blauen JGraphX-Vorgaben.</p>
 */
final class DrawioDiagramRenderer {

    /** Das Diagramm ist mit JGraphX nicht vollständig darstellbar; die Meldung nennt nur Stilnamen, keine Inhalte. */
    static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** draw.io-Blockpfeil ({@code mxgraph.arrows2.arrow}), den {@link DrawioSvgCanvas} selbst zeichnet. */
    static final String BLOCK_ARROW = "blockArrow";
    /** UML-Schnittstelle mit Kugel und Pfanne ({@code providedRequiredInterface}), ebenfalls selbst gezeichnet. */
    static final String INTERFACE = "providedRequiredInterface";

    /** Formen, die JGraphX selbst zeichnet ({@code mxGraphics2DCanvas}/{@code mxSvgCanvas}). */
    private static final Set<String> VERTEX_SHAPES = new TreeSet<String>(java.util.Arrays.asList(
            "rectangle", "ellipse", "doubleEllipse", "rhombus", "line", "image", "label", "cylinder", "swimlane",
            "actor", "cloud", "triangle", "hexagon", BLOCK_ARROW, INTERFACE));
    private static final Set<String> EDGE_SHAPES = new TreeSet<String>(java.util.Arrays.asList(
            "connector", "arrow", "line", "curve"));

    /** draw.io-Kurz- und -Aliasnamen auf die JGraphX-Form. */
    private static final Map<String, String> SHAPE_ALIASES = new HashMap<String, String>();
    /** Benannte Grundstile von draw.io (erstes Stilwort ohne {@code =}), als Schlüssel-Wert-Paare. */
    private static final Map<String, String> NAMED_STYLES = new HashMap<String, String>();

    static {
        SHAPE_ALIASES.put("rect", "rectangle");
        SHAPE_ALIASES.put("mxgraph.basic.rect", "rectangle");
        SHAPE_ALIASES.put("umlActor", "actor");
        SHAPE_ALIASES.put("filledEdge", "connector");
        SHAPE_ALIASES.put("flexArrow", "arrow");
        SHAPE_ALIASES.put("link", "connector");
        SHAPE_ALIASES.put("note", "rectangle"); // ohne Eselsohr
        SHAPE_ALIASES.put("mxgraph.arrows2.arrow", BLOCK_ARROW);

        NAMED_STYLES.put("text", "fillColor=none;strokeColor=none");
        NAMED_STYLES.put("edgeLabel", "fillColor=none;strokeColor=none;labelBackgroundColor=#FFFFFF");
        NAMED_STYLES.put("label", "shape=label");
        NAMED_STYLES.put("group", "fillColor=none;strokeColor=none");
        NAMED_STYLES.put("ellipse", "shape=ellipse;perimeter=ellipsePerimeter");
        NAMED_STYLES.put("doubleEllipse", "shape=doubleEllipse;perimeter=ellipsePerimeter");
        NAMED_STYLES.put("rhombus", "shape=rhombus;perimeter=rhombusPerimeter");
        NAMED_STYLES.put("triangle", "shape=triangle;perimeter=trianglePerimeter");
        NAMED_STYLES.put("hexagon", "shape=hexagon;perimeter=hexagonPerimeter");
        NAMED_STYLES.put("line", "shape=line");
        NAMED_STYLES.put("image", "shape=image");
        NAMED_STYLES.put("swimlane", "shape=swimlane");
        NAMED_STYLES.put("cloud", "shape=cloud");
        NAMED_STYLES.put("cylinder", "shape=cylinder");
        NAMED_STYLES.put("actor", "shape=actor");
        NAMED_STYLES.put("rounded", "rounded=1");
    }

    private static final Pattern LINE_BREAK = Pattern.compile(
            "<br\\s*/?>|</(?:div|p|li|h[1-6]|tr)\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern LIGHT_DARK = Pattern.compile("light-dark\\(\\s*([^,]+?)\\s*,.*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_HEX = Pattern.compile("#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])");
    private static final Pattern HEX_COLOR = Pattern.compile("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    /** draw.io schreibt eingebettete Bilder als {@code data:image/png,<Base64>} (ohne „;“, das trennt Stile). */
    private static final Pattern DATA_IMAGE = Pattern.compile("^data:(image/[a-zA-Z0-9.+-]+),(?!.*;base64)");

    private DrawioDiagramRenderer() {
    }

    /**
     * @param xml {@code mxfile} (erste Seite; komprimiert oder nicht) oder ein {@code mxGraphModel}
     * @return SVG-Dokument als Text
     * @throws Unsupported wenn das Diagramm nicht lesbar oder nicht vollständig darstellbar ist
     */
    static String toSvg(String xml) throws Unsupported {
        Element model = graphModel(parse(xml));
        Set<String> unsupported = new TreeSet<String>();
        normalize(model, unsupported);
        if (!unsupported.isEmpty()) {
            throw new Unsupported("nicht unterstützt: " + String.join(", ", unsupported));
        }
        mxGraph graph = new TolerantGraph();
        drawioDefaults(graph);
        try {
            new mxCodec(model.getOwnerDocument()).decode(model, graph.getModel());
        } catch (RuntimeException e) {
            throw new Unsupported("Diagrammmodell nicht lesbar (" + e.getClass().getSimpleName() + ")");
        }
        if (graph.getChildCells(graph.getDefaultParent()).length == 0) {
            throw new Unsupported("Diagramm ohne Elemente");
        }
        mxSvgCanvas canvas;
        try {
            canvas = (mxSvgCanvas) mxCellRenderer.drawCells(graph, null, 1, null,
                    new mxCellRenderer.CanvasFactory() {
                        @Override
                        public mxICanvas createCanvas(int width, int height) {
                            return new DrawioSvgCanvas(mxDomUtils.createSvgDocument(width, height));
                        }
                    });
        } catch (RuntimeException e) {
            throw new Unsupported("JGraphX konnte das Diagramm nicht zeichnen (" + e.getClass().getSimpleName() + ")");
        }
        if (canvas == null) {
            throw new Unsupported("Diagramm ohne Fläche");
        }
        Document svg = canvas.getDocument();
        fixEmbeddedImages(svg.getDocumentElement());
        return serialize(svg);
    }

    /** XML ohne DOCTYPE und ohne externe Entitäten (Diagrammdaten kommen vom Server). */
    static Document parse(String xml) throws Unsupported {
        if (xml != null && xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            // ohne DOCTYPE keine Entitäten, also auch keine externen
            throw new Unsupported("Diagrammdaten mit DOCTYPE werden nicht gelesen");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // nichts auf stderr
            return builder.parse(new InputSource(new StringReader(xml == null ? "" : xml)));
        } catch (Exception e) {
            throw new Unsupported("Diagrammdaten kein lesbares XML (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Das {@code mxGraphModel} der ersten Seite; komprimierte Seiten (Base64, Deflate, URL-kodiert) entpackt. */
    private static Element graphModel(Document doc) throws Unsupported {
        Element root = doc.getDocumentElement();
        if ("mxGraphModel".equals(root.getNodeName())) {
            return root;
        }
        NodeList diagrams = root.getElementsByTagName("diagram");
        if (!"mxfile".equals(root.getNodeName()) || diagrams.getLength() == 0) {
            throw new Unsupported("kein draw.io-Diagramm (" + root.getNodeName() + ")");
        }
        Element diagram = (Element) diagrams.item(0);
        NodeList models = diagram.getElementsByTagName("mxGraphModel");
        if (models.getLength() > 0) {
            return (Element) models.item(0);
        }
        String packed = diagram.getTextContent() == null ? "" : diagram.getTextContent().trim();
        if (packed.isEmpty()) {
            throw new Unsupported("leere Diagrammseite");
        }
        Element model = parse(inflate(packed)).getDocumentElement();
        if (!"mxGraphModel".equals(model.getNodeName())) {
            throw new Unsupported("komprimierte Seite ohne mxGraphModel");
        }
        return model;
    }

    private static String inflate(String packed) throws Unsupported {
        try {
            Inflater inflater = new Inflater(true);
            inflater.setInput(Base64.getMimeDecoder().decode(packed));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            inflater.end();
            return URLDecoder.decode(new String(out.toByteArray(), StandardCharsets.UTF_8), "UTF-8");
        } catch (Exception e) {
            throw new Unsupported("komprimierte Seite nicht lesbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Stile und Beschriftungen jeder Zelle auf JGraphX bringen; Unbekanntes in {@code unsupported} sammeln. */
    static void normalize(Element model, Set<String> unsupported) {
        NodeList cells = model.getElementsByTagName("mxCell");
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            boolean edge = "1".equals(cell.getAttribute("edge"));
            boolean vertex = "1".equals(cell.getAttribute("vertex"));
            Map<String, String> style = style(cell.getAttribute("style"), unsupported);
            if (vertex || edge) {
                shape(style, edge, unsupported);
            }
            colors(style);
            if ("1".equals(style.remove("html")) && cell.hasAttribute("value")) {
                cell.setAttribute("value", plainText(cell.getAttribute("value")));
            }
            String image = style.get("image");
            if (image != null && !image.isEmpty() && !image.startsWith("data:image/")) {
                unsupported.add("image=" + (image.contains(":") ? image.substring(0, image.indexOf(':')) + ":…"
                        : "relativ"));
            }
            cell.setAttribute("style", join(style));
        }
    }

    private static Map<String, String> style(String raw, Set<String> unsupported) {
        Map<String, String> style = new LinkedHashMap<String, String>();
        if (raw == null) {
            return style;
        }
        for (String token : raw.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                String named = NAMED_STYLES.get(trimmed);
                if (named == null) {
                    unsupported.add("Stil " + trimmed);
                    continue;
                }
                for (String pair : named.split(";")) {
                    int at = pair.indexOf('=');
                    style.put(pair.substring(0, at), pair.substring(at + 1));
                }
                continue;
            }
            style.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return style;
    }

    private static void shape(Map<String, String> style, boolean edge, Set<String> unsupported) {
        String shape = style.get("shape");
        if (shape == null || shape.isEmpty()) {
            return;
        }
        String mapped = SHAPE_ALIASES.containsKey(shape) ? SHAPE_ALIASES.get(shape) : shape;
        if (!(edge ? EDGE_SHAPES : VERTEX_SHAPES).contains(mapped)) {
            unsupported.add("shape=" + shape);
            return;
        }
        style.put("shape", mapped);
    }

    /** draw.io-Farbwörter, die JGraphX nicht kennt; „default“ ist bei Flächen weiß, bei Linien und Text schwarz. */
    private static void colors(Map<String, String> style) {
        for (Map.Entry<String, String> entry : new LinkedHashMap<String, String>(style).entrySet()) {
            String key = entry.getKey();
            if (!key.toLowerCase(Locale.ROOT).endsWith("color")) {
                continue;
            }
            String value = entry.getValue();
            Matcher lightDark = LIGHT_DARK.matcher(value);
            if (lightDark.matches()) {
                value = lightDark.group(1).trim();
            }
            if ("inherit".equalsIgnoreCase(value) || value.isEmpty()) {
                style.remove(key);
                continue;
            }
            if ("default".equalsIgnoreCase(value)) {
                value = key.equals("strokeColor") || key.equals("fontColor") ? "#000000" : "#FFFFFF";
            }
            Matcher shortHex = SHORT_HEX.matcher(value);
            if (shortHex.matches()) {
                value = "#" + shortHex.group(1) + shortHex.group(1) + shortHex.group(2) + shortHex.group(2)
                        + shortHex.group(3) + shortHex.group(3);
            }
            if (value.startsWith("#") && !HEX_COLOR.matcher(value).matches()) {
                style.remove(key); // etwa „#“ ohne Ziffern: Batik lehnt das ganze SVG ab
                continue;
            }
            style.put(key, value.length() == 9 && value.startsWith("#") ? value.substring(0, 7) : value);
        }
    }

    /** HTML-Beschriftung als Text: Zeilenumbrüche aus {@code <br>} und Blockenden, Tags weg, Entitäten aufgelöst. */
    static String plainText(String html) {
        String text = LINE_BREAK.matcher(html).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'");
        Matcher numeric = NUMERIC_ENTITY.matcher(text);
        StringBuffer decoded = new StringBuffer();
        while (numeric.find()) {
            String code = numeric.group(1);
            int value = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(new String(Character.toChars(value))));
        }
        numeric.appendTail(decoded);
        text = decoded.toString().replace("&amp;", "&");
        StringBuilder lines = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.replaceAll("[ \\t\\u00A0]+", " ").trim();
            if (trimmed.isEmpty() && (lines.length() == 0 || lines.charAt(lines.length() - 1) == '\n')) {
                continue;
            }
            lines.append(trimmed).append('\n');
        }
        return lines.toString().trim();
    }

    private static String join(Map<String, String> style) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : style.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return out.toString();
    }

    /** draw.io-Vorgaben statt der JGraphX-Vorgaben (blaue Fläche, braune Schrift). */
    private static void drawioDefaults(mxGraph graph) {
        Map<String, Object> vertex = graph.getStylesheet().getDefaultVertexStyle();
        vertex.put("fillColor", "#FFFFFF");
        vertex.put("strokeColor", "#000000");
        vertex.put("fontColor", "#000000");
        vertex.put("fontSize", "11");
        vertex.put("fontFamily", "Helvetica");
        vertex.remove("gradientColor");
        vertex.remove("shadow");
        Map<String, Object> edge = graph.getStylesheet().getDefaultEdgeStyle();
        edge.put("strokeColor", "#000000");
        edge.put("fontColor", "#000000");
        edge.put("fontSize", "11");
        edge.put("fontFamily", "Helvetica");
        edge.put("labelBackgroundColor", "#FFFFFF");
        graph.setHtmlLabels(false);
    }

    /** {@code data:image/png,<Base64>} aus draw.io als gültige Daten-URI ({@code ;base64}) für Batik. */
    private static void fixEmbeddedImages(Element element) {
        for (String name : new String[] {"xlink:href", "href"}) {
            if (element.hasAttribute(name)) {
                Matcher matcher = DATA_IMAGE.matcher(element.getAttribute(name));
                if (matcher.find()) {
                    element.setAttribute(name, matcher.replaceFirst("data:" + matcher.group(1) + ";base64,"));
                }
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                fixEmbeddedImages((Element) child);
            }
        }
    }

    private static String serialize(Document svg) throws Unsupported {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(svg), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new Unsupported("SVG nicht schreibbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * JGraphX scheitert bei manchen draw.io-Anschlusspunkten (Endpunkt ohne Form) mit einer NullPointerException;
     * dann gilt der Umriss des Elements als Anschluss, wie ohne Anschlusspunkt.
     */
    private static final class TolerantGraph extends mxGraph {
        @Override
        public mxPoint getConnectionPoint(mxCellState vertex, mxConnectionConstraint constraint) {
            try {
                return super.getConnectionPoint(vertex, constraint);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** SVG-Zeichenfläche von JGraphX plus die draw.io-Formen, die JGraphX nicht kennt. */
    private static final class DrawioSvgCanvas extends mxSvgCanvas {
        DrawioSvgCanvas(Document document) {
            super(document);
        }

        @Override
        public Element drawShape(int x, int y, int w, int h, Map<String, Object> style) {
            Object shape = style.get("shape");
            String[] parts;
            if (BLOCK_ARROW.equals(shape)) {
                parts = new String[] {blockArrowPath(x, y, w, h, style)};
            } else if (INTERFACE.equals(shape)) {
                parts = interfacePaths(x, y, w, h);
            } else {
                return super.drawShape(x, y, w, h, style);
            }
            Element group = document.createElement("g");
            double rotation = number(style, "rotation", 0);
            if (rotation != 0) {
                group.setAttribute("transform", "rotate(" + round(rotation) + " " + round(x + w / 2.0) + " "
                        + round(y + h / 2.0) + ")");
            }
            double opacity = number(style, "opacity", 100);
            if (opacity < 100) {
                group.setAttribute("opacity", String.valueOf(opacity / 100));
            }
            group.setAttribute("stroke", color(style, "strokeColor"));
            group.setAttribute("stroke-width", String.valueOf(number(style, "strokeWidth", 1) * scale));
            for (int i = 0; i < parts.length; i++) {
                Element path = document.createElement("path");
                path.setAttribute("d", parts[i]);
                // Nur der erste Teil ist Fläche; weitere Teile (Pfanne der Schnittstelle) sind reine Linien.
                path.setAttribute("fill", i == 0 ? color(style, "fillColor") : "none");
                group.appendChild(path);
            }
            appendSvgElement(group);
            return group;
        }

        private static String color(Map<String, Object> style, String key) {
            Object value = style.get(key);
            return value == null || "none".equals(value) ? "none" : value.toString();
        }
    }

    /**
     * Umriss wie draw.io {@code mxgraph.arrows2.arrow}: Schaft mit Höhe {@code (1 - dy)} der Dicke, Spitze
     * {@code dx} lang, Kerbe {@code notch} am Ende; Richtung und Spiegelung wie in draw.io.
     */
    static String blockArrowPath(int x, int y, int w, int h, Map<String, Object> style) {
        String direction = String.valueOf(style.get("direction"));
        boolean vertical = "south".equals(direction) || "north".equals(direction);
        double length = vertical ? h : w;
        double thickness = vertical ? w : h;
        double dy = thickness * 0.5 * Math.max(0, Math.min(1, number(style, "dy", 0.6)));
        double dx = Math.max(0, Math.min(length, number(style, "dx", 40)));
        double notch = Math.max(0, Math.min(length - dx, number(style, "notch", 0)));
        double[][] points = {
                {0, dy}, {length - dx, dy}, {length - dx, 0}, {length, thickness / 2}, {length - dx, thickness},
                {length - dx, thickness - dy}, {0, thickness - dy}, {notch, thickness / 2}};
        boolean flipH = "1".equals(String.valueOf(style.get("flipH")));
        boolean flipV = "1".equals(String.valueOf(style.get("flipV")));
        StringBuilder d = new StringBuilder();
        for (double[] p : points) {
            double px;
            double py;
            if ("west".equals(direction)) {
                px = w - p[0];
                py = p[1];
            } else if ("south".equals(direction)) {
                px = thickness - p[1];
                py = p[0];
            } else if ("north".equals(direction)) {
                px = p[1];
                py = h - p[0];
            } else {
                px = p[0];
                py = p[1];
            }
            if (flipH) {
                px = w - px;
            }
            if (flipV) {
                py = h - py;
            }
            d.append(d.length() == 0 ? "M " : " L ").append(round(x + px)).append(' ').append(round(y + py));
        }
        return d.append(" Z").toString();
    }

    /** Wie draw.io: Kugel links ({@code 0,2h} bis {@code 0,8h}), Pfanne als Halbbogen von oben Mitte nach unten Mitte. */
    static String[] interfacePaths(int x, int y, int w, int h) {
        double rx = w * 0.3;
        double ry = h * 0.3;
        double cy = y + h / 2.0;
        String ball = "M " + round(x) + " " + round(cy) + " A " + round(rx) + " " + round(ry) + " 0 1 1 "
                + round(x + 2 * rx) + " " + round(cy) + " A " + round(rx) + " " + round(ry) + " 0 1 1 " + round(x)
                + " " + round(cy) + " Z";
        String socket = "M " + round(x + w * 0.5) + " " + round(y) + " A " + round(w * 0.5) + " " + round(h * 0.5)
                + " 0 0 1 " + round(x + w * 0.5) + " " + round(y + h);
        return new String[] {ball, socket};
    }

    private static double number(Map<String, Object> style, String key, double fallback) {
        Object value = style.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
