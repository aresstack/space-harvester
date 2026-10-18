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
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
