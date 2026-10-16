package com.aresstack.spaceharvester.application.export;

import java.util.List;
import java.util.Objects;

/**
 * Hierarchie eines Exports nur aus IDs. Jede Seite des flachen Seiten-Arrays kommt genau einmal vor.
 *
 * @param roots    Wurzelseiten (mehrere möglich), in Lesereihenfolge
 * @param warnings Auffälligkeiten, etwa fehlende Elternseiten oder Zyklen
 */
public record ExportTree(List<Node> roots, List<String> warnings) {

    public ExportTree {
        roots = List.copyOf(roots);
        warnings = List.copyOf(warnings);
    }

    /** Eine Seite und ihre Kinder in Lesereihenfolge. */
    public record Node(String pageId, List<Node> children) {

        public Node {
            Objects.requireNonNull(pageId, "pageId");
            children = List.copyOf(children);
        }
    }
}
