package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SearchHitPrefetchTest {

    private final List<PageKey> loaded = new ArrayList<>();
    private final Map<PageKey, String> cache = Map.of(new PageKey("wiki", "1"), "3", new PageKey("wiki", "2"), "1");
    private final SearchHitPrefetch prefetch = new SearchHitPrefetch(key -> Optional.ofNullable(cache.get(key)),
            (caller, key) -> loaded.add(key));

    @Test
    void loadsOnlyHitsWhoseVersionIsNotCachedAndAtMostTenPerBatch() {
        List<KeywordSearch.Hit> hits = new ArrayList<>(List.of(hit("1", "3"), hit("2", "2"), hit("3", null)));
        IntStream.range(10, 30).forEach(i -> hits.add(hit(String.valueOf(i), "1")));

        prefetch.prefetch(AccessContext.technicalClient("app"), hits);

        assertThat(loaded).extracting(PageKey::pageId).containsExactly("2", "3", "10", "11", "12", "13", "14", "15",
                "16");
    }

    @Test
    void delegatedUsersAreNeverLoadedInTheBackground() {
        prefetch.prefetch(AccessContext.endUser("u", "app", new UserCredential("wiki", "t")), List.of(hit("9", "1")));

        assertThat(loaded).isEmpty();
    }

    private static KeywordSearch.Hit hit(String pageId, String version) {
        return new KeywordSearch.Hit("wiki", pageId, "DEV", "T", "", null, version, null, null);
    }
}
