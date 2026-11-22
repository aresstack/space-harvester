package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.TermKnowledge;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Suchmethode „Fachbegriffe“ (W8) in der progressiven Suche: aus wird das Begriffswissen nie gefragt und das Ergebnis
 * bleibt gleich; an kommen Belegseiten mit Ursache {@code TERM}; Frist und Abbruch lassen Gefundenes stehen. Alle
 * Begriffe und Seiten sind erfunden.
 */
class SearchOrchestratorTermsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final String ORIGINAL = "Leistungsakte schließen";

    private final AtomicInteger asked = new AtomicInteger();
    private final KeywordSearch keywords = mock(KeywordSearch.class);

    /** Die eigentliche Suche trifft o und a. */
    private final VariantSearch search = (caller, query, limit) -> results("o", "a");

    private static KeywordSearch.Results results(String... pageIds) {
        List<KeywordSearch.Hit> hits = java.util.Arrays.stream(pageIds)
                .map(id -> new KeywordSearch.Hit("wiki", id, "ABL", "Seite " + id, "Auszug " + id, null, "1", null,
                        null).withReasons(List.of(HitReason.text(ORIGINAL, null, List.of(), List.of("leistungsakte")))))
                .toList();
        return new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0);
    }

    /** „Leistungsakte“ ist Fachbegriff mit Belegen auf a (schon gefunden) und b. */
    private TermSearch terms(Duration delay) {
        when(keywords.search(any(), any(), isNull(), anyInt())).thenAnswer(call -> {
            KeywordSearch.Query query = call.getArgument(1);
            return query.pages() != null && query.pages().contains("wiki", "b") ? results("b") : results();
        });
        TermSearch.Knowledge knowledge = (caller, term, scope, cancelled) -> {
            asked.incrementAndGet();
            if (!term.equals("Leistungsakte")) {
                return new TermKnowledge.Answer(TermKnowledge.Outcome.NOT_A_TERM, List.of(), List.of(), List.of(),
                        List.of(), new TermKnowledge.Limits(3, 3, 0, 0, 0, 0, false, 0));
            }
            sleep(delay);
            return new TermKnowledge.Answer(TermKnowledge.Outcome.FOUND,
                    List.of(new TermKnowledge.Term("Leistungsakte", DomainTermGraph.NodeKind.TERM, List.of())),
                    List.of(), List.of(), List.of(new TermKnowledge.Page(new PageKey("wiki", "a"), "ABL", "Seite a"),
                    new TermKnowledge.Page(new PageKey("wiki", "b"), "ABL", "Seite b")),
                    new TermKnowledge.Limits(3, 3, 0, 0, 0, 0, false, 0));
        };
        return new TermSearch(keywords, (caller, query) -> PageSet.of(Map.of("wiki", List.of("o", "a", "b"))),
                knowledge);
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ProgressiveResult run(TermSearch terms, SearchProfile profile) throws Exception {
        SearchOrchestrator orchestrator = new SearchOrchestrator(search, null, (original, max) -> List.of(),
                (original, max) -> List.of(), HitScorer.none(),
                com.aresstack.spaceharvester.application.searchrun.SearchRunLog.NONE, NeighborSource.NONE,
                NeighborLoader.NONE, terms);
        try {
            return orchestrator.searchAsync(APP, KeywordSearch.Query.of(ORIGINAL), profile).result()
                    .get(10, TimeUnit.SECONDS);
        } finally {
            orchestrator.close();
        }
    }

    private static SearchProfile profile() {
        return SearchProfile.defaults().withMode(SearchProfile.Mode.ALL_VARIANTS).withExecution(
                new SearchExecutionPolicy(2, 2, Duration.ofSeconds(5), 0));
    }

    private static List<String> ids(ProgressiveResult result) {
        return result.hits().stream().map(hit -> hit.hit().pageId()).toList();
    }

    @Test
    void withoutTheMethodTheKnowledgeIsNeverAskedAndTheResultStaysTheSame() throws Exception {
        ProgressiveResult plain = run(null, profile());

        ProgressiveResult off = run(terms(Duration.ZERO), profile());

        assertThat(asked).hasValue(0);
        assertThat(ids(off)).isEqualTo(ids(plain)).containsExactly("o", "a");
        assertThat(off.hits()).extracting(hit -> hit.hit().reasons()).isEqualTo(plain.hits().stream()
                .map(hit -> hit.hit().reasons()).toList());
        assertThat(off.technicalTerms()).isEqualTo(ProgressiveResult.TechnicalTerms.OFF);
    }

    @Test
    void withTheMethodEvidencePagesComeOnceWithTheTermAsReason() throws Exception {
        ProgressiveResult result = run(terms(Duration.ZERO), profile().withTechnicalTerms(true));

        assertThat(ids(result)).containsExactly("o", "a", "b");
        assertThat(result.hits().get(2).hit().reasons()).contains(HitReason.term("Leistungsakte"));
        assertThat(result.hits().get(1).hit().reasons()).noneMatch(reason -> reason.kind() == HitReason.Kind.TERM);
        assertThat(result.technicalTerms()).isEqualTo(ProgressiveResult.TechnicalTerms.CHECKED);
    }

    @Test
    void anExpiredTimeBudgetKeepsTheHitsAndSaysTheTermsAreUnchecked() throws Exception {
        // Die Fachsicht braucht länger als die ganze Suche darf.
        ProgressiveResult result = run(terms(Duration.ofMillis(600)),
                profile().withTechnicalTerms(true).withBudget(Duration.ofMillis(300), 12));

        assertThat(ids(result)).startsWith("o", "a");
        assertThat(result.technicalTerms()).isEqualTo(ProgressiveResult.TechnicalTerms.UNCHECKED);
        assertThat(result.reason()).isNotEqualTo(ProgressiveResult.Completion.FAILED);
    }

    @Test
    void withTheMethodButWithoutTermSearchItStaysUnchecked() throws Exception {
        ProgressiveResult result = run(null, profile().withTechnicalTerms(true));

        assertThat(ids(result)).containsExactly("o", "a");
        assertThat(result.technicalTerms()).isEqualTo(ProgressiveResult.TechnicalTerms.UNCHECKED);
    }
}
