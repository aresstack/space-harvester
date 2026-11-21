package com.aresstack.spaceharvester.application.query.progressive;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.structure.LinkDirectory;
import com.aresstack.spaceharvester.application.structure.PageConnections;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link NeighborCandidates}: nur Hierarchie und Verweise, einen Schritt weit, mit den Rechten des Aufrufers; eine
 * gesperrte Seite wird nie Kandidat.
 */
class NeighborCandidatesTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-21T03:00:00Z");

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source());
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source()) : Optional.empty();
        }
    };
    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final List<PageSnapshot> pages = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final AccessPolicy policy = (context, scope, kind) -> scope.pageId() != null
            && hidden.contains(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW;

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    /** Handbuch mit Betrieb und Buchung; Betrieb hat Nachtlauf und Abschluss als Unterseiten. */
    private NeighborCandidates candidates() {
        return candidates(LinkDirectory.NONE);
    }

    private NeighborCandidates candidates(LinkDirectory links) {
        String[][] all = {
                {"0", "Handbuch", null},
                {"1", "Betrieb", "0"},
                {"2", "Buchung", "0"},
                {"4", "Nachtlauf", "1"},
                {"5", "Abschluss", "1"},
                {"6", "Waise", "77"}};
        for (String[] row : all) {
            PageMetadata metadata = new PageMetadata(row[0], "confluence:wiki/page/" + row[0], row[1], "DEV", row[2],
                    "1", T0, null, PageMetadata.sorted(Map.of()));
            pages.add(PageSnapshot.of("wiki", metadata, new NativePayload("Text".getBytes(StandardCharsets.UTF_8),
                    "text/plain", "test"), T0));
        }
        index.publish("wiki", new SearchIndexState("i1", T0), pages.stream()
                .map(page -> entry(page, confirmed("g1"))).toList());
        return new NeighborCandidates(new PageConnections(catalog, index, new AccessGuard(policy),
                links, null, null));
    }

    private static KeywordSearch.Hit hit(String pageId, String title) {
        return new KeywordSearch.Hit("wiki", pageId, "DEV", title, "Auszug", null, "1", null, null);
    }

    @Test
    void parentAndChildrenOfTheSeedAreCandidatesWithTheirEdgeAndTheSeedItselfIsNot() {
        Map<PageKey, HitReason.Neighbor> found = candidates().neighbors(CALLER, List.of(hit("1", "Betrieb")));

        assertThat(found).containsOnlyKeys(new PageKey("wiki", "0"), new PageKey("wiki", "4"),
                new PageKey("wiki", "5"));
        assertThat(found.get(new PageKey("wiki", "0"))).isEqualTo(new HitReason.Neighbor("wiki", "1", "Betrieb",
                HitReason.Neighbor.Edge.PARENT));
        assertThat(found.get(new PageKey("wiki", "4")).edge()).isEqualTo(HitReason.Neighbor.Edge.CHILD);
    }

    @Test
    void aHiddenPageIsNeverACandidate() {
        hidden.add("4");

        Map<PageKey, HitReason.Neighbor> found = candidates().neighbors(CALLER, List.of(hit("1", "Betrieb")));

        assertThat(found).doesNotContainKey(new PageKey("wiki", "4")).containsKey(new PageKey("wiki", "5"));
    }

    @Test
    void otherSeedsAreNoCandidatesAndAnUnknownSeedContributesNothing() {
        Map<PageKey, HitReason.Neighbor> found = candidates().neighbors(CALLER, List.of(hit("x", "Unbekannt"),
                hit("1", "Betrieb"), hit("0", "Handbuch")));

        // Handbuch ist selbst Treffer; Buchung kommt über Handbuch.
        assertThat(found).containsOnlyKeys(new PageKey("wiki", "4"), new PageKey("wiki", "5"),
                new PageKey("wiki", "2"));
        assertThat(found.get(new PageKey("wiki", "2")).pageId()).isEqualTo("0");
    }

    @Test
    void knownNeighborsThatAreNotLocalAreReportedForLoadingButAreNoCandidates() {
        LinkDirectory links = (caller, page) -> page.pageId().equals("4")
                ? Optional.of(new LinkDirectory.Links(List.of(new PageKey("wiki", "9"), new PageKey("wiki", "5")),
                List.of(), true))
                : Optional.empty();
        NeighborCandidates source = candidates(links);

        NeighborSource.Found found = source.find(CALLER, List.of(hit("4", "Nachtlauf"), hit("6", "Waise")));

        assertThat(found.local()).containsOnlyKeys(new PageKey("wiki", "1"), new PageKey("wiki", "5"));
        // Nachtlauf verweist auf eine fehlende Seite, die Waise hängt an einer fehlenden Elternseite.
        assertThat(found.missing()).containsOnlyKeys(new PageKey("wiki", "9"), new PageKey("wiki", "77"));
        assertThat(found.missing().get(new PageKey("wiki", "77")).edge()).isEqualTo(HitReason.Neighbor.Edge.PARENT);
        assertThat(found.missing().get(new PageKey("wiki", "9"))).isEqualTo(new HitReason.Neighbor("wiki", "4",
                "Nachtlauf", HitReason.Neighbor.Edge.LINKS_TO));
    }
}
