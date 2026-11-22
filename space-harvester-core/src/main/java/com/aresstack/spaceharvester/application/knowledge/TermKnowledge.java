package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * Begriffswissen für Chat und Suche (Paket W6): Was der Wissensgraph zu einem bestätigten Fachbegriff in einem Bereich
 * weiß, als knappe, begrenzte Antwort. Nur lesen, kein KI-Aufruf, nichts wird gespeichert. Die Antwort baut auf der
 * Begriffsgruppe der Fachsicht auf ({@code DomainTermGraph#group}, derselbe Aufbau wie {@link DomainTermGraph#around})
 * und zählt genau wie sie: Standardansicht, verworfene Aussagen nie ({@link CountedStatements}), veraltete Seiten
 * nicht, Vertrauensstufe je Aussage (W2).
 *
 * <p>Erzwungen im Kern, damit kein Aufrufer es vergessen kann:
 * <ul>
 *   <li><b>Bereich:</b> Gelesen werden nur Analysen der Seiten des Bereichs (samt ihren Dokumenten), höchstens
 *   {@link #MAX_PAGES}; ein größerer Bereich wird gekappt, nie ganz durchsucht, und {@link Limits} nennt, wie viele
 *   Seiten ausgelassen wurden. Rechte prüft {@link KnowledgeGraph#analysed} je Seite.</li>
 *   <li><b>KI-Freigabe (ADR 0028, ADR 0033 Punkt 8):</b> Jede Seite, deren Aussagen in der Antwort stehen, meldet der
 *   Kern an die {@link Delivery} des Aufrufers, bevor er die Antwort herausgibt. Gemeldet wird genau das, was geliefert
 *   wird, nicht der ganze Bereich. Wirft die Meldung, gibt es keine Antwort.</li>
 *   <li><b>Obergrenzen:</b> höchstens {@link #MAX_TERMS} Begriffe (der Fachbegriff und seine Synonyme),
 *   {@link #MAX_STATEMENTS} Aussagen, {@link #MAX_PAGES_PER_STATEMENT} Belegseiten je Aussage und
 *   {@link #MAX_NEIGHBOURS} Nachbarbegriffe; was gekappt wurde, steht in {@link Limits}.</li>
 * </ul>
 *
 * <p>Verneinung, Verbindlichkeit und Zeitbezug trennen Aussagen ({@link Statement}): „prüft“ und „prüft nicht“ sind
 * zwei Aussagen mit eigenen Belegen und eigener Stufe.
 *
 * <p>Reihenfolge der Aussagen: geprüfte zuerst, dann einige, dann ungeprüfte, zuletzt abweichende
 * ({@link DomainTermGraph.Trust}); innerhalb einer Stufe die mit mehr Belegen zuerst. Jede Aussage trägt ihre Stufe.
 */
public final class TermKnowledge {

    /** Höchstens so viele Seiten des Bereichs werden je Aufruf gelesen. */
    public static final int MAX_PAGES = 500;
    /** Höchstens so viele Begriffe je Antwort: der Fachbegriff und seine Synonyme. */
    public static final int MAX_TERMS = 5;
    /** Höchstens so viele Aussagen je Antwort, über alle Begriffe. */
    public static final int MAX_STATEMENTS = 20;
    /** Höchstens so viele Belegseiten je Aussage. */
    public static final int MAX_PAGES_PER_STATEMENT = 3;
    /** Höchstens so viele Nachbarbegriffe je Antwort. */
    public static final int MAX_NEIGHBOURS = 10;
    /** Höchstens so viele Vorschläge, wenn die Eingabe kein Fachbegriff ist. */
    public static final int MAX_CANDIDATES = 5;

    /**
     * Meldung des Aufrufers für jede Seite, deren Aussagen geliefert werden (für den Chat: Eintrag in die Liste
     * freigegebener Seiten, ADR 0033 Punkt 8). Wer nichts an ein Modell gibt (etwa die Suche), übergibt ausdrücklich
     * {@link #NONE}.
     */
    @FunctionalInterface
    public interface Delivery {

        /** Nichts geht an ein Modell; es gibt nichts einzutragen. */
        Delivery NONE = (spaceKey, key) -> {
        };

        void delivered(String spaceKey, PageKey key);
    }

    /** Ob und warum die Antwort leer ist. */
    public enum Outcome {
        /** Der Begriff ist ein bestätigter Fachbegriff oder ein bestätigtes Synonym; Aussagen kann es trotzdem keine geben. */
        FOUND,
        /** Kein Begriff angegeben. */
        EMPTY_QUERY,
        /** Der Bereich enthält keine Seite. */
        EMPTY_SCOPE,
        /** Kein bestätigter Fachbegriff und kein bestätigtes Synonym; {@link Answer#candidates()} nennt ähnliche. */
        NOT_A_TERM
    }

    /** Eine Belegseite: Kennung, Space und Titel aus den Kopfdaten. */
    public record Page(PageKey key, String spaceKey, String title) {

        public Page {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * Eine Aussage zwischen zwei Begriffen: {@code subject —verb→ object} mit ihren Merkmalen, zusammengefasst über
     * alle Belege mit genau diesen Merkmalen. Verneinung, Verbindlichkeit und Zeitbezug trennen Aussagen nach der einen
     * Regel der Fachsicht ({@link DomainTermGraph.Qualifiers}): „prüfen“ und „nicht prüfen“ sind zwei Aussagen mit
     * eigenen Belegen, nie eine.
     *
     * @param negation     {@link ParsedStatements.Negation#NEGATED} oder {@link ParsedStatements.Negation#AFFIRMED}
     *                     (nicht bestimmt gilt als nicht verneint)
     * @param modality     Verbindlichkeit nach dem Modalverb, {@link ParsedStatements.Modality#NONE} ohne
     * @param temporality  Zeitbezug, {@link ParsedStatements.Temporality#GILT} ohne besonderen
     * @param trust        {@link DomainTermGraph.Trust#ABWEICHEND}, wenn ein Beleg abweicht und keiner geprüft ist,
     *                     sonst die höchste Stufe der Belege
     * @param evidence     Zahl der Belege (Sätze)
     * @param pages        Belegseiten, höchstens {@link #MAX_PAGES_PER_STATEMENT}
     * @param omittedPages weitere Belegseiten, die nicht genannt sind
     */
    public record Statement(String subject, String verb, String object, ParsedStatements.Negation negation,
                            ParsedStatements.Modality modality, ParsedStatements.Temporality temporality,
                            DomainTermGraph.Trust trust, int evidence, List<Page> pages, int omittedPages) {

        public Statement {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(verb, "verb");
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(negation, "negation");
            Objects.requireNonNull(modality, "modality");
            Objects.requireNonNull(temporality, "temporality");
            Objects.requireNonNull(trust, "trust");
            pages = List.copyOf(pages);
        }

        /**
         * Der Wortlaut wie an der Kante der Fachsicht ({@link DomainTermGraph.Qualifiers#phrase}): „nicht prüfen“,
         * „muss prüfen (geplant)“.
         */
        public String phrase() {
            return new DomainTermGraph.Qualifiers(negation == ParsedStatements.Negation.NEGATED, modality, temporality)
                    .phrase(verb);
        }
    }

    /** Ein Begriff der Antwort mit seinen Aussagen. */
    public record Term(String term, DomainTermGraph.NodeKind kind, List<Statement> statements) {

        public Term {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(kind, "kind");
            statements = List.copyOf(statements);
        }
    }

    /**
     * Was gekappt oder ausgelassen wurde; alles 0 bzw. {@code false}, wenn die Antwort vollständig ist.
     *
     * @param pagesInScope      Seiten im Bereich
     * @param pagesRead         davon gelesen (höchstens {@link #MAX_PAGES})
     * @param omittedTerms      Synonyme über {@link #MAX_TERMS} hinaus
     * @param omittedStatements Aussagen über {@link #MAX_STATEMENTS} hinaus
     * @param omittedNeighbours Nachbarbegriffe über {@link #MAX_NEIGHBOURS} hinaus
     * @param omittedEvidencePages Belegseiten über {@link #MAX_PAGES_PER_STATEMENT} hinaus, über alle gelieferten
     *                          Aussagen ({@link Statement#omittedPages()})
     * @param graphTruncated    ob Verb-Kanten des Begriffs oder seiner Synonyme fehlen, weil die Fachsicht
     *                          {@link DomainTermGraph#MAX_NODES} Knoten erreicht hat
     * @param skippedStale      ausgelassene Aussagen veralteter Seiten
     */
    public record Limits(int pagesInScope, int pagesRead, int omittedTerms, int omittedStatements,
                         int omittedNeighbours, int omittedEvidencePages, boolean graphTruncated, int skippedStale) {

        static final Limits NONE = new Limits(0, 0, 0, 0, 0, 0, false, 0);

        /** Ob etwas gekappt oder ausgelassen wurde. */
        public boolean cut() {
            return pagesRead < pagesInScope || omittedTerms > 0 || omittedStatements > 0 || omittedNeighbours > 0
                    || omittedEvidencePages > 0 || graphTruncated || skippedStale > 0;
        }
    }

    /**
     * Die Antwort.
     *
     * @param terms      der Fachbegriff zuerst, dann seine Synonyme; leer außer bei {@link Outcome#FOUND}
     * @param neighbours Nachbarbegriffe aus den gelieferten Aussagen, nach Zahl der Belege
     * @param candidates ähnliche Fachbegriffe bei {@link Outcome#NOT_A_TERM}
     * @param pages      jede Seite, deren Aussagen geliefert werden, einmal; genau diese wurden an die
     *                   {@link Delivery} gemeldet
     */
    public record Answer(Outcome outcome, List<Term> terms, List<String> neighbours, List<String> candidates,
                         List<Page> pages, Limits limits) {

        public Answer {
            Objects.requireNonNull(outcome, "outcome");
            terms = List.copyOf(terms);
            neighbours = List.copyOf(neighbours);
            candidates = List.copyOf(candidates);
            pages = List.copyOf(pages);
            Objects.requireNonNull(limits, "limits");
        }

        static Answer of(Outcome outcome, List<String> candidates, Limits limits) {
            return new Answer(outcome, List.of(), List.of(), candidates, List.of(), limits);
        }
    }

    private final DomainTermGraph graph;
    private final AiRelease ai;

    /** @param ai KI-Freigabe je Quelle und Seite (ADR 0028): Begriffsnamen nur aus freigegebenen Quellen */
    public TermKnowledge(DomainTermGraph graph, AiRelease ai) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.ai = Objects.requireNonNull(ai, "ai");
    }

    /**
     * Was der Wissensgraph im Bereich {@code scope} über {@code query} weiß.
     *
     * @param scope     die Seiten des Bereichs (Pflicht); gelesen werden nur sie, höchstens {@link #MAX_PAGES}
     * @param delivery  erhält jede gelieferte Seite, bevor die Antwort herausgeht
     * @param cancelled bricht zwischen zwei Seiten ab ({@link java.util.concurrent.CancellationException})
     */
    public Answer answer(AccessContext caller, String query, PageSet scope, Delivery delivery,
                         BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(cancelled, "cancelled");
        if (query == null || TermText.normalize(query).isBlank()) {
            return Answer.of(Outcome.EMPTY_QUERY, List.of(), Limits.NONE);
        }
        if (scope.isEmpty()) {
            return Answer.of(Outcome.EMPTY_SCOPE, List.of(), Limits.NONE);
        }
        PageSet read = capped(scope);
        // Nur Einträge, die im Bereich gelten: „ist ein Fachbegriff“, Synonyme und Vorschläge nie aus dem ganzen
        // Glossar (Auftrag Architekt nach W7).
        DomainTermGraph.Within within = graph.groupWithin(caller, query,
                new DomainTermGraph.Options(2, false, read, false), cancelled);
        DomainTermGraph.View view = within.view();
        Limits none = new Limits(scope.size(), read.size(), 0, 0, 0, 0, false, 0);
        if (view.outcome() == DomainTermGraph.Outcome.EMPTY_START) {
            return Answer.of(Outcome.EMPTY_QUERY, List.of(), none);
        }
        if (view.outcome() == DomainTermGraph.Outcome.FOUND) {
            // Der gesuchte Begriff nach seinen eigenen Geltungsbereichen, seine Synonyme nach dem Geltungsbereich der
            // Beziehung zu ihm, nicht nach allen Einträgen des Synonyms.
            String centre = view.centre().key();
            Answer answer = build(view, scope.size(), read.size(), (key, delivered) -> mayName(within,
                    key.equals(centre) ? within.lexicon().scopes(key) : within.lexicon().edgeScopes(centre, key),
                    delivery, delivered), delivery == Delivery.NONE ? key -> true : ai::mayProcess);
            if (answer != null) {
                return deliver(answer, delivery);
            }
        }
        return Answer.of(Outcome.NOT_A_TERM, DomainTermGraph.suggestions(within.lexicon(), query, MAX_CANDIDATES,
                key -> mayName(within, within.lexicon().scopes(key), delivery, List.of())), none);
    }

    /**
     * Ob ein Name den Aufrufer erreichen darf (KI-Freigabe, ADR 0028): wenn einer der Geltungsbereiche {@code scopes}
     * (des Eintrags oder der Beziehung, über die er in die Antwort kommt) global ist, nichts an ein Modell geht
     * ({@link Delivery#NONE}), seine Quelle freigegeben ist, eine schon freigegebene Seite des Bereichs darin liegt
     * oder die Antwort eine Seite darin liefert (und damit freigibt). Was nur in Quellen ohne Freigabe gilt, bleibt
     * sonst draußen.
     */
    private boolean mayName(DomainTermGraph.Within within, List<RelationScope> scopes, Delivery delivery,
                            java.util.Collection<Page> delivered) {
        for (RelationScope scope : scopes) {
            if (scope.global() || delivery == Delivery.NONE || ai.sourceReleased(scope.sourceId())) {
                return true;
            }
            // Alle Seiten des Bereichs in der Quelle des Geltungsbereichs, nicht nur die zufällig schon aufgelösten.
            if (within.area().coveredReleased(scope, ai::mayProcess)) {
                return true;
            }
            for (Page page : delivered) {
                if (scope.covers(page.key().sourceId(), page.spaceKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Die ersten {@link #MAX_PAGES} Seiten in fester Ordnung (Quelle, Seite), damit die Kappung stabil ist. */
    static PageSet capped(PageSet scope) {
        if (scope.size() <= MAX_PAGES) {
            return scope;
        }
        Map<String, List<String>> kept = new TreeMap<>();
        int left = MAX_PAGES;
        for (Map.Entry<String, Set<String>> source : new TreeMap<>(scope.asMap()).entrySet()) {
            for (String pageId : new TreeSet<>(source.getValue())) {
                if (left == 0) {
                    return PageSet.of(kept);
                }
                kept.computeIfAbsent(source.getKey(), unused -> new ArrayList<>()).add(pageId);
                left--;
            }
        }
        return PageSet.of(kept);
    }

    /**
     * Die Antwort; {@code mayName} entscheidet je Begriff ohne gelieferte Aussage, ob sein Name mitgeht (mit den
     * gelieferten Seiten). {@code null}, wenn der gesuchte Begriff selbst nicht genannt werden darf.
     */
    private static Answer build(DomainTermGraph.View view, int inScope, int read,
                                java.util.function.BiPredicate<String, java.util.Collection<Page>> mayName,
                                java.util.function.Predicate<PageKey> released) {
        return build(view, inScope, read, mayName, released, Set.of());
    }

    /**
     * Wie oben, ohne die Begriffe {@code dropped}; baut neu, bis jeder genannte Begriff genannt werden darf.
     * {@code released}: Seiten, die ohne Lieferung schon freigegeben sind (für die Zählung der Ausgelassenen).
     */
    private static Answer build(DomainTermGraph.View view, int inScope, int read,
                                java.util.function.BiPredicate<String, java.util.Collection<Page>> mayName,
                                java.util.function.Predicate<PageKey> released, Set<String> dropped) {
        DomainTermGraph.Node centre = view.centre();
        Map<String, DomainTermGraph.Node> nodes = new LinkedHashMap<>();
        view.nodes().forEach(node -> nodes.put(node.key(), node));
        // Die Begriffsgruppe: der Fachbegriff und die Synonyme, die unmittelbar an ihm hängen.
        List<DomainTermGraph.Node> group = new ArrayList<>();
        group.add(centre);
        for (DomainTermGraph.Edge edge : view.edges()) {
            if (edge.kind() != DomainTermGraph.EdgeKind.SYNONYM) {
                continue;
            }
            String other = edge.from().equals(centre.key()) ? edge.to()
                    : edge.to().equals(centre.key()) ? edge.from() : null;
            DomainTermGraph.Node node = other == null ? null : nodes.get(other);
            if (node != null && !group.contains(node)) {
                group.add(node);
            }
        }
        // Erst filtern, dann kappen und zählen: Was nicht genannt werden darf, belegt keinen Platz und zählt nicht als
        // ausgelassen. Vorab gegen alle Seiten, die Aussagen der Gruppe liefern könnten; unten gegen die gelieferten.
        Map<PageKey, Page> possible = new LinkedHashMap<>();
        Set<String> members = new LinkedHashSet<>();
        group.forEach(node -> members.add(node.key()));
        for (DomainTermGraph.Edge edge : view.edges()) {
            if (edge.kind() == DomainTermGraph.EdgeKind.VERB
                    && (members.contains(edge.from()) || members.contains(edge.to()))) {
                edge.evidence().forEach(item -> possible.putIfAbsent(item.page().key(),
                        new Page(item.page().key(), item.page().spaceKey(), item.page().title())));
            }
        }
        List<DomainTermGraph.Node> allowed = group.stream().filter(node -> !dropped.contains(node.key()))
                .filter(node -> mayName.test(node.key(), possible.values())).toList();
        if (!allowed.contains(centre)) {
            return null;
        }
        // Hinter der Kappung zählt nur, wer auch ohne Seiten unfreigegebener Quellen nennbar wäre: Seine Aussagen
        // kämen sonst nie mit, ein Hinweis auf ihn verriete nur, dass es ihn gibt.
        List<Page> releasedPossible = possible.values().stream().filter(page -> released.test(page.key())).toList();
        int omittedTerms = (int) allowed.stream().skip(MAX_TERMS)
                .filter(node -> mayName.test(node.key(), releasedPossible)).count();
        List<DomainTermGraph.Node> shown = allowed.subList(0, Math.min(allowed.size(), MAX_TERMS));
        Set<String> groupKeys = new LinkedHashSet<>();
        group.forEach(node -> groupKeys.add(node.key()));

        // Je Begriff seine Verb-Kanten, jede Kante nur beim ersten Begriff der Gruppe, den sie berührt.
        Map<String, List<DomainTermGraph.Edge>> byTerm = new LinkedHashMap<>();
        shown.forEach(node -> byTerm.put(node.key(), new ArrayList<>()));
        for (DomainTermGraph.Edge edge : view.edges()) {
            if (edge.kind() != DomainTermGraph.EdgeKind.VERB) {
                continue;
            }
            String owner = shown.stream().map(DomainTermGraph.Node::key)
                    .filter(key -> key.equals(edge.from()) || key.equals(edge.to())).findFirst().orElse(null);
            if (owner == null) {
                continue;
            }
            byTerm.get(owner).add(edge);
        }

        // Aussagen über alle Begriffe nach Stufe und Belegen ordnen, dann kappen.
        List<Map.Entry<String, DomainTermGraph.Edge>> ranked = new ArrayList<>();
        byTerm.forEach((key, edges) -> edges.forEach(edge -> ranked.add(Map.entry(key, edge))));
        ranked.sort(Comparator.<Map.Entry<String, DomainTermGraph.Edge>, DomainTermGraph.Trust>comparing(
                        entry -> trust(entry.getValue()))
                .thenComparing(entry -> -entry.getValue().count()));
        int omittedStatements = Math.max(0, ranked.size() - MAX_STATEMENTS);
        Map<String, List<Statement>> statements = new LinkedHashMap<>();
        shown.forEach(node -> statements.put(node.key(), new ArrayList<>()));
        Map<PageKey, Page> delivered = new LinkedHashMap<>();
        // Nachbarn nur aus gelieferten Aussagen: Was die Kappung weglässt, erreicht den Aufrufer auch so nicht.
        Map<String, Integer> neighbourWeight = new LinkedHashMap<>();
        int omittedEvidencePages = 0;
        for (Map.Entry<String, DomainTermGraph.Edge> entry : ranked.subList(0, ranked.size() - omittedStatements)) {
            DomainTermGraph.Edge edge = entry.getValue();
            Map<PageKey, Page> pages = new LinkedHashMap<>();
            for (DomainTermGraph.Evidence item : edge.evidence()) {
                KnowledgeGraph.PageRef ref = item.page();
                pages.putIfAbsent(ref.key(), new Page(ref.key(), ref.spaceKey(), ref.title()));
            }
            List<Page> named = new ArrayList<>(pages.values()).subList(0, Math.min(pages.size(),
                    MAX_PAGES_PER_STATEMENT));
            named.forEach(page -> delivered.putIfAbsent(page.key(), page));
            omittedEvidencePages += pages.size() - named.size();
            for (String end : List.of(edge.from(), edge.to())) {
                if (!groupKeys.contains(end)) {
                    neighbourWeight.merge(end, edge.count(), Integer::sum);
                }
            }
            DomainTermGraph.Qualifiers qualifiers = edge.qualifiers();
            // Die Kante faltet „nicht bestimmt“ in „schlicht“; dem Chat bleibt es „nicht bestimmt“, solange keine
            // Belegstelle das Merkmal bestimmt (bei gemischten Belegen gilt der bestimmte Wert).
            List<ParsedStatements.Features> features = edge.evidence().stream().map(TermKnowledge::features).toList();
            ParsedStatements.Negation negation = features.stream()
                    .anyMatch(item -> item.negation() != ParsedStatements.Negation.UNKNOWN)
                    ? qualifiers.negated() ? ParsedStatements.Negation.NEGATED : ParsedStatements.Negation.AFFIRMED
                    : ParsedStatements.Negation.UNKNOWN;
            ParsedStatements.Modality modality = features.stream()
                    .anyMatch(item -> item.modality() != ParsedStatements.Modality.UNKNOWN)
                    ? qualifiers.modality() : ParsedStatements.Modality.UNKNOWN;
            ParsedStatements.Temporality temporality = features.stream()
                    .anyMatch(item -> item.temporality() != ParsedStatements.Temporality.UNKNOWN)
                    ? qualifiers.temporality() : ParsedStatements.Temporality.UNKNOWN;
            statements.get(entry.getKey()).add(new Statement(label(nodes, edge.from()), edge.verb(),
                    label(nodes, edge.to()), negation, modality, temporality,
                    trust(edge), edge.count(), named, pages.size() - named.size()));
        }
        // Gegen die tatsächlich gelieferten Seiten: Darf ein Name doch nicht mit (seine Seite fiel einer Grenze zum
        // Opfer), ohne ihn neu bauen; der gesuchte Begriff selbst führt dann zu keiner Antwort.
        Set<String> failing = new LinkedHashSet<>();
        shown.stream().filter(node -> !mayName.test(node.key(), delivered.values()))
                .forEach(node -> failing.add(node.key()));
        if (failing.contains(centre.key())) {
            return null;
        }
        if (!failing.isEmpty()) {
            Set<String> more = new LinkedHashSet<>(dropped);
            more.addAll(failing);
            return build(view, inScope, read, mayName, released, more);
        }
        List<Term> terms = shown.stream().map(node -> new Term(node.label(), node.kind(), statements.get(node.key())))
                .toList();
        List<String> neighbours = neighbourWeight.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> label(nodes, entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .map(entry -> label(nodes, entry.getKey())).toList();
        int omittedNeighbours = Math.max(0, neighbours.size() - MAX_NEIGHBOURS);
        return new Answer(Outcome.FOUND, terms, neighbours.subList(0, neighbours.size() - omittedNeighbours),
                List.of(), List.copyOf(delivered.values()), new Limits(inScope, read, omittedTerms,
                omittedStatements, omittedNeighbours, omittedEvidencePages, view.truncated(), view.skippedStale()));
    }

    private static ParsedStatements.Features features(DomainTermGraph.Evidence item) {
        ParsedStatements.Features features = item.statement().features();
        return features == null ? ParsedStatements.Features.UNKNOWN : features;
    }

    /** Erst melden, dann herausgeben: Wirft die Meldung, gibt es keine Antwort. */
    private static Answer deliver(Answer answer, Delivery delivery) {
        answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
        return answer;
    }

    private static DomainTermGraph.Trust trust(DomainTermGraph.Edge edge) {
        return edge.differs() ? DomainTermGraph.Trust.ABWEICHEND : edge.trust();
    }

    private static String label(Map<String, DomainTermGraph.Node> nodes, String key) {
        DomainTermGraph.Node node = nodes.get(key);
        return node == null ? key : node.label();
    }
}
