package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Abbildung der Quellressourcen auf {@link PageMetadata}, insbesondere stabil sortierte Labels. */
class KnowledgeSourceHarvestSourceTest {

    @Test
    void labelsAreSortedAndDeduplicatedSoTheirOrderDoesNotChangeTheAttributesHash() {
        PageMetadata sourceOrder = only(new Listing("veraltet,api,architektur,api"));
        PageMetadata otherOrder = only(new Listing("api,architektur,veraltet"));

        assertThat(sourceOrder.attributes()).containsEntry("confluence.labels", "api,architektur,veraltet");
        assertThat(AttributesHash.of("wiki", sourceOrder)).isEqualTo(AttributesHash.of("wiki", otherOrder));
        assertThat(sourceOrder.pageId()).isEqualTo("101");
        assertThat(sourceOrder.parentPageId()).isEqualTo("100");
    }

    @Test
    void sourceWithoutSinglePageMetadataIsUnsupportedNotEmpty() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing("api"));

        assertThatThrownBy(() -> source.pageMetadata("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void sourceWithoutNativeResponsesIsUnsupportedNotEmpty() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing("api"));

        assertThatThrownBy(() -> source.storedPage("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> source.attachments("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void throttlingIsRateLimitedAndOtherFailuresKeepTheirKind() {
        KnowledgeSourceHarvestSource throttled = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing(new RateLimitedException("HTTP 429")));
        KnowledgeSourceHarvestSource down = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing(new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE, "HTTP 503")));

        assertThatThrownBy(() -> throttled.pageMetadata("DEV", null, 10))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.RATE_LIMITED));
        assertThatThrownBy(() -> down.pageMetadata("DEV", null, 10))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void theCallersLimitReachesTheTransportAndOversizedDownloadsAreTooLarge() {
        Files files = new Files();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", files);
        PageAttachment attachment = new PageAttachment("att7", "confluence:wiki.example.org/attachment/att7", "a.png",
                "image/png", null, "1", null, null);

        assertThatThrownBy(() -> source.attachmentFile(attachment, 1024))
                .isInstanceOfSatisfying(AttachmentTooLargeException.class,
                        e -> assertThat(e.maxBytes()).isEqualTo(1024));
        assertThat(files.maxBytes).isEqualTo(1024);
        assertThatThrownBy(() -> source.attachmentFile(attachment, 10L * Integer.MAX_VALUE))
                .isInstanceOf(AttachmentTooLargeException.class);
        assertThat(files.maxBytes).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void withoutPageMetadataThereIsNoViewToReadImagesFrom() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Files());

        assertThatThrownBy(() -> source.viewImages("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void withoutPageMetadataThereIsNoReadingView() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Files());

        assertThatThrownBy(() -> source.readingPage("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void aSourceWithoutPartitionsIsOnePartitionListedFromOneDiscovery() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThat(source.partitions()).containsExactly(new Partition("notizen", "Notizen", null));
        assertThat(source.capabilities()).contains(SourceCapability.PAGE_LISTING, SourceCapability.PAGE_METADATA,
                SourceCapability.STORED_PAGE, SourceCapability.TITLE_SEARCH, SourceCapability.ADDRESSES)
                .doesNotContain(SourceCapability.LIVE_SEARCH, SourceCapability.NAVIGATION);
        PageMetadataBatch first = source.pageMetadata("notizen", null, 2);
        PageMetadataBatch second = source.pageMetadata("notizen", first.nextCursor(), 2);

        assertThat(first.pages()).extracting(PageMetadata::title).containsExactly("Start", "Betrieb");
        assertThat(second.pages()).extracting(PageMetadata::title).containsExactly("Kessel");
        assertThat(second.nextCursor()).isNull();
        // Folgestapel lesen aus derselben Discovery; die Quelle wird je Listung einmal gefragt.
        assertThat(notes.calls()).containsExactly("discover:[start]");
        PageMetadata kessel = second.pages().getFirst();
        assertThat(kessel.pageId()).isEqualTo(encoded(notes, "kessel"));
        assertThat(kessel.resourceId()).isEqualTo(notes.idOf("kessel").value());
        assertThat(kessel.parentPageId()).isEqualTo(encoded(notes, "betrieb"));
        assertThat(kessel.partitionKey()).isEqualTo("notizen");
        assertThat(kessel.version()).isEqualTo("1");
    }

    @Test
    void singlePagesAndTheirTextComeFromLoad() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));
        String betrieb = encoded(notes, "betrieb");

        assertThat(source.pageMetadata(betrieb).title()).isEqualTo("Betrieb");
        StoredPage page = source.storedPage(betrieb);

        assertThat(page.metadata().pageId()).isEqualTo(betrieb);
        assertThat(page.metadata().partitionKey()).isEqualTo("notizen");
        assertThat(new String(page.payload(), StandardCharsets.UTF_8)).isEqualTo("Nachtlauf um zwei");
        assertThat(page.mediaType()).isEqualTo("text/markdown");
        assertThat(page.nativeFormat()).isEqualTo(KnowledgeDocumentTextProjector.NATIVE_FORMAT);
        assertThat(notes.calls()).containsExactly("load:betrieb", "load:betrieb");
        assertThatThrownBy(() -> source.storedPage(encoded(notes, "fehlt")))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> source.pageMetadata("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void aRedirectIsNotFoundUnderTheRequestedPage() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourcePort redirecting = new WithoutSearch(notes) {
            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                return notes.load(notes.idOf("betrieb"));
            }
        };
        KnowledgeSourceHarvestSource source = discovering(redirecting);

        assertThatThrownBy(() -> source.storedPage(encoded(notes, "start")))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThat(source.storedPage(encoded(notes, "betrieb")).metadata().title()).isEqualTo("Betrieb");
    }

    @Test
    void foreignCursorsAndPartitionsAreRejectedAndAChangeDuringTheListingStopsIt() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThatThrownBy(() -> source.pageMetadata("notizen", "c1.abc", 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> source.pageMetadata("DEV", null, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));

        String cursor = source.pageMetadata("notizen", null, 2).nextCursor();
        notes.update("kessel", "Kessel neu");
        source.pageMetadata("notizen", null, 2);

        assertThatThrownBy(() -> source.pageMetadata("notizen", cursor, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void failuresOfTheseSourcesNameOnlyTheirKind() {
        InMemoryKnowledgeSource notes = notes().failWith("betrieb", KnowledgeSourceException.Kind.UNAVAILABLE);
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThatThrownBy(() -> source.storedPage(encoded(notes, "betrieb")))
                .isInstanceOfSatisfying(SourceAccessException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("betrieb");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void titlesAreFoundInTheDiscoveryAndAnOwnSearchKeepsTheSamePageIds() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource plain = discovering(new WithoutSearch(notes));
        KnowledgeSourceHarvestSource searchable = discovering(notes);

        assertThat(plain.findByTitle("kes", SearchSpaces.all(), 10)).extracting(HarvestSource.PageNode::pageId)
                .containsExactly(encoded(notes, "kessel"));
        assertThat(plain.findByTitle("kes", new SearchSpaces(Set.of("DEV"), Set.of()), 10)).isEmpty();
        assertThat(searchable.capabilities()).contains(SourceCapability.LIVE_SEARCH);
        assertThat(searchable.searchPages("Nachtlauf", 10)).extracting(SourceSearchMatch::pageId)
                .containsExactly(encoded(notes, "betrieb"));
    }

    @Test
    void resourceIdsAndBookmarksOfTheSourceAreItsAddresses() {
        KnowledgeSourcePort wiki = new KnowledgeSourcePort() {
            @Override
            public SourceAddress sourceId() {
                return SourceAddress.of("wiki", "wiki.intern/w");
            }

            @Override
            public List<KnowledgeResource> discover(SourceScope scope) {
                return List.of();
            }

            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.NOT_FOUND, "fehlt");
            }

            @Override
            public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
                return List.of();
            }
        };
        KnowledgeSourceHarvestSource source = discovering(wiki);
        String start = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "wiki:wiki.intern/w/Start".getBytes(StandardCharsets.UTF_8));

        assertThat(source.resolveAddress("wiki:wiki.intern/w/Start")).contains(start);
        assertThat(source.resolveAddress("wiki://wiki.intern/w/Start")).contains(start);
        assertThat(source.resolveAddress("wiki:anderes.intern/w/Start")).isEmpty();
        assertThat(source.resolveAddress("confluence:wiki.intern/w/Start")).isEmpty();
    }

    @Test
    void withoutScopeASourceWithoutPartitionsStaysUnsupported() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("notizen", "notes", "Notizen",
                new WithoutSearch(notes()));

        assertThat(source.partitions()).isEmpty();
        assertThatThrownBy(() -> source.pageMetadata("notizen", null, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    /** Start → Betrieb → Kessel, dazu eine Seite außerhalb des Umfangs; Fake-Quelle aus den Fixtures der Kopie. */
    private static InMemoryKnowledgeSource notes() {
        return new InMemoryKnowledgeSource("notes://notes.intern", "notes")
                .add("start", "Start", "Willkommen", "betrieb")
                .add("betrieb", "Betrieb", "Nachtlauf um zwei")
                .addChild("betrieb", "kessel", "Kessel", "Kessel prüfen")
                .add("verwaist", "Verwaist", "außerhalb");
    }

    private static KnowledgeSourceHarvestSource discovering(KnowledgeSourcePort port) {
        return new KnowledgeSourceHarvestSource("notizen", "notes", "Notizen", port, null,
                SourceScope.builder().startPoints("start").maxDepth(3).build());
    }

    private static String encoded(InMemoryKnowledgeSource notes, String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(notes.idOf(key).value()
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Nur {@code discover} und {@code load}: ohne Suche, ohne Partitionen. */
    private static class WithoutSearch implements KnowledgeSourcePort {

        private final InMemoryKnowledgeSource notes;

        WithoutSearch(InMemoryKnowledgeSource notes) {
            this.notes = notes;
        }

        @Override
        public SourceAddress sourceId() {
            return notes.sourceId();
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
            return notes.discover(scope);
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.load(resourceId);
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.discoverLinks(resourceId);
        }
    }

    private static PageMetadata only(Listing port) {
        return new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port)
                .pageMetadata("DEV", null, 10).pages().get(0);
    }

    /** Anhänge, deren Download immer an der Grenze scheitert; merkt sich die Grenze. */
    private static final class Files implements KnowledgeSourcePort, PageViewSource {

        int maxBytes;

        @Override
        public boolean isAttachment(KnowledgeResourceId id) {
            return true;
        }

        @Override
        public SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
                throws KnowledgeSourceException {
            this.maxBytes = maxBytes;
            throw new ResponseTooLargeException("zu groß", maxBytes);
        }

        @Override
        public PageView pageView(KnowledgeResourceId page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] loadViewImage(String src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void theSpaceSelectionGoesIntoTheQueryWithoutListingAllOtherSpaces() {
        Searchable port = new Searchable(List.of(), new KnowledgeSourceException(KnowledgeSourceException.Kind
                .ACCESS_DENIED, "Spaces"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port);

        // Die Spaceliste wird dafür nicht gebraucht: auch wenn sie nicht lesbar ist, läuft die Suche.
        source.searchPages("x", 10, new SearchSpaces(Set.of("A"), Set.of("B")));

        assertThat(port.last.includedPartitions()).containsExactly("A");
        assertThat(port.last.excludedPartitions()).containsExactly("B");
    }

    /** Sucht ohne Treffer und merkt sich die letzte Anfrage. */
    private static final class Searchable implements SearchableKnowledgeSource {

        private final List<String> partitions;
        private final KnowledgeSourceException failure;
        private SourceQuery last;

        Searchable(List<String> partitions, KnowledgeSourceException failure) {
            this.partitions = partitions;
            this.failure = failure;
        }

        @Override
        public List<SourceSearchHit> search(SourceQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public List<SourcePartition> partitions() throws KnowledgeSourceException {
            if (failure != null) {
                throw failure;
            }
            return partitions.stream().map(key -> new SourcePartition(key, key)).toList();
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Liefert genau eine Seite mit den angegebenen Labels. */
    private static final class Listing implements KnowledgeSourcePort, PartitionListingSource {

        private final String labels;
        private final KnowledgeSourceException failure;

        Listing(String labels) {
            this.labels = labels;
            this.failure = null;
        }

        Listing(KnowledgeSourceException failure) {
            this.labels = "";
            this.failure = failure;
        }

        @Override
        public PartitionResources resources(String partitionKey, String cursor, int limit)
                throws KnowledgeSourceException {
            if (failure != null) {
                throw failure;
            }
            KnowledgeResource page = KnowledgeResource.builder(
                            KnowledgeResourceId.of("confluence", "wiki.example.org/page/101"),
                            SourceAddress.of("confluence-dc"))
                    .title("Schnittstellen")
                    .revision(KnowledgeRevision.of(Instant.parse("2026-10-15T10:30:00Z"), "3"))
                    .parentId(KnowledgeResourceId.of("confluence", "wiki.example.org/page/100"))
                    .scope("DEV")
                    .metadata(KnowledgeMetadata.of(Map.of("confluence.labels", labels)))
                    .build();
            return new PartitionResources(List.of(page), "");
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }
}
