package com.aresstack.spaceharvester.application.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Quellen ohne Seitenbaum (wie Jira: Vorgänge flach unter dem Projekt) melden trotzdem eine Stelle, sonst bleibt das
 * Fadenkreuz für jede ihrer Seiten grau (Angelo, 27.11.2026). Erfundene Testdaten.
 */
class FlatSourcePositionTest {

    private static final String SOURCE = "tickets";
    private static final AccessContext READER = AccessContext.technicalClient("reader");

    @Test
    void sourceWithoutPageTreeReportsThePartitionOfTheTicketFromTheSource() {
        FlatSource source = new FlatSource(false);

        PageNavigation.Position position = navigation(source, null).position(READER, SOURCE, "ABC-7");

        assertThat(position.spaceKey()).isEqualTo("ABC");
        assertThat(position.ancestors()).isEmpty();
        assertThat(position.page().pageId()).isEqualTo("ABC-7");
        assertThat(position.page().title()).isEqualTo("Vorgang ABC-7");
        assertThat(source.metadataCalls.get()).isEqualTo(1);
    }

    @Test
    void subtaskKeepsItsParentIssueAsAncestorLikeTheTree() {
        FlatSource source = new FlatSource(false);

        PageNavigation.Position position = navigation(source, null).position(READER, SOURCE, "ABC-9");

        assertThat(position.spaceKey()).isEqualTo("ABC");
        assertThat(position.ancestors()).extracting(HarvestSource.PageNode::pageId).containsExactly("ABC-7");
        assertThat(position.page().pageId()).isEqualTo("ABC-9");
    }

    @Test
    void storedTicketNeedsNoRequestToTheSource() {
        InMemoryPageCache cache = new InMemoryPageCache();
        cache.publish(SOURCE, "ABC", InMemoryPageCache.confirmed("g1"),
                List.of(InMemoryPageCache.page(SOURCE, "ABC", "ABC-7", null)));
        FlatSource source = new FlatSource(false);

        PageNavigation.Position position = navigation(source, cache).position(READER, SOURCE, "ABC-7");

        assertThat(position.spaceKey()).isEqualTo("ABC");
        assertThat(source.metadataCalls.get()).isZero();
    }

    @Test
    void sourceWithPageTreeKeepsItsAnswer() {
        FlatSource source = new FlatSource(true);

        assertThatThrownBy(() -> navigation(source, null).position(READER, SOURCE, "ABC-7"))
                .isInstanceOf(SourceAccessException.class);
        assertThat(source.metadataCalls.get()).isZero();
    }

    private static PageNavigation navigation(HarvestSource source, InMemoryPageCache cache) {
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
        return new PageNavigation(catalog, cache, new AccessGuard((user, scope, kind) -> AccessDecision.ALLOW));
    }

    /** Ohne {@code position}: die Standardantwort {@code UNSUPPORTED} wie bei Jira. */
    private static final class FlatSource implements HarvestSource {

        private final boolean navigation;
        private final AtomicInteger metadataCalls = new AtomicInteger();

        FlatSource(boolean navigation) {
            this.navigation = navigation;
        }

        @Override
        public String id() {
            return SOURCE;
        }

        @Override
        public String type() {
            return "tickets";
        }

        @Override
        public String title() {
            return "Tickets";
        }

        @Override
        public Set<SourceCapability> capabilities() {
            Set<SourceCapability> capabilities = EnumSet.copyOf(HarvestSource.super.capabilities());
            if (navigation) {
                capabilities.add(SourceCapability.NAVIGATION);
            }
            return capabilities;
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("ABC", "Projekt ABC", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            metadataCalls.incrementAndGet();
            // ABC-9 ist eine Unteraufgabe von ABC-7.
            String parent = "ABC-9".equals(pageId) ? "ABC-7" : null;
            return new PageMetadata(pageId, "tickets:issue/" + pageId, "Vorgang " + pageId, "ABC", parent, "1", null,
                    null, PageMetadata.sorted(Map.of()));
        }
    }
}
