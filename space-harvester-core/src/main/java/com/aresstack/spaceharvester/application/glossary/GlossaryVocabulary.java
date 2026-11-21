package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Die Knoten der Fachsicht (Paket W1) aus Glossar und Begriffsbeziehungen: bestätigte Einträge ohne die Einordnung
 * {@link GlossaryDerivation.Category#TECHNISCH} (dieselbe Auswahl wie der Reiter „Fachglossar“) und bestätigte
 * {@link RelationType#SYNONYM}-Beziehungen. Sichtbarkeit, Geltungsbereich und Sperren wie dort; nichts wird gespeichert.
 */
public final class GlossaryVocabulary implements DomainTermGraph.Vocabulary {

    /** Einordnungen der Fachbegriffe: alles außer technisch, ein Eintrag ohne Einordnung zählt als unklar. */
    public static final Set<GlossaryDerivation.Category> CATEGORIES = Set.copyOf(EnumSet.complementOf(
            EnumSet.of(GlossaryDerivation.Category.TECHNISCH)));

    private final Glossary glossary;
    private final TermRelations relations;

    public GlossaryVocabulary(Glossary glossary, TermRelations relations) {
        this.glossary = Objects.requireNonNull(glossary, "glossary");
        this.relations = Objects.requireNonNull(relations, "relations");
    }

    @Override
    public List<DomainTermGraph.Term> terms(AccessContext caller) {
        return entries(caller, CATEGORIES);
    }

    private List<DomainTermGraph.Term> entries(AccessContext caller, Set<GlossaryDerivation.Category> categories) {
        List<DomainTermGraph.Term> terms = new ArrayList<>();
        String cursor = null;
        do {
            Glossary.Page page = glossary.entries(caller, new Glossary.Search(null, ReviewStatus.CONFIRMED, null,
                    null, null, null, Glossary.MAX_LIMIT, cursor, categories));
            page.entries().forEach(entry -> terms.add(new DomainTermGraph.Term(entry.term(), entry.scope())));
            cursor = page.nextCursor();
        } while (cursor != null);
        return terms;
    }

    /** Bestätigte Einträge der Einordnung „technisch“; sie werden auch über ein Synonym kein Knoten. */
    @Override
    public List<DomainTermGraph.Term> technical(AccessContext caller) {
        return entries(caller, Set.of(GlossaryDerivation.Category.TECHNISCH));
    }

    @Override
    public List<DomainTermGraph.Synonym> synonyms(AccessContext caller) {
        List<DomainTermGraph.Synonym> synonyms = new ArrayList<>();
        String cursor = null;
        do {
            TermRelations.RelationList page = relations.relations(caller, new TermRelations.RelationSearch(null,
                    RelationType.SYNONYM, ReviewStatus.CONFIRMED, null, null, TermRelations.MAX_LIMIT, cursor));
            for (TermRelation relation : page.relations()) {
                synonyms.add(new DomainTermGraph.Synonym(relation.fromTerm(), relation.toTerm(), relation.scope()));
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return synonyms;
    }
}
