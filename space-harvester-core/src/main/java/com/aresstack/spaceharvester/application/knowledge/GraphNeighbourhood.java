package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
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
    /**
     * Höchstens so viele Unterseiten mit Analyse gehen in einen Seitengraphen mit Unterseiten ({@link #pages}) ein, die
     * ersten nach Titel; die übrigen nennt {@link Stack#notConsidered()}. Mehr passt ohnehin nicht ins Bild: Selbst bei
     * der Obergrenze {@link #MAX_LIMIT} der Knoten bliebe für 200 Unterseiten kaum mehr als ihr Dokument. So bleiben
     * Projektion und Verbindungen auch unter einer Space-Wurzel mit Tausenden Unterseiten bei jedem Filterwechsel
     * begrenzt.
     */
    public static final int MAX_SUBPAGES = 200;

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
        /** Träger einer Zustandsaussage (ADR 0032). */
        BEARER,
        /** Nominaler Bezug; kein Besitz. */
        NOMINAL,
        /** Gleiche normalisierte Grundform auf einer anderen Seite: unbestätigter Kandidat, keine Identität. */
        LEXICAL_CANDIDATE,
        /** Bestätigte Begriffsbeziehung (ADR 0019) zu einer Erwähnung auf einer anderen Seite. */
        CONFIRMED_TERM_RELATION,
        /**
         * Dokument einer Seite → Dokument einer ihrer Unterseiten ({@link #pages}, „mit Unterseiten“): Struktur aus dem
         * Seitenbaum wie {@link #CONTAINS}, keine Rolle und keine Verbindung zwischen Erwähnungen.
         */
        SUBPAGE;

        /** Verbindung zwischen zwei Seiten (gestrichelt darzustellen). */
        public boolean crossPage() {
            return this == LEXICAL_CANDIDATE || this == CONFIRMED_TERM_RELATION;
        }

        /** Rolle einer Aussage. */
        public boolean role() {
            return this != CONTAINS && this != SUBPAGE && this != NOMINAL && !crossPage();
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
     * Eine Seite samt Unterseiten als ein Teilgraph ({@link #pages}).
     *
     * @param graph        Teilgraph über die ganze Menge; Grenzen, {@code truncated} und {@code hidden} gelten für die
     *                     ganze Menge, jeder Knoten trägt seine Herkunftsseite ({@link Node#page()})
     * @param missing      Seiten der Menge, die der Aufrufer lesen darf, die nicht gesperrt sind und die schlicht keine
     *                     (aktuelle oder veraltete) Analyse haben; nur Schlüssel, keine Titel; die Seite zuerst, dann
     *                     nach Quelle und Seiten-ID. Gesperrte Seiten und Seiten ohne Leserecht stehen weder hier noch
     *                     als Knoten im Graphen noch in einem Zähler.
     * @param skippedStale Seiten der Menge, die nur fehlen, weil sie veraltet sind und
     *                     {@link Options#includeStale()} aus ist
     * @param considered    wie viele Unterseiten (ohne die Seite selbst) in die Rechnung eingingen: lesbar, nicht
     *                      gesperrt und innerhalb von Quellen- und Seitengrenze ({@link Options#sourceId()},
     *                      {@link Options#pages()}), mit oder ohne Analyse, ohne die in {@code notConsidered}
     * @param notConsidered wie viele solcher Unterseiten mit Analyse über {@link #MAX_SUBPAGES} hinaus nicht
     *                      berücksichtigt wurden: weder als Knoten noch in {@code considered}
     */
    public record Stack(Subgraph graph, List<PageKey> missing, int skippedStale, int considered, int notConsidered) {

        public Stack {
            Objects.requireNonNull(graph, "graph");
            missing = List.copyOf(missing);
        }

        /** Ohne Unterseiten jenseits von {@link #MAX_SUBPAGES}. */
        public Stack(Subgraph graph, List<PageKey> missing, int skippedStale, int considered) {
            this(graph, missing, skippedStale, considered, 0);
        }

        /** Nur ein Teilgraph, ohne Unterseiten. */
        public static Stack of(Subgraph graph) {
            return new Stack(graph, List.of(), 0, 0, 0);
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
     * @param pages        nur diese Seiten samt ihren Dokumenten, etwa eine Seite samt Unterseiten aus der Auswahl im
     *                     Baum (ADR 0029), oder {@code null} ohne diese Grenze; gilt für Fokus, Ziele von Verbindungen
     *                     und die Begriffssuche gleich
     */
    public record Options(int maxNodes, int maxEdges, Set<PageGraph.NodeKind> kinds, Set<EdgeType> types,
                          boolean includeStale, String sourceId, String spaceKey, PageSet pages) {

        public static final Options DEFAULT = new Options(DEFAULT_MAX_NODES, DEFAULT_MAX_EDGES,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(EdgeType.class), true, null, null);

        public Options {
            if (maxNodes < 1 || maxNodes > MAX_LIMIT || maxEdges < 0 || maxEdges > MAX_LIMIT) {
                throw new IllegalArgumentException("Grenzen zwischen 1 und " + MAX_LIMIT);
            }
            kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
            types = types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
        }

        public Options(int maxNodes, int maxEdges, Set<PageGraph.NodeKind> kinds, Set<EdgeType> types,
                       boolean includeStale, String sourceId, String spaceKey) {
            this(maxNodes, maxEdges, kinds, types, includeStale, sourceId, spaceKey, null);
        }

        /** Dieselben Grenzen, nur auf diesen Seiten (ADR 0029); {@code null} hebt die Grenze auf. */
        public Options within(PageSet pages) {
            return new Options(maxNodes, maxEdges, kinds, types, includeStale, sourceId, spaceKey, pages);
        }
    }

    /**
     * Eine Seite als Fokus. Aussagen kommen mit ihren Beteiligten: erst jede Kernaussage samt Rollenkanten und
     * beteiligten Erwähnungen, dann die übrigen Aussagen ebenso, jeweils in Textreihenfolge. Ein Fünftel beider Grenzen
     * bleibt für Verbindungen zu anderen Seiten frei, solange es welche gibt. Was danach noch Platz hat: nominale
     * Bezüge und übrige Erwähnungen; Enthält-Kanten vom Dokument zu Erwähnungen nur für sonst unverbundene Erwähnungen.
     */
    public Subgraph page(AccessContext caller, PageKey key, Options options) {
        return page(caller, DocumentKey.page(key), options);
    }

    /** Wie oben für ein Dokument: Seite, Anhang oder enthaltene Datei (ADR 0026, Punkt 10). */
    public Subgraph page(AccessContext caller, DocumentKey key, Options options) {
        Optional<KnowledgeGraph.AnalysedPage> found = graph.page(caller, key);
        if (found.isEmpty() || !admitted(found.get().page(), options)) {
            return Subgraph.empty();
        }
        return collect(caller, new Projection(found.get()), List.of(), options);
    }

    /**
     * Eine Seite samt Unterseiten als ein Teilgraph (Checkbox „mit Unterseiten“): wie {@link #page}, nur über die
     * Menge aus Seite und Unterseiten, mit <em>einem</em> Satz Grenzen für die ganze Menge. Verbindungen zu anderen
     * Seiten bleiben in der Menge ({@link Options#within}); eine schon gesetzte Grenze {@link Options#pages()} schneidet
     * die Menge weiter. Reihenfolge, damit jede Seite zu Wort kommt: das Dokument der Seite, dann die Dokumente der
     * Unterseiten (nach Titel ohne Groß-/Kleinschreibung, dann Seiten-ID) je mit {@link EdgeType#SUBPAGE}-Kante; die
     * Kernaussagen der Seite, dann die der Unterseiten, dann ebenso die übrigen Aussagen; Verbindungen innerhalb der
     * Menge; was danach Platz hat wie bei {@link #page}, die Seite jeweils vor ihren Unterseiten. Vorab kommen nur
     * höchstens {@code max(1, maxNodes / 4)} Dokumente von Unterseiten; die übrigen folgen je vor den Aussagen ihrer
     * Unterseite, soweit Platz ist, sonst zählen sie als ausgeblendet. So bleibt auch bei vielen Unterseiten Platz für
     * die Kernaussagen der Seite. Von den Unterseiten mit Analyse gehen nur die ersten {@link #MAX_SUBPAGES} nach Titel
     * ein, die übrigen zählt {@link Stack#notConsidered()}. Die Verbindungen aller beteiligten Seiten entstehen in einem
     * Durchgang über den Graphen ({@link KnowledgeGraph#links(AccessContext, java.util.Collection,
     * java.util.function.Predicate)}), nicht in einem je Unterseite. Bestandteile einer Unterseite, deren Dokument
     * keinen Platz fand, kommen gar nicht erst: keine losen Aussagen oder Erwähnungen ohne ihre Seite.
     *
     * <p>Gesperrte Unterseiten ({@link PageSelection#locked()}) und Seiten, die der Aufrufer nicht lesen darf
     * ({@link KnowledgeGraph#readable}), erscheinen nirgends: weder als Knoten noch in {@link Stack#missing()} noch in
     * {@link Stack#considered()}. {@link Stack#missing()} nennt nur lesbare, nicht gesperrte Seiten ohne Analyse.
     * Veraltete Seiten zählen bei {@code includeStale = false} in {@link Stack#skippedStale()}; Seiten außerhalb der
     * Quellen- oder Seitengrenze fallen still weg. Mit {@code subpages} bleiben Verbindungen immer in der Menge, auch
     * wenn sie nur aus der Seite besteht (alle Unterseiten gesperrt): Eine gesperrte Seite kommt so auch nicht als Ziel
     * einer Verbindung herein. Mit {@code subpages = null} genau wie {@link #page}.
     *
     * @param subpages die Unterseiten aus {@code Holdings#descendants} mit den Rechten des Aufrufers (ohne die Seite
     *                 selbst, sonst wird sie übergangen) oder {@code null}
     */
    public Stack pages(AccessContext caller, PageKey page, PageSelection subpages, Options options) {
        Objects.requireNonNull(page, "page");
        List<PageKey> keys = keys(page, subpages);
        Options within = inSet(page, subpages, options);
        Options anyState = new Options(within.maxNodes(), within.maxEdges(), within.kinds(), within.types(), true,
                within.sourceId(), within.spaceKey(), within.pages());
        Projection head = null;
        List<KnowledgeGraph.AnalysedPage> analysed = new ArrayList<>();
        List<PageKey> missing = new ArrayList<>();
        int skippedStale = 0;
        int considered = 0;
        for (PageKey key : keys) {
            if (within.pages() != null && !within.pages().contains(key)
                    || within.sourceId() != null && !within.sourceId().equals(key.sourceId())) {
                continue;
            }
            Optional<KnowledgeGraph.AnalysedPage> found = graph.page(caller, key);
            if (found.isEmpty() && !graph.readable(caller, key, spaceOf(subpages, key))) {
                continue; // ohne Leserecht: weder Knoten noch „ohne Analyse“ noch gezählt
            }
            if (!key.equals(page)) {
                considered++;
            }
            if (found.isEmpty()) {
                missing.add(key);
            } else if (admitted(found.get().page(), within)) {
                if (key.equals(page)) {
                    head = new Projection(found.get());
                } else {
                    analysed.add(found.get());
                }
            } else if (found.get().page().state() == PageStatements.State.STALE
                    && admitted(found.get().page(), anyState)) {
                skippedStale++;
            }
        }
        analysed.sort(Comparator.comparing((KnowledgeGraph.AnalysedPage found) -> title(found.page()),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(found -> found.page().key().pageId())
                .thenComparing(found -> found.page().key().sourceId()));
        // Erst nach der Grenze projizieren: Die Projektion je Unterseite ist der teure Teil.
        int notConsidered = Math.max(0, analysed.size() - MAX_SUBPAGES);
        considered -= notConsidered;
        List<Projection> below = analysed.subList(0, analysed.size() - notConsidered).stream().map(Projection::new)
                .toList();
        if (head == null && below.isEmpty()) {
            return new Stack(Subgraph.empty(), missing, skippedStale, considered, notConsidered);
        }
        // Unterseiten jenseits der Grenze auch nicht als Ziele von Verbindungen: Sonst kämen ihre Erwähnungen ohne ihr
        // Dokument herein, obwohl sie als „nicht berücksichtigt“ zählen.
        Options inGraph = within;
        if (notConsidered > 0) {
            Set<PageKey> cut = new java.util.HashSet<>();
            analysed.subList(analysed.size() - notConsidered, analysed.size())
                    .forEach(found -> cut.add(found.page().key()));
            inGraph = within.within(PageSet.ofKeys(keys.stream()
                    .filter(key -> within.pages().contains(key) && !cut.contains(key)).toList()));
        }
        return new Stack(collect(caller, head, below, inGraph), missing, skippedStale, considered, notConsidered);
    }

    /**
     * Die Grenzen, mit denen {@link #pages} über Seite und Unterseiten rechnet: mit {@code subpages} nur die Seite
     * und ihre nicht gesperrten Unterseiten, geschnitten mit einer schon gesetzten Grenze {@link Options#pages()};
     * ohne {@code subpages} unverändert. Mit Unterseiten immer in der Menge, auch wenn nur die Seite bleibt (alle
     * Unterseiten gesperrt): Sonst kämen Erwähnungen gesperrter Unterseiten als Ziele von Verbindungen herein. Für
     * Erweiterungen ({@link #node}) in derselben Ansicht, damit auch sie in der Menge bleiben.
     */
    public static Options inSet(PageKey page, PageSelection subpages, Options options) {
        Objects.requireNonNull(page, "page");
        if (subpages == null) {
            return options;
        }
        List<PageKey> keys = keys(page, subpages);
        return options.within(PageSet.ofKeys(options.pages() == null ? keys
                : keys.stream().filter(options.pages()::contains).toList()));
    }

    /** Die Seite, dann ihre nicht gesperrten Unterseiten nach Quelle und Seiten-ID. */
    private static List<PageKey> keys(PageKey page, PageSelection subpages) {
        List<PageKey> keys = new ArrayList<>();
        keys.add(page);
        if (subpages != null) {
            subpages.pages().asMap().forEach((sourceId, pageIds) -> pageIds.stream().sorted()
                    .map(pageId -> new PageKey(sourceId, pageId))
                    .filter(key -> !key.equals(page) && !subpages.locked().contains(key))
                    .forEach(keys::add));
        }
        return keys;
    }

    /** Der Space einer Seite der Auswahl, wenn ihre Quelle dort genau einen hat; sonst {@code null}. */
    private static String spaceOf(PageSelection selection, PageKey key) {
        if (selection == null) {
            return null;
        }
        List<String> spaces = selection.spaces().stream().filter(space -> space.sourceId().equals(key.sourceId()))
                .map(SpaceSummary::spaceKey).distinct().toList();
        return spaces.size() == 1 ? spaces.getFirst() : null;
    }

    private static String title(KnowledgeGraph.PageRef page) {
        return page.title() == null ? page.key().pageId() : page.title();
    }

    /**
     * Baut den Teilgraphen einer Seite ({@code head}, darf fehlen) und ihrer Unterseiten mit einem gemeinsamen
     * {@link Builder}; ohne Unterseiten genau die Schritte von {@link #page}.
     */
    private Subgraph collect(AccessContext caller, Projection head, List<Projection> subpages, Options options) {
        List<Projection> parts = new ArrayList<>();
        if (head != null) {
            parts.add(head);
        }
        parts.addAll(subpages);
        Builder builder = new Builder(options);
        String focus = head == null ? null : head.document().id();
        if (head != null) {
            builder.node(node(head.page, head.document()));
        }
        // Dokumente der ersten Unterseiten vor allem anderen, je mit Unterseiten-Kante vom Dokument der Seite; nur ein
        // Viertel der Grenze, damit die Kernaussagen der Seite auch bei vielen Unterseiten Platz finden.
        for (Projection subpage : subpages.subList(0, Math.min(subpages.size(), Math.max(1, options.maxNodes() / 4)))) {
            subpageDocument(builder, focus, subpage);
        }
        // Platz nur freihalten, wenn nach den Filtern Verbindungen übrig bleiben. Die Verbindungen aller Seiten in einem
        // Durchgang über den Graphen, nicht einer je Unterseite.
        boolean linked = false;
        Map<DocumentKey, List<KnowledgeGraph.Link>> links = crossPageAllowed(options)
                ? graph.links(caller, parts.stream().map(part -> part.page.page().document()).toList(),
                        target -> admitted(target, options))
                : Map.of();
        for (Projection part : parts) {
            part.links = links.getOrDefault(part.page.page().document(), List.of()).stream()
                    .filter(link -> options.types().contains(type(link)))
                    .toList();
            linked |= !part.links.isEmpty();
        }
        if (linked) {
            builder.reserve(Math.max(1, options.maxNodes() / 5), Math.max(1, options.maxEdges() / 5));
        }
        // 1. Aussagen mit ihren Beteiligten, Kern zuerst: erst die Kernaussagen aller Seiten, dann die übrigen. Die
        // übrigen Dokumente der Unterseiten je vor deren Aussagen, soweit Platz ist (sonst ausgeblendet gezählt).
        for (boolean core : new boolean[] {true, false}) {
            for (Projection part : parts) {
                if (part != head) {
                    subpageDocument(builder, focus, part);
                }
                if (!placed(builder, part)) {
                    continue;
                }
                statements(builder, part, part.statements().stream()
                        .filter(statement -> core(part.page, statement.id()) == core).toList());
            }
        }
        // Ab hier nur noch Seiten, deren Dokument im Teilgraphen steht: Was eine Unterseite ohne Platz für ihr Dokument
        // sonst noch hätte, zählt als ausgeblendet, statt nach dem Freigeben als lose Insel zu erscheinen.
        List<Projection> placed = new ArrayList<>();
        Set<DocumentKey> unplaced = new java.util.HashSet<>();
        for (Projection part : parts) {
            if (placed(builder, part)) {
                placed.add(part);
            } else {
                unplaced.add(part.page.page().document());
                part.graph.nodes().forEach(node -> builder.omit(node(part.page, node)));
            }
        }
        java.util.function.Predicate<KnowledgeGraph.PageRef> target = page -> !unplaced.contains(page.document());
        // 2. Verbindungen zu anderen Seiten.
        builder.release();
        for (Projection part : placed) {
            candidates(caller, part.page, null, part.links, builder, mentionId -> part.nodes.containsKey(mentionId)
                    && attach(builder, part.page, part.graph, part.document().id(), part.nodes.get(mentionId)),
                    target);
        }
        // 3. Nicht verbrauchter Platz geht zuerst an Aussagen, die in Schritt 1 keinen Platz fanden.
        for (Projection part : placed) {
            statements(builder, part, part.statements());
        }
        // 4. Nominale Bezüge (auch Ketten), dann übrige Erwähnungen.
        for (Projection part : placed) {
            boolean grown = true;
            while (grown) {
                grown = false;
                for (PageGraph.Edge edge : part.graph.edges()) {
                    if (edge.kind() != PageGraph.EdgeKind.NOMINAL
                            || builder.has(edge.from()) == builder.has(edge.to())) {
                        continue;
                    }
                    String missing = builder.has(edge.from()) ? edge.to() : edge.from();
                    grown |= builder.connect(edge(edge), node(part.page, part.nodes.get(missing)));
                }
            }
        }
        for (Projection part : placed) {
            for (PageGraph.Node mention : part.graph.nodes()) {
                if (mention.kind() == PageGraph.NodeKind.MENTION && !builder.connected(mention.id())) {
                    attach(builder, part.page, part.graph, part.document().id(), mention);
                }
            }
        }
        // 5. Kanten zwischen schon aufgenommenen Knoten nachtragen: Rollen zuerst, dann nominale Bezüge.
        for (boolean roles : new boolean[] {true, false}) {
            for (Projection part : parts) {
                for (PageGraph.Edge edge : part.graph.edges()) {
                    Edge mapped = edge(edge);
                    boolean wanted = roles ? mapped.type().role() : mapped.type() == EdgeType.NOMINAL;
                    if (wanted && builder.has(edge.from()) && builder.has(edge.to())) {
                        builder.connect(mapped, node(part.page, part.nodes.get(edge.to())));
                    }
                }
            }
        }
        return builder.build(focus);
    }

    /**
     * Ob die Bestandteile der Seite in den Teilgraphen dürfen: Ihr Dokument steht darin, oder die Filter zeigen keine
     * Dokumente (dann wie bisher ohne Anker).
     */
    private static boolean placed(Builder builder, Projection part) {
        return !builder.options.kinds().contains(PageGraph.NodeKind.DOCUMENT) || builder.has(part.document().id());
    }

    /**
     * Das Dokument einer Unterseite mit {@link EdgeType#SUBPAGE}-Kante vom Dokument der Seite ({@code focus}); ohne
     * Seite oder ohne sichtbare Unterseiten-Kanten allein. Schon aufgenommen: nichts; ohne Platz zählt es als
     * ausgeblendet.
     */
    private static void subpageDocument(Builder builder, String focus, Projection subpage) {
        Node document = node(subpage.page, subpage.document());
        if (builder.has(document.id())) {
            return;
        }
        if (focus != null && builder.has(focus) && builder.options.types().contains(EdgeType.SUBPAGE)) {
            builder.connect(new Edge(focus, document.id(), EdgeType.SUBPAGE, "STRUCTURE", null, null, null, true),
                    document);
        } else {
            builder.node(document);
        }
    }

    /** Aussagen in der gegebenen Reihenfolge, jede mit ihren Rollenkanten und Beteiligten, soweit Platz ist. */
    private static void statements(Builder builder, Projection part, List<PageGraph.Node> statements) {
        for (PageGraph.Node statement : statements) {
            if (!attach(builder, part.page, part.graph, part.document().id(), statement)) {
                continue;
            }
            for (PageGraph.Edge role : part.graph.edgesOf(statement.id())) {
                if (role.from().equals(statement.id()) && edge(role).type().role()) {
                    builder.connect(edge(role), node(part.page, part.nodes.get(role.to())));
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
        Optional<String> document = documentOf(nodeId);
        Optional<KnowledgeGraph.AnalysedPage> page = document.isEmpty() ? graph.page(caller, key.get())
                : graph.document(caller, key.get(), document.get());
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
            return page(caller, page.get().page().document(), options);
        }
        Builder builder = new Builder(options);
        builder.node(node(page.get(), focus));
        for (PageGraph.Edge edge : projection.edgesOf(nodeId)) {
            String other = edge.from().equals(nodeId) ? edge.to() : edge.from();
            builder.connect(edge(edge), node(page.get(), nodes.get(other)));
        }
        if (focus.kind() == PageGraph.NodeKind.MENTION && crossPageAllowed(options)) {
            candidates(caller, page.get(), nodeId, graph.links(caller, page.get().page().document()), builder,
                    builder::has, target -> true);
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
        Map<DocumentKey, Optional<Projection>> projections = new LinkedHashMap<>();
        String focus = null;
        for (KnowledgeGraph.Hit hit : search.hits()) {
            ParsedStatements.Mention mention = hit.mention();
            if (!builder.node(new Node(mention.id(), PageGraph.NodeKind.MENTION, mention.text(), mention.lemma(),
                    hit.page(), mention.start(), mention.end(), false))) {
                continue;
            }
            focus = focus == null ? mention.id() : focus;
            Optional<Projection> projection = projections.computeIfAbsent(hit.page().document(),
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

    /**
     * Die Standardprojektion einer Seite mit Knoten nach ID, einmal je Seite einer Begriffssuche oder eines
     * Seitengraphen.
     */
    private static final class Projection {

        private final KnowledgeGraph.AnalysedPage page;
        private final PageGraph graph;
        private final Map<String, PageGraph.Node> nodes = new LinkedHashMap<>();
        /** Verbindungen zu anderen Seiten nach den Filtern; nur im Seitengraphen gesetzt. */
        private List<KnowledgeGraph.Link> links = List.of();
        private List<PageGraph.Node> statements;

        private Projection(KnowledgeGraph.AnalysedPage page) {
            this.page = page;
            this.graph = page.graph();
            graph.nodes().forEach(node -> nodes.put(node.id(), node));
        }

        PageGraph.Node document() {
            return graph.nodes().getFirst();
        }

        /** Die Aussagen, Kern zuerst, jeweils in Textreihenfolge. */
        List<PageGraph.Node> statements() {
            if (statements == null) {
                List<PageGraph.Node> sorted = new ArrayList<>(graph.nodes().stream()
                        .filter(node -> node.kind() == PageGraph.NodeKind.STATEMENT).toList());
                sorted.sort(Comparator.comparingInt((PageGraph.Node node) -> rank(page, node))
                        .thenComparingInt(PageGraph.Node::start));
                statements = List.copyOf(sorted);
            }
            return statements;
        }
    }

    /**
     * Kandidaten-Kanten von Erwähnungen dieser Seite (oder nur von {@code mentionId}) zu anderen Seiten.
     * {@code source} stellt die Ausgangserwähnung bereit (fügt sie bei Bedarf hinzu) und meldet, ob sie im Teilgraphen
     * ist; nur dann kommen Kante und Zielknoten hinzu. Ziele, die {@code targets} nicht zulässt (eine Unterseite der
     * Menge ohne Platz für ihr Dokument), zählen als ausgeblendet.
     */
    private void candidates(AccessContext caller, KnowledgeGraph.AnalysedPage origin, String mentionId,
                            List<KnowledgeGraph.Link> links, Builder builder,
                            java.util.function.Predicate<String> source,
                            java.util.function.Predicate<KnowledgeGraph.PageRef> targets) {
        for (KnowledgeGraph.Link link : links) {
            EdgeType type = type(link);
            if (!builder.options.types().contains(type) || !admitted(link.target(), builder.options)
                    || mentionId != null && !link.mentions().contains(mentionId)) {
                continue;
            }
            boolean allowed = targets.test(link.target());
            Optional<KnowledgeGraph.AnalysedPage> target = graph.page(caller, link.target().document());
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
                    if (allowed && source.test(start)) {
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
        if (options.pages() != null && !options.pages().contains(page.key())) {
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
    /**
     * Der Dokumentteil einer Knoten-ID ({@code ~<DocumentKey#fileName()>} hinter dem letzten {@code @}, siehe
     * {@link PageStatementAnalysis#documentId(DocumentKey, String)}); leer für Knoten einer Seite selbst.
     */
    static Optional<String> documentOf(String nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        int hash = nodeId.indexOf('#');
        String document = hash < 0 ? nodeId : nodeId.substring(0, hash);
        int at = document.lastIndexOf('@');
        int tilde = at < 0 ? -1 : document.indexOf(PageStatementAnalysis.DOCUMENT, at);
        return tilde < 0 || tilde == document.length() - 1 ? Optional.empty()
                : Optional.of(document.substring(tilde + 1));
    }

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
            if (edges.containsKey(key) || edge.type().crossPage()
                    && edges.containsKey(edge.to() + "|" + edge.from() + "|" + edge.type())) {
                return true; // eine Verbindung zwischen zwei Seiten der Menge nur einmal, nicht in beide Richtungen
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
