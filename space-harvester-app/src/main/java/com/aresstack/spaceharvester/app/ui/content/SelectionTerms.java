package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.app.ui.Relation;
import com.aresstack.spaceharvester.application.knowledge.VerbQueries;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Die Begriffe einer Auswahl links für {@link TermsTab} (Angelo 17.11.2026): Akronyme, Bezeichner und Beziehungen aus
 * dem Core, begrenzt auf die Seiten der Auswahl (ADR 0029, {@code TermSearch.within}, {@code RelationSearch.pages}).
 * Beim Wissensquellen-Kopf gilt keine Seitenmenge, damit auch von Hand angelegte Beziehungen ohne Fundstelle zu sehen
 * sind. Die Seitenmenge wird einmal aufgelöst, beim ersten Abruf im Hintergrund. Nie auf dem EDT aufrufen.
 */
final class SelectionTerms implements TermsTab.Selection {

    private final Workspace workspace;
    private final ExportPanel.Selection selection;
    private final AppRuntime runtime;
    /** Fest aufgelöste Seiten, etwa die Unterseiten einer Seite (Fahrplan P2); sonst {@code null}. */
    private final com.aresstack.spaceharvester.application.holdings.PageSelection fixed;
    private final String fixedTitle;
    private PageSet pages;
    private boolean resolved;

