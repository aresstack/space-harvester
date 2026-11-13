package com.aresstack.spaceharvester.application.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.CatalogPage;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.LockedPage;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.Stock;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Die Liste freigegebener Seiten für die Oberfläche (ADR 0028, Abschnitte 3 und 6): Eintragen nur lokaler Seiten einer
 * Auswahl mit ihrer Wurzel als Knoten, Anzahl neuer Seiten, Gruppen und Entfernen je Seite, Gruppe oder Space.
 */
class PageReleaseServiceTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("app");
    private static final Instant NOW = Instant.parse("2026-11-13T21:00:00Z");
    private static final CacheGeneration CONFIRMED = new CacheGeneration("g2", CacheState.STALE, NOW, NOW);
    private static final SourceCatalog SOURCES = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(mock(HarvestSource.class)) : Optional.empty();
        }
    };

    private final Map<String, Stock> stocks = new HashMap<>();
    private final HoldingsCatalog catalog = new HoldingsCatalog() {
        @Override
        public Optional<Stock> stock(String sourceId, String spaceKey) {
            return Optional.ofNullable(stocks.get(sourceId + "/" + spaceKey));
        }

        @Override
        public List<String> spaceKeys(String sourceId) {
            return stocks.keySet().stream().filter(key -> key.startsWith(sourceId + "/"))
                    .map(key -> key.substring(sourceId.length() + 1)).sorted().toList();
        }

        @Override
        public Optional<String> spaceOf(String sourceId, String pageId) {
            return Optional.empty();
        }
    };
    private final PageReleases releases = PageReleases.inMemory();
    private Holdings holdings;

    @AfterEach
    void close() {
        if (holdings != null) {
            holdings.close();
        }
    }

    /** DEV: 1 lokal mit 2 (nur Quelle) und 3 (veraltet), unter 3 die veraltete 4 und die gesperrte 6; OPS: 8 lokal. */
    private PageReleaseService service(boolean opsVisible) {
        return service(new AccessGuard((context, scope, kind) -> !opsVisible && kind == AccessKind.METADATA
                && "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW));
    }

    private PageReleaseService service(AccessGuard access) {
        stocks.put("wiki/DEV", new Stock(CONFIRMED, List.of(
                new CatalogPage("1", null, true, "3", "3"),
                new CatalogPage("2", "1", false, "1", null),
                new CatalogPage("3", "1", true, "4", "3"),
                new CatalogPage("4", "3", true, "2", "2")),
                Set.of("4"), List.of(new LockedPage("6", "3"))));
        stocks.put("wiki/OPS", new Stock(CONFIRMED, List.of(new CatalogPage("8", null, true, "1", "1")), Set.of(),
                List.of()));
        close();
        holdings = new Holdings(SOURCES, catalog, access, 100);
        return new PageReleaseService(releases, holdings, access, PageReleaseServiceTest::header,
                key -> key.pageId().equals("3"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aPageTheCallerMayNotSeeInAVisibleSpaceIsNeitherShownNorRemovedNorReported() {
        PageReleaseService open = service(true);
        open.release(CALLER, Selection.of(Selection.Root.subtree("wiki", "DEV", "3")));
        // Der Space ist sichtbar, Seite 3 (zugleich der Knoten) nicht.
        PageReleaseService restricted = service(new AccessGuard((context, scope, kind) -> kind == AccessKind.METADATA
                && "3".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW));
        List<PageReleases.Change> changes = new java.util.ArrayList<>();
        restricted.subscribe(CALLER, changes::add);

        assertThat(restricted.groups(CALLER)).extracting(group -> group.node() + "/" + group.nodeTitle() + "/"
                + group.count()).containsExactly("3/null/1");
        assertThat(restricted.pages(CALLER, "wiki", "DEV", "3")).extracting(PageReleaseService.ReleasedPage::title)
                .containsExactly("Seite 4");
        assertThat(restricted.remove(CALLER, List.of(key("3")))).isEmpty();
        assertThat(releases.contains(key("3"))).isTrue();

        open.remove(CALLER, List.of(key("3")));
        assertThat(changes).as("nur verborgene Seiten geändert: keine Meldung").isEmpty();
        open.remove(CALLER, List.of(key("4")));
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.added()).isEmpty();
            assertThat(change.removed()).extracting(entry -> entry.key().pageId()).containsExactly("4");
        });
    }

    private static Optional<PageHeader> header(PageKey key) {
        if (key.pageId().equals("2")) {
            return Optional.empty();
        }
        PageMetadata metadata = new PageMetadata(key.pageId(), "confluence:wiki/page/" + key.pageId(),
                key.pageId().equals("3") ? "Betrieb" : "Seite " + key.pageId(), "DEV", null, "1", NOW, null,
                PageMetadata.sorted(Map.of()));
        return Optional.of(PageHeader.of(key.sourceId(), metadata, NOW));
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    @Test
    void onlyStoredPagesOfTheSelectionComeOnTheListWithTheirRootAsNode() {
        PageReleaseService service = service(true);
        Selection subtree = Selection.of(Selection.Root.subtree("wiki", "DEV", "3"));

        assertThat(service.newPages(CALLER, subtree)).isEqualTo(2);
        assertThat(service.release(CALLER, subtree)).extracting(entry -> entry.key().pageId() + "@" + entry.node())
                .containsExactlyInAnyOrder("3@3", "4@3");
        assertThat(releases.contains(key("6"))).as("gesperrt").isFalse();
        assertThat(service.newPages(CALLER, subtree)).isZero();

        // Der ganze Space danach: nur das Neue kommt dazu, 3 und 4 behalten ihren Knoten; 2 ist nur in der Quelle.
        Selection space = Selection.of(Selection.Root.space("wiki", "DEV"));
        assertThat(service.newPages(CALLER, space)).isEqualTo(1);
        assertThat(service.release(CALLER, space)).extracting(entry -> entry.key().pageId())
                .containsExactly("1");
        assertThat(releases.entries()).allSatisfy(entry -> {
            assertThat(entry.spaceKey()).isEqualTo("DEV");
            assertThat(entry.origin()).isEqualTo(PageReleases.CHAT);
            assertThat(entry.releasedAt()).isEqualTo(NOW);
        });
        assertThat(releases.contains(key("2"))).isFalse();
        assertThat(service.release(CALLER, space)).isEmpty();
    }

    @Test
    void theListIsGroupedBySpaceAndNodeAndRemovableByPageGroupAndSpace() {
        PageReleaseService service = service(true);
        service.release(CALLER, Selection.of(Selection.Root.subtree("wiki", "DEV", "3")));
        service.release(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")));
        service.release(CALLER, Selection.of(Selection.Root.space("wiki", "OPS")));

        assertThat(service.groups(CALLER)).extracting(group -> group.spaceKey() + "/" + group.node() + "/"
                + group.nodeTitle() + "/" + group.count())
                .containsExactly("DEV/null/null/1", "DEV/3/Betrieb/2", "OPS/null/null/1");
        assertThat(service.pages(CALLER, "wiki", "DEV", "3"))
                .extracting(page -> page.title() + ":" + page.indexed())
                .containsExactly("Betrieb:true", "Seite 4:false");

        assertThat(service.remove(CALLER, List.of(key("4")))).extracting(entry -> entry.key().pageId())
                .containsExactly("4");
        assertThat(service.removeGroup(CALLER, "wiki", "DEV", "3")).extracting(entry -> entry.key().pageId())
                .containsExactly("3");
        assertThat(service.removeSpace(CALLER, "wiki", "DEV")).extracting(entry -> entry.key().pageId())
                .containsExactly("1");
        assertThat(releases.entries()).extracting(entry -> entry.key().pageId()).containsExactly("8");
    }

    @Test
    void pagesInSpacesTheCallerMayNotSeeNeitherAppearNorAreRemoved() {
        service(true).release(CALLER, Selection.of(Selection.Root.space("wiki", "OPS")));
        PageReleaseService restricted = service(false);

        assertThat(restricted.groups(CALLER)).isEmpty();
        assertThat(restricted.remove(CALLER, List.of(key("8")))).isEmpty();
        assertThat(restricted.removeSpace(CALLER, "wiki", "OPS")).isEmpty();
        assertThat(releases.contains(key("8"))).isTrue();
    }
}
