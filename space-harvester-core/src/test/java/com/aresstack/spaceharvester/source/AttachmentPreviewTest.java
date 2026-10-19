package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.sources.AttachmentPreview;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vorschau und PDF-Konvertierung von Anhängen über die Dokumentkonvertierung der Confluence-Kopie (#183): derselbe
 * Pfad wie im Enterprise AI Client, abgelegt als eigene Asset-Variante je Anhang, Version und Art.
 */
class AttachmentPreviewTest {

    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final String CONVERSION = "/wiki/rest/documentConversion/latest/conversion/";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @TempDir
    Path directory;

    private final FakeConfluence confluence = new FakeConfluence();
    private final Map<String, ConfluenceHttpResponse> conversions = new HashMap<>();
    private final List<String> conversionRequests = new ArrayList<>();

    AttachmentPreviewTest() {
        confluence.space("DEV", "Entwicklung");
        confluence.page("100", "Start", "DEV", "<p>S</p>");
        confluence.homepage("DEV", "100");
        confluence.page("101", "Planung", "DEV", "<p>P</p>");
        confluence.child("100", "101");
        confluence.attachment("att7", "101", "Planung.xlsx", DocumentFixtures.XLSX, "xlsx");
    }

    @Test
    void theConfluenceSourceOffersPreviewsAndAnotherSourceDoesNot() {
        assertThat(source().capabilities()).contains(SourceCapability.ATTACHMENT_PREVIEWS);
        assertThat(new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource("x"))
                .capabilities()).doesNotContain(SourceCapability.ATTACHMENT_PREVIEWS);
    }

    @Test
    void theConvertedDocumentIsStoredOncePerVersionAsItsOwnAsset() {
        byte[] pdf = DocumentFixtures.pdf("Planung Quartal");
        conversions.put(CONVERSION + "convert/7/1", new ConfluenceHttpResponse(200, "application/pdf", pdf));
        HarvestSource source = source();
        Assets assets = assets(source, 1_000_000);
        PageAttachmentList list = list(source);

        Assets.AssetContent preview = assets.preview(list, "att7", null, true);

        assertThat(conversionRequests).containsExactly(CONVERSION + "convert/7/1");
        assertThat(preview.content()).isEqualTo(pdf);
        assertThat(preview.asset().mediaType()).isEqualTo(DocumentFixtures.PDF);
        assertThat(preview.asset().kind()).isEqualTo(AssetKind.OTHER);
        assertThat(preview.asset().attachmentId()).isEqualTo("preview-document-att7");
        assertThat(preview.asset().version()).isEqualTo(Assets.PREVIEW_RENDITION + ":1");
        assertThat(preview.asset().title()).isEqualTo("Planung.xlsx.pdf");
        // Das Original selbst ist dafür nicht geladen worden und bleibt ein eigener Eintrag.
        assertThat(confluence.requestCount("/download/")).isZero();

        Assets.AssetContent again = assets(source, 1_000_000).preview(list, "att7", AttachmentPreview.DOCUMENT, false);

        assertThat(again.asset().sha256()).isEqualTo(preview.asset().sha256());
        assertThat(conversionRequests).hasSize(1);
    }

    @Test
    void withoutAConvertedDocumentTheNextKindIsTriedAndReadersDoNotFillTheStore() {
        conversions.put(CONVERSION + "poster/7/1", new ConfluenceHttpResponse(200, "image/png", PNG));
        HarvestSource source = source();
        Assets assets = assets(source, 1_000_000);
        PageAttachmentList list = list(source);

        Assets.AssetContent preview = assets.preview(list, "att7", null, false);

        assertThat(conversionRequests).containsExactly(CONVERSION + "convert/7/1", CONVERSION + "poster/7/1");
        assertThat(preview.asset().mediaType()).isEqualTo("image/png");
        assertThat(preview.asset().kind()).isEqualTo(AssetKind.IMAGE);
        assertThat(new FileSystemAssetStore(directory).find("wiki", "preview-poster-att7",
                Assets.previewVersion("1"))).isEmpty();
    }

    @Test
    void aLoginPageIsNoPreviewAndWithoutAnyPreviewTheAnswerIsNotFound() {
        conversions.put(CONVERSION + "poster/7/1", new ConfluenceHttpResponse(200, "text/html",
                "<html>Anmelden</html>".getBytes(StandardCharsets.UTF_8)));
        HarvestSource source = source();
        PageAttachmentList list = list(source);

        assertThatThrownBy(() -> assets(source, 1_000_000).preview(list, "att7", AttachmentPreview.POSTER, true))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.INVALID_RESPONSE));
        assertThatThrownBy(() -> assets(source, 1_000_000).preview(list, "att7", null, true))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
    }

    @Test
    void stillBeingGeneratedIsUnavailableAndAPreviewOverTheLimitIsTooLarge() {
        conversions.put(CONVERSION + "convert/7/1", new ConfluenceHttpResponse(202, "application/json",
                new byte[0]));
        conversions.put(CONVERSION + "thumbnail/7/1", new ConfluenceHttpResponse(200, "image/png", PNG));
        HarvestSource source = source();
        PageAttachmentList list = list(source);

        assertThatThrownBy(() -> assets(source, 1_000_000).preview(list, "att7", AttachmentPreview.DOCUMENT, true))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> assets(source, 4).preview(list, "att7", AttachmentPreview.THUMBNAIL, true))
                .isInstanceOf(AssetTooLargeException.class);
    }

    @Test
    void onlyAttachmentsOfThePageHavePreviews() {
        HarvestSource source = source();
        PageAttachmentList list = list(source);

        assertThatThrownBy(() -> assets(source, 1_000_000).preview(list, "att8", null, true))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThat(conversionRequests).isEmpty();
    }

    /** Die Fake-Confluence der Kopie, dazu Antworten der Dokumentkonvertierung, die sie selbst nicht kennt. */
    private HarvestSource source() {
        ConfluenceKnowledgeSource port = new ConfluenceKnowledgeSource(SourceAddress.of("wiki"),
                ConfluenceConfig.builder(FakeConfluence.BASE).build(), (uri, headers, maxBytes) -> {
                    String path = uri.getRawPath();
                    if (path.startsWith(CONVERSION)) {
                        conversionRequests.add(path);
                        ConfluenceHttpResponse known = conversions.get(path);
                        return known != null ? known : new ConfluenceHttpResponse(404, "application/json",
                                "{\"statusCode\":404}".getBytes(StandardCharsets.UTF_8));
                    }
                    return confluence.get(uri, headers, maxBytes);
                }, null);
        return new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port);
    }

    private static PageAttachmentList list(HarvestSource source) {
        return new PageAttachmentList("wiki", "DEV", source.attachments("101"), NOW);
    }

    private Assets assets(HarvestSource source, long maxBytes) {
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
        return new Assets(catalog, new FileSystemAssetStore(directory), maxBytes, Clock.fixed(NOW, ZoneOffset.UTC),
                null, new TikaDocumentText());
    }
}
