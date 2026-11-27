package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.application.glossary.TechnicalTerms;
import com.aresstack.spaceharvester.application.holdings.Selection.Root;
import com.aresstack.spaceharvester.application.knowledge.VerbQueries;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermOrigin;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Die Wortsuche (Angelo 27.11.2026): sucht nicht nach Seiten, sondern nach den erkannten Begriffen im Bereich der
 * Suche, getrennt nach Worttypen wie im Reiter „Begriffe“ (Akronyme, Bezeichner, Verben). Dieselben Abfragen des Core
 * wie dort ({@link SelectionTerms}, {@code TermSearch.within}, {@code VerbQueries}); dazu die Herkunft (Seiten,
 * Anhänge) aus den Dateitypen und weitere Suchtexte, etwa Synonyme von Fachbegriffen. Nie auf dem EDT aufrufen.
 */
public final class WordSearch {

    /** Die Worttypen der Wortsuche, wie die Abschnitte des Reiters „Begriffe“. */
    public enum Kind {
        ACRONYMS(TermsTab.ACRONYMS, "Akronym"),
        IDENTIFIERS(TermsTab.IDENTIFIERS, "Bezeichner"),
        VERBS(TermsTab.VERBS, "Verb");

        private final String label;
        private final String one;

        Kind(String label, String one) {
            this.label = label;
            this.one = one;
        }

        /** Name in den Suchoptionen, wie der Abschnitt im Reiter „Begriffe“. */
        public String label() {
            return label;
        }

        /** Name eines einzelnen Worts in der Liste. */
        public String one() {
            return one;
        }
    }

    /**
     * Ein gefundenes Wort: ein Begriff oder ein Verb.
     *
     * @param text   Wortlaut
     * @param kind   Worttyp
     * @param pages  Zahl der Seiten im Bereich
     * @param count  Fundstellen (Begriffe) bzw. Aussagen (Verben)
     * @param detail Ausschreibungen eines Akronyms bzw. weitere Schreibweisen, sonst leer
     * @param term   der Begriff des Core oder {@code null} bei einem Verb
     * @param verb   das Verb des Core oder {@code null} bei einem Begriff
     */
    public record Word(String text, Kind kind, int pages, int count, String detail, TermQueries.TermSummary term,
                       VerbQueries.Verb verb) {

        public Word {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(kind, "kind");
            detail = detail == null ? "" : detail;
        }

        /** Schlüssel gegen Doppelte, wenn mehrere Suchtexte denselben Begriff finden. */
        public String key() {
            return kind + "\u0000" + (term != null ? term.normalized() : verb.lemma());
        }

        static Word of(TermQueries.TermSummary term, Kind kind) {
            List<String> more = !term.expansions().isEmpty() ? term.expansions()
                    : term.spellings().stream().filter(spelling -> !spelling.equals(term.term())).toList();
            return new Word(term.term(), kind, term.pages(), term.occurrences() + term.documentOccurrences(),
                    String.join(" · ", more), term, null);
        }

        static Word of(VerbQueries.Verb verb) {
            return new Word(verb.lemma(), Kind.VERBS, verb.pages(), verb.statements(), "", null, verb);
        }
    }

    /** Eine Seite mit Fundstellen eines Worts. */
    public record Place(String sourceId, String spaceKey, String pageId, String title, int count) {
    }

    /** Wie viele Seiten im Bereich ohne (aktuelle) Aussagenanalyse sind; ihre Verben fehlen. */
    public record VerbCoverage(int analysed, int unanalysed, int stale) {

        public boolean complete() {
            return unanalysed == 0 && stale == 0;
        }
    }

    /** So viele Einträge lädt die Liste je Schritt, wie der Reiter „Begriffe“. */
    public static final int STEP = TermsTab.STEP;

    private final Workspace workspace;
    private final SelectionTerms terms;
    private final ExportPanel.Selection selection;
    private final AppRuntime runtime;
    private final TermOrigin origin;

