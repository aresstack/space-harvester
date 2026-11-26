package com.aresstack.spaceharvester.application.tree;

import java.util.List;
import java.util.Objects;

/**
 * Baum aus Seiten-IDs. Jede Seite der Eingabe kommt genau einmal vor, Wurzeln und Kinder sind nach {@code pageId}
 * sortiert.
 *
 * @param roots     Wurzelknoten: echte Wurzelseiten und abgelöste Teilbäume (siehe {@code anomalies})
 * @param anomalies Seiten, die nur durch Ablösen zur Wurzel wurden, nach {@code pageId} sortiert
 * @param nodeCount Anzahl aller Knoten
 * @param depth     größte Tiefe; eine einzelne Wurzel ohne Kinder hat Tiefe 1, ein leerer Baum 0
 */
public record PageTree(List<Node> roots, List<Anomaly> anomalies, int nodeCount, int depth) {

    public PageTree {
        roots = List.copyOf(roots);
        anomalies = List.copyOf(anomalies);
    }

    /**
     * Ein Knoten mit seinen Kindern.
     *
     * @param more {@code true}, wenn der Knoten Kinder hat, die dieser Ausschnitt nicht enthält ({@link #levels});
     *             {@code children} ist dann leer
     */
    public record Node(String pageId, List<Node> children, boolean more) {

        public Node {
            Objects.requireNonNull(pageId, "pageId");
            children = List.copyOf(children);
        }

        public Node(String pageId, List<Node> children) {
            this(pageId, children, false);
        }
    }

    /**
     * Ein Ausschnitt von höchstens {@code levels} Ebenen ab {@code rootPageId} bzw. ab den Wurzeln (ADR 0040, Punkt 12):
     * tiefere Ebenen fragt der Aufrufer mit dem Knoten, der {@link Node#more()} trägt, als neuer Wurzel nach. So ist
     * jede Tiefe erreichbar, ohne dass eine Antwort beliebig tief verschachtelt ist. Abweichungen bleiben, soweit ihre
     * Seite im Ausschnitt liegt.
     *
     * @param rootPageId Seite, deren Teilbaum gemeint ist, oder {@code null} für den ganzen Baum
     * @param levels     Zahl der Ebenen, mindestens 1
     * @return leer, wenn {@code rootPageId} nicht im Baum ist
     */
    public java.util.Optional<PageTree> levels(String rootPageId, int levels) {
        if (levels < 1) {
            throw new IllegalArgumentException("levels muss mindestens 1 sein");
        }
        List<Node> start;
        if (rootPageId == null) {
            start = roots;
        } else {
            Node found = find(rootPageId);
            if (found == null) {
                return java.util.Optional.empty();
            }
            start = List.of(found);
        }
        int[] count = {0};
        int[] deepest = {0};
        List<Node> cut = start.stream().map(node -> cut(node, 1, levels, count, deepest)).toList();
        java.util.Set<String> included = new java.util.HashSet<>();
        java.util.ArrayDeque<Node> open = new java.util.ArrayDeque<>(cut);
        while (!open.isEmpty()) {
            Node node = open.pop();
            included.add(node.pageId());
            open.addAll(node.children());
        }
        return java.util.Optional.of(new PageTree(cut,
                anomalies.stream().filter(anomaly -> included.contains(anomaly.pageId())).toList(),
                count[0], deepest[0]));
    }

    private Node find(String pageId) {
        java.util.ArrayDeque<Node> open = new java.util.ArrayDeque<>(roots);
        while (!open.isEmpty()) {
            Node node = open.pop();
            if (node.pageId().equals(pageId)) {
                return node;
            }
            open.addAll(node.children());
        }
        return null;
    }

    private static Node cut(Node node, int level, int levels, int[] count, int[] deepest) {
        count[0]++;
        deepest[0] = Math.max(deepest[0], level);
        if (level == levels) {
            return node.children().isEmpty() ? node : new Node(node.pageId(), List.of(), true);
        }
        return new Node(node.pageId(), node.children().stream()
                .map(child -> cut(child, level + 1, levels, count, deepest)).toList(), node.more());
    }

    /**
     * Eine Seite, die als Wurzel erscheint, obwohl sie eine Elternseite angibt.
     *
     * @param pageId Seite, die zur Wurzel ihres Teilbaums wurde
     * @param kind   warum
     */
    public record Anomaly(String pageId, Kind kind) {

        public Anomaly {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public enum Kind {
        /**
         * Die angegebene Elternseite ist nicht in der Eingabe: nicht geerntet, gelöscht, in einen anderen Space
         * verschoben oder für den Aufrufer nicht sichtbar. Welcher Fall vorliegt, sagt der Baum bewusst nicht.
         */
        MISSING_PARENT,
        /** Die Seite liegt in einem Elternzyklus; der Zyklus wurde an ihr aufgebrochen. */
        CYCLE
    }
}
