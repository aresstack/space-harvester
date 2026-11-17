package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.app.ui.Relation;
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
    private PageSet pages;
    private boolean resolved;

    SelectionTerms(AppRuntime runtime, ExportPanel.Selection selection) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workspace = runtime.workspace();
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @Override
    public String title() {
        return selection.title();
    }

    /** Die Seiten der Auswahl; {@code null} beim Wissensquellen-Kopf (keine Grenze). */
    synchronized PageSet pages() {
        if (!resolved) {
            pages = selection.global() ? null : workspace.resolveSelection(selection.toCore(runtime)).pages();
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

    private Pager.Fetch<TermQueries.TermSummary> fetch(TermKind kind, String q) {
        return cursor -> {
            TermQueries.TermList list = workspace.terms(new TermQueries.TermSearch(null, null, kind,
                    q == null || q.isBlank() ? null : q, false, TermsTab.STEP, cursor, null).within(pages()));
            return new Pager.Page<>(list.terms(), list.nextCursor());
        };
    }

    @Override
    public Pager<Relation> relations(String q) {
        Pager.Fetch<Relation> fetch = cursor -> {
            TermRelations.RelationList list = workspace.termRelations(null, null, null,
                    q == null || q.isBlank() ? null : q, TermsTab.STEP, cursor, pages());
            return new Pager.Page<>(list.relations().stream().map(Relation::of).toList(), list.nextCursor());
        };
        return new Pager<>((one, other) -> 0, List.of(List.of(fetch)));
    }

    /** Die Gesamtzahlen aus dem Core (BG1b): Bezeichner sind Nomen und Kennungen wie in der Liste. */
    @Override
    public TermsTab.Totals totals() {
        PageSet within = pages();
        TermQueries.TermTotals terms = workspace.termTotals(new TermQueries.TermSearch(null, null, null, null, false,
                TermsTab.STEP, null, null).within(within));
        int relations = workspace.termRelationCounts(within).values().stream().mapToInt(Integer::intValue).sum();
        return new TermsTab.Totals(terms.of(TermKind.ACRONYM), terms.of(TermKind.NOUN) + terms.of(TermKind.IDENTIFIER),
                relations);
    }

    @Override
    public TermQueries.OccurrenceList occurrences(TermQueries.TermSummary term) {
        return workspace.occurrences(new TermQueries.OccurrenceSearch(term.normalized(), term.kind(), null, null,
                TermQueries.MAX_LIMIT, null, 0).within(pages()));
    }
}
