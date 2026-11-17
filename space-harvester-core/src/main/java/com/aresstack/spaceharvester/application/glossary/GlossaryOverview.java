package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Teilzahlen der Begriffsableitung für die Anzeige des Fachglossars (ADR 0032, FL5b): je Aufrufer gerechnet, nichts
 * gespeichert. Die Liste liefert je Begriff eines Geltungsbereichs die Wichtigkeit ohne Belege, damit der Reiter nach
 * Wichtigkeit sortieren kann; die Einzelheiten je Begriff liefern Verhalten, Eigenschaften und Ist-Beziehungen mit
 * Belegen. Gerechnet wird nur über die Seiten mit Aussagenanalyse, die der Aufrufer lesen darf; jeder Beleg ist also für
 * ihn lesbar.
 *
 * <p>Verknüpft wird über den Suchwert: {@link GlossaryDerivation.Term#normalized()} ist
 * {@link GlossaryEntry#normalized()} desselben Begriffs.
 */
public final class GlossaryOverview {

    private final Function<AccessContext, Collection<KnowledgeGraph.AnalysedPage>> analysed;
    private final Supplier<GlossaryRuleSettings> settings;
    private final Glossary glossary;

    /**
     * @param analysed die Seiten mit Aussagenanalyse, die ein Aufrufer lesen darf ({@code KnowledgeGraph#analysed})
     * @param settings Regeln der Begriffsableitung ({@code space-harvester.glossary.rules.*}), je Aufruf gelesen
     */
    public GlossaryOverview(Function<AccessContext, Collection<KnowledgeGraph.AnalysedPage>> analysed,
                            Supplier<GlossaryRuleSettings> settings) {
        this(analysed, settings, null);
    }

    /**
     * Mit Glossar für die fertige Sicht ({@link #view}).
     *
     * @param glossary das Fachglossar, aus dem die Sicht die Einträge liest
     */
    public GlossaryOverview(Function<AccessContext, Collection<KnowledgeGraph.AnalysedPage>> analysed,
                            Supplier<GlossaryRuleSettings> settings, Glossary glossary) {
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.glossary = glossary;
    }

    /**
     * Was der Reiter zeigt.
     *
     * @param q          Suchtext: Präfix des Begriffs oder eines seiner Wörter, oder {@code null}
     * @param categories Einordnungen oder {@code null} für alle; ohne Einordnung zählt {@code UNKLAR}. Der Reiter
     *                   fragt standardmäßig {@link #WITHOUT_TECHNICAL}
     * @param statuses   gezeigte Prüfstatus (Umschalter wie die Einordnung); {@code null} oder leer heißt
     *                   {@link #WITHOUT_REJECTED}: Vorschläge und bestätigte, ohne abgelehnte
     */
    public record Query(String q, Set<GlossaryDerivation.Category> categories, Set<ReviewStatus> statuses) {

        /** Standard des Reiters: ohne {@code TECHNISCH}. */
        public static final Set<GlossaryDerivation.Category> WITHOUT_TECHNICAL = Set.of(
                GlossaryDerivation.Category.FACHLICH, GlossaryDerivation.Category.ROLLE,
                GlossaryDerivation.Category.UNKLAR);

        /** Standard des Reiters beim Status: Vorschläge und bestätigte, ohne abgelehnte. */
        public static final Set<ReviewStatus> WITHOUT_REJECTED = Set.of(ReviewStatus.PROPOSED,
                ReviewStatus.CONFIRMED);

        /** Alles außer abgelehnten, ohne {@code TECHNISCH}. */
        public static final Query DEFAULT = new Query(null, WITHOUT_TECHNICAL, WITHOUT_REJECTED);

        public Query {
            categories = categories == null ? null : Set.copyOf(categories);
            statuses = statuses == null || statuses.isEmpty() ? WITHOUT_REJECTED : Set.copyOf(statuses);
        }

        /** Frühere Form mit einem Status oder {@code null} für Vorschläge und bestätigte. */
        public Query(String q, Set<GlossaryDerivation.Category> categories, ReviewStatus status) {
            this(q, categories, status == null ? null : Set.of(status));
        }

        /**
         * Frühere Form: der eine gewählte Status, {@code null} beim Standard oder bei mehreren.
         *
         * @deprecated {@link #statuses()} lesen
         */
        @Deprecated
        public ReviewStatus status() {
            return statuses.size() == 1 && !statuses.equals(WITHOUT_REJECTED) ? statuses.iterator().next() : null;
        }
    }

    /**
     * Eine Zeile der Sicht: ein Glossareintrag mit seiner Wichtigkeit.
     *
     * @param entry      der Eintrag, wie der Aufrufer ihn sehen darf
     * @param importance Teilzahlen in der Auswahl oder {@code null}, wenn die Ableitung ihn dort nicht kennt
     * @param category   Einordnung des Eintrags ({@code UNKLAR} ohne Einordnung)
     */
    public record ViewRow(GlossaryEntry entry, GlossaryDerivation.Importance importance,
                          GlossaryDerivation.Category category) {
    }

    /**
     * Die fertige Sicht des Reiters: sortiert, gefiltert, gezählt. Hält das Ergebnis der Ableitung, damit
     * {@link #term} ohne neues Rechnen antwortet; so lange halten, wie der Reiter die Auswahl zeigt.
     *
     * @param rows             Einträge nach Wichtigkeit absteigend (Seitenstreuung, Handelnder, Erwähnungen), Einträge
     *                         ohne Wichtigkeit hinten, alphabetisch
     * @param countsByCategory Zahlen je Einordnung über Suchtext und Status, ohne den Filter der Einordnung (jede
     *                         Einordnung enthalten, auch 0)
     * @param countsByStatus   Zahlen je Status über Suchtext und Einordnung, ohne den Filter des Status (jeder Status
     *                         enthalten, auch 0)
     * @param scope            Geltungsbereich der Einträge (aus der Auswahl)
     * @param pages            ausgewertete Seiten und Dokumente der Auswahl
     * @param stalePages       übersprungene veraltete Analysen
     */
    public record View(List<ViewRow> rows, Map<GlossaryDerivation.Category, Integer> countsByCategory,
                       Map<ReviewStatus, Integer> countsByStatus, RelationScope scope, int pages, int stalePages,
                       GlossaryDerivation.Result derivation) {

        public View {
            rows = List.copyOf(rows);
            countsByCategory = Map.copyOf(countsByCategory);
            countsByStatus = Map.copyOf(countsByStatus);
        }

        /** Einzelheiten eines Begriffs aus dem gehaltenen Ergebnis, ohne neues Rechnen; leer, wenn unbekannt. */
        public Optional<GlossaryDerivation.Term> term(String normalized) {
            return Optional.ofNullable(derivation.term(normalized));
        }
    }

    /**
     * Die fertige Sicht über eine im Bestand aufgelöste Auswahl (ADR 0029), wie {@code StatusQuo#view}: Wichtigkeit
     * über die Seiten der Auswahl (Knoten samt Kindern), Einträge aus dem Geltungsbereich, in dem die Auswahl liegt
     * (ein Space: global, Quelle und Space; eine Quelle: global und Quelle; mehrere Quellen: alle).
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft; es bleibt nichts zurück
     */
    public View view(AccessContext caller, PageSelection selection, Query query, BooleanSupplier cancelled) {
        Objects.requireNonNull(selection, "selection");
        return view(caller, scopeOf(selection), selection::contains, query, cancelled);
    }

    /**
     * Die fertige Sicht über die Seiten, die {@code pages} annimmt, mit Einträgen des Geltungsbereichs {@code scope}.
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft; es bleibt nichts zurück
     */
    public View view(AccessContext caller, RelationScope scope, Predicate<PageKey> pages, Query query,
                     BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(cancelled, "cancelled");
        if (glossary == null) {
            throw new IllegalStateException("Sicht ohne Glossar");
        }
        List<KnowledgeGraph.AnalysedPage> selected = analysed.apply(caller).stream()
                .filter(page -> pages.test(page.page().key())).toList();
        GlossaryDerivation.Result result = GlossaryDerivation.of(selected, cancelled,
                GlossaryDerivation.Rules.of(settings.get()));
        return assemble(caller, scope, result, query, cancelled);
    }

    /**
     * Verfeinern: dieselbe Auswahl mit anderem Suchtext, Status oder Einordnung. Nutzt das gehaltene Ergebnis der
     * vorigen Sicht und liest nur die Einträge neu (sie können sich seither geändert haben), ohne die Ableitung noch
     * einmal zu rechnen. Neue Auswahl oder Ende eines Auftrags verlangen die volle Sicht. {@code caller} muss derselbe
     * sein wie bei der vorigen Sicht, denn die Wichtigkeit gilt je Aufrufer.
     *
     * @throws CancellationException sobald {@code cancelled} zutrifft; es bleibt nichts zurück
     */
    public View view(AccessContext caller, View previous, Query query, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(cancelled, "cancelled");
        if (glossary == null) {
            throw new IllegalStateException("Sicht ohne Glossar");
        }
        return assemble(caller, previous.scope(), previous.derivation(), query, cancelled);
    }

    private View assemble(AccessContext caller, RelationScope scope, GlossaryDerivation.Result result, Query query,
                          BooleanSupplier cancelled) {
        List<GlossaryEntry> entries = new ArrayList<>(entries(caller, scope, query.q(), null, cancelled));
        entries.addAll(entries(caller, scope, query.q(), ReviewStatus.REJECTED, cancelled));
        Map<GlossaryDerivation.Category, Integer> byCategory = new EnumMap<>(GlossaryDerivation.Category.class);
        Map<ReviewStatus, Integer> byStatus = new EnumMap<>(ReviewStatus.class);
        for (GlossaryDerivation.Category category : GlossaryDerivation.Category.values()) {
            byCategory.put(category, 0);
        }
        for (ReviewStatus status : ReviewStatus.values()) {
            byStatus.put(status, 0);
        }
        List<ViewRow> rows = new ArrayList<>();
        for (GlossaryEntry entry : entries) {
            GlossaryDerivation.Category category = Glossary.categoryOf(entry);
            boolean statusFits = query.statuses().contains(entry.status());
            boolean categoryFits = query.categories() == null || query.categories().contains(category);
            if (statusFits) {
                byCategory.merge(category, 1, Integer::sum);
            }
            if (categoryFits) {
                byStatus.merge(entry.status(), 1, Integer::sum);
            }
            if (statusFits && categoryFits) {
                GlossaryDerivation.Term term = result.term(entry.normalized());
                rows.add(new ViewRow(entry, term == null ? null : term.importance(), category));
            }
        }
        rows.sort(ROW_ORDER);
        return new View(rows, byCategory, byStatus, scope, result.pages(), result.stalePages(), result);
    }

    /** Wichtige zuerst; ohne Wichtigkeit hinten; sonst nach Suchwert. */
    static final Comparator<ViewRow> ROW_ORDER = Comparator
            .comparing((ViewRow row) -> row.importance() == null)
            .thenComparing(ViewRow::importance, Comparator.nullsLast(GlossaryDerivation.Importance.ORDER.reversed()))
            .thenComparing(row -> row.entry().normalized());

    /** Geltungsbereich einer Auswahl: ein Space, eine Quelle oder alles. */
    static RelationScope scopeOf(PageSelection selection) {
        Set<String> sources = new HashSet<>();
        Set<String> spaces = new HashSet<>();
        selection.spaces().forEach(space -> {
            sources.add(space.sourceId());
            spaces.add(space.sourceId() + '\u0000' + space.spaceKey());
        });
        if (sources.size() != 1) {
            return RelationScope.GLOBAL;
        }
        String source = sources.iterator().next();
        return spaces.size() == 1 ? new RelationScope(source, selection.spaces().get(0).spaceKey())
                : new RelationScope(source, null);
    }

    private List<GlossaryEntry> entries(AccessContext caller, RelationScope scope, String q, ReviewStatus status,
                                        BooleanSupplier cancelled) {
        List<GlossaryEntry> all = new ArrayList<>();
        String cursor = null;
        do {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            Glossary.Page page = glossary.entries(caller, new Glossary.Search(q, status, null, scope.sourceId(),
                    scope.spaceKey(), null, Glossary.MAX_LIMIT, cursor));
            all.addAll(page.entries());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    /**
     * Ein Begriff der Liste, ohne Belege.
     *
     * @param term       Wortlaut (häufigste Schreibweise)
     * @param normalized Suchwert, gleich {@link GlossaryEntry#normalized()}
     * @param importance Teilzahlen der Wichtigkeit
     * @param category   Einordnung nach Regeln
     * @param rule       Regel der Einordnung
     */
    public record Row(String term, String normalized, GlossaryDerivation.Importance importance,
                      GlossaryDerivation.Category category, GlossaryDerivation.CategoryRule rule) {
    }

    /**
     * Die Liste eines Geltungsbereichs, nach Wichtigkeit ({@link GlossaryDerivation.Importance#ORDER}) absteigend.
     *
     * @param rows       Begriffe
     * @param pages      ausgewertete Seiten und Dokumente
     * @param stalePages übersprungene veraltete Analysen
     */
    public record Overview(List<Row> rows, int pages, int stalePages) {

        public Overview {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Teilzahlen aller Begriffe eines Geltungsbereichs.
     *
     * @param sourceId Quelle oder {@code null} für alle lesbaren Seiten
     * @param spaceKey Space der Quelle oder {@code null}
     * @throws CancellationException sobald {@code cancelled} zutrifft
     */
    public Overview importance(AccessContext caller, String sourceId, String spaceKey, BooleanSupplier cancelled) {
        GlossaryDerivation.Result result = derive(caller, sourceId, spaceKey, cancelled);
        return new Overview(result.terms().stream().map(term -> new Row(term.term(), term.normalized(),
                term.importance(), term.category(), term.rule())).toList(), result.pages(), result.stalePages());
    }

    /**
     * Einzelheiten eines Begriffs im Geltungsbereich mit Belegen, die der Aufrufer lesen darf, oder leer, wenn die
     * Ableitung den Begriff dort nicht kennt.
     *
     * @param term Wortlaut oder Suchwert
     */
    public Optional<GlossaryDerivation.Term> term(AccessContext caller, String sourceId, String spaceKey, String term,
                                                  BooleanSupplier cancelled) {
        Objects.requireNonNull(term, "term");
        return Optional.ofNullable(derive(caller, sourceId, spaceKey, cancelled).term(TermText.normalize(term)));
    }

    private GlossaryDerivation.Result derive(AccessContext caller, String sourceId, String spaceKey,
                                             BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        if (sourceId == null && spaceKey != null) {
            throw new IllegalArgumentException("spaceKey nur mit sourceId");
        }
        return GlossaryDerivation.of(inScope(analysed.apply(caller), sourceId, spaceKey), cancelled,
                GlossaryDerivation.Rules.of(settings.get()));
    }

    /** Die Seiten eines Geltungsbereichs: Quelle ({@code null} alle) und optional Space. */
    static List<KnowledgeGraph.AnalysedPage> inScope(Collection<KnowledgeGraph.AnalysedPage> pages, String sourceId,
                                                     String spaceKey) {
        return pages.stream()
                .filter(page -> sourceId == null || page.page().key().sourceId().equals(sourceId))
                .filter(page -> spaceKey == null || spaceKey.equals(page.page().spaceKey()))
                .toList();
    }
}
