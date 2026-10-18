package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Lesetext lokaler Dateien: Textdateien direkt, Dokumente über die Extraktion (#180, #183). */
class LocalFilesTextProjectorTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    private final LocalFilesTextProjector projector = new LocalFilesTextProjector(new TikaDocumentText());

    @Test
    void textFilesStayAsTheyWere() {
        Optional<PageText> text = projector.project(file("notiz.txt", "text/plain",
                "Erste Zeile\r\nZweite".getBytes(StandardCharsets.UTF_8)));

        assertThat(text).map(PageText::schema).contains(LocalFilesTextProjector.SCHEMA);
        assertThat(text).map(PageText::text).contains("Erste Zeile\nZweite");
    }

    @Test
    void documentsAreReadByTheExtraction() {
        Optional<PageText> pdf = projector.project(file("plan.pdf", DocumentFixtures.PDF,
                DocumentFixtures.pdf("Baustellenplan Ostflügel")));
        Optional<PageText> pptx = projector.project(file("folien.pptx", DocumentFixtures.PPTX,
                DocumentFixtures.pptx("Strategie Folie")));

        assertThat(pdf).map(PageText::schema).contains(LocalFilesTextProjector.DOCUMENT_SCHEMA);
        assertThat(pdf.orElseThrow().text()).contains("Baustellenplan Ostflügel");
        assertThat(pptx.orElseThrow().text()).contains("Strategie Folie");
    }

    @Test
    void imagesAndBrokenDocumentsHaveNoText() {
        assertThat(projector.project(file("bild.png", "image/png", DocumentFixtures.pngSignature()))).isEmpty();
        assertThat(projector.project(file("kaputt.pdf", DocumentFixtures.PDF,
                "%PDF-1.7 kaputt".getBytes(StandardCharsets.US_ASCII)))).isEmpty();
    }

    @Test
    void aTimeoutFailsTheProjectionSoTheIndexKeepsItsEntry() {
        DocumentText slow = new DocumentText() {
            @Override
            public String id() {
                return "langsam";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public Optional<String> detect(byte[] content) {
                return Optional.empty();
            }

            @Override
            public Extracted extract(String title, String mediaType, byte[] content) {
                throw new DocumentTextException(DocumentTextException.Reason.TIMEOUT, "zu langsam", null);
            }
        };

        assertThatThrownBy(() -> new LocalFilesTextProjector(slow).project(file("plan.pdf", DocumentFixtures.PDF,
                DocumentFixtures.pdf("x")))).isInstanceOf(DocumentTextException.class);
    }

    private static PageSnapshot file(String name, String mediaType, byte[] bytes) {
        PageMetadata metadata = new PageMetadata("id-" + name, "file:/daten/" + name, name, "docs", null, "1", NOW,
                null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("docs", metadata, new NativePayload(bytes, mediaType, "file"), NOW);
    }
}
