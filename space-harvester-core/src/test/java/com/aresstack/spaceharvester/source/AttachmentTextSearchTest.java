package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Anhangstexte im Volltextindex (#183), Ende zu Ende ohne Netz: Ernte mit Tika, {@link LucenePageTextIndex},
 * {@link KeywordSearch}. Ein Treffer im Anhang ist ein Treffer der Seite mit Trefferart {@code ATTACHMENT} und gilt mit
 * deren Rechten.
 */
class AttachmentTextSearchTest {

    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final AccessContext APP = AccessContext.technicalClient("app");

    @TempDir
    Path directory;

    private final Map<String, byte[]> files = new HashMap<>();
    /** Seiten der Quelle; fehlt eine, ist sie gelöscht. */
    private final Map<String, String> pages = new HashMap<>(Map.of("101", "Planung", "103", "Gesperrt"));
    private LucenePageTextIndex index;

    @AfterEach
    void close() {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void aWordInAnAttachmentFindsItsPageAsAnAttachmentHit() {
        files.put("att1", DocumentFixtures.pdf("Wartungsfenster Samstag Rechenzentrum"));
        Assets assets = assets();
        assets.harvest(list("101", attachment("att1", "betrieb.pdf", DocumentFixtures.PDF)));

        KeywordSearch.Results results = search(allow()).search(APP, KeywordSearch.Query.of("Wartungsfenster"), null,
                10);

        assertThat(results.hits()).hasSize(1);
        KeywordSearch.Hit hit = results.hits().getFirst();
        assertThat(hit.pageId()).isEqualTo("101");
        assertThat(hit.title()).isEqualTo("Planung");
        assertThat(hit.spaceKey()).isEqualTo("DEV");
        assertThat(hit.matchedIn()).isEqualTo(KeywordSearch.MatchedIn.ATTACHMENT);
        assertThat(hit.attachment()).isEqualTo(new KeywordSearch.AttachmentMatch("att1", "betrieb.pdf"));
        assertThat(hit.snippet()).contains("Wartungsfenster");
    }

    @Test
    void thePageAndItsAttachmentAreOneHitAndTheFileNameIsSearchable() {
        files.put("att1", DocumentFixtures.docx("Abnahme der Brücke"));
        Assets assets = assets();
        index.put(new PageTextIndex.Entry(new PageKey("wiki", "101"), "DEV", "Planung", "Die Abnahme ist geplant.",
                "sha256:p"));
        assets.harvest(list("101", attachment("att1", "Schleuse Protokoll.docx", DocumentFixtures.DOCX)));

        KeywordSearch search = search(allow());
        List<KeywordSearch.Hit> both = search.search(APP, KeywordSearch.Query.of("Abnahme"), null, 10).hits();
        List<KeywordSearch.Hit> byName = search.search(APP, KeywordSearch.Query.of("schleuse"), null, 10).hits();
        List<KeywordSearch.Hit> page = search.search(APP, KeywordSearch.Query.of("geplant"), null, 10).hits();

        assertThat(both).extracting(KeywordSearch.Hit::pageId).containsExactly("101");
        assertThat(byName).extracting(KeywordSearch.Hit::matchedIn).containsExactly(KeywordSearch.MatchedIn.ATTACHMENT);
        assertThat(page).extracting(KeywordSearch.Hit::matchedIn).containsExactly(KeywordSearch.MatchedIn.PAGE);
        assertThat(page.getFirst().attachment()).isNull();
    }

    @Test
    void anAttachmentOfAPageWithoutContentGrantStaysInvisible() {
        files.put("att1", DocumentFixtures.pdf("Wartungsfenster Samstag"));
        files.put("att8", DocumentFixtures.pdf("Wartungsfenster geheim Tresor"));
        Assets assets = assets();
        assets.harvest(list("101", attachment("att1", "betrieb.pdf", DocumentFixtures.PDF)));
        assets.harvest(list("103", attachment("att8", "geheim.pdf", DocumentFixtures.PDF)));
        AccessGuard noContentFor103 = new AccessGuard(deny("103", AccessKind.CONTENT));
        AccessGuard noMetadataFor103 = new AccessGuard(deny("103", AccessKind.METADATA));

        for (AccessGuard guard : List.of(noContentFor103, noMetadataFor103)) {
            KeywordSearch.Results all = search(guard).search(APP, KeywordSearch.Query.of("Wartungsfenster"), null,
                    10);
            KeywordSearch.Results secret = search(guard).search(APP, KeywordSearch.Query.of("Tresor"), null, 10);

            assertThat(all.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("101");
            assertThat(all.hits()).allSatisfy(hit -> assertThat(hit.attachment().title()).isNotEqualTo("geheim.pdf"));
            assertThat(secret.hits()).isEmpty();
            assertThat(secret.found()).isEmpty();
            assertThat(secret.loading()).isZero();
            // Kein Cursor, der auf einen verborgenen Treffer warten ließe.
            assertThat(secret.nextCursor()).isNull();
        }
        // Gelöschte Seite: ihr Anhang findet sie nicht mehr.
        pages.remove("101");
        assertThat(search(allow()).search(APP, KeywordSearch.Query.of("Samstag"), null, 10).hits()).isEmpty();
    }

    @Test
    void anAttachmentRemovedFromThePageLeavesTheIndexAndThePageItsAttachments() {
        files.put("att1", DocumentFixtures.pdf("Wartungsfenster Samstag"));
        files.put("att2", DocumentFixtures.pdf("Notfallplan Sonntag"));
        Assets assets = assets();
        assets.harvest(list("101", attachment("att1", "betrieb.pdf", DocumentFixtures.PDF),
                attachment("att2", "notfall.pdf", DocumentFixtures.PDF)));

        assets.harvest(list("101", attachment("att2", "notfall.pdf", DocumentFixtures.PDF)));

        assertThat(index.search("Wartungsfenster", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
        assertThat(index.search("Notfallplan", Set.of("wiki"), SearchSpaces.all(), 10))
                .extracting(PageTextIndex.Match::attachmentId).containsExactly("att2");
        index.remove(new PageKey("wiki", "101"));
        assertThat(index.search("Notfallplan", Set.of("wiki"), SearchSpaces.all(), 10)).isEmpty();
    }

    @Test
    void harvestingAgainDoesNotRewriteAnUnchangedEntryAndAReopenedIndexKeepsIt() {
        files.put("att1", DocumentFixtures.pdf("Wartungsfenster Samstag"));
        Assets assets = assets();
        PageAttachmentList list = list("101", attachment("att1", "betrieb.pdf", DocumentFixtures.PDF));
        assets.harvest(list);
        Optional<String> state = index.attachmentInputSha256(new PageKey("wiki", "101"), "att1");

        assets.harvest(list);
        index.close();
        index = LucenePageTextIndex.open(directory.resolve("text"));

        assertThat(state).isPresent();
        assertThat(index.attachmentInputSha256(new PageKey("wiki", "101"), "att1")).isEqualTo(state);
        assertThat(index.search("Samstag", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
    }

    @Test
    void anImageAttachmentIsIndexedWithItsStoredDescriptionAndNeverDescribedByIndexing() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
        files.put("att5", png);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ImageDescriber vision = new ImageDescriber() {
            @Override
            public String id() {
                return "vision";
            }

            @Override
            public String modelVersion() {
                return "v1";
            }

            @Override
            public String describe(byte[] image, String mediaType) {
                calls.incrementAndGet();
                return "Glasfaser zwischen zwei Rechenzentren";
            }
        };
        PageAttachmentList list = list("101", attachment("att5", "Netzplan Foto.png", "image/png"));
        Assets assets = assets(vision);
        KeywordSearch search = search(allow());

        // Ernten ruft das Modell nie auf: ohne Beschreibung steht nur der Dateiname im Index. Keine OCR.
        assets.harvest(list);
        assertThat(calls).hasValue(0);
        assertThat(search.search(APP, KeywordSearch.Query.of("Netzplan"), null, 10).hits())
                .extracting(KeywordSearch.Hit::matchedIn).containsExactly(KeywordSearch.MatchedIn.ATTACHMENT);
        assertThat(search.search(APP, KeywordSearch.Query.of("Glasfaser"), null, 10).hits()).isEmpty();

        // Beschrieben wird nur auf Abruf (#371); die Beschreibung kommt danach in den Index.
        assets.describe(list, "att5");
        List<KeywordSearch.Hit> described = search.search(APP, KeywordSearch.Query.of("Glasfaser"), null, 10)
                .hits();
        assertThat(calls).hasValue(1);
        assertThat(described).extracting(KeywordSearch.Hit::pageId).containsExactly("101");
        assertThat(described.getFirst().attachment()).isEqualTo(new KeywordSearch.AttachmentMatch("att5",
                "Netzplan Foto.png"));
        assertThat(described.getFirst().snippet()).contains("Glasfaser");

        // Ein neu aufgebauter Index holt die Beschreibung aus der Ablage von #371, ohne das Modell zu fragen.
        index.close();
        index = LucenePageTextIndex.open(directory.resolve("text-neu"));
        assets(vision).harvest(list);
        assertThat(calls).hasValue(1);
        assertThat(index.search("Glasfaser", Set.of("wiki"), SearchSpaces.all(), 10))
                .extracting(PageTextIndex.Match::attachmentId).containsExactly("att5");
    }

    private KeywordSearch search(AccessGuard guard) {
        return new KeywordSearch(catalog(), guard, index, null);
    }

    private static AccessPolicy allow() {
        return (context, scope, kind) -> AccessDecision.ALLOW;
    }

    private static AccessPolicy deny(String pageId, AccessKind denied) {
        return (context, scope, kind) -> pageId.equals(scope.pageId()) && (kind == denied
                || denied == AccessKind.METADATA) ? AccessDecision.DENY : AccessDecision.ALLOW;
    }

    private KeywordSearch search(AccessPolicy policy) {
        return search(new AccessGuard(policy));
    }

    private Assets assets() {
        return assets(null);
    }

    private Assets assets(ImageDescriber describer) {
        if (index == null) {
            index = LucenePageTextIndex.open(directory.resolve("text"));
        }
        return new Assets(catalog(), new FileSystemAssetStore(directory.resolve("assets")), 1_000_000,
                Clock.fixed(NOW, ZoneOffset.UTC), describer, new TikaDocumentText(), index);
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

    private static PageAttachmentList list(String pageId, PageAttachment... attachments) {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments(pageId, List.of(attachments), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }

    /** Ohne eigene Suche: Treffer kommen nur aus dem Volltextindex, Kopfdaten live. */
    private final class FakeSource implements HarvestSource {

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            return new AttachmentFile(attachment, files.get(attachment.attachmentId()), NOW);
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
            String title = pages.get(pageId);
            if (title == null) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "weg", null);
            }
            return new PageMetadata(pageId, pageId, title, "DEV", null, "1", null, null, new TreeMap<>());
        }
    }
}
