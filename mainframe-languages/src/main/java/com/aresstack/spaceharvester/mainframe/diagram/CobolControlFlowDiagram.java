package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Render stored graph facts without inventing any additional edges.
 *
 * <p>Kompakt (Angelo 28.11.2026: Blockbildung auch für COBOL) werden Ketten einfacher Anweisungen zu einem Block
 * „📦 N Anweisungen (Lx–Ly)“ zusammengefasst: aufeinanderfolgende STATEMENT-Knoten, die nur über eine NEXT-Kante
 * verbunden sind und von nirgends sonst angesprungen werden. Kanten von und zu anderen Knoten bleiben, wie sie sind.
 * Mit KI beschreibt das Modell jeden Block anhand seiner Quelltextzeilen, jede Verzweigung als Frage und jedes
 * aufgerufene Programm mit seinem Zweck ({@link FlowLabels}).
 */
public final class CobolControlFlowDiagram {

    private static final Pattern CALL_TARGET = Pattern.compile("CALL\\s+['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);

    private CobolControlFlowDiagram() { }

    public static String render(CobolControlFlow.Procedure graph) {
        return render(graph, null, false);
    }

    /**
     * @param labels  Beschriftungen aus Quelltext und KI; {@code null} = nur die Fakten des Graphen
     * @param compact Ketten einfacher Anweisungen zu Blöcken zusammenfassen
     */
    public static String render(CobolControlFlow.Procedure graph, FlowLabels labels, boolean compact) {
        Map<String, String> blockOf = compact ? blocks(graph) : Map.of();
        Map<String, List<CobolControlFlow.Node>> members = new LinkedHashMap<>();
        for (var node : graph.nodes()) {
            String block = blockOf.get(node.id());
            if (block != null) {
                members.computeIfAbsent(block, k -> new ArrayList<>()).add(node);
            }
        }
        StringBuilder out = new StringBuilder("flowchart TD\n");
        for (var node : graph.nodes()) {
            String block = blockOf.get(node.id());
            if (block != null) {
                if (block.equals(node.id())) {
                    emitBlock(out, block, members.get(block), labels);
                }
                continue;
            }
            String label = node.id() + ": " + node.label() + " (L" + node.span().start().line() + ")";
            out.append("  ").append(node.id());
            if (node.kind() == CobolControlFlow.NodeKind.DECISION) {
                String text = labels == null ? label : node.id() + ": " + labels.question(node.label() + " (L"
                        + node.span().start().line() + ")", node.span().start().line(), node.span().end().line());
                out.append("{\"").append(lines(labels, text)).append("\"}");
            } else {
                String about = node.kind() == CobolControlFlow.NodeKind.CALL ? callee(node, labels) : null;
                out.append("[\"").append(escape(label));
                if (about != null) {
                    out.append(NEWLINE).append(lines(labels, about));
                }
                out.append("\"]");
            }
            out.append('\n');
        }
        for (var edge : graph.edges()) {
            String from = blockOf.getOrDefault(edge.from(), edge.from());
            String to = blockOf.getOrDefault(edge.to(), edge.to());
            if (from.equals(to) && blockOf.containsKey(edge.from())) {
                continue; // Kante innerhalb eines Blocks
            }
            String label = edge.kind().name();
            if (!edge.condition().isBlank()) label += ": " + edge.condition();
            if (!edge.callSite().isBlank()) label += " [" + edge.callSite() + "]";
            out.append("  ").append(from).append(" -->|\"").append(escape(label))
                    .append("\"| ").append(to).append('\n');
        }
        return out.toString();
    }

    /**
     * Blöcke: Knoten-ID → ID des ersten Knotens seines Blocks, nur für Blöcke aus mindestens zwei Anweisungen.
     * Ein Knoten setzt die Kette fort, wenn er eine STATEMENT ist, genau eine eingehende Kante hat und diese eine
     * NEXT-Kante vom Vorgänger ist, der selbst genau eine ausgehende Kante hat.
     */
    static Map<String, String> blocks(CobolControlFlow.Procedure graph) {
        Map<String, CobolControlFlow.Node> byId = new HashMap<>();
        graph.nodes().forEach(n -> byId.put(n.id(), n));
        Map<String, List<CobolControlFlow.Edge>> out = new HashMap<>();
        Map<String, List<CobolControlFlow.Edge>> in = new HashMap<>();
        for (var edge : graph.edges()) {
            out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
        }
        Map<String, String> blockOf = new HashMap<>();
        for (var node : graph.nodes()) {
            if (!statement(node) || continues(node.id(), byId, in, out)) {
                continue; // kein Kettenanfang
            }
            List<String> chain = new ArrayList<>();
            String current = node.id();
            while (true) {
                chain.add(current);
                List<CobolControlFlow.Edge> next = out.getOrDefault(current, List.of());
                if (next.size() != 1 || !continues(next.get(0).to(), byId, in, out)
                        || chain.contains(next.get(0).to())) {
                    break;
                }
                current = next.get(0).to();
            }
            if (chain.size() > 1) {
                chain.forEach(id -> blockOf.put(id, node.id()));
            }
        }
        return blockOf;
    }

    /** Ob der Knoten die Kette seines einzigen Vorgängers fortsetzt. */
    private static boolean continues(String id, Map<String, CobolControlFlow.Node> byId,
            Map<String, List<CobolControlFlow.Edge>> in, Map<String, List<CobolControlFlow.Edge>> out) {
        CobolControlFlow.Node node = byId.get(id);
        List<CobolControlFlow.Edge> incoming = in.getOrDefault(id, List.of());
        if (node == null || !statement(node) || incoming.size() != 1) {
            return false;
        }
        CobolControlFlow.Edge edge = incoming.get(0);
        CobolControlFlow.Node before = byId.get(edge.from());
        return edge.kind() == CobolControlFlow.EdgeKind.NEXT && before != null && statement(before)
                && out.getOrDefault(before.id(), List.of()).size() == 1;
    }

    private static boolean statement(CobolControlFlow.Node node) {
        return node.kind() == CobolControlFlow.NodeKind.STATEMENT;
    }

    private static void emitBlock(StringBuilder out, String id, List<CobolControlFlow.Node> nodes, FlowLabels labels) {
        nodes = nodes.stream().sorted(java.util.Comparator.comparingInt(
                (CobolControlFlow.Node n) -> n.span().start().line()).thenComparingInt(n -> n.span().start().column()))
                .toList();
        int from = Integer.MAX_VALUE;
        int to = 0;
        for (var node : nodes) {
            from = Math.min(from, node.span().start().line());
            to = Math.max(to, node.span().end().line());
        }
        String fact = "📦 " + nodes.size() + " Anweisungen (L" + from + "–L" + to + ")";
        String about = labels == null ? null : labels.describeLines(from, to, FlowLabels.BLOCK_PURPOSE);
        out.append("  ").append(id).append("[\"").append(escape(fact));
        if (about != null) {
            out.append(NEWLINE).append(lines(labels, about));
        } else {
            // Ohne KI die Anweisungen selbst, so viele, wie die eingestellte Länge erlaubt
            int budget = (labels == null ? FlowLabels.Layout.DEFAULT : labels.layout()).detailLength();
            int used = 0;
            for (var node : nodes) {
                if (used + node.label().length() > budget) {
                    out.append(NEWLINE).append("…");
                    break;
                }
                out.append(NEWLINE).append(escape(node.label()));
                used += node.label().length();
            }
        }
        out.append("\"]\n");
        out.append("  style ").append(id).append(" fill:#f5f5f5,stroke:#9e9e9e,stroke-dasharray: 5 5\n");
    }

    private static String callee(CobolControlFlow.Node node, FlowLabels labels) {
        if (labels == null) {
            return null;
        }
        Matcher m = CALL_TARGET.matcher(node.label());
        return m.find() ? labels.callee(m.group(1), node.label(), null) : null;
    }

    /** Zeilenumbruch in einem Mermaid-Label (wie im Natural-Flussdiagramm). */
    private static final String NEWLINE = "\\n";

    /** Umbrochen und je Zeile maskiert, Zeilen mit {@link #NEWLINE} verbunden. */
    private static String lines(FlowLabels labels, String text) {
        String wrapped = labels == null ? text : labels.wrap(text);
        List<String> escaped = new ArrayList<>();
        for (String line : wrapped.split("\n")) {
            escaped.add(escape(line));
        }
        return String.join(NEWLINE, escaped);
    }

    private static String escape(String text) {
        // Keep source-derived strings inside quoted labels, including Mermaid delimiters.
        return text.replace("#", "＃").replace("\"", "＂").replace("&", "＆")
                .replace("<", "＜").replace(">", "＞").replace("|", "｜").replace("`", "｀")
                .replace("\\", "＼").replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }
}
