package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BscwCachedPositionTest {

    private static final String SOURCE = "bscw-bund";
    private static final AccessContext READER = AccessContext.technicalClient("reader");

    @Test
    void opensIndexedDocumentThroughItsPersistedParentFoldersAfterSourceRestart() {
        InMemoryPageCache cache = new InMemoryPageCache();
        cache.publish(SOURCE, SOURCE, InMemoryPageCache.confirmed("g1"), List.of(
                InMemoryPageCache.page(SOURCE, SOURCE, "root", null),
                InMemoryPageCache.page(SOURCE, SOURCE, "group", "root"),
                InMemoryPageCache.page(SOURCE, SOURCE, "project", "group"),
                InMemoryPageCache.page(SOURCE, SOURCE, "slides.pptx", "project")));
        PageNavigation navigation = navigation(cache, "bscw");

        PageNavigation.Position position = navigation.position(READER, SOURCE, "slides.pptx");

        assertThat(position.ancestors()).extracting(HarvestSource.PageNode::pageId)
                .containsExactly("group", "project");
        assertThat(position.ancestors()).extracting(HarvestSource.PageNode::kind)
                .containsExactly(HarvestSource.PageNode.Kind.FOLDER, HarvestSource.PageNode.Kind.FOLDER);
        assertThat(position.page().pageId()).isEqualTo("slides.pptx");
    }

    @Test
    void doesNotInventAncestorsWhenTheCacheHasNotCatalogedACompletePath() {
        InMemoryPageCache cache = new InMemoryPageCache();
        cache.publish(SOURCE, SOURCE, InMemoryPageCache.confirmed("g1"), List.of(
                InMemoryPageCache.page(SOURCE, SOURCE, "slides.pptx", "project")));
        PageNavigation.Position position = navigation(cache, "bscw").position(READER, SOURCE, "slides.pptx");

        assertThat(position.ancestors()).isEmpty();
    }

    @Test
    void doesNotReplaceOtherSourcesLiveAncestryWithCachedMetadata() {
        InMemoryPageCache cache = new InMemoryPageCache();
        cache.publish(SOURCE, SOURCE, InMemoryPageCache.confirmed("g1"), List.of(
                InMemoryPageCache.page(SOURCE, SOURCE, "root", null),
                InMemoryPageCache.page(SOURCE, SOURCE, "team", "root"),
                InMemoryPageCache.page(SOURCE, SOURCE, "slides.pptx", "team")));
        PageNavigation.Position position = navigation(cache, "confluence")
                .position(READER, SOURCE, "slides.pptx");

        assertThat(position.ancestors()).isEmpty();
    }

    private static PageNavigation navigation(InMemoryPageCache cache, String type) {
        HarvestSource source = new HarvestSource() {
            @Override
            public String id() {
                return SOURCE;
            }

            @Override
            public String type() {
                return type;
            }

            @Override
            public String title() {
                return "BSCW";
            }

            @Override
            public boolean navigationRootIsPage() {
                return "bscw".equals(type);
            }

            @Override
            public List<Partition> partitions() {
                return List.of(new Partition(SOURCE, "BSCW", null));
            }

            @Override
            public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
                return new PageMetadataBatch(List.of(), null);
            }

            @Override
            public PageMetadata pageMetadata(String pageId) {
                return cache.header(new com.aresstack.spaceharvester.application.snapshot.PageKey(SOURCE, pageId))
                        .orElseThrow().metadata();
            }

            @Override
            public PagePosition position(String pageId) {
                return new PagePosition(SOURCE, "BSCW", List.of(),
                        new PageNode(pageId, pageId, SOURCE, false, PageNode.Kind.FILE));
            }
        };
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return SOURCE.equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
        return new PageNavigation(catalog, cache, new AccessGuard((user, scope, permission) ->
                AccessDecision.ALLOW));
    }
}
