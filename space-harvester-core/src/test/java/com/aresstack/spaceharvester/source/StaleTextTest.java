package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.PartRules;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der alte Stand bleibt lieferbar, bis das Original neu gelesen ist (ADR 0026, Punkt 11, Abnahme 8): nach einem
 * Wechsel der Extraktor-Version, nach einem gescheiterten Lesen der neuen Version und nach einem Import ohne
 * strukturierte Extraktion, jeweils als veraltet gekennzeichnet.
 */
class StaleTextTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final String NEXT_VERSION = "3.3-neu";

    @TempDir
    Path directory;

    private final Map<String, byte[]> files = new HashMap<>();
    private LucenePageTextIndex index;

    @AfterEach
    void close() {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void afterANewExtractorVersionTheOldTextStaysDeliverableAndSearchableAsStale() {
        files.put("att1", DocumentFixtures.docx("Abnahme der Schleuse"));
        String sha = Assets.sha256(files.get("att1"));
        PageAttachmentList list = list(attachment("att1", "protokoll.docx", DocumentFixtures.DOCX));
        assets(new TikaDocumentStructure()).harvest(list);
        assertThat(assets(new TikaDocumentStructure()).storedText(sha)).map(AssetText::stale).contains(false);

        Versioned next = new Versioned(false);
        Assets upgraded = assets(next);
        AssetText stored = upgraded.storedText(sha).orElseThrow();

        assertThat(stored.stale()).isTrue();
        assertThat(stored.version()).isEqualTo(TikaDocumentStructure.VERSION);
        assertThat(stored.text()).contains("Schleuse");
        assertThat(stored.outcome()).map(Outcome::status).contains(ExtractionStatus.OK);
        AssetText delivered = upgraded.text(list, "att1", false);
        assertThat(delivered.stale()).isTrue();
        assertThat(delivered.text()).contains("Schleuse");
        assertThat(next.reads).as("Abruf liest nichts neu").hasValue(0);
        assertThat(upgraded.reindex(list, "att1")).as("Indexeintrag bleibt").isFalse();
        assertThat(index.search("Schleuse", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
    }

    @Test
    void aFailedReadOfTheNewVersionLeavesTheOldTextInPlace() {
        files.put("att1", DocumentFixtures.docx("Abnahme der Schleuse"));
        String sha = Assets.sha256(files.get("att1"));
        PageAttachmentList list = list(attachment("att1", "protokoll.docx", DocumentFixtures.DOCX));
        assets(new TikaDocumentStructure()).harvest(list);

        Versioned failing = new Versioned(true);
        Assets upgraded = assets(failing);
        upgraded.harvest(list);

        assertThat(failing.reads).as("die Ernte holt den Text nach und scheitert").hasValue(1);
        AssetText stored = upgraded.storedText(sha).orElseThrow();
        assertThat(stored.stale()).isTrue();
        assertThat(stored.text()).contains("Schleuse");
        assertThat(index.search("Schleuse", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
    }

    @Test
    void anImportedTextIsStaleUntilTheOriginalIsReadAndNeverClaimsTheCurrentExtraction() {
        Assets assets = assets(new TikaDocumentStructure());
        byte[] docx = DocumentFixtures.docx("Abnahme der Schleuse");
        String sha = Assets.sha256(docx);

        assertThat(assets.adoptText(sha, "mitgebracht", "tika", TikaDocumentStructure.VERSION)).isTrue();
        AssetText imported = assets.storedText(sha).orElseThrow();
        assertThat(imported.stale()).isTrue();
        assertThat(imported.extractor()).isEqualTo(Assets.IMPORTED_EXTRACTOR);
        assertThat(imported.version()).isEqualTo(Assets.UNKNOWN_VERSION);
        assertThat(imported.text()).isEqualTo("mitgebracht");

        String other = Assets.sha256(DocumentFixtures.docx("anderer Inhalt"));
        assets.adoptText(other, "aus dem Paket", "tika", "3.3-s1");
        assertThat(assets.storedText(other)).hasValueSatisfying(text -> {
            assertThat(text.stale()).isTrue();
            assertThat(text.extractor()).isEqualTo("tika");
            assertThat(text.version()).isEqualTo("3.3-s1");
        });

        files.put("att1", docx);
        assets.harvest(list(attachment("att1", "protokoll.docx", DocumentFixtures.DOCX)));
        AssetText read = assets.storedText(sha).orElseThrow();
        assertThat(read.stale()).as("nach dem Lesen aktuell").isFalse();
        assertThat(read.version()).isEqualTo(TikaDocumentStructure.VERSION);
        assertThat(read.text()).contains("Schleuse");
    }

    private Assets assets(DocumentStructure structure) {
        if (index == null) {
            index = LucenePageTextIndex.open(directory.resolve("text"));
        }
        Assets assets = new Assets(catalog(), new FileSystemAssetStore(directory.resolve("assets")), 16_000_000,
                Clock.fixed(NOW, ZoneOffset.UTC), null, new TikaDocumentText(), index);
        assets.documentExtractions(new DocumentExtractions(structure,
                new FileSystemExtractionStore(directory.resolve("assets")), ExtractionLimits.DEFAULTS));
        return assets;
    }

    /** Tika unter einer neuen Version; zählt, wie oft gelesen wird, und scheitert auf Wunsch mit Status. */
    private static final class Versioned implements DocumentStructure {
        private final TikaDocumentStructure tika = new TikaDocumentStructure();
        private final boolean fail;
        private final AtomicInteger reads = new AtomicInteger();

        Versioned(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String id() {
            return tika.id();
        }

        @Override
        public String version() {
            return NEXT_VERSION;
        }

        @Override
        public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                          StructureSink sink) {
            reads.incrementAndGet();
            return fail ? failed() : tika.extract(original, title, mediaType, limits, sink);
        }

        @Override
        public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                          StructureSink sink, PartRules rules) {
            reads.incrementAndGet();
            return fail ? failed() : tika.extract(original, title, mediaType, limits, sink, rules);
        }

        @Override
        public Optional<InputStream> openPart(Path original, String title, String mediaType, ExtractionLimits limits,
                                              PartRules rules, PartId.Content id) {
            return tika.openPart(original, title, mediaType, limits, rules, id);
        }

        private static StructuredDocument failed() {
            return new StructuredDocument(DocumentHead.EMPTY, Outcome.of(ExtractionStatus.FAILED,
                    StatusReason.CORRUPT), List.of(), 0, 0);
        }
    }

    private SourceCatalog catalog() {
        HarvestSource source = new FakeSource();
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
    }

    private static PageAttachment attachment(String id, String title, String mediaType) {
        return new PageAttachment(id, "test:wiki/attachment/" + id, title, mediaType, null, "1", null, null);
    }

    private static PageAttachmentList list(PageAttachment... attachments) {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("101", List.of(attachments), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }

    private final class FakeSource implements HarvestSource {

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            byte[] content = files.get(attachment.attachmentId());
            if (content == null) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            return new AttachmentFile(attachment, content, NOW);
        }

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, pageId, "Planung", "DEV", null, "1", null, null, new TreeMap<>());
        }
    }
}