    SelectionTerms(AppRuntime runtime, ExportPanel.Selection selection) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workspace = runtime.workspace();
        this.selection = Objects.requireNonNull(selection, "selection");
        this.fixed = null;
        this.fixedTitle = null;
    }

    /**
     * Die Begriffe einer schon aufgelösten Seitenmenge, etwa der Unterseiten einer Seite für den Abschnitt „Aus
     * Unterseiten“ (Fahrplan P2).
     */
    SelectionTerms(Workspace workspace, String title,
                   com.aresstack.spaceharvester.application.holdings.PageSelection pages) {
        this.runtime = null;
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.selection = null;
        this.fixed = Objects.requireNonNull(pages, "pages");
        this.fixedTitle = Objects.requireNonNull(title, "title");
    }

    @Override
    public String title() {
        return fixed != null ? fixedTitle : selection.title();
    }

    /** Die Seiten der Auswahl; {@code null} beim Wissensquellen-Kopf (keine Grenze). */
    synchronized PageSet pages() {
        if (!resolved) {
            pages = fixed != null ? fixed.pages() : SelectionHoldings.pages(runtime, selection);
            resolved = true;
        }
        return pages;
    }

    @Override
    public Pager<TermQueries.TermSummary> acronyms(String q) {
        return new Pager<>(Comparator.comparing(TermQueries.TermSummary::normalized),
                List.of(List.of(fetch(TermKind.ACRONYM, q))));
    }

    @Override
    public Pager<TermQueries.TermSummary> identifiers(String q) {
        return new Pager<>(Comparator.comparing(TermQueries.TermSummary::normalized),
                List.of(List.of(fetch(TermKind.NOUN, q), fetch(TermKind.IDENTIFIER, q))));
    }

    /** Die Verben aus den Aussagen (K1), nach Zahl der Aussagen, wie der Core sie ordnet. */
    @Override
    public Pager<VerbQueries.Verb> verbs(String q) {
        Pager.Fetch<VerbQueries.Verb> fetch = cursor -> {
            VerbQueries.VerbList list = workspace.verbs(pages(), q == null || q.isBlank() ? null : q, TermsTab.step(),
                    cursor);
            return new Pager.Page<>(list.verbs(), list.nextCursor());
        };
        return new Pager<>((one, other) -> 0, List.of(List.of(fetch)));
    }

    @Override
    public List<VerbQueries.Occurrence> verbOccurrences(VerbQueries.Verb verb) {
        return workspace.verbOccurrences(pages(), verb, TermQueries.MAX_LIMIT);
    }

    private Pager.Fetch<TermQueries.TermSummary> fetch(TermKind kind, String q) {
        return cursor -> {
            TermQueries.TermList list = workspace.terms(new TermQueries.TermSearch(null, null, kind,
                    q == null || q.isBlank() ? null : q, false, TermsTab.step(), cursor, null).within(pages()));
            return new Pager.Page<>(list.terms(), list.nextCursor());
        };
    }

    @Override
    public Pager<Relation> relations(String q) {
        Pager.Fetch<Relation> fetch = cursor -> {
            TermRelations.RelationList list = workspace.termRelations(null, null, null,
                    q == null || q.isBlank() ? null : q, TermsTab.step(), cursor, pages());
            return new Pager.Page<>(list.relations().stream().map(Relation::of).toList(), list.nextCursor());
        };
        return new Pager<>((one, other) -> 0, List.of(List.of(fetch)));
    }

    /** Die Gesamtzahlen aus dem Core (BG1b): Bezeichner sind Nomen und Kennungen wie in der Liste. */
    @Override
    public TermsTab.Totals totals() {
        return totals(partial -> { });
    }

    /**
     * Die Frage nach den Gesamtzahlen: „Wissensquellen“, eine Quelle und ein Space sind Knoten, deren Zahlen der Core
     * verwaltet (Angelo 24.11.2026) und ohne Seitenmenge beantwortet; alles andere zählt über seine Seiten.
     */
    private TermQueries.TermSearch node(PageSet within) {
        boolean managed = fixed == null && selection instanceof ExportPanel.Selection.Node node
                && node.pageId() == null;
        if (managed) {
            return new TermQueries.TermSearch(selection.sourceId(), selection.spaceKey(), null, null, false,
                    TermsTab.step(), null, null);
        }
        return new TermQueries.TermSearch(null, null, null, null, false, TermsTab.step(), null, null).within(within);
    }

    /** Von Hand neu aufbauen: die Quelle der Auswahl samt ihren Spaces, bei „Wissensquellen“ alle Quellen. */
    @Override
    public void rebuild() {
        workspace.rebuildTermTotals(selection == null ? null : selection.sourceId());
    }

    /** Ohne Titel dieselbe Auswahl; eine fest aufgelöste Seitenmenge hat keinen Schlüssel. */
    @Override
    public Object key() {
        return fixed != null ? null : List.of(runtime, selection.normalized());
    }

    @Override
    public TermsTab.Totals totals(java.util.function.Consumer<TermsTab.Partial> progress) {
        progress.accept(new TermsTab.Partial(0, 0, null, null));
        PageSet within = pages();
        // Begriffe zuerst und mit Zwischenständen: Sie dauern bei großen Auswahlen am längsten.
        TermQueries.TermTotals terms = workspace.termTotals(node(within), sofar -> progress.accept(
                new TermsTab.Partial(sofar.of(TermKind.ACRONYM),
                        sofar.of(TermKind.NOUN) + sofar.of(TermKind.IDENTIFIER), null, null)));
        int acronyms = terms.of(TermKind.ACRONYM);
        int identifiers = terms.of(TermKind.NOUN) + terms.of(TermKind.IDENTIFIER);
        progress.accept(new TermsTab.Partial(acronyms, identifiers, null, null));
        int relations = workspace.termRelationCounts(within).values().stream().mapToInt(Integer::intValue).sum();
        progress.accept(new TermsTab.Partial(acronyms, identifiers, null, relations));
        VerbQueries.VerbList verbs = workspace.verbs(within, null, 1, null);
        progress.accept(new TermsTab.Partial(acronyms, identifiers, verbs.total(), relations));
        // Der Bestand der Auswahl, wie ihn der Export-Reiter zeigt: lokal gespeicherte von allen Seiten.
        com.aresstack.spaceharvester.application.holdings.PageSelection holdings = fixed != null ? fixed
                : SelectionHoldings.resolve(runtime, selection);
        return new TermsTab.Totals(terms.of(TermKind.ACRONYM), terms.of(TermKind.NOUN) + terms.of(TermKind.IDENTIFIER),
                relations, holdings.counts().stored(), holdings.size(), verbs.total(), verbs.unanalysed(),
                verbs.stalePages());
    }

    @Override
    public TermQueries.OccurrenceList occurrences(TermQueries.TermSummary term) {
        return workspace.occurrences(new TermQueries.OccurrenceSearch(term.normalized(), term.kind(), null, null,
                TermQueries.MAX_LIMIT, null, 0).within(pages()));
    }
}
