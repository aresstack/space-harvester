package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Fachsicht des Wissensgraphen (Paket W1): Knoten sind nur bestätigte Fachbegriffe des Glossars und ihre bestätigten
 * Synonyme, Kanten die Verben der Aussagen zwischen ihnen. Spring-, REST- und Swing-frei; rechnet nie und speichert
 * nichts, sondern liest die gespeicherten Aussagen über {@link KnowledgeGraph#analysed} (Rechte je Seite, nur
 * eingeschaltete Quellen, genau eine Ablage: die des gewählten Analysators, {@link AnalyzerStores}).
 *
 * <p>Eine Verb-Kante {@code A —verb→ B} entsteht aus einer Aussage der Standardansicht (wirksame Stufe plausibel, zählt
 * nach {@link CountedStatements}, keine Zustandsaussage), deren Handelnder auf den Knoten {@code A} und deren Betroffener,
 * Empfänger oder Präpositionalobjekt auf den Knoten {@code B} passt. „Passt“ ist eine Kandidatenzuordnung wie
 * {@link KnowledgeGraph.Match#LEXICAL}: gleiche normalisierte Grundform, Kopfform oder gleicher Wortlaut, und der
 * Geltungsbereich des Eintrags deckt die Seite. Nichts wird als identisch behauptet: Jeder Beleg trägt den Wortlaut der
 * Erwähnungen. Erwähnungen, die auf keinen Knoten passen, erzeugen weder Kante noch Knoten.
 *
 * <p>Synonyme stehen als eigene Knoten neben ihrem Fachbegriff, verbunden durch eine {@link EdgeKind#SYNONYM}-Kante;
 * sie werden nie mit ihm verschmolzen.
 */
public final class DomainTermGraph {

    /** Höchstens so viele Knoten zeigt eine Fachsicht; mehr meldet {@link View#truncated()}. */
    public static final int MAX_NODES = 40;

    /** Ein bestätigter Fachbegriff des Glossars. */
    public record Term(String term, RelationScope scope) {

        public Term {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Eine bestätigte Synonym-Beziehung, deren eines Ende ein Fachbegriff ist. */
    public record Synonym(String fromTerm, String toTerm, RelationScope scope) {

        public Synonym {
            Objects.requireNonNull(fromTerm, "fromTerm");
            Objects.requireNonNull(toTerm, "toTerm");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Die Begriffe, die der Aufrufer sehen darf: bestätigte Fachbegriffe und bestätigte Synonyme. */
    public interface Vocabulary {

        List<Term> terms(AccessContext caller);

        List<Synonym> synonyms(AccessContext caller);

        /**
         * Bestätigte Einträge der Einordnung „technisch“: Sie werden auch als Ende eines Synonyms kein Knoten.
         */
        default List<Term> technical(AccessContext caller) {
            return List.of();
        }
    }

    /** Art eines Knotens. */
    public enum NodeKind {
        /** Bestätigter Fachbegriff des Glossars. */
        TERM,
        /** Bestätigtes Synonym eines Fachbegriffs. */
        SYNONYM
    }

    /** Art einer Kante. */
    public enum EdgeKind {
        /** Verb einer Aussage, gerichtet vom Handelnden zum Betroffenen. */
        VERB,
        /** Bestätigte Synonym-Beziehung, ungerichtet. */
        SYNONYM
    }

    /**
     * Ein Knoten.
     *
     * @param key    Suchwert ({@link TermText#normalize}), eindeutig im Graphen
     * @param label  Wortlaut wie im Glossar bzw. in der Beziehung
     * @param depth  Abstand zur Mitte: 0 die Mitte, 1 Nachbar, 2 Nachbar eines Nachbarn
     * @param parent Schlüssel des Knotens, über den er gefunden wurde; {@code null} für die Mitte
     */
    public record Node(String key, String label, NodeKind kind, int depth, String parent) {

        public Node {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Vertrauen in einen Beleg (Paket W2). Die Reihenfolge ist die Rangfolge an der Kante: die höchste Stufe ihrer
     * Belege, wobei {@link #ABWEICHEND} nur als Kennzeichen zählt ({@link Edge#differs()}).
     */
    public enum Trust {
        /** Von Hand bestätigt ({@link ParsedStatements.Review#CONFIRMED}) oder vom Prüfschritt bestätigt. */
        GEPRUEFT,
        /** Die andere Ablage (Parser bzw. KI) liest den Satz gleich ({@link AnalyzerComparison.Agreement#AGREES}). */
        EINIG,
        /** Nur die Lesart des gewählten Analysators, nicht geprüft. */
        UNGEPRUEFT,
        /** Die andere Ablage liest den Satz anders ({@link AnalyzerComparison.Agreement#DIFFERS}); nicht geprüft. */
        ABWEICHEND
    }

    /**
     * Port für den Vergleich der Analysatoren (K2, {@link AnalyzerComparison}); die Vergleichslogik liegt allein dort.
     * Gefragt wird je Seite und Abruf höchstens einmal, und nur, wenn eine Aussage der Seite eine Kante trägt.
     */
    @FunctionalInterface
    public interface Comparison {

        /** Kein Vergleich: jede Aussage ist {@link AnalyzerComparison.Agreement#NOT_COMPARABLE}. */
        Comparison NONE = (caller, page) -> Map.of();

        /**
         * Die Abgleiche einer Seite je Aussage-ID der eingestellten Ablage ({@link AnalyzerComparison#comparisons});
         * fehlt eine ID, ist die Aussage nicht vergleichbar.
         */
        Map<String, AnalyzerComparison.Comparison> comparisons(AccessContext caller, KnowledgeGraph.PageRef page);

        /**
         * Der Abgleich der beiden Ablagen als Port. Eine Seite, die nicht (mehr) im Bestand liegt oder nicht lesbar
         * ist, ist nicht vergleichbar; die Fachsicht selbst bleibt stehen.
         */
        static Comparison of(AnalyzerComparison analyzers) {
            Objects.requireNonNull(analyzers, "analyzers");
            return (caller, page) -> {
                try {
                    return analyzers.comparisons(caller, page.key());
                } catch (com.aresstack.spaceharvester.application.query.CacheMissException
                         | com.aresstack.spaceharvester.application.access.AccessDeniedException e) {
                    return Map.of();
                }
            };
        }
    }

    /**
     * Die Stufe eines Belegs: geprüft geht vor, dann der Vergleich der Analysatoren.
     */
    static Trust trust(ParsedStatements.Statement statement, AnalyzerComparison.Agreement agreement) {
        if (statement.review() == ParsedStatements.Review.CONFIRMED || statement.check() != null
                && statement.check().verdict() == ParsedStatements.Verdict.STIMMT) {
            return Trust.GEPRUEFT;
        }
        return switch (agreement == null ? AnalyzerComparison.Agreement.NOT_COMPARABLE : agreement) {
            case AGREES -> Trust.EINIG;
            case DIFFERS -> Trust.ABWEICHEND;
            case NOT_COMPARABLE -> Trust.UNGEPRUEFT;
        };
    }

    /**
     * Ein Beleg einer Verb-Kante: genau eine gespeicherte Aussage.
     *
     * @param page        Seite mit Stand
     * @param statement   die Aussage, wie gespeichert (Herkunft, Prüfstatus und Review daran)
     * @param analyzerId  Analysator der Ablage
     * @param fromText    Wortlaut der Erwähnung, die auf den Ausgangsknoten passt
     * @param toText      Wortlaut der Erwähnung, die auf den Zielknoten passt
     * @param trust       Vertrauen in diesen Beleg (Paket W2)
     * @param comparison  Abgleich mit der anderen Ablage (K2) oder {@code null}, wenn nicht gefragt (von Hand bestätigt)
     *                    bzw. nicht vergleichbar; bei {@link Trust#ABWEICHEND} mit der Lesart der anderen Ablage
     */
    public record Evidence(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement, String analyzerId,
                           String fromText, String toText, Trust trust, AnalyzerComparison.Comparison comparison) {

        public Evidence {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(statement, "statement");
            trust = trust == null ? Trust.UNGEPRUEFT : trust;
        }

        /** Mit Stufe, ohne Abgleich. */
        public Evidence(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement, String analyzerId,
                        String fromText, String toText, Trust trust) {
            this(page, statement, analyzerId, fromText, toText, trust, null);
        }

        /** Ohne Vergleich der Analysatoren. */
        public Evidence(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement, String analyzerId,
                        String fromText, String toText) {
            this(page, statement, analyzerId, fromText, toText,
                    DomainTermGraph.trust(statement, AnalyzerComparison.Agreement.NOT_COMPARABLE), null);
        }

        /** Die Lesart der anderen Ablage, wenn sie abweicht; sonst {@code null}. */
        public AnalyzerComparison.Reading otherReading() {
            return trust == Trust.ABWEICHEND && comparison != null
                    && comparison.agreement() == AnalyzerComparison.Agreement.DIFFERS ? comparison.other() : null;
        }
    }

    /**
     * Eine Kante.
     *
     * @param verb     Grundform des Verbs bei {@link EdgeKind#VERB}, sonst {@code null}
     * @param count    Zahl der Belege
     * @param evidence alle Belege, nach Quelle, Seite und Satz
     * @param trust    höchste Stufe der Belege: {@link Trust#GEPRUEFT}, {@link Trust#EINIG} oder
     *                 {@link Trust#UNGEPRUEFT}; bei Synonymen {@link Trust#GEPRUEFT} (bestätigte Beziehung)
     * @param differs  ob ein Beleg abweicht und keiner geprüft ist
     */
    public record Edge(String from, String to, EdgeKind kind, String verb, int count, List<Evidence> evidence,
                       Trust trust, boolean differs) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
            evidence = List.copyOf(evidence);
            trust = trust == null ? Trust.UNGEPRUEFT : trust;
        }

        /** Stufe und Kennzeichen aus den Belegen. */
        public Edge(String from, String to, EdgeKind kind, String verb, int count, List<Evidence> evidence) {
            this(from, to, kind, verb, count, evidence, kind == EdgeKind.SYNONYM ? Trust.GEPRUEFT : rank(evidence),
                    kind == EdgeKind.VERB && differs(evidence));
        }

        private static Trust rank(List<Evidence> evidence) {
            Trust best = Trust.UNGEPRUEFT;
            for (Evidence item : evidence) {
                if (item.trust() == Trust.GEPRUEFT) {
                    return Trust.GEPRUEFT;
                }
                if (item.trust() == Trust.EINIG) {
                    best = Trust.EINIG;
                }
            }
            return best;
        }

        private static boolean differs(List<Evidence> evidence) {
            return evidence.stream().anyMatch(item -> item.trust() == Trust.ABWEICHEND)
                    && evidence.stream().noneMatch(item -> item.trust() == Trust.GEPRUEFT);
        }
    }

    /** Ob und warum die Fachsicht leer ist. */
    public enum Outcome {
        /** Mindestens die Mitte steht. */
        FOUND,
        /** Kein Startbegriff eingegeben. */
        EMPTY_START,
        /** Der Startbegriff ist kein bestätigter Fachbegriff und kein bestätigtes Synonym. */
        NOT_A_TERM
    }

    /**
     * Eine Fachsicht um einen Startbegriff.
     *
     * @param nodes          Knoten, die Mitte zuerst
     * @param truncated      ob es mehr als {@link #MAX_NODES} Knoten gäbe
     * @param skippedStale   Aussagen auf veralteten Seiten, die ausgelassen wurden (nur ohne {@code includeStale})
     * @param analyzers      Analysatoren aller gelesenen Seiten der Ablage, wie gespeichert (in der Regel genau einer),
     *                       auch ohne Kante
     * @param analyzer       die eingestellte Ablage oder {@code null}, wenn unbekannt; nennt die Statuszeile, wenn
     *                       noch keine Seite analysiert ist
     */
    public record View(Outcome outcome, List<Node> nodes, List<Edge> edges, boolean truncated, int skippedStale,
                       Set<String> analyzers, AnalyzerChoice analyzer) {

        public View {
            Objects.requireNonNull(outcome, "outcome");
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            analyzers = Set.copyOf(analyzers);
        }

        /** Ohne Angabe der gewählten Ablage. */
        public View(Outcome outcome, List<Node> nodes, List<Edge> edges, boolean truncated, int skippedStale,
                    Set<String> analyzers) {
            this(outcome, nodes, edges, truncated, skippedStale, analyzers, null);
        }

        static View of(Outcome outcome) {
            return new View(outcome, List.of(), List.of(), false, 0, Set.of(), null);
        }

        public Node centre() {
            return nodes.isEmpty() ? null : nodes.getFirst();
        }
    }

    /**
     * Grenzen und Filter.
     *
     * @param depth        1 oder 2
     * @param includeStale Aussagen veralteter Seiten mitnehmen
     * @param pages        nur diese Seiten samt Dokumenten (Auswahl im Baum, ADR 0029) oder {@code null} für alle
     * @param checkedOnly  „nur geprüft“ (Paket W2): nur Belege der Stufe {@link Trust#GEPRUEFT} zählen; Kanten ohne
     *                     solche fallen weg
     */
    public record Options(int depth, boolean includeStale, PageSet pages, boolean checkedOnly) {

        public static final Options DEFAULT = new Options(2, false, null, false);

        public Options {
            if (depth < 1 || depth > 2) {
                throw new IllegalArgumentException("Tiefe 1 oder 2");
            }
        }

        /** Ohne „nur geprüft“. */
        public Options(int depth, boolean includeStale, PageSet pages) {
            this(depth, includeStale, pages, false);
        }
    }

    private final Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed;
    private final Vocabulary vocabulary;
    private final Comparison comparison;
    private final java.util.function.Supplier<AnalyzerChoice> selected;

    public DomainTermGraph(KnowledgeGraph graph, Vocabulary vocabulary) {
        this(graph, vocabulary, () -> null);
    }

    /** @param selected die eingestellte Ablage, aus der {@code graph} liest; für die Statuszeile */
    public DomainTermGraph(KnowledgeGraph graph, Vocabulary vocabulary,
                           java.util.function.Supplier<AnalyzerChoice> selected) {
        this(graph, vocabulary, selected, Comparison.NONE);
    }

    /**
     * @param selected   die eingestellte Ablage, aus der {@code graph} liest; für die Statuszeile
     * @param comparison Vergleich der Analysatoren (K2) oder {@link Comparison#NONE}
     */
    public DomainTermGraph(KnowledgeGraph graph, Vocabulary vocabulary,
                           java.util.function.Supplier<AnalyzerChoice> selected, Comparison comparison) {
        this(Objects.requireNonNull(graph, "graph")::analysed, vocabulary, selected, comparison);
    }

    DomainTermGraph(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed, Vocabulary vocabulary) {
        this(analysed, vocabulary, () -> null);
    }

    DomainTermGraph(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed, Vocabulary vocabulary,
                    java.util.function.Supplier<AnalyzerChoice> selected) {
        this(analysed, vocabulary, selected, Comparison.NONE);
    }

    DomainTermGraph(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed, Vocabulary vocabulary,
                    Comparison comparison) {
        this(analysed, vocabulary, () -> null, comparison);
    }

    DomainTermGraph(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed, Vocabulary vocabulary,
                    java.util.function.Supplier<AnalyzerChoice> selected, Comparison comparison) {
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
        this.selected = Objects.requireNonNull(selected, "selected");
        this.comparison = Objects.requireNonNull(comparison, "comparison");
    }

    /**
     * Vorschläge für das Startfeld der Fachsicht: bestätigte Fachbegriffe und aufgenommene Synonyme, deren Wortlaut
     * {@code query} enthält; Wortanfänge zuerst, Fachbegriffe vor Synonymen, sonst alphabetisch.
     */
    public List<String> suggestions(AccessContext caller, String query, int limit) {
        Objects.requireNonNull(caller, "caller");
        String wanted = query == null ? "" : TermText.normalize(query).strip();
        if (wanted.isEmpty() || limit < 1) {
            return List.of();
        }
        Lexicon lexicon = new Lexicon(vocabulary.terms(caller), vocabulary.synonyms(caller),
                vocabulary.technical(caller));
        return lexicon.labels.keySet().stream().filter(key -> key.contains(wanted))
                .sorted(java.util.Comparator.<String>comparingInt(key -> key.startsWith(wanted) ? 0 : 1)
                        .thenComparing(key -> lexicon.kinds.get(key))
                        .thenComparing(lexicon.labels::get, String.CASE_INSENSITIVE_ORDER))
                .limit(limit).map(lexicon.labels::get).toList();
    }

    /**
     * Die Fachsicht um {@code start}: die Mitte, ihre Nachbarn über Verb- und Synonymkanten, bei Tiefe 2 auch deren
     * Nachbarn, höchstens {@link #MAX_NODES} Knoten. Ein Fachbegriff ohne sichtbaren Beleg steht als Knoten ohne
     * Verb-Kanten da.
     *
     * @param cancelled bricht zwischen zwei Seiten ab; dann gilt der Aufruf als verworfen
     */
    public View around(AccessContext caller, String start, Options options, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancelled, "cancelled");
        String centre = start == null ? "" : TermText.normalize(start).strip();
        if (centre.isEmpty()) {
            return View.of(Outcome.EMPTY_START);
        }
        Lexicon lexicon = new Lexicon(vocabulary.terms(caller), vocabulary.synonyms(caller),
                vocabulary.technical(caller));
        if (!lexicon.labels.containsKey(centre)) {
            return View.of(Outcome.NOT_A_TERM);
        }

        Map<String, EdgeBuilder> edges = new LinkedHashMap<>();
        // Dieselbe Sicht, als wären die veralteten Aussagen aktuell, in derselben Reihenfolge wie mit „veraltete
        // zeigen“ (Synonyme zuerst, dann Seite für Seite): nur was dort läge, zählt als ausgelassen.
        Map<String, EdgeBuilder> withStale = new LinkedHashMap<>();
        lexicon.synonymEdges.forEach(pair -> {
            String key = "syn\u0000" + pair[0] + "\u0000" + pair[1];
            edges.computeIfAbsent(key, unused -> new EdgeBuilder(pair[0], pair[1], EdgeKind.SYNONYM, null));
            withStale.computeIfAbsent(key, unused -> new EdgeBuilder(pair[0], pair[1], EdgeKind.SYNONYM, null));
        });
        // Je ausgelassener veralteter Aussage die Paare, die sie verbände; gezählt wird erst nach dem Aufbau der Sicht.
        List<Set<String>> stale = new ArrayList<>();
        Set<String> analyzers = new LinkedHashSet<>();
        for (KnowledgeGraph.AnalysedPage page : analysed.apply(caller)) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Fachsicht abgebrochen");
            }
            KnowledgeGraph.PageRef ref = page.page();
            if (options.pages() != null && !options.pages().contains(ref.key())) {
                continue;
            }
            analyzers.add(page.analysis().analyzerId());
            ParsedStatements result = page.analysis().result();
            // Abgleich der Seite erst, wenn eine Aussage eine Kante trägt, und dann nur einmal.
            Map<String, AnalyzerComparison.Comparison> compared = null;
            Map<String, ParsedStatements.Mention> mentions = new LinkedHashMap<>();
            result.mentions().forEach(mention -> mentions.putIfAbsent(mention.id(), mention));
            for (ParsedStatements.Statement statement : result.statements()) {
                if (!counts(statement)) {
                    continue;
                }
                List<Match> actors = new ArrayList<>();
                List<Match> objects = new ArrayList<>();
                for (ParsedStatements.Argument argument : statement.arguments()) {
                    ParsedStatements.Mention mention = mentions.get(argument.mention());
                    String key = mention == null ? null : lexicon.match(mention, ref);
                    if (key == null) {
                        continue;
                    }
                    switch (argument.role()) {
                        case ACTOR -> actors.add(new Match(key, mention.text()));
                        case AFFECTED_OBJECT, RECIPIENT, PREPOSITIONAL_OBJECT ->
                                objects.add(new Match(key, mention.text()));
                        default -> {
                        }
                    }
                }
                if (actors.isEmpty() || objects.isEmpty()) {
                    continue;
                }
                AnalyzerComparison.Comparison match = null;
                if (statement.review() != ParsedStatements.Review.CONFIRMED) {
                    if (compared == null) {
                        compared = Objects.requireNonNullElse(comparison.comparisons(caller, ref), Map.of());
                    }
                    match = compared.get(statement.id());
                    if (match != null && match.own() == null) {
                        // Nur in der anderen Ablage: gleich gebaute Kennung, aber nicht diese Aussage.
                        match = null;
                    }
                }
                Trust trust = trust(statement, match == null ? null : match.agreement());
                if (options.checkedOnly() && trust != Trust.GEPRUEFT) {
                    // Auch veraltet nicht mitzählen: in „nur geprüft“ läge die Kante ohnehin nicht.
                    continue;
                }
                String verb = verb(statement);
                if (ref.state() == PageStatements.State.STALE && !options.includeStale()) {
                    Set<String> pairs = new LinkedHashSet<>();
                    for (Match actor : actors) {
                        for (Match object : objects) {
                            if (!actor.key().equals(object.key())
                                    && pairs.add(actor.key() + "\u0000" + object.key())) {
                                withStale.computeIfAbsent("verb\u0000" + actor.key() + "\u0000" + verb + "\u0000"
                                                + object.key(),
                                        unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb))
                                        .add(new Evidence(ref, statement, page.analysis().analyzerId(), actor.text(),
                                                object.text(), trust, match));
                            }
                        }
                    }
                    if (!pairs.isEmpty()) {
                        stale.add(pairs);
                    }
                    continue;
                }
                Set<String> seen = new LinkedHashSet<>();
                for (Match actor : actors) {
                    for (Match object : objects) {
                        if (actor.key().equals(object.key())
                                || !seen.add(actor.key() + "\u0000" + object.key())) {
                            continue;
                        }
                        String key = "verb\u0000" + actor.key() + "\u0000" + verb + "\u0000" + object.key();
                        Evidence item = new Evidence(ref, statement, page.analysis().analyzerId(), actor.text(),
                                object.text(), trust, match);
                        edges.computeIfAbsent(key,
                                unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb)).add(item);
                        withStale.computeIfAbsent(key,
                                unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb)).add(item);
                    }
                }
            }
        }
        View view = layout(centre, lexicon, edges, options.depth(), 0, analyzers);
        Set<String> shown = new java.util.HashSet<>();
        if (!stale.isEmpty()) {
            layout(centre, lexicon, withStale, options.depth(), 0, analyzers).nodes()
                    .forEach(node -> shown.add(node.key()));
        }
        // Nur veraltete Aussagen, deren Kante in dieser Sicht läge, wären sie aktuell: beide Enden wären Knoten.
        int skippedStale = (int) stale.stream().filter(pairs -> pairs.stream().anyMatch(pair -> {
            int cut = pair.indexOf('\u0000');
            return shown.contains(pair.substring(0, cut)) && shown.contains(pair.substring(cut + 1));
        })).count();
        return new View(view.outcome(), view.nodes(), view.edges(), view.truncated(), skippedStale, analyzers,
                selected.get());
    }

    /**
     * Ob eine Aussage für die Fachsicht zählt: Standardansicht ({@link PageGraph#DEFAULT_QUALITIES}), nicht vom
     * Menschen abgelehnt ({@link CountedStatements}), keine Zustandsaussage (ihr Prädikat ist kein Verb) und aus dem
     * Originalsatz: Aussagen der Vorstufe und Korrekturen des Prüfschritts sind nach ADR 0032 nie Beleg, sonst zählte
     * eine Stelle doppelt (die Korrektur legt neben dem Original eine Aussage mit demselben Verb an).
     */
    static boolean counts(ParsedStatements.Statement statement) {
        return statement.derivation().origin() == ParsedStatements.Origin.ORIGINAL
                && PageGraph.DEFAULT_QUALITIES.contains(statement.effectiveQuality())
                && CountedStatements.plausible(statement) && !statement.state();
    }

    /** Grundform des Verbs wie im Verben-Verzeichnis (K1): Grundform, sonst Wortlaut, getrimmt und klein. */
    static String verb(ParsedStatements.Statement statement) {
        String lemma = statement.predicateLemma() == null || statement.predicateLemma().isBlank()
                ? statement.predicateText() : statement.predicateLemma();
        return lemma.strip().toLowerCase(Locale.ROOT);
    }

    private static View layout(String centre, Lexicon lexicon, Map<String, EdgeBuilder> edges, int depth,
                               int skippedStale, Set<String> analyzers) {
        Map<String, List<EdgeBuilder>> touching = new LinkedHashMap<>();
        for (EdgeBuilder edge : edges.values()) {
            if (edge.kind == EdgeKind.VERB && edge.evidence.isEmpty()) {
                continue;
            }
            touching.computeIfAbsent(edge.from, unused -> new ArrayList<>()).add(edge);
            touching.computeIfAbsent(edge.to, unused -> new ArrayList<>()).add(edge);
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put(centre, lexicon.node(centre, 0, null));
        boolean truncated = false;
        List<String> frontier = List.of(centre);
        for (int level = 1; level <= depth; level++) {
            List<String> next = new ArrayList<>();
            for (String key : frontier) {
                for (EdgeBuilder edge : touching.getOrDefault(key, List.of())) {
                    String other = edge.from.equals(key) ? edge.to : edge.from;
                    if (nodes.containsKey(other)) {
                        continue;
                    }
                    if (nodes.size() >= MAX_NODES) {
                        truncated = true;
                        continue;
                    }
                    nodes.put(other, lexicon.node(other, level, key));
                    next.add(other);
                }
            }
            frontier = next;
        }
        List<Edge> shown = new ArrayList<>();
        Set<EdgeBuilder> taken = new LinkedHashSet<>();
        for (String key : nodes.keySet()) {
            for (EdgeBuilder edge : touching.getOrDefault(key, List.of())) {
                if (nodes.containsKey(edge.from) && nodes.containsKey(edge.to) && taken.add(edge)) {
                    shown.add(edge.build());
                }
            }
        }
        return new View(Outcome.FOUND, new ArrayList<>(nodes.values()), shown, truncated, skippedStale, analyzers);
    }

    private record Match(String key, String text) {
    }

    private static final class EdgeBuilder {

        private final String from;
        private final String to;
        private final EdgeKind kind;
        private final String verb;
        private final List<Evidence> evidence = new ArrayList<>();
        private int count;

        private EdgeBuilder(String from, String to, EdgeKind kind, String verb) {
            this.from = from;
            this.to = to;
            this.kind = kind;
            this.verb = verb;
        }

        private void add(Evidence item) {
            count++;
            evidence.add(item);
        }

        private Edge build() {
            return new Edge(from, to, kind, verb, count, evidence);
        }
    }

    /** Die Knoten mit Geltungsbereichen, für den Abgleich der Erwähnungen. */
    static final class Lexicon {

        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, NodeKind> kinds = new LinkedHashMap<>();
        private final Map<String, List<RelationScope>> scopes = new LinkedHashMap<>();
        private final List<String[]> synonymEdges = new ArrayList<>();

        private Lexicon(List<Term> terms, List<Synonym> synonyms, List<Term> technical) {
            Set<String> technisch = new java.util.HashSet<>();
            technical.forEach(term -> technisch.add(TermText.normalize(term.term()).strip()));
            for (Term term : terms) {
                String key = TermText.normalize(term.term()).strip();
                if (key.isEmpty()) {
                    continue;
                }
                labels.putIfAbsent(key, term.term().strip());
                kinds.put(key, NodeKind.TERM);
                scopes.computeIfAbsent(key, unused -> new ArrayList<>()).add(term.scope());
            }
            Set<String> fachbegriffe = Set.copyOf(kinds.keySet());
            for (Synonym synonym : synonyms) {
                String from = TermText.normalize(synonym.fromTerm()).strip();
                String to = TermText.normalize(synonym.toTerm()).strip();
                if (from.isEmpty() || to.isEmpty() || from.equals(to)
                        || !fachbegriffe.contains(from) && !fachbegriffe.contains(to)) {
                    continue;
                }
                String term = fachbegriffe.contains(from) ? from : to;
                String other = term.equals(from) ? to : from;
                if (!fachbegriffe.contains(other) && technisch.contains(other)) {
                    // Ein bestätigter technischer Eintrag wird auch über ein Synonym kein Knoten der Fachsicht.
                    continue;
                }
                // Nur wo Synonym und Fachbegriff beide gelten: Ein gleich geschriebener Fachbegriff einer anderen
                // Quelle zieht kein fremdes Synonym nach. Sind beide Enden Fachbegriffe, muss es bei beiden gelten,
                // gleich in welcher Richtung das Synonym gespeichert ist.
                List<RelationScope> shared = new ArrayList<>();
                for (RelationScope termScope : scopes.get(term)) {
                    intersection(termScope, synonym.scope()).ifPresent(shared::add);
                }
                if (fachbegriffe.contains(other)) {
                    List<RelationScope> both = new ArrayList<>();
                    for (RelationScope one : shared) {
                        for (RelationScope two : scopes.get(other)) {
                            intersection(one, two).ifPresent(both::add);
                        }
                    }
                    shared = both;
                }
                if (shared.isEmpty()) {
                    continue;
                }
                if (!fachbegriffe.contains(other)) {
                    labels.putIfAbsent(other, (other.equals(from) ? synonym.fromTerm() : synonym.toTerm()).strip());
                    kinds.putIfAbsent(other, NodeKind.SYNONYM);
                    scopes.computeIfAbsent(other, unused -> new ArrayList<>()).addAll(shared);
                }
                synonymEdges.add(new String[] {term, other});
            }
        }

        /** Wo beide Geltungsbereiche gelten, oder leer, wenn sie sich nicht überschneiden. */
        static java.util.Optional<RelationScope> intersection(RelationScope one, RelationScope two) {
            if (one.global()) {
                return java.util.Optional.of(two);
            }
            if (two.global()) {
                return java.util.Optional.of(one);
            }
            if (!one.sourceId().equals(two.sourceId())) {
                return java.util.Optional.empty();
            }
            if (one.spaceKey() == null) {
                return java.util.Optional.of(two);
            }
            if (two.spaceKey() == null || one.spaceKey().equals(two.spaceKey())) {
                return java.util.Optional.of(one);
            }
            return java.util.Optional.empty();
        }

        private Node node(String key, int depth, String parent) {
            return new Node(key, labels.get(key), kinds.get(key), depth, parent);
        }

        /** Der Knoten, auf den die Erwähnung passt, oder {@code null}. Pronomen und offene Bezüge passen nie. */
        private String match(ParsedStatements.Mention mention, KnowledgeGraph.PageRef page) {
            if (mention.kind() == ParsedStatements.MentionKind.PRONOUN
                    || mention.kind() == ParsedStatements.MentionKind.CLAUSE || mention.unresolved()) {
                return null;
            }
            for (String candidate : candidates(mention)) {
                List<RelationScope> covering = scopes.get(candidate);
                if (covering != null && covering.stream()
                        .anyMatch(scope -> scope.covers(page.key().sourceId(), page.spaceKey()))) {
                    return candidate;
                }
            }
            return null;
        }

        private static Set<String> candidates(ParsedStatements.Mention mention) {
            Set<String> candidates = new LinkedHashSet<>();
            if (mention.lemma() != null && !mention.lemma().isBlank()) {
                candidates.add(TermText.normalize(mention.lemma()).strip());
            }
            if (mention.headForm() != null && !mention.headForm().isBlank()) {
                candidates.add(TermText.normalize(mention.headForm()).strip());
            }
            candidates.add(TermText.normalize(mention.text()).strip());
            candidates.remove("");
            return candidates;
        }
    }
}
