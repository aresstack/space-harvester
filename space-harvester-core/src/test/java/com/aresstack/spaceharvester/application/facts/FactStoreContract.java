package com.aresstack.spaceharvester.application.facts;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für Ablagen der Anreicherungen, die zugleich {@link FactStore} sind: Fakten entstehen beim Speichern,
 * werden mit ihrem Ergebnis ersetzt und gelöscht und sind nach Typ, Argument und Quelle abfragbar.
 */
public abstract class FactStoreContract {

    protected static final PageKey NIGHT = new PageKey("wiki", "101");
    protected static final PageKey DAY = new PageKey("wiki", "102");
    protected static final PageKey FILE = new PageKey("files", "a.txt");

    protected abstract EnrichmentStore store();

    protected FactStore facts() {
        return (FactStore) store();
    }

    @Test
    void savingAResultDerivesItsFacts() {
        store().save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
        store().save(FactTestResults.properties(NIGHT, FactTestResults.PROPERTIES));

        assertThat(facts().factsOf(NIGHT)).extracting(MetadataFact::type).containsExactly(
                FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION, FactTypes.PROPERTY, FactTypes.PROPERTY);
        assertThat(facts().factsOf(NIGHT)).containsExactlyElementsOf(concat(
                EnrichmentFacts.of(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT)),
                EnrichmentFacts.of(FactTestResults.properties(NIGHT, FactTestResults.PROPERTIES))));
        assertThat(facts().factsOf(DAY)).isEmpty();
        // Ergebnisse bleiben, wie sie waren.
        assertThat(store().find(NIGHT)).extracting(Enrichment::processorId).containsExactly("acronyms", "properties");
    }

    @Test
    void aNewResultReplacesTheFactsOfItsProcessorOnly() {
        store().save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
        store().save(FactTestResults.entities(NIGHT, FactTestResults.TEXT));

        store().save(FactTestResults.acronyms(NIGHT, "Nur noch das HTTP hier."));

        assertThat(facts().factsOf(NIGHT)).filteredOn(fact -> fact.provenance().processorId().equals("acronyms"))
                .flatExtracting(fact -> fact.values(FactTypes.SHORT_FORM)).containsExactly("http");
        assertThat(facts().factsOf(NIGHT)).anyMatch(fact -> fact.type().equals(FactTypes.MENTION));
    }

    @Test
    void factsDisappearWithTheirPage() {
        store().save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
        store().save(FactTestResults.acronyms(DAY, FactTestResults.TEXT));

        store().removeAll(NIGHT);

        assertThat(facts().factsOf(NIGHT)).isEmpty();
        assertThat(facts().factsOf(DAY)).isNotEmpty();
    }

    @Test
    void factsAreQueryableByTypeArgumentAndSource() {
        store().save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
        store().save(FactTestResults.acronyms(DAY, "Das Domain Name System (DNS) und die API."));
        store().save(FactTestResults.acronyms(FILE, FactTestResults.TEXT));

        List<MetadataFact> api = facts().facts(new FactStore.Query(FactTypes.ACRONYM_DEFINITION,
                List.of(new ArgumentCondition(FactTypes.SHORT_FORM, "API")), Set.of("wiki")), null, 100);
        assertThat(api).extracting(MetadataFact::page).containsExactly(NIGHT);

        List<MetadataFact> candidates = facts().facts(new FactStore.Query(FactTypes.ACRONYM_CANDIDATE, List.of(),
                Set.of("wiki", "files")), null, 100);
        // API je Seite, dazu DNS auf DAY.
        assertThat(candidates).hasSize(4);
        assertThat(candidates).extracting(MetadataFact::id).isSorted();

        assertThat(facts().facts(new FactStore.Query(null, List.of(new ArgumentCondition(FactTypes.SHORT_FORM,
                "dns")), Set.of("wiki")), null, 100)).extracting(MetadataFact::type).containsExactlyInAnyOrder(
                FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION);
        assertThat(facts().facts(new FactStore.Query(FactTypes.ACRONYM_CANDIDATE, List.of(), Set.of()), null, 100))
                .isEmpty();
    }

    @Test
    void afterContinuesBehindTheLastId() {
        store().save(FactTestResults.acronyms(NIGHT, FactTestResults.TEXT));
        store().save(FactTestResults.acronyms(DAY, FactTestResults.TEXT));
        FactStore.Query query = new FactStore.Query(FactTypes.ACRONYM_CANDIDATE, List.of(), Set.of("wiki"));

        List<MetadataFact> first = facts().facts(query, null, 1);
        List<MetadataFact> rest = facts().facts(query, first.getFirst().id(), 10);

        assertThat(first).hasSize(1);
        assertThat(rest).hasSize(1).doesNotContainAnyElementsOf(first);
    }

    private static List<MetadataFact> concat(List<MetadataFact> a, List<MetadataFact> b) {
        List<MetadataFact> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