    /**
     * @param selection der Bereich der Suche, wie links gewählt und in den Suchoptionen angehakt
     * @param origin    Seiten, Anhänge oder beides, aus den Dateitypen der Suchoptionen
     */
    public WordSearch(AppRuntime runtime, ExportPanel.Selection selection, TermOrigin origin) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workspace = runtime.workspace();
        this.selection = Objects.requireNonNull(selection, "selection");
        this.terms = new SelectionTerms(runtime, selection);
        this.origin = origin == null ? TermOrigin.PAGES : origin;
    }

    /** Der Bereich als Auswahl: ein Knoten bzw. die angehakten Quellen und gewählten Knoten als Sammlung. */
    public static ExportPanel.Selection area(List<Root> roots, String title) {
        if (roots.size() == 1) {
            Root root = roots.get(0);
            return new ExportPanel.Selection.Node(root.sourceId(), root.spaceKey(), root.pageId(), title);
        }
        return new ExportPanel.Selection.Collection(AREA, title, roots, false);
    }

    /** Kennung der Sammlung „Bereich der Suche“; kein Favoriten-Ordner trägt sie. */
    static final String AREA = "search-area";

    /** Die Wurzeln des Bereichs. */
    public List<Root> roots() {
        return selection.roots(runtime);
    }

    /**
     * Die Wörter zu den Suchtexten {@code queries} (leer bzw. nur ein leerer: alle) in der Reihenfolge der Worttypen;
     * innerhalb eines Typs alphabetisch, Verben wie im Core nach Zahl der Aussagen.
     */
    public Pager<Word> words(Set<Kind> kinds, List<String> queries) {
        List<String> texts = queries.isEmpty() ? java.util.Collections.singletonList(null) : queries;
        List<List<Pager.Fetch<Word>>> groups = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!kinds.contains(kind)) {
                continue;
            }
            List<Pager.Fetch<Word>> streams = new ArrayList<>();
            for (String q : texts) {
                switch (kind) {
                    case ACRONYMS -> streams.add(terms(TermKind.ACRONYM, q, kind));
                    case IDENTIFIERS -> {
                        streams.add(terms(TermKind.NOUN, q, kind));
                        streams.add(terms(TermKind.IDENTIFIER, q, kind));
                    }
                    case VERBS -> streams.add(verbs(q));
                }
            }
            groups.add(streams);
        }
        // Verben ordnet der Core nach Aussagen; die übrigen nach Suchwert. Dieselbe Ordnung je Strom (Pager).
        return new Pager<>(Comparator.comparing((Word word) -> word.kind() == Kind.VERBS ? "" : word.term()
                .normalized()), groups);
    }

    private Pager.Fetch<Word> terms(TermKind termKind, String q, Kind kind) {
        String text = q == null || q.isBlank() ? null : q;
        return cursor -> {
            TermQueries.TermList list = workspace.terms(new TermQueries.TermSearch(null, null, termKind, text, false,
                    STEP, cursor, null, origin).within(terms.pages()));
            return new Pager.Page<>(list.terms().stream().map(term -> Word.of(term, kind)).toList(),
                    list.nextCursor());
        };
    }

    private Pager.Fetch<Word> verbs(String q) {
        String text = q == null || q.isBlank() ? null : q;
        return cursor -> {
            VerbQueries.VerbList list = workspace.verbs(terms.pages(), text, STEP, cursor);
            return new Pager.Page<>(list.verbs().stream().map(Word::of).toList(), list.nextCursor());
        };
    }

    /** Die Seiten mit Fundstellen eines Worts im Bereich, je Seite einmal, mit Zahl der Fundstellen. */
    public List<Place> places(Word word) throws Exception {
        Map<String, Place> byPage = new LinkedHashMap<>();
        if (word.term() != null) {
            TermQueries.OccurrenceList list = workspace.occurrences(new TermQueries.OccurrenceSearch(
                    word.term().normalized(), word.term().kind(), null, null, TermQueries.MAX_LIMIT, null, 0, origin)
                    .within(terms.pages()));
            for (TermQueries.Occurrence occurrence : list.occurrences()) {
                byPage.merge(occurrence.sourceId() + "/" + occurrence.pageId(),
                        new Place(occurrence.sourceId(), occurrence.spaceKey(), occurrence.pageId(),
                                occurrence.title(), 1),
                        (one, other) -> new Place(one.sourceId(), one.spaceKey(), one.pageId(), one.title(),
                                one.count() + 1));
            }
        } else {
            for (VerbQueries.Occurrence occurrence : terms.verbOccurrences(word.verb())) {
                var page = occurrence.page();
                byPage.merge(page.key().sourceId() + "/" + page.key().pageId(),
                        new Place(page.key().sourceId(), page.spaceKey(), page.key().pageId(), page.title(), 1),
                        (one, other) -> new Place(one.sourceId(), one.spaceKey(), one.pageId(), one.title(),
                                one.count() + 1));
            }
        }
        return List.copyOf(byPage.values());
    }

    /** Wie weit die Aussagenanalyse den Bereich abdeckt; ohne sie gibt es keine Verben. */
    public VerbCoverage verbCoverage() {
        VerbQueries.VerbList list = workspace.verbs(terms.pages(), null, 1, null);
        return new VerbCoverage(list.analysedPages(), list.unanalysed(), list.stalePages());
    }

    /**
     * Die Wurzeln des Bereichs, für die es noch keinen Fachbegriff gibt (Fachglossar nicht erstellt). Eine Seite als
     * Wurzel zählt über ihren Space, denn das Glossar ordnet Fachbegriffe Quellen und Spaces zu.
     */
    public List<Root> withoutTechnicalTerms() {
        List<Root> missing = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();
        for (Root root : roots()) {
            String key = root.sourceId() + "\u0000" + root.spaceKey();
            if (!checked.add(key)) {
                continue;
            }
            TechnicalTerms.Scope scope = new TechnicalTerms.Scope(Set.of(root.sourceId()),
                    root.spaceKey() == null ? Set.of() : Set.of(root.spaceKey()));
            if (workspace.technicalTerms(null, scope, 1, null).hits().isEmpty()) {
                missing.add(root);
            }
        }
        return List.copyOf(missing);
    }

    /** Die Herkunft der Begriffe aus den angehakten Dateitypen: nur Seiten, nur Anhänge oder beides. */
    public static TermOrigin origin(boolean pages, boolean documents) {
        return pages && documents ? TermOrigin.ALL : documents ? TermOrigin.DOCUMENTS : TermOrigin.PAGES;
    }
}
