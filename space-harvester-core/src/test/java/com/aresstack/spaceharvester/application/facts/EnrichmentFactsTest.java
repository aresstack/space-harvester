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
    void everyDistinctExpansionIsADefinitionAndIdentifiersAreTheirOwnType() {
        // Wie AcronymFindings im EAC: je unterschiedlicher Ausschreibung ein ACRONYM_DEFINITION, Kennungen getrennt.
        Enrichment result = FactTestResults.acronyms(PAGE,
                "Die Projektgruppe (PG) tagt. Die PG (Programmiergruppe) auch, mit MAX_SIZE.");

        List<MetadataFact> facts = EnrichmentFacts.of(result);

        assertThat(facts).extracting(MetadataFact::type).containsExactly(FactTypes.ACRONYM_CANDIDATE,
                FactTypes.ACRONYM_DEFINITION, FactTypes.ACRONYM_DEFINITION, FactTypes.TECHNICAL_IDENTIFIER_CANDIDATE);
        assertThat(facts.subList(1, 3)).extracting(fact -> fact.values(FactTypes.LONG_FORM).get(0))
                .containsExactly("projektgruppe", "programmiergruppe");
        assertThat(facts.get(1).payloadJson()).contains("\"conflictingLongForms\":true");
        assertThat(facts.get(3).values(FactTypes.TERM)).containsExactly("max_size");
        assertThat(facts.get(3).role()).isEqualTo(FactTypes.MENTIONS);
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
    void activitiesBecomeEventsWithPredicateRolesStatusAndQualifiers() {
        List<MetadataFact> facts = EnrichmentFacts.of(FactTestResults.activities(PAGE, FactTestResults.ACTIVITIES));

        assertThat(facts).extracting(MetadataFact::type).containsOnly(FactTypes.EVENT);
        MetadataFact restart = facts.stream().filter(f -> f.values(FactTypes.PREDICATE).contains("starten"))
                .findFirst().orElseThrow();
        assertThat(restart.values("ACTOR")).containsExactly("administrator");
        assertThat(restart.values("AFFECTED_OBJECT")).containsExactly("dienst");
        assertThat(restart.values(FactTypes.STATUS)).containsExactly("asserted");
        assertThat(restart.values(FactTypes.QUALIFIER)).containsExactly("modal");
        assertThat(restart.payloadJson()).contains("\"modal\":\"muss\"").contains("\"participants\":[");
        assertThat(restart.anchor().charStart()).isZero();
        assertThat(restart.anchor().charEnd()).isGreaterThan(FactTestResults.ACTIVITIES.indexOf("starten"));
        assertThat(restart.provenance().processorId()).isEqualTo(EnrichmentFacts.ACTIVITIES);
        MetadataFact call = facts.stream().filter(f -> f.values(FactTypes.PREDICATE).contains("aufrufen"))
                .findFirst().orElseThrow();
        assertThat(call.values(FactTypes.STATUS)).containsExactly("negated");
        assertThat(call.values(FactTypes.QUALIFIER)).containsExactly("negated");
    }

    @Test
    void relationsKeepKindSubjectObjectAndNegation() {
        List<MetadataFact> facts = EnrichmentFacts.of(FactTestResults.relations(PAGE, FactTestResults.RELATIONS));

        assertThat(facts).extracting(MetadataFact::type).containsOnly(FactTypes.RELATION);
        assertThat(facts).extracting(fact -> fact.values(FactTypes.KIND).getFirst() + ":"
                + fact.values(FactTypes.SUBJECT).getFirst() + ">" + fact.values(FactTypes.OBJECT).getFirst() + "/"
                + fact.values(FactTypes.STATUS).getFirst())
                .contains("is_a:antrag>dokument/asserted", "is_a:entwurf>antrag/negated");
        assertThat(facts).anySatisfy(fact -> assertThat(fact.values(FactTypes.KIND)).containsExactly("part_of"));
        assertThat(facts.getFirst().payloadJson()).contains("\"kind\":\"is_a\"");
    }

    @Test
    void repeatedRolesCountTheirOrdinal() {
        Instant at = Instant.parse("2026-10-18T08:00:00Z");
        List<MetadataFact> facts = EnrichmentFacts.of(new Enrichment(PAGE, "activities", "1", "sha256:x", null,
                Enrichment.Status.OK, "{\"activities\":[{\"predicate\":\"prüfen\",\"lemma\":\"prüfen\","
                + "\"negated\":true,\"modal\":\"soll\",\"participants\":[{\"role\":\"ACTOR\",\"term\":\"A\","
                + "\"normalized\":\"a\",\"pronoun\":false},{\"role\":\"ACTOR\",\"term\":\"B\","
                + "\"normalized\":\"b\",\"pronoun\":false},{\"role\":\"ACTOR\",\"term\":\"er\","
                + "\"normalized\":\"er\",\"pronoun\":true}]}]}", List.of(), null, at));

        assertThat(facts.getFirst().arguments()).filteredOn(argument -> argument.role().equals("ACTOR"))
                .extracting(MetadataFact.Argument::ordinal, MetadataFact.Argument::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "a"), org.assertj.core.groups.Tuple.tuple(1, "b"));
        assertThat(facts.getFirst().arguments()).filteredOn(argument -> argument.role().equals(FactTypes.QUALIFIER))
                .extracting(MetadataFact.Argument::ordinal, MetadataFact.Argument::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "negated"),
                        org.assertj.core.groups.Tuple.tuple(1, "modal"));
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
    void aResultWithMoreFactsThanTheLimitIsCutAndSaysSo() {
        Enrichment full = FactTestResults.many(PAGE, EnrichmentFacts.ENTITIES, EnrichmentFacts.MAX_PER_RESULT);
        Enrichment over = FactTestResults.many(PAGE, EnrichmentFacts.ENTITIES, EnrichmentFacts.MAX_PER_RESULT + 1);

        assertThat(EnrichmentFacts.of(full)).hasSize(EnrichmentFacts.MAX_PER_RESULT);
        assertThat(EnrichmentFacts.truncated(full)).isFalse();
        assertThat(EnrichmentFacts.of(over)).hasSize(EnrichmentFacts.MAX_PER_RESULT);
        assertThat(EnrichmentFacts.truncated(over)).isTrue();
        assertThat(EnrichmentFacts.truncated(FactTestResults.acronyms(PAGE, FactTestResults.TEXT))).isFalse();
    }

    @Test
    void conditionsAreNormalizedLikeArguments() {
        assertThat(ArgumentCondition.parse("short_form: API ")).isEqualTo(new ArgumentCondition("SHORT_FORM",
                "api"));
    }
}
