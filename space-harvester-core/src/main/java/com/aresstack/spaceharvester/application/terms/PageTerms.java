package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die Begriffe einer Seite für die Leseansicht (Reiter „Begriffe“, #339), gelesen aus dem gespeicherten Ergebnis des
 * Prozessors {@code terms} für genau den angezeigten Text, wie {@code PageAcronyms} für {@code acronyms}. Dieselben
 * Daten wie der globale Begriffsindex, nur für diese Seite; erkannt wird nichts. Fundstellen gibt es nur bei
 * {@link State#CURRENT}.
 *
 * @param state    ob und warum Begriffe vorliegen
 * @param result   das gespeicherte Ergebnis oder {@code null}
 * @param terms    aufsteigend nach Suchwert, dann Art; leer außer bei {@link State#CURRENT}
 * @param warnings Hinweise des Prozessors, etwa gekürzte Listen
 */
public record PageTerms(State state, Enrichment result, List<TermPayload.Entry> terms, List<String> warnings) {

    public PageTerms {
        Objects.requireNonNull(state, "state");
        terms = List.copyOf(terms);
        warnings = List.copyOf(warnings);
    }

    /** Zustand der Analyse gegenüber dem angezeigten Text. */
    public enum State {
        /** Aus genau diesem Text mit der aktuellen Prozessorversion; Fundstellen gelten. */
        CURRENT,
        /** Es gibt ein Ergebnis, aber für einen anderen Text oder eine andere Prozessorversion. */
        STALE,
        /** Der letzte Lauf ist fehlgeschlagen. */
        FAILED,
        /** Noch kein Ergebnis oder kein lesbarer Text. */
        MISSING
    }

    /**
     * Liest die Begriffe einer Seite.
     *
     * @param key   die angezeigte Seite; Ergebnisse anderer Seiten werden nie verwendet
     * @param views die Anreicherungen dieser Seite ({@link Enrichments#view})
     * @param text  der angezeigte Text aus derselben Payload oder {@code null}
     */
    public static PageTerms read(PageKey key, List<Enrichments.View> views, PageText text) {
        Objects.requireNonNull(key, "key");
        Enrichments.View view = views.stream()
                .filter(candidate -> TermPayload.PROCESSOR.equals(candidate.enrichment().processorId())
                        && key.equals(candidate.enrichment().key()))
                .findFirst().orElse(null);
        if (view == null) {
            return new PageTerms(State.MISSING, null, List.of(), List.of());
        }
        Enrichment result = view.enrichment();
        if (text == null) {
            return new PageTerms(State.MISSING, result, List.of(), result.warnings());
        }
        if (view.freshness() != Enrichment.Freshness.CURRENT || !result.inputSha256().equals(text.inputSha256())) {
            return new PageTerms(State.STALE, result, List.of(), result.warnings());
        }
        if (result.status() == Enrichment.Status.FAILED) {
            return new PageTerms(State.FAILED, result, List.of(), result.warnings());
        }
        List<TermPayload.Entry> entries;
        try {
            entries = TermPayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return new PageTerms(State.FAILED, result, List.of(), result.warnings());
        }
        List<TermPayload.Entry> checked = new ArrayList<>(entries.size());
        for (TermPayload.Entry entry : entries) {
            // Eine Fundstelle außerhalb des Textes wäre ein Fehler des Ergebnisses; sie wird nie markiert.
            List<TermOccurrence> occurrences = entry.occurrences().stream()
                    .filter(occurrence -> occurrence.end() <= text.text().length()).toList();
            checked.add(new TermPayload.Entry(entry.term(), entry.normalized(), entry.kind(), entry.count(),
                    entry.expansion(), entry.section(), occurrences));
        }
        return new PageTerms(State.CURRENT, result, checked, result.warnings());
    }

    /** Die Begriffe einer Art. */
    public List<TermPayload.Entry> of(TermKind kind) {
        return terms.stream().filter(entry -> entry.kind() == kind).toList();
    }

    /** Zahl der Akronyme (Reiter „Begriffe“: Kachel Akronyme). */
    public int acronymCount() {
        return of(TermKind.ACRONYM).size();
    }

    /** Zahl der Bezeichner: Nomen, Nominalgruppen und Kennungen (Kachel Bezeichner). */
    public int identifierCount() {
        return terms.size() - acronymCount();
    }
}
