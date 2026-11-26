package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.HitScorer;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.RuleBasedExpansion;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F38-4, ADR 0038 Punkt 10: laufende, wartende und beendete Suchen sind getrennt begrenzt, alles einstellbar; eine neue
 * Suche verdrängt nie eine andere, sondern wartet oder wird ausdrücklich abgewiesen.
 */
class ProgressiveSearchesTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicLong now = new AtomicLong();
    private final AccessContext caller = AccessContext.technicalClient("client");
    private SearchOrchestrator orchestrator;

    @AfterEach
    void close() {
        release.countDown();
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void aSearchWithoutAFreeSlotWaitsAndStartsWhenOneEnds() throws Exception {
        ProgressiveSearches searches = searches(limits(1, 1, Duration.ofMinutes(5)));
        var running = searches.start(caller, KeywordSearch.Query.of("langsam"), SearchProfile.defaults());
        var waiting = searches.start(caller, KeywordSearch.Query.of("danach"), SearchProfile.defaults());

        assertThat(waiting.snapshot().waiting()).isTrue();
        assertThat(waiting.snapshot().done()).isFalse();
        release.countDown();
        running.result().get(10, TimeUnit.SECONDS);
        assertThat(waiting.result().get(10, TimeUnit.SECONDS).reason())
                .isNotEqualTo(ProgressiveResult.Completion.CANCELLED);
        assertThat(waiting.snapshot().done()).isTrue();
    }

    @Test
    void aFullRegisterRejectsANewSearchAndKeepsAllOthers() throws Exception {
        ProgressiveSearches searches = searches(limits(1, 1, Duration.ofMinutes(5)));
        var finished = searches.start(caller, KeywordSearch.Query.of("schnell"), SearchProfile.defaults());
        finished.result().get(10, TimeUnit.SECONDS);
        var running = searches.start(caller, KeywordSearch.Query.of("langsam"), SearchProfile.defaults());
        var waiting = searches.start(caller, KeywordSearch.Query.of("danach"), SearchProfile.defaults());

        assertThatThrownBy(() -> searches.start(caller, KeywordSearch.Query.of("noch eine"),
                SearchProfile.defaults())).isInstanceOf(ProgressiveSearches.CapacityExceededException.class);
        assertThat(searches.find(caller, finished.id())).isPresent();
        assertThat(searches.find(caller, running.id())).isPresent();
        assertThat(searches.find(caller, waiting.id())).isPresent();
    }

    @Test
    void cancellingAWaitingSearchEndsItWithoutHits() throws Exception {
        ProgressiveSearches searches = searches(limits(1, 1, Duration.ofMinutes(5)));
        searches.start(caller, KeywordSearch.Query.of("langsam"), SearchProfile.defaults());
        var waiting = searches.start(caller, KeywordSearch.Query.of("danach"), SearchProfile.defaults());

        assertThat(searches.cancel(caller, waiting.id())).isTrue();
        assertThat(waiting.result().get(1, TimeUnit.SECONDS).reason())
                .isEqualTo(ProgressiveResult.Completion.CANCELLED);
        assertThat(waiting.snapshot().hits()).isEmpty();
    }

    @Test
    void aFinishedSearchStaysForTheConfiguredRetention() throws Exception {
        ProgressiveSearches searches = searches(limits(4, 0, Duration.ofSeconds(30)));
        var finished = searches.start(caller, KeywordSearch.Query.of("schnell"), SearchProfile.defaults());
        finished.result().get(10, TimeUnit.SECONDS);

        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(searches.find(caller, finished.id())).isPresent();
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(searches.find(caller, finished.id())).isEmpty();
    }

    private static ProgressiveSearches.Limits limits(int running, int waiting, Duration retention) {
        return new ProgressiveSearches.Limits(running, waiting, retention, ProgressiveSearches.DEFAULT_MAX_TIME_BUDGET);
    }

    private ProgressiveSearches searches(ProgressiveSearches.Limits limits) {
        VariantSearch search = (AccessContext who, KeywordSearch.Query query, int limit) -> {
            if (query.text().contains("langsam")) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new KeywordSearch.Results("wiki", List.of(), List.of(), null, true, 0);
        };
        HitScorer scorer = (original, hits) -> new double[hits.size()];
        RuleBasedExpansion rules = new RuleBasedExpansion();
        orchestrator = new SearchOrchestrator(search, search, rules, rules, scorer);
        return new ProgressiveSearches(orchestrator, SearchProfile::defaults, limits, now::get);
    }
}
