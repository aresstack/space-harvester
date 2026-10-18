package com.aresstack.spaceharvester.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
        org.w3c.dom.Document document = DrawioDiagramRenderer.parse(new String(mxfile, StandardCharsets.UTF_8));
        String name = "";
        NodeList diagrams = document.getDocumentElement().getElementsByTagName("diagram");
        if (diagrams.getLength() > 0) {
            name = ((Element) diagrams.item(0)).getAttribute("name");
        }
        Element model = DrawioDiagramRenderer.graphModel(document);
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
        return raw == null || raw.isEmpty() ? "" : DrawioDiagramRenderer.plainText(raw);
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
}
