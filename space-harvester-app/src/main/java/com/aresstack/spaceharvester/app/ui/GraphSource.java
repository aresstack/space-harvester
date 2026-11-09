package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Woher der Graph unter Begriffe › Graph seine Knoten und Kanten nimmt: zu einem Startbegriff dessen Nachbarschaft bis
 * zur gewünschten Tiefe. Heute liefern sie die Beziehungen zwischen Begriffen ({@link #ofRelations}); ein späterer,
 * dauerhafter Wissensgraph im Core (#499) tritt hinter dieselbe Schnittstelle. Die Arbeit läuft außerhalb des EDT.
 */
interface GraphSource {

    /** Höchstens so viele Knoten zeigt ein Graph; mehr meldet {@link Graph#truncated()}. */
    int MAX_NODES = 40;

    /**
     * @param start     Startbegriff in der Mitte, wie eingegeben
     * @param depth     1 oder 2: Nachbarn bzw. auch deren Nachbarn
     * @param proposals ob auch vorgeschlagene Beziehungen Kanten werden, sonst nur bestätigte
     */
    Graph around(String start, int depth, boolean proposals) throws Exception;

    /**
     * Ein Begriff im Graphen.
     *
     * @param key    Suchwert, eindeutig im Graphen
     * @param label  Wortlaut
     * @param depth  Abstand zur Mitte: 0 die Mitte, 1 Nachbar, 2 Nachbar eines Nachbarn
     * @param parent Schlüssel des Knotens, über den er gefunden wurde; {@code null} für die Mitte
     */
    record Node(String key, String label, int depth, String parent) {
        public Node {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * Eine Beziehung zwischen zwei Knoten.
     *
     * @param from      Schlüssel des Ausgangsknotens
     * @param to        Schlüssel des Zielknotens
     * @param type      Art als Name von {@code RelationType}, für die Beschriftung ({@link Relation#typeText})
     * @param confirmed bestätigt; sonst ein Vorschlag (gestrichelt)
     * @param directed  ob die Richtung zählt (Pfeil)
     */
    record Edge(String from, String to, String type, boolean confirmed, boolean directed) {
    }

    /** Knoten (die Mitte zuerst) und Kanten; {@code truncated}, wenn es mehr als {@link #MAX_NODES} Knoten gäbe. */
    record Graph(List<Node> nodes, List<Edge> edges, boolean truncated) {
        public Graph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        static final Graph EMPTY = new Graph(List.of(), List.of(), false);

        Node centre() {
            return nodes.isEmpty() ? null : nodes.get(0);
        }
    }

    /** Beziehungen des Core, die den Begriff (Suchwert) an einem Ende haben. */
    @FunctionalInterface
    interface RelationFetch {
        /** @param proposals ob auch vorgeschlagene, sonst nur bestätigte */
        List<TermRelation> touching(String normalized, boolean proposals) throws Exception;
    }

    /** Der Graph aus den Beziehungen zwischen Begriffen: Begriffe als Knoten, Beziehungen als Kanten. */
    static GraphSource ofRelations(RelationFetch fetch) {
        Objects.requireNonNull(fetch, "fetch");
        return (start, depth, proposals) -> {
            String centre = TermText.normalize(start);
            if (centre.isEmpty()) {
                return Graph.EMPTY;
            }
            Map<String, Node> nodes = new LinkedHashMap<>();
            nodes.put(centre, new Node(centre, start.trim(), 0, null));
            Map<String, Edge> edges = new LinkedHashMap<>();
            boolean truncated = false;
            List<String> frontier = List.of(centre);
            for (int level = 1; level <= Math.max(1, Math.min(2, depth)); level++) {
                List<String> next = new ArrayList<>();
                for (String key : frontier) {
                    for (TermRelation relation : fetch.touching(key, proposals)) {
                        boolean confirmed = relation.status() == ReviewStatus.CONFIRMED;
                        if (relation.status() == ReviewStatus.REJECTED || !proposals && !confirmed
                                || !relation.touches(key)) {
                            continue;
                        }
                        if (level == 1 && TermText.normalize(relation.fromTerm()).equals(centre)) {
                            // Die Mitte heißt, wie der Core sie schreibt, nicht wie eingetippt.
                            nodes.put(centre, new Node(centre, relation.fromTerm(), 0, null));
                        }
                        String other = relation.other(key);
                        String otherKey = TermText.normalize(other);
                        if (!nodes.containsKey(otherKey)) {
                            if (nodes.size() >= MAX_NODES) {
                                truncated = true;
                                continue;
                            }
                            nodes.put(otherKey, new Node(otherKey, other, level, key));
                            next.add(otherKey);
                        }
                        edges.putIfAbsent(relation.id(), new Edge(TermText.normalize(relation.fromTerm()),
                                TermText.normalize(relation.toTerm()), relation.type().name(), confirmed,
                                relation.type().directed()));
                    }
                }
                frontier = next;
            }
            List<Edge> shown = edges.values().stream()
                    .filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to())).toList();
            return new Graph(new ArrayList<>(nodes.values()), shown, truncated);
        };
    }
}
