package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fokussierte, begrenzte Teilgraphen des globalen Knowledge Graph für den Reiter „Analysen → Graph“ (#499
 * Abschnitt F, ADR 0024). Lädt nie den ganzen Graphen: Ausgangspunkt ist eine Seite, ein Knoten (Dokument, Erwähnung,
 * Aussage) oder ein Begriff; geliefert werden die direkten Nachbarn (ein Schritt) bis zu einer Knoten- und
 * Kantengrenze, mit Kennzeichen, ob gekürzt wurde. Weitere Nachbarschaften lädt die Oberfläche gezielt mit dem
 * nächsten Fokus nach.
 *
 * <p>Alles läuft über {@link KnowledgeGraph}, also mit denselben Rechten je Seite: Knoten, Kanten und Zähler
 * entstehen nur aus Seiten, die der Aufrufer lesen darf. Verbindungen zwischen Seiten sind Kanten zwischen
 * eigenständigen Erwähnungsknoten – {@link EdgeType#LEXICAL_CANDIDATE} (gleiche Grundform, unbestätigt) oder
 * {@link EdgeType#CONFIRMED_TERM_RELATION} (bestätigte Begriffsbeziehung) –, nie ein gemeinsamer Entitätsknoten.
 * Nichts hier rechnet oder analysiert.
 */
public class GraphNeighbourhood {

    /** Voreinstellung der Knotengrenze (UI-Projektion, konfigurierbar). */
    public static final int DEFAULT_MAX_NODES = 50;
    /** Voreinstellung der Kantengrenze. */
    public static final int DEFAULT_MAX_EDGES = 100;
    /** Obergrenze beider Grenzen. */
    public static final int MAX_LIMIT = 500;

    private final KnowledgeGraph graph;

    public GraphNeighbourhood(KnowledgeGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Art einer Kante im Teilgraphen. */
    public enum EdgeType {
        CONTAINS,
        ACTOR,
        AFFECTED_OBJECT,
        RECIPIENT,
        PREPOSITIONAL_OBJECT,
        INSTRUMENT,
        TIME,
        CIRCUMSTANCE,
        /** Nominaler Bezug; kein Besitz. */
        NOMINAL,
        /** Gleiche normalisierte Grundform auf einer anderen Seite: unbestätigter Kandidat, keine Identität. */
        LEXICAL_CANDIDATE,
        /** Bestätigte Begriffsbeziehung (ADR 0019) zu einer Erwähnung auf einer anderen Seite. */
        CONFIRMED_TERM_RELATION;

        /** Verbindung zwischen zwei Seiten (gestrichelt darzustellen). */
        public boolean crossPage() {
            return this == LEXICAL_CANDIDATE || this == CONFIRMED_TERM_RELATION;
        }

        /** Rolle einer Aussage. */
        public boolean role() {
            return this != CONTAINS && this != NOMINAL && !crossPage();
        }
    }

    /**
     * Ein Knoten.
     *
     * @param id    eindeutig über alle Seiten (wie {@link PageGraph.Node#id()})
     * @param page  Seite mit Stand
     * @param start Spanne im Lesetext oder -1 beim Dokument
     * @param core  bei Aussagen: Kernsicht
     */
    public record Node(String id, PageGraph.NodeKind kind, String label, String lemma, KnowledgeGraph.PageRef page,
                       int start, int end, boolean core) {

        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(page, "page");
        }
    }

    /**
     * Eine gerichtete Kante.
     *
     * @param origin   Herkunft ({@code STRUCTURE}, {@link ParsedStatements.Basis} oder bei Seitenverbindungen der
     *                 Begriff)
     * @param evidence Satz als {@code s<Satz>} oder {@code null}
     */
    public record Edge(String from, String to, EdgeType type, String origin, String relation, String marker,
                       String evidence, boolean certain) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Ein Teilgraph.
     *
     * @param focus     Ausgangsknoten oder {@code null} bei einem Begriff ohne Treffer
     * @param truncated ob Knoten oder Kanten wegen der Grenzen fehlen
     * @param hidden    Zahl der weggelassenen sichtbaren Knoten (bei Begriffen eine Untergrenze: Aussagen zu Treffern
     *                  jenseits der Grenze sind nicht mitgezählt)
     */
    public record Subgraph(String focus, List<Node> nodes, List<Edge> edges, boolean truncated, int hidden) {

        public Subgraph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public static Subgraph empty() {
            return new Subgraph(null, List.of(), List.of(), false, 0);
        }
    }

    /**
     * Grenzen und Filter.
     *
     * @param kinds        sichtbare Knotenarten
     * @param types        sichtbare Kantenarten
     * @param includeStale veraltete Seiten zeigen (gekennzeichnet)
     * @param sourceId     nur diese Quelle oder {@code null}
     * @param spaceKey     nur dieser Space oder {@code null}
     */
    public record Options(int maxNodes, int maxEdges, Set<PageGraph.NodeKind> kinds, Set<EdgeType> types,
                          boolean includeStale, String sourceId, String spaceKey) {

        public static final Options DEFAULT = new Options(DEFAULT_MAX_NODES, DEFAULT_MAX_EDGES,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(EdgeType.class), true, null, null);

        public Options {
            if (maxNodes < 1 || maxNodes > MAX_LIMIT || maxEdges < 0 || maxEdges > MAX_LIMIT) {
                throw new IllegalArgumentException("Grenzen zwischen 1 und " + MAX_LIMIT);
            }
            kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
            types = types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
        }
    }

    /**
     * Eine Seite als Fokus. Aussagen kommen mit ihren Beteiligten: erst jede Kernaussage samt Rollenkanten und
     * beteiligten Erwähnungen, dann die übrigen Aussagen ebenso, jeweils in Textreihenfolge. Ein Fünftel beider Grenzen
     * bleibt für Verbindungen zu anderen Seiten frei, solange es welche gibt. Was danach noch Platz hat: nominale
     * Bezüge und übrige Erwähnungen; Enthält-Kanten vom Dokument zu Erwähnungen nur für sonst unverbundene Erwähnungen.
     */
    public Subgraph page(AccessContext caller, PageKey key, Options options) {
        Optional<KnowledgeGraph.AnalysedPage> found = graph.page(caller, key);
        if (found.isEmpty() || !admitted(found.get().page(), options)) {
            return Subgraph.empty();
        }
        KnowledgeGraph.AnalysedPage page = found.get();
        PageGraph projection = page.graph();
        Map<String, PageGraph.Node> local = new LinkedHashMap<>();
        projection.nodes().forEach(node -> local.put(node.id(), node));
        PageGraph.Node document = projection.nodes().getFirst();
        Builder builder = new Builder(options);
        builder.node(node(page, document));
        // Platz nur freihalten, wenn nach den Filtern Verbindungen übrig bleiben.
        List<KnowledgeGraph.Link> links = crossPageAllowed(options) ? graph.links(caller, key).stream()
                .filter(link -> options.types().contains(type(link)) && admitted(link.target(), options))
                .toList() : List.of();
        if (!links.isEmpty()) {
            builder.reserve(Math.max(1, options.maxNodes() / 5), Math.max(1, options.maxEdges() / 5));
        }
        // 1. Aussagen mit ihren Beteiligten, Kern zuerst.
        List<PageGraph.Node> statements = new ArrayList<>(projection.nodes().stream()
                .filter(node -> node.kind() == PageGraph.NodeKind.STATEMENT).toList());
        statements.sort(Comparator.comparingInt((PageGraph.Node node) -> rank(page, node))
                .thenComparingInt(PageGraph.Node::start));
        statements(builder, page, projection, local, document.id(), statements);
        // 2. Verbindungen zu anderen Seiten.
        builder.release();
        candidates(caller, page, null, links, builder, mentionId -> local.containsKey(mentionId)
                && attach(builder, page, projection, document.id(), local.get(mentionId)));
        // 3. Nicht verbrauchter Platz geht zuerst an Aussagen, die in Schritt 1 keinen Platz fanden.
        statements(builder, page, projection, local, document.id(), statements);
        // 4. Nominale Bezüge (auch Ketten), dann übrige Erwähnungen.
        boolean grown = true;
        while (grown) {
            grown = false;
            for (PageGraph.Edge edge : projection.edges()) {
                if (edge.kind() != PageGraph.EdgeKind.NOMINAL || builder.has(edge.from()) == builder.has(edge.to())) {
                    continue;
                }
                String missing = builder.has(edge.from()) ? edge.to() : edge.from();
                grown |= builder.connect(edge(edge), node(page, local.get(missing)));
            }
        }
        for (PageGraph.Node mention : projection.nodes()) {
            if (mention.kind() == PageGraph.NodeKind.MENTION && !builder.connected(mention.id())) {
                attach(builder, page, projection, document.id(), mention);
            }
        }
        // 5. Kanten zwischen schon aufgenommenen Knoten nachtragen: Rollen zuerst, dann nominale Bezüge.
        for (boolean roles : new boolean[] {true, false}) {
            for (PageGraph.Edge edge : projection.edges()) {
                Edge mapped = edge(edge);
                boolean wanted = roles ? mapped.type().role() : mapped.type() == EdgeType.NOMINAL;
                if (wanted && builder.has(edge.from()) && builder.has(edge.to())) {
                    builder.connect(mapped, node(page, local.get(edge.to())));
                }
            }
        }
        return builder.build(document.id());
    }

    /** Aussagen in der gegebenen Reihenfolge, jede mit ihren Rollenkanten und Beteiligten, soweit Platz ist. */
    private static void statements(Builder builder, KnowledgeGraph.AnalysedPage page, PageGraph projection,
                                   Map<String, PageGraph.Node> local, String document,
                                   List<PageGraph.Node> statements) {
        for (PageGraph.Node statement : statements) {
            if (!attach(builder, page, projection, document, statement)) {
                continue;
            }
            for (PageGraph.Edge role : projection.edgesOf(statement.id())) {
                if (role.from().equals(statement.id()) && edge(role).type().role()) {
                    builder.connect(edge(role), node(page, local.get(role.to())));
                }
            }
        }
    }

    private static EdgeType type(KnowledgeGraph.Link link) {
        return link.match() == KnowledgeGraph.Match.LEXICAL ? EdgeType.LEXICAL_CANDIDATE
                : EdgeType.CONFIRMED_TERM_RELATION;
    }

    /**
     * Ein Knoten als Fokus mit seinen direkten Nachbarn: Aussage → Beteiligte und Dokument; Erwähnung → Aussagen, an
     * denen sie beteiligt ist, nominale Bezüge, Dokument und Kandidaten anderer Seiten; Dokument wie {@link #page}. Ein
     * Nachbar kommt nur mit einer Kante, die die Filter zeigen.
     */
    public Subgraph node(AccessContext caller, String nodeId, Options options) {
        Optional<PageKey> key = pageOf(nodeId);
        if (key.isEmpty()) {
            return Subgraph.empty();
        }
        Optional<KnowledgeGraph.AnalysedPage> page = graph.page(caller, key.get());
        if (page.isEmpty() || !admitted(page.get().page(), options)) {
            return Subgraph.empty();
        }
        PageGraph projection = page.get().graph();
        Map<String, PageGraph.Node> nodes = new LinkedHashMap<>();
        projection.nodes().forEach(node -> nodes.put(node.id(), node));
        PageGraph.Node focus = nodes.get(nodeId);
        if (focus == null) {
            return Subgraph.empty(); // andere Fassung oder unbekannt: nichts verraten
        }
        if (focus.kind() == PageGraph.NodeKind.DOCUMENT) {
            return page(caller, key.get(), options);
        }
        Builder builder = new Builder(options);
        builder.node(node(page.get(), focus));
        for (PageGraph.Edge edge : projection.edgesOf(nodeId)) {
            String other = edge.from().equals(nodeId) ? edge.to() : edge.from();
            builder.connect(edge(edge), node(page.get(), nodes.get(other)));
        }
        if (focus.kind() == PageGraph.NodeKind.MENTION && crossPageAllowed(options)) {
            candidates(caller, page.get(), nodeId, graph.links(caller, key.get()), builder, builder::has);
        }
        return builder.build(nodeId);
    }

    /**
     * Ein Begriff als Fokus: passende Erwähnungen auf sichtbaren Seiten, die die Filter zulassen, mit ihrer Umgebung in
     * der Seitenprojektion ({@link PageGraph}, Standardansicht): die Aussagen, an denen sie beteiligt sind, samt deren
     * übrigen Beteiligten, und ihre nominalen Bezüge. So steht ein Begriff nicht allein, wenn er nur über einen
     * nominalen Bezug oder als Mitbeteiligter vorkommt. Quelle, Space und „veraltet“ gelten schon bei der Suche, damit
     * die Grenze nur Treffer im gewählten Umfang zählt.
     */
    public Subgraph term(AccessContext caller, String term, Options options) {
        KnowledgeGraph.Search search = graph.search(caller, term,
                Math.min(options.maxNodes(), KnowledgeGraph.MAX_LIMIT), page -> admitted(page, options));
        Builder builder = new Builder(options);
        Map<PageKey, Optional<Projection>> projections = new LinkedHashMap<>();
        String focus = null;
        for (KnowledgeGraph.Hit hit : search.hits()) {
            ParsedStatements.Mention mention = hit.mention();
            if (!builder.node(new Node(mention.id(), PageGraph.NodeKind.MENTION, mention.text(), mention.lemma(),
                    hit.page(), mention.start(), mention.end(), false))) {
                continue;
            }
            focus = focus == null ? mention.id() : focus;
            Optional<Projection> projection = projections.computeIfAbsent(hit.page().key(),
                    key -> graph.page(caller, key).map(Projection::new));
            if (projection.isEmpty()) {
                continue;
            }
            Projection here = projection.get();
            for (PageGraph.Edge edge : here.graph.edgesOf(mention.id())) {
                PageGraph.Node other = here.nodes.get(edge.from().equals(mention.id()) ? edge.to() : edge.from());
                if (other == null || other.kind() == PageGraph.NodeKind.DOCUMENT) {
                    continue;
                }
                builder.connect(edge(edge), node(here.page, other));
                if (other.kind() == PageGraph.NodeKind.STATEMENT && builder.has(other.id())) {
                    // Die übrigen Beteiligten derselben Aussage: „Anwendungen nutzen LDAP“, nicht nur „nutzen“.
                    for (PageGraph.Edge role : here.graph.edgesOf(other.id())) {
                        PageGraph.Node participant = here.nodes.get(role.from().equals(other.id()) ? role.to()
                                : role.from());
                        if (participant != null && participant.kind() != PageGraph.NodeKind.DOCUMENT) {
                            builder.connect(edge(role), node(here.page, participant));
                        }
                    }
                }
            }
        }
        builder.extraHidden = Math.max(0, search.total() - search.hits().size());
        return builder.build(focus);
    }

    /** Die Standardprojektion einer Seite mit Knoten nach ID, einmal je Seite einer Begriffssuche. */
    private static final class Projection {

        private final KnowledgeGraph.AnalysedPage page;
        private final PageGraph graph;
        private final Map<String, PageGraph.Node> nodes = new LinkedHashMap<>();

        private Projection(KnowledgeGraph.AnalysedPage page) {
            this.page = page;
            this.graph = page.graph();
            graph.nodes().forEach(node -> nodes.put(node.id(), node));
        }
    }

    /**
     * Kandidaten-Kanten von Erwähnungen dieser Seite (oder nur von {@code mentionId}) zu anderen Seiten.
     * {@code source} stellt die Ausgangserwähnung bereit (fügt sie bei Bedarf hinzu) und meldet, ob sie im Teilgraphen
     * ist; nur dann kommen Kante und Zielknoten hinzu.
     */
    private void candidates(AccessContext caller, KnowledgeGraph.AnalysedPage origin, String mentionId,
                            List<KnowledgeGraph.Link> links, Builder builder,
                            java.util.function.Predicate<String> source) {
        for (KnowledgeGraph.Link link : links) {
            EdgeType type = type(link);
            if (!builder.options.types().contains(type) || !admitted(link.target(), builder.options)
                    || mentionId != null && !link.mentions().contains(mentionId)) {
                continue;
            }
            Optional<KnowledgeGraph.AnalysedPage> target = graph.page(caller, link.target().key());
            if (target.isEmpty()) {
                continue;
            }
            Map<String, ParsedStatements.Mention> there = new LinkedHashMap<>();
            target.get().analysis().result().mentions().forEach(mention -> there.put(mention.id(), mention));
            List<String> from = mentionId != null ? List.of(mentionId) : link.mentions();
            for (String targetId : link.targetMentions()) {
                ParsedStatements.Mention mention = there.get(targetId);
                if (mention == null) {
                    continue;
                }
                Node targetNode = new Node(mention.id(), PageGraph.NodeKind.MENTION, mention.text(),
                        mention.lemma(), target.get().page(), mention.start(), mention.end(), false);
                for (String start : from) {
                    if (source.test(start)) {
                        builder.connect(new Edge(start, targetId, type, link.term(), null, null, null, false),
                                targetNode);
                    } else {
                        builder.omit(targetNode); // Ausgangserwähnung ohne Platz: Ziel fehlt ebenso
                    }
                }
            }
        }
    }

    private static boolean crossPageAllowed(Options options) {
        return options.kinds().contains(PageGraph.NodeKind.MENTION)
                && (options.types().contains(EdgeType.LEXICAL_CANDIDATE)
                || options.types().contains(EdgeType.CONFIRMED_TERM_RELATION));
    }

    /**
     * Nimmt einen Knoten der Seite mit seiner Enthält-Kante vom Dokument auf; zeigen die Filter Dokument oder
     * Enthält-Kanten nicht, den Knoten allein.
     */
    private static boolean attach(Builder builder, KnowledgeGraph.AnalysedPage page, PageGraph projection,
                                  String document, PageGraph.Node node) {
        PageGraph.Edge contains = containsEdge(projection, document, node.id());
        if (contains != null && builder.has(document) && builder.options.types().contains(EdgeType.CONTAINS)) {
            return builder.connect(edge(contains), node(page, node));
        }
        return builder.node(node(page, node));
    }

    private static PageGraph.Edge containsEdge(PageGraph projection, String document, String nodeId) {
        for (PageGraph.Edge edge : projection.edgesOf(nodeId)) {
            if (edge.kind() == PageGraph.EdgeKind.CONTAINS && edge.from().equals(document)) {
                return edge;
            }
        }
        return null;
    }

    private static boolean admitted(KnowledgeGraph.PageRef page, Options options) {
        if (page.state() == PageStatements.State.STALE && !options.includeStale()) {
            return false;
        }
        if (options.sourceId() != null && !options.sourceId().equals(page.key().sourceId())) {
            return false;
        }
        return options.spaceKey() == null || options.spaceKey().equals(page.spaceKey());
    }

    private static int rank(KnowledgeGraph.AnalysedPage page, PageGraph.Node node) {
        if (node.kind() == PageGraph.NodeKind.STATEMENT) {
            return core(page, node.id()) ? 0 : 1;
        }
        return 2;
    }

    private static boolean core(KnowledgeGraph.AnalysedPage page, String statementId) {
        return page.analysis().result().statements().stream()
                .anyMatch(statement -> statement.id().equals(statementId) && statement.core());
    }

    private static Node node(KnowledgeGraph.AnalysedPage page, PageGraph.Node node) {
        String label = node.kind() == PageGraph.NodeKind.DOCUMENT && node.label() == null
                ? page.page().key().pageId() : node.label();
        return new Node(node.id(), node.kind(), label, node.lemma(), page.page(), node.start(), node.end(),
                node.kind() == PageGraph.NodeKind.STATEMENT && core(page, node.id()));
    }

    private static Edge edge(PageGraph.Edge edge) {
        return new Edge(edge.from(), edge.to(), EdgeType.valueOf(edge.kind().name()), edge.origin(), edge.relation(),
                edge.marker(), edge.evidence(), edge.certain());
    }

    /**
     * Die Seite eines Knotens aus seiner ID ({@code quelle/seite@fassung} bzw. {@code …#s1.t2}); leer, wenn die ID
     * nicht so aufgebaut ist.
     */
    static Optional<PageKey> pageOf(String nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        int hash = nodeId.indexOf('#');
        String document = hash < 0 ? nodeId : nodeId.substring(0, hash);
        int at = document.lastIndexOf('@');
        int slash = document.indexOf('/');
        if (at < 0 || slash <= 0 || slash >= at - 1) {
            return Optional.empty();
        }
        return Optional.of(new PageKey(document.substring(0, slash), document.substring(slash + 1, at)));
    }

    /**
     * Sammelt Knoten und Kanten bis zu den Grenzen; Kanten nur zwischen aufgenommenen Knoten. Jeder weggelassene
     * sichtbare Knoten zählt einmal, auch wenn er mehrfach angeboten wird.
     */
    private static final class Builder {

        private final Options options;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Edge> edges = new LinkedHashMap<>();
        private final Set<String> omitted = new java.util.HashSet<>();
        private final Set<String> linked = new java.util.HashSet<>();
        private int reservedNodes;
        private int reservedEdges;
        private int extraHidden;
        private boolean truncated;

        Builder(Options options) {
            this.options = options;
        }

        /** Hält Platz für eine spätere Phase frei, bis {@link #release()}. */
        void reserve(int nodes, int edges) {
            reservedNodes = Math.min(nodes, options.maxNodes() - 1);
            reservedEdges = Math.min(edges, options.maxEdges());
        }

        void release() {
            reservedNodes = 0;
            reservedEdges = 0;
        }

        boolean has(String id) {
            return nodes.containsKey(id);
        }

        /** Ob der Knoten schon an einer Kante hängt. */
        boolean connected(String id) {
            return linked.contains(id);
        }

        /** Ein sichtbarer Knoten, der aus Platzgründen gar nicht erst angeboten wird, zählt als weggelassen. */
        void omit(Node node) {
            if (node != null && options.kinds().contains(node.kind()) && !nodes.containsKey(node.id())) {
                omitted.add(node.id());
                truncated = true;
            }
        }

        /** @return ob der Knoten (jetzt oder schon) im Teilgraphen ist */
        boolean node(Node node) {
            if (node == null || !options.kinds().contains(node.kind())) {
                return false;
            }
            if (nodes.containsKey(node.id())) {
                return true;
            }
            if (nodes.size() >= options.maxNodes() - reservedNodes) {
                omitted.add(node.id());
                truncated = true;
                return false;
            }
            nodes.put(node.id(), node);
            return true;
        }

        /**
         * Nimmt {@code other} nur zusammen mit {@code edge} auf (das andere Ende muss schon da sein): ohne sichtbare
         * Kante kein Knoten, ohne Platz für die Kante auch nicht.
         *
         * @return ob {@code other} danach im Teilgraphen ist
         */
        boolean connect(Edge edge, Node other) {
            if (other == null || !options.types().contains(edge.type())
                    || !options.kinds().contains(other.kind())) {
                return false;
            }
            String anchor = edge.from().equals(other.id()) ? edge.to() : edge.from();
            if (!nodes.containsKey(anchor)) {
                return false;
            }
            String key = edge.from() + "|" + edge.to() + "|" + edge.type();
            if (edges.containsKey(key)) {
                return true;
            }
            if (edges.size() >= options.maxEdges() - reservedEdges) {
                truncated = true;
                if (!nodes.containsKey(other.id())) {
                    omitted.add(other.id());
                }
                return false;
            }
            if (!node(other)) {
                return false;
            }
            edges.put(key, edge);
            linked.add(edge.from());
            linked.add(edge.to());
            return true;
        }

        Subgraph build(String focus) {
            if (focus != null && !nodes.containsKey(focus)) {
                focus = null;
            }
            int hidden = (int) omitted.stream().filter(id -> !nodes.containsKey(id)).count() + extraHidden;
            return new Subgraph(focus, List.copyOf(nodes.values()), List.copyOf(edges.values()),
                    truncated || hidden > 0, hidden);
        }
    }
}
