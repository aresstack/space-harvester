package com.aresstack.spaceharvester.application.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Das Verzeichnis der Verweise (Paket V1): beide Richtungen, Zuordnung, Aktualität, Rechte, Beobachter. */
class PageLinksTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("test");
    /** Alles frei, auch quellenweit (wie der lokale Bediener der App). */
    private static final AccessPolicy ALLOW = new AccessPolicy() {
        @Override
        public AccessDecision decide(AccessContext context,
                                     com.aresstack.spaceharvester.application.access.ResourceScope scope,
                                     AccessKind kind) {
            return AccessDecision.ALLOW;
        }

        @Override
        public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
            return AccessDecision.ALLOW;
        }
    };

    private final FakePages pages = new FakePages();
    private final InMemoryLinkIndex index = new InMemoryLinkIndex();
    private PageLinks links = links(ALLOW);

    @AfterEach
    void close() {
        links.close();
    }

    private PageLinks links(AccessPolicy policy) {
        return new PageLinks(pages, index, List.of(new LineExtractor()), new AccessGuard(policy),
                Duration.ofMillis(10));
    }

    @Test
    void incomingLinksComeFromTheStoredPagesByIdAndByTitle() {
        pages.page("1", "DEV", "Start", "id:3");
        pages.page("2", "DEV", "Plan", "title:DEV/Ziel");
        pages.page("3", "DEV", "Ziel", "");
        links.reconcileNow("wiki");

        PageLinks.Links target = links.links(CALLER, key("3"));

        assertThat(target.incoming()).containsExactly(key("1"), key("2"));
        assertThat(target.outgoing()).isEmpty();
        assertThat(target.outgoingKnown()).isTrue();
        assertThat(target.coverage()).isEqualTo(new PageLinks.Coverage(3, 3));
        assertThat(target.complete()).isTrue();
        assertThat(links.links(CALLER, key("2")).outgoing()).containsExactly(key("3"));
    }

    @Test
    void coverageTellsHowManyKnownPagesAreStoredAndAPageWithoutPayloadIsNotIndexed() {
        pages.page("1", "DEV", "Start", "id:2");
        pages.headerOnly("2", "DEV", "Nur Kopfdaten");
        links.reconcileNow("wiki");

        PageLinks.Links header = links.links(CALLER, key("2"));

        assertThat(header.incoming()).containsExactly(key("1"));
        assertThat(header.outgoingKnown()).isFalse();
        assertThat(header.coverage()).isEqualTo(new PageLinks.Coverage(1, 2));
        assertThat(header.complete()).isFalse();
    }

    @Test
    void aTitleTargetResolvesTheSameInBothDirectionsAlsoForAPageWithoutPayload() {
        pages.page("1", "DEV", "Start", "title:DEV/Nur Kopfdaten");
        pages.headerOnly("2", "DEV", "Nur Kopfdaten");
        links.reconcileNow("wiki");

        assertThat(links.links(CALLER, key("1")).outgoing()).containsExactly(key("2"));
        assertThat(links.links(CALLER, key("2")).incoming()).containsExactly(key("1"));

        pages.headerOnly("3", "DEV", "nur kopfdaten");
        links.applyNow("wiki", List.of("3"));

        assertThat(links.links(CALLER, key("1")).outgoing()).as("nicht mehr eindeutig").isEmpty();
        assertThat(links.links(CALLER, key("2")).incoming()).isEmpty();
    }

    @Test
    void aTargetThatIsNotKnownLocallyOrNotUniqueMakesNoEdge() {
        pages.page("1", "DEV", "Start", "id:99\ntitle:DEV/Doppelt\ntitle:DEV/Fehlt");
        pages.page("2", "DEV", "Doppelt", "");
        pages.page("3", "DEV", "doppelt", "");
        links.reconcileNow("wiki");

        assertThat(links.links(CALLER, key("1")).outgoing()).isEmpty();
        assertThat(links.links(CALLER, key("2")).incoming()).isEmpty();
        assertThat(links.links(CALLER, key("3")).incoming()).isEmpty();
    }

    @Test
    void renamingTheTargetDropsTheOldEdgeAndANewOneNeedsTheNewTitle() {
        pages.page("1", "DEV", "Start", "title:DEV/Alt");
        pages.page("2", "DEV", "Alt", "");
        links.reconcileNow("wiki");
        assertThat(links.links(CALLER, key("2")).incoming()).containsExactly(key("1"));

        pages.page("2", "DEV", "Neu", "");
        links.applyNow("wiki", List.of("2"));

        assertThat(links.links(CALLER, key("2")).incoming()).isEmpty();
        assertThat(links.links(CALLER, key("1")).outgoing()).isEmpty();

        pages.page("1", "DEV", "Start", "title:DEV/Neu");
        links.applyNow("wiki", List.of("1"));

        assertThat(links.links(CALLER, key("2")).incoming()).containsExactly(key("1"));
    }

    @Test
    void aRemovedPageLeavesTheDirectoryAndItsEdgesGo() {
        pages.page("1", "DEV", "Start", "id:2");
        pages.page("2", "DEV", "Ziel", "");
        links.reconcileNow("wiki");

        pages.remove("1");
        links.applyNow("wiki", List.of("1"));

        assertThat(links.links(CALLER, key("2")).incoming()).isEmpty();
        assertThat(index.count("wiki")).isEqualTo(1);
    }

    @Test
    void reconcileRemovesEntriesOfPagesTheCacheNoLongerHasAndSkipsUnchangedPayloads() {
        pages.page("1", "DEV", "Start", "id:2");
        pages.page("2", "DEV", "Ziel", "");
        links.reconcileNow("wiki");
        pages.remove("1");
        int reads = pages.snapshotReads;

        links.reconcileNow("wiki");

        assertThat(index.pageIds("wiki")).containsExactly("2");
        assertThat(pages.snapshotReads).as("unveränderte Payload nicht neu gelesen").isEqualTo(reads);
    }

    @Test
    void aSourceWithoutExtractorHasNoDirectory() {
        pages.foreign("7", "DEV", "Datei");
        links.reconcileNow("wiki");

        PageLinks.Links foreign = links.links(CALLER, key("7"));

        assertThat(foreign.outgoingKnown()).isFalse();
        assertThat(foreign.coverage().indexed()).isZero();
        assertThat(pages.snapshotReads).as("ohne Extraktor keine Payload geladen").isZero();
    }

    @Test
    void pagesTheCallerMayNotSeeAreLeftOutAndCountersHidden() {
        pages.page("1", "DEV", "Start", "id:3");
        pages.page("2", "GEHEIM", "Intern", "id:3");
        pages.page("3", "DEV", "Ziel", "");
        links.reconcileNow("wiki");
        links.close();
        // Quellenweit unbestimmt: die Zähler bleiben verborgen.
        links = links((context, scope, kind) -> "GEHEIM".equals(scope.spaceKey())
                ? AccessDecision.DENY : AccessDecision.ALLOW);

        PageLinks.Links target = links.links(CALLER, key("3"));

        assertThat(target.incoming()).containsExactly(key("1"));
        assertThat(target.coverage()).isEqualTo(PageLinks.Coverage.HIDDEN);
        assertThatThrownBy(() -> links.links(CALLER, key("2")))
                .isInstanceOf(com.aresstack.spaceharvester.application.access.AccessDeniedException.class);
        assertThat(links.links(CALLER, List.of(key("2"), key("3")))).containsOnlyKeys(key("3"));
    }

    @Test
    void changesAreAnnouncedByTheObserverWithoutPolling() throws InterruptedException {
        pages.page("1", "DEV", "Start", "");
        pages.page("2", "DEV", "Ziel", "");
        links.reconcileNow("wiki");
        List<PageLinks.LinksChanged> events = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        links.subscribe(event -> {
            events.add(event);
            delivered.countDown();
        });

        pages.page("1", "DEV", "Start", "id:2");
        links.pagesChanged("wiki", "DEV", List.of("1"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.getFirst().sourceId()).isEqualTo("wiki");
        assertThat(events.getFirst().pageIds()).contains("1", "2");
        assertThat(links.links(CALLER, key("2")).incoming()).containsExactly(key("1"));
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    /** Liest Zeilen {@code id:<id>} und {@code title:<space>/<titel>} aus Payloads von Ressourcen {@code test:…}. */
    private static final class LineExtractor implements PageLinkExtractor {

        @Override
        public boolean supports(PageMetadata metadata) {
            return metadata.resourceId().startsWith("test:");
        }

        @Override
        public List<LinkTarget> targets(PageSnapshot snapshot) {
            List<LinkTarget> targets = new ArrayList<>();
            for (String line : new String(snapshot.payload().bytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("id:")) {
                    targets.add(LinkTarget.page(line.substring(3)));
                } else if (line.startsWith("title:")) {
                    String[] parts = line.substring(6).split("/", 2);
                    targets.add(LinkTarget.titled(parts[0], parts[1]));
                }
            }
            return targets;
        }
    }

    private static final class FakePages implements PageLinks.StoredPages {

        private final Map<String, PageHeader> headers = new TreeMap<>();
        private final Map<String, PageSnapshot> snapshots = new TreeMap<>();
        int snapshotReads;

        void page(String pageId, String spaceKey, String title, String payload) {
            PageSnapshot snapshot = PageSnapshot.of("wiki", metadata(pageId, "test:" + pageId, spaceKey, title),
                    new NativePayload(payload.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), Instant.EPOCH);
            snapshots.put(pageId, snapshot);
            headers.put(pageId, snapshot.header());
        }

        void headerOnly(String pageId, String spaceKey, String title) {
            PageSnapshot snapshot = PageSnapshot.of("wiki", metadata(pageId, "test:" + pageId, spaceKey, title),
                    new NativePayload(new byte[0], "text/plain", "test"), Instant.EPOCH);
            snapshots.remove(pageId);
            headers.put(pageId, snapshot.header());
        }

        void foreign(String pageId, String spaceKey, String title) {
            PageSnapshot snapshot = PageSnapshot.of("wiki", metadata(pageId, "file:" + pageId, spaceKey, title),
                    new NativePayload("id:1".getBytes(StandardCharsets.UTF_8), "text/plain", "file"), Instant.EPOCH);
            snapshots.put(pageId, snapshot);
            headers.put(pageId, snapshot.header());
        }

        void remove(String pageId) {
            headers.remove(pageId);
            snapshots.remove(pageId);
        }

        private static PageMetadata metadata(String pageId, String resourceId, String spaceKey, String title) {
            return new PageMetadata(pageId, resourceId, title, spaceKey, null, "1", Instant.EPOCH, null, null);
        }

        @Override
        public Optional<PageHeader> header(PageKey key) {
            return Optional.ofNullable(headers.get(key.pageId()));
        }

        @Override
        public Optional<PageSnapshot> snapshot(PageKey key) {
            snapshotReads++;
            return Optional.ofNullable(snapshots.get(key.pageId()));
        }

        @Override
        public List<PageLinks.StoredPage> pages(String sourceId, String afterPageId, int limit) {
            List<PageLinks.StoredPage> listed = new ArrayList<>();
            for (Map.Entry<String, PageHeader> entry : headers.entrySet()) {
                if (afterPageId != null && entry.getKey().compareTo(afterPageId) <= 0 || listed.size() == limit) {
                    continue;
                }
                PageSnapshot snapshot = snapshots.get(entry.getKey());
                listed.add(new PageLinks.StoredPage(entry.getValue(),
                        snapshot == null ? null : snapshot.payload().rawSha256()));
            }
            return listed;
        }

        @Override
        public long known(String sourceId) {
            return headers.size();
        }
    }
}
