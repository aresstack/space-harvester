package com.aresstack.spaceharvester.application.enrichment.statements;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.facts.EnrichmentFacts;
import com.aresstack.spaceharvester.application.facts.FactOrigin;
import com.aresstack.spaceharvester.application.facts.FactReview;
import com.aresstack.spaceharvester.application.facts.FactTypes;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Der Prozessor {@code statements}: aus ohne Schalter oder Modell, nie für nicht freigegebene Quellen, und seine
 * Ergebnisse werden Fakten derselben Typen wie der regelbasierte Weg.
 */
class StatementProcessorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final StatementSettings ON = StatementAnalyzerTest.settings(false);

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final FakeStatementChat chat = new FakeStatementChat();
    private final AtomicReference<StatementChat> model = new AtomicReference<>(chat);
    private final AtomicReference<StatementSettings> settings = new AtomicReference<>(ON);
    private final StatementProcessor processor = new StatementProcessor(StatementAnalyzerTest.SENTENCES,
            settings::get, model::get, Set.of("wiki")::contains);
    private final Enrichments enrichments = new Enrichments(PROJECTOR, List.of(processor), store, CLOCK);

    @Test
    void offByDefaultNothingIsSentOrStored() {
        settings.set(StatementSettings.DEFAULT);
        chat.enqueueAnswer(StatementAnalyzerTest.ANSWER);
        PageSnapshot page = page("wiki");

        assertThat(StatementSettings.DEFAULT.enabled()).isFalse();
        assertThat(processor.appliesTo(page.key())).isFalse();
        assertThat(enrichments.refresh(page).computed()).isZero();
        assertThat(enrichments.recompute(page, StatementProcessor.ID).computed()).isZero();
        assertThat(store.find(page.key())).isEmpty();
        assertThat(chat.requests()).isEmpty();
    }

    @Test
    void withoutChatModelTheProcessorIsSkipped() {
        model.set(null);
        PageSnapshot page = page("wiki");

        assertThat(processor.available()).isFalse();
        assertThat(processor.version()).isEqualTo("off");
        assertThat(enrichments.refresh(page).computed()).isZero();
        assertThat(enrichments.recompute(page, null).computed()).isZero();
        assertThat(store.find(page.key())).isEmpty();
        assertThatThrownBy(() -> processor.process(page.key(), PROJECTOR.project(page).orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSourceNotReleasedForExternalProcessingNeverReachesTheChat() {
        chat.enqueueAnswer(StatementAnalyzerTest.ANSWER);
        PageSnapshot secret = page("intern");
        PageText text = PROJECTOR.project(secret).orElseThrow();

        assertThat(processor.appliesTo(secret.key())).isFalse();
        assertThat(enrichments.refresh(secret).computed()).isZero();
        assertThat(enrichments.recompute(secret, null).computed()).isZero();
        assertThat(enrichments.recompute(secret, StatementProcessor.ID).computed()).isZero();
        assertThatThrownBy(() -> processor.process(secret.key(), text)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> processor.process(text)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(secret.key())).isEmpty();
        assertThat(chat.requests()).isEmpty();
    }

    @Test
    void aReleasedSourceGetsStatementsAsFactsOfTheSameTypes() {
        chat.enqueueAnswer(StatementAnalyzerTest.ANSWER);
        PageSnapshot page = page("wiki");

        Enrichments.Run run = enrichments.refresh(page);

        assertThat(run.computed()).isEqualTo(1);
        assertThat(chat.requests()).hasSize(1);
        Enrichment result = store.find(page.key()).getFirst();
        assertThat(result.status()).isEqualTo(Enrichment.Status.OK);
        assertThat(result.processorId()).isEqualTo(EnrichmentFacts.STATEMENTS);
        assertThat(result.processorVersion()).isEqualTo(processor.version()).contains("chat-json/1/test-model");
        // Aktuell: kein zweiter Aufruf beim nächsten Abgleich.
        assertThat(enrichments.refresh(page).current()).isEqualTo(1);
        assertThat(chat.requests()).hasSize(1);

        List<MetadataFact> facts = EnrichmentFacts.of(result);
        assertThat(facts).extracting(MetadataFact::type).containsExactly(FactTypes.EVENT, FactTypes.EVENT,
                FactTypes.RELATION, FactTypes.RELATION, FactTypes.RELATION, FactTypes.MENTION, FactTypes.MENTION,
                FactTypes.MENTION);
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.origin()).isEqualTo(FactOrigin.AUTOMATIC);
            assertThat(fact.review()).isEqualTo(FactReview.UNREVIEWED);
            assertThat(fact.provenance().processorId()).isEqualTo("statements");
            assertThat(fact.provenance().processorVersion()).contains("test-model");
            assertThat(fact.values(FactTypes.STATUS)).containsExactly("extracted");
        });
        // Erwähnungen zuletzt, damit sie an der Grenze je Ergebnis nicht Handlungen und Relationen verdrängen.
        assertThat(facts.get(5).values(FactTypes.TERM)).containsExactly("sachbearbeitung");
        assertThat(facts.get(5).role()).isEqualTo(FactTypes.MENTIONS);
        MetadataFact release = facts.get(1);
        assertThat(release.values(FactTypes.PREDICATE)).containsExactly("auslösen");
        assertThat(release.values("ACTOR")).containsExactly("sachbearbeitung");
        assertThat(release.values("AFFECTED_OBJECT")).containsExactly("zahlung");
        assertThat(release.anchor().charStart()).isZero();
        MetadataFact state = facts.get(3);
        assertThat(state.values(FactTypes.KIND)).containsExactly("state");
        assertThat(state.values(FactTypes.SUBJECT)).containsExactly("beleg");
        assertThat(state.values(FactTypes.VALUE)).containsExactly("eingereicht");
        assertThat(state.anchor().charStart()).isEqualTo(StatementAnalyzerTest.SENTENCE.indexOf("eingereichten"));
        MetadataFact order = facts.get(4);
        assertThat(order.values(FactTypes.KIND)).containsExactly("before");
        assertThat(order.values(FactTypes.SUBJECT)).containsExactly("prüfen");
        assertThat(order.values(FactTypes.OBJECT)).containsExactly("auslösen");
        assertThat(order.values(FactTypes.VALUE)).containsExactly("anschließend");
        // Dieselben Fakten über die Ablage.
        assertThat(store.factsOf(page.key())).hasSize(facts.size());
    }

    @Test
    void anotherChatModelMakesTheResultStale() {
        chat.enqueueAnswer(StatementAnalyzerTest.ANSWER);
        PageSnapshot page = page("wiki");
        enrichments.refresh(page);
        String before = processor.version();

        FakeStatementChat other = new FakeStatementChat("other-model");
        other.enqueueAnswer(StatementAnalyzerTest.ANSWER);
        model.set(other);

        assertThat(processor.version()).isNotEqualTo(before).contains("other-model");
        assertThat(enrichments.refresh(page).computed()).isEqualTo(1);
        assertThat(other.requests()).hasSize(1);
    }

    @Test
    void qualifiersAndModalityBecomeArguments() {
        chat.enqueueAnswer("{\"mentions\": [{\"id\": \"m1\", \"sentence\": 1, \"from\": 1, \"to\": 2, \"text\": "
                + "\"Die Sachbearbeitung\", \"lemma\": \"Sachbearbeitung\"}, {\"id\": \"m2\", \"sentence\": 1, "
                + "\"from\": 4, \"to\": 5, \"text\": \"die Zahlung\", \"lemma\": \"Zahlung\"}], \"events\": [{\"id\": "
                + "\"e1\", \"sentence\": 1, \"predicate\": {\"from\": 3, \"to\": 3, \"text\": \"löst\"}, \"particle\": "
                + "{\"from\": 7, \"to\": 7, \"text\": \"aus\"}, \"lemma\": \"auslösen\", \"participants\": [{\"role\": "
                + "\"ACTOR\", \"mention\": \"m1\"}, {\"role\": \"AFFECTED_OBJECT\", \"mention\": \"m2\"}], "
                + "\"negation\": {\"from\": 6, \"to\": 6, \"text\": \"nicht\"}}]}");
        PageSnapshot page = text("wiki", "102", "1", "Die Sachbearbeitung löst die Zahlung nicht aus.");

        enrichments.refresh(page);

        MetadataFact event = EnrichmentFacts.of(store.find(page.key()).getFirst()).stream()
                .filter(fact -> fact.type().equals(FactTypes.EVENT)).findFirst().orElseThrow();
        assertThat(event.values(FactTypes.QUALIFIER)).containsExactly("negated");
        assertThat(event.payloadJson()).contains("\"negation\":\"nicht\"");
    }

    @Test
    void aParagraphWithoutAnswerFailsTheWholeResult() {
        chat.enqueueAnswer("kein JSON");
        chat.enqueueAnswer("immer noch keins");
        PageSnapshot page = page("wiki");

        Enrichments.Run run = enrichments.refresh(page);

        assertThat(run.failed()).isEqualTo(1);
        Enrichment result = store.find(page.key()).getFirst();
        assertThat(result.status()).isEqualTo(Enrichment.Status.FAILED);
        assertThat(EnrichmentFacts.of(result)).isEmpty();
    }

    private static PageSnapshot page(String sourceId) {
        return text(sourceId, "101", "1", StatementAnalyzerTest.SENTENCE);
    }
}
