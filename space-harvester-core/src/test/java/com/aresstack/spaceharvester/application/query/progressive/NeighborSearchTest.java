package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * {@link NeighborSearch} mit Nachladen: Fehlende Nachbarn und lokale ohne Treffer gehen an den Lader, innerhalb des
 * Umfangs; die Suche wartet nicht, und ohne {@code load} lädt nichts.
 */
class NeighborSearchTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final HitReason.Neighbor VIA = new HitReason.Neighbor("wiki", "o", "Seite o",
            HitReason.Neighbor.Edge.LINKS_TO);

    private final KeywordSearch keywords = mock(KeywordSearch.class);
    private final List<Collection<PageKey>> loaded = new ArrayList<>();
    private final NeighborLoader loader = (caller, pages) -> {
        loaded.add(List.copyOf(pages));
        return Optional.of(new NeighborLoader.Load() {
            @Override
            public String id() {
                return "bg-1";
            }

            @Override
            public int pages() {
                return pages.size();
            }

            @Override
            public Subscription whenDone(Consumer<Boolean> done) {
                return Subscription.NONE;
            }

            @Override
            public void cancel() {
            }
        });
    };

    /** a und b liegen lokal (a trifft, b nicht), x und y fehlen; y liegt außerhalb des Umfangs. */
    private final NeighborSource neighbors = new NeighborSource() {
        @Override
        public Map<PageKey, HitReason.Neighbor> neighbors(AccessContext caller, List<KeywordSearch.Hit> seeds) {
            return find(caller, seeds).local();
        }

        @Override
        public Found find(AccessContext caller, List<KeywordSearch.Hit> seeds) {
            return new Found(Map.of(key("a"), VIA, key("b"), VIA), Map.of(key("x"), VIA, key("y"), VIA));
        }
    };

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    private static KeywordSearch.Hit hit(String pageId) {
        return new KeywordSearch.Hit("wiki", pageId, "DEV", "Seite " + pageId, "Auszug", null, "1", null, null);
    }

    private NeighborSearch search() {
        when(keywords.search(any(), any(), isNull(), anyInt())).thenReturn(new KeywordSearch.Results("wiki",
                List.of(hit("a")), List.of(), null, true, 0));
        return new NeighborSearch(keywords, neighbors, loader);
    }

    @Test
    void missingNeighborsAndLocalOnesWithoutAHitAreLoadedWithinTheScopeWhileTheHitsComeAtOnce() {
        KeywordSearch.Query query = KeywordSearch.Query.of("Nachtlauf")
                .within(PageSet.ofKeys(Set.of(key("o"), key("a"), key("b"), key("x"))));

        NeighborSearch.Result result = search().search(APP, query, List.of(hit("o")), true);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("a");
        assertThat(result.load()).map(NeighborLoader.Load::id).contains("bg-1");
        assertThat(loaded).containsExactly(List.of(key("b"), key("x")));
    }

    @Test
    void withoutLoadingNothingIsLoaded() {
        NeighborSearch.Result result = search().search(APP, KeywordSearch.Query.of("Nachtlauf"), List.of(hit("o")),
                false);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("a");
        assertThat(result.load()).isEmpty();
        assertThat(loaded).isEmpty();
    }
}
