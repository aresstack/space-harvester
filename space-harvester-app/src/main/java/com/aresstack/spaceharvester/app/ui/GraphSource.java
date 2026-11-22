package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Woher der Graph unter Analysen seine Knoten und Kanten nimmt: zu einem Startbegriff dessen Nachbarschaft bis
 * zur gewünschten Tiefe. Heute liefern sie die Beziehungen zwischen Begriffen ({@link #ofRelations}); ein späterer,
 * dauerhafter Wissensgraph im Core (#499) tritt hinter dieselbe Schnittstelle. Die Arbeit läuft außerhalb des EDT.
 */
public interface GraphSource {

    /** Höchstens so viele Knoten zeigt ein Graph; mehr meldet {@link Graph#truncated()}. */
    int MAX_NODES = 40;

    /**
     * @param start     Startbegriff in der Mitte, wie eingegeben
     * @param depth     1 oder 2: Nachbarn bzw. auch deren Nachbarn
     * @param proposals ob auch vorgeschlagene Beziehungen Kanten werden, sonst nur bestätigte
     */
    Graph around(String start, int depth, boolean proposals) throws Exception;

    /**
     * Wie {@link #around(String, int, boolean)}; {@code cancelled} meldet, dass die Anfrage überholt ist, und eine
     * Quelle, die lange rechnet, bricht dann ab ({@link java.util.concurrent.CancellationException}).
     */
    default Graph around(String start, int depth, boolean proposals, java.util.function.BooleanSupplier cancelled)
            throws Exception {
        return around(start, depth, proposals, false, cancelled);
    }

    /**
     * Wie {@link #around(String, int, boolean, java.util.function.BooleanSupplier)}, mit „nur geprüft“ (Paket W2); nur
     * die Fachsicht wertet es aus.
     */
    default Graph around(String start, int depth, boolean proposals, boolean checkedOnly,
                         java.util.function.BooleanSupplier cancelled) throws Exception {
        return around(start, depth, proposals);
    }

    /**
     * Ein Begriff im Graphen.
     *
     * @param key    Suchwert, eindeutig im Graphen
     * @param label  Wortlaut
     * @param depth  Abstand zur Mitte: 0 die Mitte, 1 Nachbar, 2 Nachbar eines Nachbarn
     * @param parent Schlüssel des Knotens, über den er gefunden wurde; {@code null} für die Mitte
     */
    record Node(String key, String label, int depth, String parent, boolean synonym) {
        public Node {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
        }

        /** Ein Begriff, kein Synonym der Fachsicht. */
        Node(String key, String label, int depth, String parent) {
            this(key, label, depth, parent, false);
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
     * @param style     wie die Kante gezeichnet wird
     * @param label     Beschriftung der Fachsicht („Grundform · N“) oder {@code null} für die Art der Beziehung
     * @param evidence  Belege einer Verb-Kante der Fachsicht, sonst leer
     * @param trust     Vertrauen einer Verb-Kante (Paket W2), sonst {@code null}
     * @param differs   ob ein Beleg der Verb-Kante abweicht und keiner geprüft ist
     * @param tip       Tooltip der Kante oder {@code null}
     * @param qualifiers Verneinung, Modalverb und Zeitbezug der Aussage einer Verb-Kante (Paket W9), sonst schlicht
     * @param hidden    beim Bogen „+ n weitere“ die Aussagen, für die er steht (Paket W9), sonst leer
     */
    record Edge(String from, String to, String type, boolean confirmed, boolean directed, Style style, String label,
                List<DomainTermGraph.Evidence> evidence, DomainTermGraph.Trust trust, boolean differs, String tip,
                DomainTermGraph.Qualifiers qualifiers, List<Edge> hidden) {

        public Edge {
            style = style == null ? Style.RELATION : style;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            qualifiers = qualifiers == null ? DomainTermGraph.Qualifiers.PLAIN : qualifiers;
            hidden = hidden == null ? List.of() : List.copyOf(hidden);
        }

        /** Eine Kante mit schlichter Aussage. */
        Edge(String from, String to, String type, boolean confirmed, boolean directed, Style style, String label,
             List<DomainTermGraph.Evidence> evidence, DomainTermGraph.Trust trust, boolean differs, String tip) {
            this(from, to, type, confirmed, directed, style, label, evidence, trust, differs, tip, null, null);
        }

        /** Der Bogen „+ n weitere“ (Paket W9): steht für die Aussagen in {@link #hidden}. */
        boolean bundle() {
            return !hidden.isEmpty();
        }

        /** Der Wortlaut der Aussage ohne Zahl und „≠“, etwa „nicht prüfen“; sonst die Beschriftung. */
        String phrase() {
            return label == null ? "" : label.replaceFirst("( ≠)? · \\d+$", "");
        }

        /** Ohne Vertrauen (Beziehungen, Synonyme). */
        Edge(String from, String to, String type, boolean confirmed, boolean directed, Style style, String label,
             List<DomainTermGraph.Evidence> evidence) {
            this(from, to, type, confirmed, directed, style, label, evidence, null, false, null);
        }

        /** Eine Beziehung zwischen Begriffen (wie vor der Fachsicht). */
        Edge(String from, String to, String type, boolean confirmed, boolean directed) {
            this(from, to, type, confirmed, directed, Style.RELATION, null, List.of());
        }
    }

    /** Zeichenart einer Kante. */
    enum Style {
        /** Beziehung zwischen Begriffen: Farbe je Art, beschriftet mit der Art. */
        RELATION,
        /** Verb der Fachsicht: Pfeil mit Beschriftung „Grundform · N“. */
        VERB,
        /** Synonym der Fachsicht: dünne Linie ohne Pfeil und ohne Beschriftung. */
        SYNONYM
    }

    /**
     * Knoten (die Mitte zuerst) und Kanten; {@code truncated}, wenn es mehr als {@link #MAX_NODES} Knoten gäbe.
     * {@code note} ergänzt die Statuszeile (Fachsicht: Ablage, ausgelassene veraltete Aussagen) oder ist leer;
     * {@code empty} erklärt einen leeren Graphen oder ist {@code null}.
     */
    record Graph(List<Node> nodes, List<Edge> edges, boolean truncated, String note, String empty) {
        public Graph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            note = note == null ? "" : note;
        }

        Graph(List<Node> nodes, List<Edge> edges, boolean truncated) {
            this(nodes, edges, truncated, "", null);
        }

        public static final Graph EMPTY = new Graph(List.of(), List.of(), false);

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

    /** Die Fachsicht des Core zu einem Startbegriff und einer Tiefe (Paket W1), auf Wunsch nur Geprüftes (W2). */
    @FunctionalInterface
    interface DomainFetch {
        DomainTermGraph.View around(String start, int depth, boolean checkedOnly,
                                    java.util.function.BooleanSupplier cancelled) throws Exception;
    }

    /**
     * Die Fachsicht (Paket W1): bestätigte Fachbegriffe und Synonyme als Knoten, Verben der Aussagen als Kanten. Nimmt
     * keine Vorschläge auf; der Schalter „Vorschläge zeigen“ wirkt hier nicht.
     *
     * @param analyzerName lesbarer Name einer Analysator-Kennung für die Statuszeile
     */
    static GraphSource ofDomainTerms(DomainFetch fetch, java.util.function.Function<String, String> analyzerName) {
        Objects.requireNonNull(fetch, "fetch");
        Objects.requireNonNull(analyzerName, "analyzerName");
        return new GraphSource() {
            @Override
            public Graph around(String start, int depth, boolean proposals) throws Exception {
                return around(start, depth, proposals, false, () -> false);
            }

            @Override
            public Graph around(String start, int depth, boolean proposals, boolean checkedOnly,
                                java.util.function.BooleanSupplier cancelled) throws Exception {
                return domainGraph(fetch.around(start, Math.max(1, Math.min(2, depth)), checkedOnly, cancelled),
                        start, analyzerName);
            }
        };
    }

    /**
     * Eine fertige Fachsicht des Core als zeichenbarer Graph, wie {@link #ofDomainTerms} sie liefert; auch die
     * seitenweite ({@code DomainTermGraph.onPages}, Paket W4b) mit {@code start} {@code null}.
     */
    static Graph ofDomainView(DomainTermGraph.View view, String start,
                              java.util.function.Function<String, String> analyzerName) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(analyzerName, "analyzerName");
        return domainGraph(view, start == null ? "" : start, analyzerName);
    }

    private static Graph domainGraph(DomainTermGraph.View view, String start,
                                     java.util.function.Function<String, String> analyzerName) {
        if (view.outcome() == DomainTermGraph.Outcome.NOT_A_TERM) {
            return new Graph(List.of(), List.of(), false, "", "„" + start.trim() + "“ " + NOT_A_TERM);
        }
        if (view.outcome() == DomainTermGraph.Outcome.EMPTY_START) {
            return Graph.EMPTY;
        }
        List<Node> nodes = new ArrayList<>();
        for (DomainTermGraph.Node node : view.nodes()) {
            nodes.add(new Node(node.key(), node.label(), node.depth(), node.parent(),
                    node.kind() == DomainTermGraph.NodeKind.SYNONYM));
        }
        Map<String, String> labels = new java.util.HashMap<>();
        view.nodes().forEach(node -> labels.put(node.key(), node.label()));
        // Eine Kante je Aussage (Paket W9); je Knotenpaar und Richtung höchstens MAX_ARCS Bögen.
        Map<String, List<DomainTermGraph.Edge>> directions = new LinkedHashMap<>();
        for (DomainTermGraph.Edge edge : view.edges()) {
            if (edge.kind() == DomainTermGraph.EdgeKind.VERB) {
                directions.computeIfAbsent(edge.from() + "\u0000" + edge.to(), unused -> new ArrayList<>()).add(edge);
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (DomainTermGraph.Edge edge : view.edges()) {
            if (edge.kind() == DomainTermGraph.EdgeKind.SYNONYM) {
                edges.add(new Edge(edge.from(), edge.to(), "SYNONYM", true, false, Style.SYNONYM, null,
                        List.of()));
                continue;
            }
            List<DomainTermGraph.Edge> same = directions.remove(edge.from() + "\u0000" + edge.to());
            if (same != null) {
                edges.addAll(arcs(same, labels));
            }
        }
        List<String> notes = new ArrayList<>();
        // Immer die Ablage nennen, aus der gelesen wurde, auch ohne Kante (Auflage Architekt).
        List<String> names = view.analyzers().stream().map(analyzerName).filter(Objects::nonNull).distinct().sorted()
                .toList();
        if (names.isEmpty() && view.analyzer() != null) {
            String name = analyzerName.apply(view.analyzer()
                    == com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL
                    ? com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer.ID
                    : com.aresstack.spaceharvester.app.nlp.MaltStatementAnalyzer.ID);
            names = name == null ? List.of() : List.of(name);
        }
        if (!names.isEmpty()) {
            notes.add("Aussagen aus " + String.join(", ", names));
        }
        if (view.skippedStale() > 0) {
            notes.add(view.skippedStale() + (view.skippedStale() == 1 ? " veraltete Aussage" : " veraltete Aussagen")
                    + " ausgelassen");
        }
        return new Graph(nodes, edges, view.truncated(), String.join(" · ", notes), null);
    }

    /** Stufe einer Verb-Kante in Worten (Linienart, Tooltip). */
    static String trustText(DomainTermGraph.Trust trust, boolean differs) {
        if (differs) {
            return TRUST_DIFFERS;
        }
        return switch (trust == null ? DomainTermGraph.Trust.UNGEPRUEFT : trust) {
            case GEPRUEFT -> TRUST_CHECKED;
            case EINIG -> TRUST_AGREED;
            case UNGEPRUEFT, ABWEICHEND -> TRUST_UNCHECKED;
        };
    }

    /** So viele Bögen zeigt die Fachsicht höchstens je Knotenpaar und Richtung (UX W9). */
    int MAX_ARCS = 3;

    /**
     * Die Bögen der Aussagen eines Knotenpaars in einer Richtung, die mit den meisten Belegen zuerst: höchstens
     * {@link #MAX_ARCS}; gibt es mehr, zeigen die zwei stärksten ihre Kante und der dritte Bogen „+ n weitere“.
     */
    private static List<Edge> arcs(List<DomainTermGraph.Edge> same, Map<String, String> labels) {
        List<DomainTermGraph.Edge> sorted = new ArrayList<>(same);
        sorted.sort(java.util.Comparator.comparingInt(DomainTermGraph.Edge::count).reversed());
        List<Edge> arcs = new ArrayList<>();
        int shown = sorted.size() > MAX_ARCS ? MAX_ARCS - 1 : sorted.size();
        for (int i = 0; i < shown; i++) {
            arcs.add(verbEdge(sorted.get(i), labels));
        }
        if (shown < sorted.size()) {
            List<Edge> rest = new ArrayList<>();
            List<DomainTermGraph.Evidence> evidence = new ArrayList<>();
            int count = 0;
            for (DomainTermGraph.Edge edge : sorted.subList(shown, sorted.size())) {
                rest.add(verbEdge(edge, labels));
                evidence.addAll(edge.evidence());
                count += edge.count();
            }
            DomainTermGraph.Edge first = sorted.getFirst();
            arcs.add(new Edge(first.from(), first.to(), "VERB", true, true, Style.VERB, moreText(rest.size()), evidence,
                    null, false, labelOf(labels, first.from()) + " – " + rest.size() + " weitere Aussagen – "
                    + labelOf(labels, first.to()) + " · " + Texts.count(count, "Beleg", "Belege"), null, rest));
        }
        return arcs;
    }

    /** Eine Aussage als Verb-Kante: „nicht prüfen · 2“, Tooltip „Plan – nicht prüfen – Ziel · 2 Belege“ (UX W9). */
    private static Edge verbEdge(DomainTermGraph.Edge edge, Map<String, String> labels) {
        String phrase = edge.phrase();
        return new Edge(edge.from(), edge.to(), "VERB", true, true, Style.VERB,
                phrase + (edge.differs() ? " ≠" : "") + " · " + edge.count(), edge.evidence(), edge.trust(),
                edge.differs(), labelOf(labels, edge.from()) + " – " + phrase + " – " + labelOf(labels, edge.to())
                + " · " + Texts.count(edge.count(), "Beleg", "Belege"), edge.qualifiers(), null);
    }

    /** Beschriftung des Bogens, der für {@code n} weitere Aussagen steht. */
    static String moreText(int n) {
        return "+ " + n + " weitere";
    }

    private static String labelOf(Map<String, String> labels, String key) {
        return labels.getOrDefault(key, key);
    }

    /**
     * Genaue Stufe eines Belegs (Pille im Overlay, Tooltip der Kante): „von Hand bestätigt“, „vom Prüfschritt
     * bestätigt“, „Parser und KI einig“, „nur &lt;Analysator&gt;“ oder „abweichend“.
     */
    static String stage(DomainTermGraph.Evidence item, java.util.function.Function<String, String> analyzerName) {
        return switch (item.trust()) {
            case GEPRUEFT -> item.statement().review()
                    == com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review.CONFIRMED
                    ? STAGE_BY_HAND : STAGE_BY_CHECK;
            case EINIG -> STAGE_AGREED;
            case UNGEPRUEFT -> {
                String name = item.analyzerId() == null ? null : analyzerName.apply(item.analyzerId());
                yield "nur " + (name == null ? "Analysator" : name);
            }
            case ABWEICHEND -> STAGE_DIFFERS;
        };
    }

    /** Genaue Stufen je Beleg (Paket W2). */
    String STAGE_BY_HAND = "von Hand bestätigt";
    String STAGE_BY_CHECK = "vom Prüfschritt bestätigt";
    String STAGE_AGREED = "Parser und KI einig";
    String STAGE_DIFFERS = "abweichend";

    /** Stufen einer Kante in Worten (Paket W2). */
    String TRUST_CHECKED = "Geprüft";
    String TRUST_AGREED = "Parser und KI einig";
    String TRUST_UNCHECKED = "Ungeprüft";
    String TRUST_DIFFERS = "Abweichend";

    /** Hinweis, wenn der Startbegriff der Fachsicht kein bestätigter Fachbegriff ist. */
    String NOT_A_TERM = "ist kein bestätigter Fachbegriff und kein bestätigtes Synonym im Glossar.";
}
