package com.aresstack.spaceharvester.application.facts;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fakten entstehen deterministisch aus den bestehenden Ergebnissen; deren Formate bleiben unverändert. */
class EnrichmentFactsTest {

    private static final PageKey PAGE = new PageKey("wiki", "101");

    @Test
    void anAcronymWithExpansionIsACandidateAndADefinition() {
        Enrichment result = FactTestResults.acronyms(PAGE, FactTestResults.TEXT);

        List<MetadataFact> facts = EnrichmentFacts.of(result);

        assertThat(facts).extracting(MetadataFact::type, MetadataFact::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FactTypes.ACRONYM_CANDIDATE, FactTypes.MENTIONS),
                org.assertj.core.groups.Tuple.tuple(FactTypes.ACRONYM_DEFINITION, FactTypes.DEFINES));
        MetadataFact definition = facts.get(1);
        assertThat(definition.values(FactTypes.SHORT_FORM)).containsExactly("api");
        assertThat(definition.values(FactTypes.LONG_FORM)).containsExactly("application programming interface");
        assertThat(definition.payloadJson()).contains("\"longForm\":\"Application Programming Interface\"");
        assertThat(definition.provenance().processorId()).isEqualTo("acronyms");
        assertThat(definition.provenance().inputSha256()).isEqualTo(result.inputSha256());
        assertThat(definition.anchor().charStart()).isEqualTo(FactTestResults.TEXT.indexOf("API"));
        assertThat(facts.get(0).payloadJson()).contains("\"count\":2");
    }

    @Test
    void entitiesBecomeMentionsWithTheirTerm() {
        List<MetadataFact> facts = EnrichmentFacts.of(FactTestResults.entities(PAGE, FactTestResults.TEXT));

        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.type()).isEqualTo(FactTypes.MENTION);
            assertThat(fact.values(FactTypes.TERM)).hasSize(1);
        });
        assertThat(facts).flatExtracting(fact -> fact.values(FactTypes.TERM)).contains("dienst");
    }

    @Test
    void propertiesKeepSubjectValueAndNegation() {
        List<MetadataFact> facts = EnrichmentFacts.of(FactTestResults.properties(PAGE, FactTestResults.PROPERTIES));

        assertThat(facts).extracting(fact -> fact.values(FactTypes.SUBJECT).getFirst() + "="
                + fact.values(FactTypes.VALUE).getFirst() + "/" + fact.values(FactTypes.STATUS).getFirst())
                .containsExactly("server=verfügbar/negated", "dienst=verfügbar/asserted");
    }

    @Test
    void theSameResultGivesTheSameFactsWithStableIdsThatHideThePage() {
        Enrichment result = FactTestResults.acronyms(PAGE, FactTestResults.TEXT);

        List<MetadataFact> first = EnrichmentFacts.of(result);
        assertThat(EnrichmentFacts.of(result)).isEqualTo(first);
        assertThat(first).extracting(MetadataFact::id).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{32}").doesNotContain("101"));
        assertThat(EnrichmentFacts.of(FactTestResults.acronyms(new PageKey("wiki", "102"), FactTestResults.TEXT)))
                .extracting(MetadataFact::id).doesNotContainAnyElementsOf(first.stream().map(MetadataFact::id)
                        .toList());
    }

    @Test
    void otherProcessorsFailedOrBrokenResultsCarryNoFacts() {
        Instant at = Instant.parse("2026-10-18T08:00:00Z");
        assertThat(EnrichmentFacts.of(new Enrichment(PAGE, "outline", "1", "sha256:x", null, Enrichment.Status.OK,
                "{\"headings\":[]}", List.of(), null, at))).isEmpty();
        assertThat(EnrichmentFacts.of(new Enrichment(PAGE, "acronyms", "2", "sha256:x", null,
                Enrichment.Status.FAILED, "{}", List.of(), "TIMEOUT", at))).isEmpty();
        assertThat(EnrichmentFacts.of(new Enrichment(PAGE, "acronyms", "2", "sha256:x", null, Enrichment.Status.OK,
                "{\"acronyms\":[{\"acronym\":3}]}", List.of(), null, at))).isEmpty();
    }

    @Test
    void conditionsAreNormalizedLikeArguments() {
        assertThat(ArgumentCondition.parse("short_form: API ")).isEqualTo(new ArgumentCondition("SHORT_FORM",
                "api"));
    }
}
