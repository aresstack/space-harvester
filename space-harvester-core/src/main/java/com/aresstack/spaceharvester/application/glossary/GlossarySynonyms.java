package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.SearchExpansion;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Suchmethode „Synonyme der Fachbegriffe einbeziehen“ (K5a, Angelo 21.11.2026): Ist ein Suchbegriff ein Fachbegriff
 * des Glossars (bestätigt, nicht {@code TECHNISCH}, sichtbar; wie K3/K5b), kommen seine VORGESCHLAGENEN Synonyme als
 * zusätzliche Anfragen dazu. Bestätigte Synonyme weitet die gewöhnliche {@link SearchExpansion} schon aus, Abgelehntes
 * nie. Treffer daraus tragen die Art {@link #PROPOSED} („Synonym (Vorschlag)“). Zustandslos; wirkt nur, wenn die
 * Anfrage die Methode einschaltet ({@code KeywordSearch.Query#glossarySynonyms()}).
 */
public final class GlossarySynonyms implements SearchExpansion.Counterparts {

    /** Art der Beziehung am Treffer: vorgeschlagenes Synonym eines Fachbegriffs. */
    public static final String PROPOSED = HitReason.PROPOSED_SYNONYM;

    private final BiFunction<AccessContext, RelationScope, Set<String>> technicalTerms;
    private final Proposed proposed;

    /** Die vorgeschlagenen Synonyme eines Begriffs im Umfang der Suche. */
    @FunctionalInterface
    public interface Proposed {

        List<String> synonyms(AccessContext caller, String term, String sourceId, String spaceKey);
    }

    public GlossarySynonyms(Glossary glossary, TermRelations relations) {
        this(glossary::confirmedTerms, relations::proposedSynonyms);
    }

    /**
     * @param technicalTerms die sichtbaren Fachbegriffe (normalisiert) eines Umfangs
     * @param proposed       die vorgeschlagenen Synonyme eines Begriffs
     */
    public GlossarySynonyms(BiFunction<AccessContext, RelationScope, Set<String>> technicalTerms, Proposed proposed) {
        this.technicalTerms = Objects.requireNonNull(technicalTerms, "technicalTerms");
        this.proposed = Objects.requireNonNull(proposed, "proposed");
    }

    /** Als Erweiterung der Schlagwortsuche. */
    public SearchExpansion expansion() {
        return SearchExpansion.typed(this);
    }

    @Override
    public List<SearchExpansion.Counterpart> counterparts(AccessContext caller, String term, String sourceId,
                                                          String spaceKey) {
        String key = TermText.normalize(term);
        if (key.isEmpty() || !technicalTerms.apply(caller, scope(sourceId, spaceKey)).contains(key)) {
            return List.of();
        }
        List<String> found = proposed.synonyms(caller, term, sourceId, spaceKey);
        return found == null ? List.of() : found.stream()
                .filter(Objects::nonNull)
                .map(text -> new SearchExpansion.Counterpart(text, PROPOSED))
                .toList();
    }

    /**
     * Prüft, ob das Glossar für den Aufrufer lesbar ist (für den gelben Hinweis der Suche); wie viele Vorschläge die
     * Suche stellen würde, sagt {@code KeywordSearch#glossarySynonymsWanted}. Wirft, wenn es nicht lesbar ist.
     */
    public void requireReadable(AccessContext caller) {
        technicalTerms.apply(caller, RelationScope.GLOBAL);
    }

    private static RelationScope scope(String sourceId, String spaceKey) {
        return sourceId == null ? RelationScope.GLOBAL : new RelationScope(sourceId, spaceKey);
    }
}
