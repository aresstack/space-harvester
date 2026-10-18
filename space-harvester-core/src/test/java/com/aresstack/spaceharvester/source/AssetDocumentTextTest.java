package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anhänge im AssetStore mit Typerkennung am Inhalt und Text über Tika (#183), Ende zu Ende ohne Netz. */
class AssetDocumentTextTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    @TempDir
    Path directory;

    private final Map<String, byte[]> files = new HashMap<>();

    @Test
    void aPdfDeclaredAsImageIsStoredAsPdfAndGetsItsText() {
        files.put("att1", DocumentFixtures.pdf("Wartungsfenster Samstag"));
        Assets assets = assets();
        PageAttachmentList list = list(attachment("att1", "scan.png", "image/png"));

        AssetHarvest harvest = assets.harvest(list);

        assertThat(harvest.entries().getFirst().status()).isEqualTo(AssetHarvest.Status.STORED);
        assertThat(harvest.entries().getFirst().asset().mediaType()).isEqualTo(DocumentFixtures.PDF);
        assertThat(harvest.entries().getFirst().asset().kind()).isEqualTo(AssetKind.OTHER);
        assertThat(assets.text(list, "att1").text()).contains("Wartungsfenster Samstag");
    }

    @Test
    void aWordDocumentWithoutMediaTypeIsRecognisedAndReadOnce() {
        files.put("att1", DocumentFixtures.docx("Abnahmeprotokoll Brücke"));
        Assets assets = assets();
        PageAttachmentList list = list(attachment("att1", "protokoll", "application/octet-stream"));

        AssetHarvest harvest = assets.harvest(list);

        assertThat(harvest.entries().getFirst().asset().mediaType()).isEqualTo(DocumentFixtures.DOCX);
        // Der Text lag schon nach dem Ablegen bereit; ein neuer Dienst liest ihn aus der Ablage.
        Assets restarted = assets();
        assertThat(restarted.text(list, "att1").text()).contains("Abnahmeprotokoll Brücke");
        assertThat(restarted.text(list, "att1").createdAt()).isEqualTo(NOW);
    }

    @Test
    void aPdfNamedLikeADiagramIsStoredAsADocument() {
        files.put("att1", DocumentFixtures.pdf("Netzplan Rechenzentrum"));
        Assets assets = assets();
        PageAttachmentList list = list(attachment("att1", "report.drawio", "application/vnd.jgraph.mxfile"));

        AssetHarvest harvest = assets.harvest(list);

        assertThat(harvest.entries().getFirst().asset().mediaType()).isEqualTo(DocumentFixtures.PDF);
        assertThat(harvest.entries().getFirst().asset().kind()).isEqualTo(AssetKind.OTHER);
        assertThat(assets.text(list, "att1").text()).contains("Netzplan Rechenzentrum");
    }

    @Test
    void harvestingAgainFillsInTheTextOfDocumentsStoredWithoutExtraction() {
        files.put("att1", DocumentFixtures.docx("Altbestand Schleuse"));
        PageAttachmentList list = list(attachment("att1", "alt.docx", DocumentFixtures.DOCX));
        AssetHarvest first = assets(null).harvest(list);
        files.remove("att1");
        TikaDocumentText extraction = new TikaDocumentText();

        AssetHarvest again = assets(extraction).harvest(list);

        assertThat(again.entries().getFirst().status()).isEqualTo(AssetHarvest.Status.REUSED);
        assertThat(new FileSystemAssetStore(directory)
                .text(first.entries().getFirst().asset().sha256(), extraction.id(), extraction.version()))
                .hasValueSatisfying(text -> assertThat(text.text()).contains("Altbestand Schleuse"));
    }

    private Assets assets() {
        return assets(new TikaDocumentText());
    }

    private Assets assets(TikaDocumentText extraction) {
        HarvestSource source = new FakeSource();
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
        return new Assets(catalog, new FileSystemAssetStore(directory), 1_000_000, Clock.fixed(NOW, ZoneOffset.UTC),
                null, extraction);
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
            throw new UnsupportedOperationException();
        }
    }
}
