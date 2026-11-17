package com.aresstack.spaceharvester.document;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Ein draw.io-Diagramm als Struktur für KI-Systeme (ADR 0018): Knoten mit Beschriftung und Verbindungen mit Quelle,
 * Ziel und Beschriftung, gelesen aus den {@code mxfile}-Quelldaten (auch komprimiert). Die Originaldatei bleibt
 * maßgeblich; das gerenderte Backup-PNG wird dafür nicht gebraucht.
 */
public final class DrawioGraph {

    /** Ein Knoten; {@code parent} ist der Container (Gruppe, Swimlane) oder {@code null}. */
    public record Node(String id, String label, String parent) {
    }

    /** Eine Verbindung; {@code source} und {@code target} sind Knoten-IDs oder {@code null}, wenn lose. */
    public record Edge(String id, String label, String source, String target) {
    }

    private static final Pattern LINE_BREAK = Pattern.compile(
            "<br\\s*/?>|</(?:div|p|li|h[1-6]|tr)\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

    private final String name;
    private final List<Node> nodes;
    private final List<Edge> edges;

    private DrawioGraph(String name, List<Node> nodes, List<Edge> edges) {
        this.name = name;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    /** Name der ersten Diagrammseite, sonst leer. */
    public String name() {
        return name;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    /**
     * Liest die erste Seite des Diagramms.
     *
     * @throws DrawioDiagramRenderer.Unsupported wenn die Daten kein lesbares draw.io-Diagramm sind
     */
    public static DrawioGraph of(byte[] mxfile) throws DrawioDiagramRenderer.Unsupported {
        org.w3c.dom.Document document = parse(new String(mxfile, StandardCharsets.UTF_8));
        String name = "";
        NodeList diagrams = document.getDocumentElement().getElementsByTagName("diagram");
        if (diagrams.getLength() > 0) {
            name = ((Element) diagrams.item(0)).getAttribute("name");
        }
        Element model = graphModel(document);
        Map<String, Element> cells = new LinkedHashMap<>();
        NodeList all = model.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            Element cell = cell(element);
            if (cell != null && !element.getAttribute("id").isEmpty()) {
                cells.put(element.getAttribute("id"), element);
            }
        }
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, Element> entry : cells.entrySet()) {
            Element element = entry.getValue();
            Element cell = cell(element);
            String label = label(element, cell);
            if ("1".equals(cell.getAttribute("edge"))) {
                edges.add(new Edge(entry.getKey(), label, blank(cell.getAttribute("source")),
                        blank(cell.getAttribute("target"))));
            } else if ("1".equals(cell.getAttribute("vertex"))) {
                String parent = blank(cell.getAttribute("parent"));
                Element parentCell = parent == null ? null : cells.containsKey(parent) ? cell(cells.get(parent)) : null;
                nodes.add(new Node(entry.getKey(), label,
                        parentCell != null && "1".equals(parentCell.getAttribute("vertex")) ? parent : null));
            }
        }
        // Beschriftungen von Verbindungen sind oft eigene Kindknoten der Verbindung.
        List<Node> kept = new ArrayList<>();
        Map<String, Edge> byId = new LinkedHashMap<>();
        for (Edge edge : edges) {
            byId.put(edge.id(), edge);
        }
        for (Node node : nodes) {
            String parent = blank(cell(cells.get(node.id())).getAttribute("parent"));
            Edge edge = parent == null ? null : byId.get(parent);
            if (edge != null) {
                String label = edge.label().isEmpty() ? node.label() : edge.label() + " " + node.label();
                byId.put(edge.id(), new Edge(edge.id(), label.trim(), edge.source(), edge.target()));
            } else {
                kept.add(node);
            }
        }
        return new DrawioGraph(name, kept, new ArrayList<>(byId.values()));
    }

    /** Die mxCell eines Elements: es selbst oder bei {@code <object>}/{@code <UserObject>} das Kind. */
    private static Element cell(Element element) {
        if ("mxCell".equals(element.getNodeName())) {
            Element parent = element.getParentNode() instanceof Element p ? p : null;
            if (parent != null && ("object".equals(parent.getNodeName())
                    || "UserObject".equals(parent.getNodeName()))) {
                return null; // gehört zum umschließenden Objekt
            }
            return element;
        }
        if ("object".equals(element.getNodeName()) || "UserObject".equals(element.getNodeName())) {
            NodeList children = element.getElementsByTagName("mxCell");
            return children.getLength() == 0 ? null : (Element) children.item(0);
        }
        return null;
    }

    private static String label(Element element, Element cell) {
        String raw = element == cell ? cell.getAttribute("value") : element.getAttribute("label");
        return raw == null || raw.isEmpty() ? "" : plainText(raw);
    }

    private static String blank(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Lesbarer Text: Knoten je Zeile, Verbindungen als „A → B (Beschriftung)“. */
    public String text() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Node node : nodes) {
            labels.put(node.id(), node.label().isEmpty() ? node.id() : node.label().replace('\n', ' '));
        }
        StringBuilder out = new StringBuilder();
        if (!name.isEmpty()) {
            out.append("Diagramm: ").append(name).append('\n');
        }
        for (Node node : nodes) {
            if (!node.label().isEmpty()) {
                out.append("- ").append(node.label().replace('\n', ' ')).append('\n');
            }
        }
        for (Edge edge : edges) {
            out.append(labels.getOrDefault(edge.source(), "?")).append(" → ")
                    .append(labels.getOrDefault(edge.target(), "?"));
            if (!edge.label().isEmpty()) {
                out.append(" (").append(edge.label().replace('\n', ' ')).append(')');
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    // Lesen der mxfile-Quelldaten (bis drawio-java#16 im alten JGraphX-Renderer, jetzt nur noch hier gebraucht).

    /** XML ohne DOCTYPE und ohne externe Entitäten (Diagrammdaten kommen vom Server). */
    private static org.w3c.dom.Document parse(String xml) throws DrawioDiagramRenderer.Unsupported {
        if (xml != null && xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            // ohne DOCTYPE keine Entitäten, also auch keine externen
            throw new DrawioDiagramRenderer.Unsupported("Diagrammdaten mit DOCTYPE werden nicht gelesen");
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
            throw new DrawioDiagramRenderer.Unsupported(
                    "Diagrammdaten kein lesbares XML (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Das {@code mxGraphModel} der ersten Seite; komprimierte Seiten (Base64, Deflate, URL-kodiert) entpackt. */
    private static Element graphModel(org.w3c.dom.Document doc) throws DrawioDiagramRenderer.Unsupported {
        Element root = doc.getDocumentElement();
        if ("mxGraphModel".equals(root.getNodeName())) {
            return root;
        }
        NodeList diagrams = root.getElementsByTagName("diagram");
        if (!"mxfile".equals(root.getNodeName()) || diagrams.getLength() == 0) {
            throw new DrawioDiagramRenderer.Unsupported("kein draw.io-Diagramm (" + root.getNodeName() + ")");
        }
        Element diagram = (Element) diagrams.item(0);
        NodeList models = diagram.getElementsByTagName("mxGraphModel");
        if (models.getLength() > 0) {
            return (Element) models.item(0);
        }
        String packed = diagram.getTextContent() == null ? "" : diagram.getTextContent().trim();
        if (packed.isEmpty()) {
            throw new DrawioDiagramRenderer.Unsupported("leere Diagrammseite");
        }
        Element model = parse(inflate(packed)).getDocumentElement();
        if (!"mxGraphModel".equals(model.getNodeName())) {
            throw new DrawioDiagramRenderer.Unsupported("komprimierte Seite ohne mxGraphModel");
        }
        return model;
    }

    private static String inflate(String packed) throws DrawioDiagramRenderer.Unsupported {
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
            throw new DrawioDiagramRenderer.Unsupported(
                    "komprimierte Seite nicht lesbar (" + e.getClass().getSimpleName() + ")");
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
}
