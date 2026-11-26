package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow;

/** Render stored graph facts without inventing any additional edges. */
public final class CobolControlFlowDiagram {
    private CobolControlFlowDiagram() { }

    public static String render(CobolControlFlow.Procedure graph) {
        StringBuilder out = new StringBuilder("flowchart TD\n");
        for (var node : graph.nodes()) {
            String label = node.id() + ": " + node.label() + " (L" + node.span().start().line() + ")";
            out.append("  ").append(node.id());
            if (node.kind() == CobolControlFlow.NodeKind.DECISION) out.append("{\"").append(escape(label)).append("\"}");
            else out.append("[\"").append(escape(label)).append("\"]");
            out.append('\n');
        }
        for (var edge : graph.edges()) {
            String label = edge.kind().name();
            if (!edge.condition().isBlank()) label += ": " + edge.condition();
            if (!edge.callSite().isBlank()) label += " [" + edge.callSite() + "]";
            out.append("  ").append(edge.from()).append(" -->|\"").append(escape(label))
                    .append("\"| ").append(edge.to()).append('\n');
        }
        return out.toString();
    }

    private static String escape(String text) {
        // Keep source-derived strings inside quoted labels, including Mermaid delimiters.
        return text.replace("#", "＃").replace("\"", "＂").replace("&", "＆")
                .replace("<", "＜").replace(">", "＞").replace("|", "｜").replace("`", "｀")
                .replace("\\", "＼").replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }
}
