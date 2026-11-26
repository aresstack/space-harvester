package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
 *
 * <p>Zwei Sichten teilen sich denselben Aufbau der Kanten: {@link #around} um einen Startbegriff und
 * {@link #onPages} seitenweit ohne Startbegriff (Paket W4b).
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
     * @param depth  Abstand zur Mitte: 0 die Mitte, 1 Nachbar, 2 Nachbar eines Nachbarn; in der seitenweiten Sicht
     *               ({@link #onPages}) ohne Mitte immer 0
     * @param parent Schlüssel des Knotens, über den er gefunden wurde; {@code null} für die Mitte und für jeden Knoten
     *               der seitenweiten Sicht
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
     * Gefragt wird je Seite und Abruf höchstens einmal, und nur, wenn eine ungeprüfte Aussage der aktuellen Seite (nicht
     * eines Anhangs) eine Kante trägt und „nur geprüft“ aus ist.
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
     * Die Merkmale einer Aussage, die eine Verb-Kante teilen (Paket W9, ADR 0032): Verneinung, Verbindlichkeit
     * (Modalverb) und Zeitbezug. „prüfen“ und „nicht prüfen“ sind zwei Kanten mit eigenen Belegen, nie eine. Nicht
     * bestimmte Merkmale (ältere Analysen, andere Analysatoren) gelten als schlicht: nicht verneint, ohne Modalverb,
     * gilt. Die einzige Regel dafür; Fachsicht, Begriffswissen (W6), Chat und Suche nutzen sie über die Kante.
     *
     * @param negated     ob die Aussage verneint ist
     * @param modality    Modalverb, {@link ParsedStatements.Modality#NONE} ohne
     * @param temporality Zeitbezug, {@link ParsedStatements.Temporality#GILT} ohne besonderen
     */
    public record Qualifiers(boolean negated, ParsedStatements.Modality modality,
                             ParsedStatements.Temporality temporality) {

        /** Schlicht: nicht verneint, ohne Modalverb, gilt. */
        public static final Qualifiers PLAIN = new Qualifiers(false, ParsedStatements.Modality.NONE,
                ParsedStatements.Temporality.GILT);

        public Qualifiers {
            modality = modality == null || modality == ParsedStatements.Modality.UNKNOWN
                    ? ParsedStatements.Modality.NONE : modality;
            temporality = temporality == null || temporality == ParsedStatements.Temporality.UNKNOWN
                    ? ParsedStatements.Temporality.GILT : temporality;
        }

        /** Die Merkmale einer gespeicherten Aussage. */
        public static Qualifiers of(ParsedStatements.Statement statement) {
            ParsedStatements.Features features = statement == null ? null : statement.features();
            if (features == null) {
                return PLAIN;
            }
            return new Qualifiers(features.negation() == ParsedStatements.Negation.NEGATED, features.modality(),
                    features.temporality());
        }

        /** Ob nichts davon besonders ist. */
        public boolean plain() {
            return equals(PLAIN);
        }

        /** Ob die Aussage nicht mehr gilt (früher oder verworfen); die Fachsicht zeichnet sie blasser. */
        public boolean past() {
            return temporality == ParsedStatements.Temporality.FRUEHER
                    || temporality == ParsedStatements.Temporality.VERWORFEN;
        }

        /**
         * Der Wortlaut mit der Grundform {@code verb}: „prüfen“, „nicht prüfen“, „muss prüfen“, „muss nicht prüfen
         * (geplant)“, „prüfen (früher)“; „gilt“ wird nicht angeschrieben.
         */
        public String phrase(String verb) {
            return phrase(verb, true);
        }

        /**
         * Wie {@link #phrase(String)}; ohne Grundform ({@code base} falsch: {@code verb} ist die gebeugte Form aus dem
         * Text) ohne Modalverb und „nicht“ davor, sonst entstünde „muss nicht prüft“. Der Zeitbezug bleibt.
         */
        public String phrase(String verb, boolean base) {
            StringBuilder text = new StringBuilder();
            if (base && modality != ParsedStatements.Modality.NONE) {
                text.append(modality.name().toLowerCase(Locale.ROOT)).append(' ');
            }
            if (base && negated) {
                text.append("nicht ");
            }
            text.append(verb == null ? "" : verb);
            String time = switch (temporality) {
                case GEPLANT -> "geplant";
                case FRUEHER -> "früher";
                case VERWORFEN -> "verworfen";
                default -> null;
            };
            if (time != null) {
                text.append(" (").append(time).append(')');
            }
            return text.toString();
        }

        String key() {
            return (negated ? "n" : "a") + "\u0000" + modality.name() + "\u0000" + temporality.name();
        }
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
     * @param qualifiers Merkmale aller Belege dieser Kante (Paket W9); bei Synonymen {@link Qualifiers#PLAIN}
     */
    public record Edge(String from, String to, EdgeKind kind, String verb, int count, List<Evidence> evidence,
                       Trust trust, boolean differs, Qualifiers qualifiers) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
            evidence = List.copyOf(evidence);
            trust = trust == null ? Trust.UNGEPRUEFT : trust;
            qualifiers = qualifiers == null ? Qualifiers.PLAIN : qualifiers;
        }

        /** Merkmale aus dem ersten Beleg (alle Belege einer Kante teilen sie). */
        public Edge(String from, String to, EdgeKind kind, String verb, int count, List<Evidence> evidence,
                    Trust trust, boolean differs) {
            this(from, to, kind, verb, count, evidence, trust, differs, kind == EdgeKind.VERB && !evidence.isEmpty()
                    ? Qualifiers.of(evidence.getFirst().statement()) : Qualifiers.PLAIN);
        }

        /** Stufe und Kennzeichen aus den Belegen. */
        public Edge(String from, String to, EdgeKind kind, String verb, int count, List<Evidence> evidence) {
            this(from, to, kind, verb, count, evidence, kind == EdgeKind.SYNONYM ? Trust.GEPRUEFT : rank(evidence),
                    kind == EdgeKind.VERB && differs(evidence));
        }

        /** Der Wortlaut der Aussage mit ihren Merkmalen ({@link Qualifiers#phrase}); bei Synonymen {@code null}. */
        public String phrase() {
            if (kind != EdgeKind.VERB) {
                return null;
            }
            boolean base = evidence.isEmpty() || evidence.stream().anyMatch(item -> item.statement().predicateLemma()
                    != null && !item.statement().predicateLemma().isBlank());
            return qualifiers.phrase(verb, base);
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
        NOT_A_TERM,
        /**
         * Nur seitenweit ({@link #onPages}): Kein bestätigter Fachbegriff dieser Seiten ist durch ein Verb mit einem
         * anderen verbunden. Ein neutraler Hinweis, kein Fehler.
         */
        NO_EDGES
    }

    /**
     * Eine Fachsicht um einen Startbegriff oder seitenweit über eine Seitenauswahl ({@link #onPages}).
     *
     * @param nodes          Knoten, die Mitte zuerst; seitenweit ohne Mitte, nach Zahl der Belege geordnet
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

        /**
         * Die Mitte: der erste Knoten. Die seitenweite Sicht ({@link #onPages}) hat keine Mitte; dort ist der erste
         * Knoten nur der mit den meisten Belegen und darf nicht als Mitte gelten.
         */
        public Node centre() {
            return nodes.isEmpty() ? null : nodes.getFirst();
        }
    }

    /**
     * Grenzen und Filter.
     *
     * @param depth        1 oder 2; die seitenweite Sicht ({@link #onPages}) kennt keine Tiefe und übergeht sie
     * @param includeStale Aussagen veralteter Seiten mitnehmen
     * @param pages        nur diese Seiten samt Dokumenten (Auswahl im Baum, ADR 0029) oder {@code null} für alle;
     *                     für {@link #onPages} Pflicht
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
    /**
     * Die sichtbaren Analysen nur der genannten Seiten ({@link KnowledgeGraph#analysed(AccessContext, PageSet)}), damit
     * eine Auswahl nicht erst die ganze Ablage liest; {@code null}: {@link #analysed} lesen und danach filtern.
     */
    private final java.util.function.BiFunction<AccessContext, PageSet, List<KnowledgeGraph.AnalysedPage>> analysedOn;
    /** Wo eine lesbare Seite liegt, auch ohne Analyse ({@link KnowledgeGraph#place}); für den Bereich. */
    private java.util.function.BiFunction<AccessContext, PageKey, java.util.Optional<KnowledgeGraph.Place>> places =
            (caller, key) -> java.util.Optional.empty();
    private final Vocabulary vocabulary;
    private final Comparison comparison;
    private final java.util.function.Supplier<AnalyzerChoice> selected;

    /** Ohne Vergleich der Analysatoren ({@link Comparison#NONE}). */
    public DomainTermGraph(KnowledgeGraph graph, Vocabulary vocabulary) {
        this(graph, vocabulary, () -> null, Comparison.NONE);
    }

    /**
     * @param selected   die eingestellte Ablage, aus der {@code graph} liest; für die Statuszeile
     * @param comparison Vergleich der Analysatoren (K2) oder {@link Comparison#NONE}
     */
    public DomainTermGraph(KnowledgeGraph graph, Vocabulary vocabulary,
                           java.util.function.Supplier<AnalyzerChoice> selected, Comparison comparison) {
        this(Objects.requireNonNull(graph, "graph")::analysed, graph::analysed, vocabulary, selected, comparison);
        this.places = graph::place;
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
        this(analysed, null, vocabulary, selected, comparison);
    }

    /**
     * @param analysedOn die sichtbaren Analysen nur der genannten Seiten, oder {@code null}: dann liest eine Auswahl
     *                   alle über {@code analysed} und filtert danach
     */
    DomainTermGraph(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed,
                    java.util.function.BiFunction<AccessContext, PageSet, List<KnowledgeGraph.AnalysedPage>> analysedOn,
                    Vocabulary vocabulary, java.util.function.Supplier<AnalyzerChoice> selected,
                    Comparison comparison) {
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.analysedOn = analysedOn;
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
        return suggestions(lexicon(caller), wanted, limit, key -> true);
    }

    /**
     * Vorschläge aus {@code lexicon} (wie {@link #suggestions(AccessContext, String, int)}), nur Einträge, die
     * {@code allowed} (Schlüssel) zulässt; gekappt wird erst danach.
     */
    static List<String> suggestions(Lexicon lexicon, String query, int limit,
                                    java.util.function.Predicate<String> allowed) {
        String wanted = query == null ? "" : TermText.normalize(query).strip();
        if (wanted.isEmpty() || limit < 1) {
            return List.of();
        }
        return lexicon.labels.keySet().stream().filter(key -> key.contains(wanted)).filter(allowed)
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
        Lexicon lexicon = lexicon(caller);
        if (!lexicon.labels.containsKey(centre)) {
            return View.of(Outcome.NOT_A_TERM);
        }
        return collect(caller, lexicon, options, cancelled)
                .finish(edges -> layout(centre, lexicon, edges, options.depth(), 0, Set.of()), selected);
    }

    /**
     * Die Begriffsgruppe um {@code start} für das Begriffswissen (Paket W6, {@link TermKnowledge}): der Begriff, die
     * unmittelbar an ihm hängenden Synonyme und alle Verb-Partner dieser Gruppe, höchstens {@link #MAX_NODES} Knoten,
     * die Partner mit den meisten Belegen zuerst. Gezählt wird genau wie in {@link #around} (derselbe Aufbau). Anders als
     * dort meldet {@link View#truncated()} nur, dass Verb-Kanten der Gruppe fehlen; Nachbarn der Partner gehören nicht
     * dazu und werden nicht gesucht. {@link Options#depth()} gilt hier nicht.
     */
    View group(AccessContext caller, String start, Options options, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancelled, "cancelled");
        String centre = start == null ? "" : TermText.normalize(start).strip();
        if (centre.isEmpty()) {
            return View.of(Outcome.EMPTY_START);
        }
        Lexicon lexicon = lexicon(caller);
        if (!lexicon.labels.containsKey(centre)) {
            return View.of(Outcome.NOT_A_TERM);
        }
        return collect(caller, lexicon, options, cancelled).finish(edges -> groupLayout(centre, lexicon, edges),
                selected);
    }

    /**
     * Ergebnis von {@link #groupWithin}: die Begriffsgruppe, die gelesenen Seiten des Bereichs und das Lexikon, das nur
     * Einträge kennt, deren Geltungsbereich den Bereich schneidet.
     */
    record Within(View view, Area area, Lexicon lexicon) {
    }

    /**
     * Der Bereich als Prüfung für Geltungsbereiche: global, oder eine lesbare Seite des Bereichs liegt darin (Quelle,
     * Space). Analysierte Seiten sind schon bekannt; Seiten ohne Analyse werden nur nachgeschlagen, wenn ein
     * Geltungsbereich ihrer Quelle sonst nicht abgedeckt wäre, und dann nur einmal.
     */
    static final class Area implements java.util.function.Predicate<RelationScope> {
        private final List<KnowledgeGraph.Place> known = new ArrayList<>();
        private final List<PageKey> rest = new ArrayList<>();
        private final java.util.function.Function<PageKey, java.util.Optional<KnowledgeGraph.Place>> resolve;
        private final Map<PageKey, java.util.Optional<KnowledgeGraph.Place>> resolved = new LinkedHashMap<>();

        Area(List<KnowledgeGraph.PageRef> analysed, PageSet pages,
             java.util.function.Function<PageKey, java.util.Optional<KnowledgeGraph.Place>> resolve) {
            this.resolve = resolve;
            Set<PageKey> seen = new java.util.HashSet<>();
            for (KnowledgeGraph.PageRef ref : analysed) {
                if (seen.add(ref.key())) {
                    known.add(new KnowledgeGraph.Place(ref.key(), ref.spaceKey()));
                }
            }
            pages.asMap().forEach((sourceId, ids) -> ids.forEach(pageId -> {
                PageKey key = new PageKey(sourceId, pageId);
                if (!seen.contains(key)) {
                    rest.add(key);
                }
            }));
        }

        @Override
        public boolean test(RelationScope scope) {
            if (scope.global()) {
                return true;
            }
            for (KnowledgeGraph.Place place : known) {
                if (scope.covers(place.key().sourceId(), place.spaceKey())) {
                    return true;
                }
            }
            for (PageKey key : rest) {
                if (!key.sourceId().equals(scope.sourceId())) {
                    continue;
                }
                java.util.Optional<KnowledgeGraph.Place> place = resolved.computeIfAbsent(key, resolve);
                if (place.isPresent() && scope.covers(key.sourceId(), place.get().spaceKey())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Ob eine lesbare Seite des Bereichs in {@code scope} liegt, für die {@code released} gilt (KI-Freigabe). Anders
         * als {@link #test} bricht das nicht bei der ersten deckenden Seite ab: Alle Seiten der Quelle des
         * Geltungsbereichs werden geprüft und dafür nötigenfalls nachgeschlagen (je Seite einmal, durch die Kappung
         * des Bereichs begrenzt), damit das Ergebnis nicht davon abhängt, was {@link #test} zuvor schon aufgelöst hat.
         */
        boolean coveredReleased(RelationScope scope, java.util.function.Predicate<PageKey> released) {
            for (KnowledgeGraph.Place place : known) {
                if (scope.covers(place.key().sourceId(), place.spaceKey()) && released.test(place.key())) {
                    return true;
                }
            }
            for (PageKey key : rest) {
                if (scope.sourceId() != null && !key.sourceId().equals(scope.sourceId())) {
                    continue;
                }
                if (!released.test(key)) {
                    continue; // nicht freigegeben: muss gar nicht nachgeschlagen werden
                }
                java.util.Optional<KnowledgeGraph.Place> place = resolved.computeIfAbsent(key, resolve);
                if (place.isPresent() && scope.covers(key.sourceId(), place.get().spaceKey())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Die Begriffsgruppe wie {@link #group}, aber nur mit Einträgen, die im Bereich {@code options.pages()} gelten
     * (für {@link TermKnowledge}): Ein Fachbegriff oder Synonym zählt nur, wenn einer seiner Geltungsbereiche global
     * ist oder eine lesbare Seite des Bereichs abdeckt (Quelle, Space), auch eine noch nicht analysierte. So entscheiden auch „ist ein Fachbegriff“,
     * Synonyme und Vorschläge nur nach dem Bereich, nie nach dem ganzen Glossar. Die Seiten werden einmal gelesen.
     *
     * @throws IllegalArgumentException ohne Seitenauswahl
     */
    Within groupWithin(AccessContext caller, String start, Options options, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancelled, "cancelled");
        if (options.pages() == null) {
            throw new IllegalArgumentException("Das Begriffswissen braucht einen Bereich");
        }
        List<KnowledgeGraph.AnalysedPage> source = (analysedOn != null ? analysedOn.apply(caller, options.pages())
                : analysed.apply(caller)).stream().filter(page -> options.pages().contains(page.page().key()))
                .toList();
        // Auch Seiten ohne Analyse gehören zum Bereich: ihr Space kommt aus dem Bestand (nur wenn nötig).
        // Nachschlagen nur, solange nicht abgebrochen: Der Aufbau des Lexikons kann viele Seiten auflösen.
        Area area = new Area(source.stream().map(KnowledgeGraph.AnalysedPage::page).toList(), options.pages(), key -> {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            return places.apply(caller, key);
        });
        Lexicon lexicon = new Lexicon(vocabulary.terms(caller), vocabulary.synonyms(caller),
                vocabulary.technical(caller), area);
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
        String centre = start == null ? "" : TermText.normalize(start).strip();
        if (centre.isEmpty()) {
            return new Within(View.of(Outcome.EMPTY_START), area, lexicon);
        }
        if (!lexicon.labels.containsKey(centre)) {
            return new Within(View.of(Outcome.NOT_A_TERM), area, lexicon);
        }
        View view = collect(caller, lexicon, options, cancelled, source)
                .finish(edges -> groupLayout(centre, lexicon, edges), selected);
        return new Within(view, area, lexicon);
    }

    /**
     * Die seitenweite Fachsicht ohne Startbegriff (Paket W4b): alle bestätigten Fachbegriffe und Synonyme der Seiten
     * {@code options.pages()}, die dort mit einem anderen Knoten durch ein Verb verbunden sind, höchstens
     * {@link #MAX_NODES}. Gezählt wird genau wie in {@link #around} (derselbe Aufbau): Standardansicht,
     * „nur geprüft“, veraltete Seiten und Vergleich der Analysatoren.
     *
     * <p>Die Knoten sind nach der Zahl ihrer Belege geordnet (Summe der Belege ihrer Verb-Kanten), absteigend, bei
     * Gleichstand nach Wortlaut (ohne Groß-/Kleinschreibung), dann nach Schlüssel; so bleibt die Auswahl stabil.
     * {@link View#truncated()} meldet, dass es mehr Knoten gäbe. Gekürzt wird in dieser Ordnung, aber nie so, dass ein
     * Knoten ohne sichtbare Kante dasteht: Hat ein Knoten noch keinen gezeigten Partner, kommt er nur zusammen mit
     * seinem stärksten Partner in die Sicht, sonst fällt er weg. Es gibt keine Mitte: Jeder Knoten hat Tiefe 0 und
     * keinen Vorgänger, und {@link View#centre()} ist hier bedeutungslos. Synonym-Kanten stehen nur zwischen zwei gezeigten
     * Knoten; ein Synonym wird nur über eigene Verb-Kanten ein Knoten. Verbindet kein Verb zwei Knoten, lautet das
     * Ergebnis {@link Outcome#NO_EDGES}.
     *
     * <p>{@code options.pages()} soll nur lesbare Seiten nennen; der Kern prüft das Leserecht trotzdem selbst: Gelesen
     * wird nur, was {@link KnowledgeGraph#analysed} für {@code caller} liefert (nur sichtbare Seiten). Eine genannte
     * Seite, die der Aufrufer nicht sehen darf, trägt nichts bei.
     *
     * @param options   {@link Options#pages()} ist Pflicht; {@link Options#depth()} gilt hier nicht
     * @param cancelled bricht zwischen zwei Seiten ab; dann gilt der Aufruf als verworfen
     * @throws IllegalArgumentException ohne Seitenauswahl
     */
    public View onPages(AccessContext caller, Options options, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancelled, "cancelled");
        if (options.pages() == null) {
            throw new IllegalArgumentException("Die seitenweite Fachsicht braucht eine Seitenauswahl");
        }
        Lexicon lexicon = lexicon(caller);
        return collect(caller, lexicon, options, cancelled).finish(edges -> pageLayout(lexicon, edges), selected);
    }

    private Lexicon lexicon(AccessContext caller) {
        return new Lexicon(vocabulary.terms(caller), vocabulary.synonyms(caller), vocabulary.technical(caller));
    }

    /**
     * Der gemeinsame Aufbau beider Sichten: Seite für Seite die Verb-Kanten zwischen Knoten des {@code lexicon} samt
     * Belegen, Stufen ({@link #trustFor}), Vergleich der Analysatoren nur, wo er die Stufe ändern kann, „nur geprüft“
     * und die ausgelassenen veralteten Aussagen. Nur hier stehen die Zählregeln.
     *
     * @param cancelled bricht zwischen zwei Seiten ab ({@link java.util.concurrent.CancellationException})
     */
    private Collected collect(AccessContext caller, Lexicon lexicon, Options options, BooleanSupplier cancelled) {
        // Mit Auswahl nur deren Seiten lesen, nicht die ganze Ablage; der Filter unten bleibt als Absicherung.
        return collect(caller, lexicon, options, cancelled, options.pages() != null && analysedOn != null
                ? analysedOn.apply(caller, options.pages()) : analysed.apply(caller));
    }

    /** Wie oben, über schon gelesene Analysen {@code source}. */
    private Collected collect(AccessContext caller, Lexicon lexicon, Options options, BooleanSupplier cancelled,
                              List<KnowledgeGraph.AnalysedPage> source) {
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
        for (KnowledgeGraph.AnalysedPage page : source) {
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
                        case ACTOR -> actors.add(new Match(key, mention.text(), argument.role()));
                        case AFFECTED_OBJECT, RECIPIENT, PREPOSITIONAL_OBJECT ->
                                objects.add(new Match(key, mention.text(), argument.role()));
                        default -> {
                        }
                    }
                }
                if (actors.isEmpty() || objects.isEmpty()) {
                    continue;
                }
                AnalyzerComparison.Comparison match = null;
                // Fragen nur, wo der Abgleich die Stufe ändern kann: nicht geprüft, nicht „nur geprüft“, Seite aktuell
                // (sonst ist ohnehin nichts vergleichbar) und die Seite selbst (Anhänge vergleicht K2 nicht).
                if (trust(statement, null) != Trust.GEPRUEFT && !options.checkedOnly()
                        && ref.state() == PageStatements.State.CURRENT && ref.document().isPage()) {
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
                // Verneinung, Modalverb und Zeitbezug trennen Kanten (W9): eine Regel, hier.
                String qualified = verb + "\u0000" + Qualifiers.of(statement).key();
                if (ref.state() == PageStatements.State.STALE && !options.includeStale()) {
                    Set<String> pairs = new LinkedHashSet<>();
                    for (Match actor : actors) {
                        for (Match object : objects) {
                            if (!actor.key().equals(object.key())
                                    && pairs.add(actor.key() + "\u0000" + object.key())) {
                                withStale.computeIfAbsent("verb\u0000" + actor.key() + "\u0000" + qualified + "\u0000"
                                                + object.key(),
                                        unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb))
                                        .add(evidence(ref, statement, page.analysis().analyzerId(), actor, object,
                                                trust, match));
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
                        String key = "verb\u0000" + actor.key() + "\u0000" + qualified + "\u0000" + object.key();
                        Evidence item = evidence(ref, statement, page.analysis().analyzerId(), actor, object, trust,
                                match);
                        edges.computeIfAbsent(key,
                                unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb)).add(item);
                        withStale.computeIfAbsent(key,
                                unused -> new EdgeBuilder(actor.key(), object.key(), EdgeKind.VERB, verb)).add(item);
                    }
                }
            }
        }
        return new Collected(edges, withStale, stale, analyzers);
    }

    /**
     * Ergebnis von {@code collect}.
     *
     * @param edges     die Kanten der Sicht
     * @param withStale dieselben Kanten, als wären die veralteten Aussagen aktuell
     * @param stale     je ausgelassener veralteter Aussage die Paare (Schlüssel von und nach), die sie verbände
     * @param analyzers Analysatoren aller gelesenen Seiten
     */
    private record Collected(Map<String, EdgeBuilder> edges, Map<String, EdgeBuilder> withStale,
                             List<Set<String>> stale, Set<String> analyzers) {

        /**
         * Legt die Sicht mit {@code layout} an und zählt die ausgelassenen veralteten Aussagen: nur die, deren Kante in
         * derselben Sicht mit „veraltete zeigen“ läge (beide Enden wären Knoten).
         */
        View finish(Function<Map<String, EdgeBuilder>, View> layout,
                    java.util.function.Supplier<AnalyzerChoice> selected) {
            View view = layout.apply(edges);
            Set<String> shown = new java.util.HashSet<>();
            if (!stale.isEmpty()) {
                layout.apply(withStale).nodes().forEach(node -> shown.add(node.key()));
            }
            // Nur veraltete Aussagen, deren Kante in dieser Sicht läge, wären sie aktuell: beide Enden wären Knoten.
            int skippedStale = (int) stale.stream().filter(pairs -> pairs.stream().anyMatch(pair -> {
                int cut = pair.indexOf('\u0000');
                return shown.contains(pair.substring(0, cut)) && shown.contains(pair.substring(cut + 1));
            })).count();
            return new View(view.outcome(), view.nodes(), view.edges(), view.truncated(), skippedStale, analyzers,
                    selected.get());
        }
    }

    /**
     * Ob eine Aussage für die Fachsicht zählt: Standardansicht ({@link PageGraph#DEFAULT_QUALITIES}), nicht vom
     * Menschen abgelehnt ({@link CountedStatements}), keine Zustandsaussage (ihr Prädikat ist kein Verb) und mit eigener
     * Stelle ({@link ParsedStatements.Derivation#ownSite}): Aussagen der Vorstufe und Korrekturen des Prüfschritts sind
     * nach ADR 0032 nie Beleg, sonst zählte eine Stelle doppelt (die Korrektur legt neben dem Original eine Aussage mit
     * demselben Verb an). Programmaussagen zählen als Ist-Zustand mit (Fortschreibung 26.11.2026).
     */
    static boolean counts(ParsedStatements.Statement statement) {
        return statement.derivation().ownSite()
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
        Map<String, List<EdgeBuilder>> touching = touching(edges);
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
        return new View(Outcome.FOUND, new ArrayList<>(nodes.values()), shown(nodes, touching), truncated,
                skippedStale, analyzers);
    }

    /**
     * Seitenweit: Knoten sind alle Einträge mit mindestens einer belegten Verb-Kante, nach Zahl der Belege absteigend,
     * dann Wortlaut ohne Groß-/Kleinschreibung, dann Schlüssel; höchstens {@link #MAX_NODES}, jeder mit mindestens
     * einem gezeigten Verb-Partner, alle Tiefe 0 ohne Vorgänger. Synonym-Kanten zählen nicht für die Auswahl und erscheinen nur zwischen gezeigten Knoten.
     */
    /** Aufbau für {@link #group}: Gruppe zuerst, dann ihre Verb-Partner nach Zahl der Belege. */
    private static View groupLayout(String centre, Lexicon lexicon, Map<String, EdgeBuilder> edges) {
        Map<String, List<EdgeBuilder>> touching = touching(edges);
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put(centre, lexicon.node(centre, 0, null));
        List<String> group = new ArrayList<>();
        group.add(centre);
        for (EdgeBuilder edge : touching.getOrDefault(centre, List.of())) {
            String other = edge.from.equals(centre) ? edge.to : edge.from;
            if (edge.kind == EdgeKind.SYNONYM && !nodes.containsKey(other) && nodes.size() < MAX_NODES) {
                nodes.put(other, lexicon.node(other, 1, centre));
                group.add(other);
            }
        }
        List<EdgeBuilder> verbs = new ArrayList<>();
        Map<EdgeBuilder, String> owner = new java.util.IdentityHashMap<>();
        for (String key : group) {
            for (EdgeBuilder edge : touching.getOrDefault(key, List.of())) {
                if (edge.kind == EdgeKind.VERB && !owner.containsKey(edge)) {
                    owner.put(edge, key);
                    verbs.add(edge);
                }
            }
        }
        // Partner nach allen Belegen zwischen Gruppe und Partner (Paket W9: eine Kante je Aussage, ein Partner kann
        // mehrere tragen); gleich stark bleibt die Reihenfolge der stärksten Kante.
        Map<String, Integer> weight = new java.util.HashMap<>();
        for (EdgeBuilder edge : verbs) {
            String key = owner.get(edge);
            weight.merge(edge.from.equals(key) ? edge.to : edge.from, edge.evidence.size(), Integer::sum);
        }
        verbs.sort(java.util.Comparator.comparingInt((EdgeBuilder edge) -> {
            String key = owner.get(edge);
            return weight.get(edge.from.equals(key) ? edge.to : edge.from);
        }).thenComparingInt(edge -> edge.evidence.size()).reversed());
        boolean truncated = false;
        for (EdgeBuilder edge : verbs) {
            String key = owner.get(edge);
            String other = edge.from.equals(key) ? edge.to : edge.from;
            if (nodes.containsKey(other)) {
                continue;
            }
            if (nodes.size() >= MAX_NODES) {
                truncated = true;
                continue;
            }
            nodes.put(other, lexicon.node(other, nodes.get(key).depth() + 1, key));
        }
        return new View(Outcome.FOUND, new ArrayList<>(nodes.values()), shown(nodes, touching), truncated, 0,
                Set.of());
    }

    private static View pageLayout(Lexicon lexicon, Map<String, EdgeBuilder> edges) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (EdgeBuilder edge : edges.values()) {
            if (edge.kind == EdgeKind.VERB && !edge.evidence.isEmpty()) {
                weights.merge(edge.from, edge.evidence.size(), Integer::sum);
                weights.merge(edge.to, edge.evidence.size(), Integer::sum);
            }
        }
        if (weights.isEmpty()) {
            return new View(Outcome.NO_EDGES, List.of(), List.of(), false, 0, Set.of());
        }
        List<String> ranked = weights.keySet().stream()
                .sorted(java.util.Comparator.<String>comparingInt(weights::get).reversed()
                        .thenComparing(lexicon.labels::get, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .toList();
        Map<String, Integer> rank = new java.util.HashMap<>();
        ranked.forEach(key -> rank.put(key, rank.size()));
        // Verb-Partner je Knoten, der stärkste zuerst: Ein Knoten kommt nur mit einem sichtbaren Partner in die Sicht.
        Map<String, java.util.TreeSet<String>> partners = new java.util.HashMap<>();
        for (EdgeBuilder edge : edges.values()) {
            if (edge.kind == EdgeKind.VERB && !edge.evidence.isEmpty()) {
                partners.computeIfAbsent(edge.from, unused -> new java.util.TreeSet<>(
                        java.util.Comparator.comparingInt(rank::get))).add(edge.to);
                partners.computeIfAbsent(edge.to, unused -> new java.util.TreeSet<>(
                        java.util.Comparator.comparingInt(rank::get))).add(edge.from);
            }
        }
        Set<String> kept = new java.util.HashSet<>();
        for (String key : ranked) {
            if (kept.contains(key)) {
                continue;
            }
            if (partners.get(key).stream().anyMatch(kept::contains)) {
                if (kept.size() < MAX_NODES) {
                    kept.add(key);
                }
            } else if (kept.size() + 2 <= MAX_NODES) {
                kept.add(key);
                kept.add(partners.get(key).first());
            }
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        ranked.stream().filter(kept::contains).forEach(key -> nodes.put(key, lexicon.node(key, 0, null)));
        return new View(Outcome.FOUND, new ArrayList<>(nodes.values()), shown(nodes, touching(edges)),
                kept.size() < ranked.size(), 0, Set.of());
    }

    /** Je Knoten die Kanten, die ihn berühren; Verb-Kanten ohne Beleg (nur veraltet belegt) fallen weg. */
    private static Map<String, List<EdgeBuilder>> touching(Map<String, EdgeBuilder> edges) {
        Map<String, List<EdgeBuilder>> touching = new LinkedHashMap<>();
        for (EdgeBuilder edge : edges.values()) {
            if (edge.kind == EdgeKind.VERB && edge.evidence.isEmpty()) {
                continue;
            }
            touching.computeIfAbsent(edge.from, unused -> new ArrayList<>()).add(edge);
            touching.computeIfAbsent(edge.to, unused -> new ArrayList<>()).add(edge);
        }
        return touching;
    }

    /** Die Kanten zwischen zwei gezeigten Knoten, in der Reihenfolge der Knoten, jede einmal. */
    private static List<Edge> shown(Map<String, Node> nodes, Map<String, List<EdgeBuilder>> touching) {
        List<Edge> shown = new ArrayList<>();
        Set<EdgeBuilder> taken = new LinkedHashSet<>();
        for (String key : nodes.keySet()) {
            for (EdgeBuilder edge : touching.getOrDefault(key, List.of())) {
                if (nodes.containsKey(edge.from) && nodes.containsKey(edge.to) && taken.add(edge)) {
                    shown.add(edge.build());
                }
            }
        }
        return shown;
    }

    private record Match(String key, String text, ParsedStatements.Role role) {
    }

    /** Ein Beleg mit der Stufe, die für genau diese Kante gilt ({@link #trustFor}). */
    private static Evidence evidence(KnowledgeGraph.PageRef ref, ParsedStatements.Statement statement,
                                     String analyzerId, Match actor, Match object, Trust trust,
                                     AnalyzerComparison.Comparison match) {
        Trust edgeTrust = trustFor(trust, match, object.role());
        return new Evidence(ref, statement, analyzerId, actor.text(), object.text(), edgeTrust,
                edgeTrust == trust ? match : null);
    }

    /**
     * Die Stufe eines Belegs für eine Kante, die an {@code objectRole} endet. Der Abgleich (K2) vergleicht nur Verb,
     * Handelnde und Betroffene: Endet die Kante an einem Empfänger oder Präpositionalobjekt, sagt „einig“ über sie
     * nichts, und eine Abweichung allein bei den Betroffenen auch nicht; beides bleibt dann ungeprüft. Ein anderes
     * Verb oder ein anderer Handelnder trifft jede Kante der Aussage.
     */
    static Trust trustFor(Trust trust, AnalyzerComparison.Comparison match, ParsedStatements.Role objectRole) {
        if (objectRole == ParsedStatements.Role.AFFECTED_OBJECT) {
            return trust;
        }
        return switch (trust) {
            case GEPRUEFT, UNGEPRUEFT -> trust;
            case EINIG -> Trust.UNGEPRUEFT;
            case ABWEICHEND -> match != null && (match.differences().contains(AnalyzerComparison.Difference.VERB)
                    || match.differences().contains(AnalyzerComparison.Difference.ACTOR)) ? trust : Trust.UNGEPRUEFT;
        };
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
        /** Je Synonymkante (beide Richtungen) die Geltungsbereiche, in denen die Beziehung selbst gilt. */
        private final Map<String, List<RelationScope>> edgeScopes = new LinkedHashMap<>();

        private Lexicon(List<Term> terms, List<Synonym> synonyms, List<Term> technical) {
            this(terms, synonyms, technical, scope -> true);
        }

        /** Nur Geltungsbereiche, die {@code within} zulässt (Bereich des Begriffswissens). */
        private Lexicon(List<Term> terms, List<Synonym> synonyms, List<Term> technical,
                        java.util.function.Predicate<RelationScope> within) {
            Set<String> technisch = new java.util.HashSet<>();
            technical.forEach(term -> technisch.add(TermText.normalize(term.term()).strip()));
            // Fachbegriffe, die nur außerhalb des Bereichs gelten: weder Knoten noch über ein Synonym ein Synonymknoten
            // (sonst passte er im Bereich, wo er in der Fachsicht nie passt).
            Set<String> outside = new java.util.HashSet<>();
            for (Term term : terms) {
                String key = TermText.normalize(term.term()).strip();
                if (!key.isEmpty() && !within.test(term.scope())) {
                    outside.add(key);
                }
            }
            for (Term term : terms) {
                String key = TermText.normalize(term.term()).strip();
                if (key.isEmpty() || !within.test(term.scope())) {
                    continue;
                }
                labels.putIfAbsent(key, term.term().strip());
                kinds.put(key, NodeKind.TERM);
                scopes.computeIfAbsent(key, unused -> new ArrayList<>()).add(term.scope());
            }
            Set<String> fachbegriffe = Set.copyOf(kinds.keySet());
            outside.removeAll(fachbegriffe);
            for (Synonym synonym : synonyms) {
                String from = TermText.normalize(synonym.fromTerm()).strip();
                String to = TermText.normalize(synonym.toTerm()).strip();
                if (from.isEmpty() || to.isEmpty() || from.equals(to)
                        || !fachbegriffe.contains(from) && !fachbegriffe.contains(to)) {
                    continue;
                }
                String term = fachbegriffe.contains(from) ? from : to;
                String other = term.equals(from) ? to : from;
                if (outside.contains(other)) {
                    continue;
                }
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
                shared.removeIf(scope -> !within.test(scope));
                if (shared.isEmpty()) {
                    continue;
                }
                if (!fachbegriffe.contains(other)) {
                    labels.putIfAbsent(other, (other.equals(from) ? synonym.fromTerm() : synonym.toTerm()).strip());
                    kinds.putIfAbsent(other, NodeKind.SYNONYM);
                    scopes.computeIfAbsent(other, unused -> new ArrayList<>()).addAll(shared);
                }
                synonymEdges.add(new String[] {term, other});
                edgeScopes.computeIfAbsent(term + "\u0000" + other, unused -> new ArrayList<>()).addAll(shared);
                edgeScopes.computeIfAbsent(other + "\u0000" + term, unused -> new ArrayList<>()).addAll(shared);
            }
        }

        /** Die Geltungsbereiche eines Knotens (leer, wenn er nicht im Lexikon steht). */
        List<RelationScope> scopes(String key) {
            return List.copyOf(scopes.getOrDefault(key, List.of()));
        }

        /** Wo die Synonymbeziehung zwischen {@code one} und {@code two} gilt (leer ohne Beziehung). */
        List<RelationScope> edgeScopes(String one, String two) {
            return List.copyOf(edgeScopes.getOrDefault(one + "\u0000" + two, List.of()));
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
