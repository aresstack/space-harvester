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

    /** Ein Knoten mit seinen Kindern. */
    public record Node(String pageId, List<Node> children) {

        public Node {
            Objects.requireNonNull(pageId, "pageId");
            children = List.copyOf(children);
        }
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
