package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SearchHitPrefetchTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final List<PageKey> loaded = Collections.synchronizedList(new ArrayList<>());
    private final Map<PageKey, String> cache = Map.of(new PageKey("wiki", "1"), "3", new PageKey("wiki", "2"), "1");

    @Test
    void loadsEveryHitWhoseVersionIsNotCachedAndWaitsForIt() throws Exception {
        List<KeywordSearch.Hit> hits = new ArrayList<>(List.of(hit("1", "3"), hit("2", "2"), hit("3", null)));
        IntStream.range(10, 30).forEach(i -> hits.add(hit(String.valueOf(i), "1")));

        try (SearchHitPrefetch prefetch = prefetch(Duration.ofSeconds(10), null)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, hits);
            assertThat(fill.pending()).isEmpty();
            assertThat(fill.failed()).isZero();
            assertThat(fill.progress().done()).isTrue();
            assertThat(prefetch.progress(fill.progressId())).contains(fill.progress());
        }

        // Nicht nur die ersten zehn: jede gefundene Seite des Stapels, die gleiche Version nicht noch einmal.
        assertThat(loaded).extracting(PageKey::pageId).hasSize(22).doesNotContain("1").contains("2", "3", "29");
    }

    @Test
    void whatIsStillLoadingIsCountedAndKeepsLoadingForTechnicalCallers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, List.of(hit("5", "1"), hit("6", "1")));
            assertThat(fill.pending()).extracting(KeywordSearch.Hit::pageId).containsExactly("5", "6");
            assertThat(fill.failed()).isZero();

            release.countDown();
        }
        assertThat(loaded).extracting(PageKey::pageId).containsExactlyInAnyOrder("5", "6");
    }

    @Test
    void delegatedUsersLoadOnlyDuringTheirCall() throws Exception {
        AccessContext user = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));
        CountDownLatch release = new CountDownLatch(1);
        List<KeywordSearch.Hit> hits = IntStream.range(40, 50).mapToObj(i -> hit(String.valueOf(i), "1")).toList();

        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            assertThat(prefetch.prefetch(user, hits).pending()).hasSize(10);

            release.countDown();
            TimeUnit.MILLISECONDS.sleep(200);
        }
        // Nur die beiden schon laufenden Abrufe enden regulär, die wartenden entfallen mit dem Aufruf.
        assertThat(loaded).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void aDisabledPrefetchLoadsNothing() {
        assertThat(SearchHitPrefetch.disabled().prefetch(APP, List.of(hit("9", "1"))))
                .isEqualTo(KeywordSearch.Prefetch.Fill.NONE);
        assertThat(loaded).isEmpty();
    }

    @Test
    void aFailedLoadIsCountedAsAGapNotAsLoaded() throws Exception {
        try (SearchHitPrefetch prefetch = new SearchHitPrefetch(key -> Optional.empty(), (caller, key) -> {
            if (key.pageId().equals("7")) {
                throw new IllegalStateException("Quelle antwortet nicht");
            }
            loaded.add(key);
        }, SearchHitPrefetch.background(2, 50), Duration.ofSeconds(10))) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, List.of(hit("7", "1"), hit("8", "1")));

            assertThat(fill.failed()).isEqualTo(1);
            assertThat(fill.pending()).isEmpty();
        }
        assertThat(loaded).extracting(PageKey::pageId).containsExactly("8");
    }

    private SearchHitPrefetch prefetch(Duration wait, CountDownLatch release) {
        return new SearchHitPrefetch(key -> Optional.ofNullable(cache.get(key)), (caller, key) -> {
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            loaded.add(key);
        }, SearchHitPrefetch.background(2, 50), wait);
    }

    private static KeywordSearch.Hit hit(String pageId, String version) {
        return new KeywordSearch.Hit("wiki", pageId, "DEV", "T", "", null, version, null, null);
    }
}
