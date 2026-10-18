package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Typerkennung am Inhalt und Textextraktion über die Kopie aus dem Enterprise AI Client (#183). */
class TikaDocumentTextTest {

    private static final String TEXT = "Quartalsbericht Nordlicht";

    private final TikaDocumentText documents = new TikaDocumentText();

    @ParameterizedTest
    @CsvSource({
            "bericht.docx, docx",
            "bericht.xlsx, xlsx",
            "bericht.pptx, pptx",
            "bericht.pdf, pdf",
            "bericht.odt, odt",
            "bericht.rtf, rtf"
    })
    void readsTheTextOfOfficePdfOpenDocumentAndRtf(String title, String format) {
        Fixture fixture = Fixture.of(format);

        DocumentText.Extracted extracted = documents.extract(title, fixture.mediaType, fixture.bytes.apply(TEXT));

        assertThat(extracted.text()).contains(TEXT);
        assertThat(extracted.truncated()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"docx", "xlsx", "pptx", "pdf", "odt"})
    void recognisesTheFormatByItsContentAlone(String format) {
        Fixture fixture = Fixture.of(format);

        assertThat(documents.detect(fixture.bytes.apply(TEXT))).contains(fixture.mediaType);
    }

    @Test
    void recognisesImagesAndLeavesUnknownBytesUnknown() {
        assertThat(documents.detect(DocumentFixtures.pngSignature())).contains("image/png");
        assertThat(documents.detect(new byte[] {0, 1, 2, 3, (byte) 0xFE, (byte) 0xFF, 7, 9})).isEmpty();
        assertThat(documents.detect(new byte[0])).isEmpty();
    }

    @Test
    void filesAboveTheSizeLimitAreNotParsed() {
        TikaDocumentText small = new TikaDocumentText(100, 1000, Duration.ofSeconds(30));
        byte[] pdf = DocumentFixtures.pdf(TEXT);

        assertThatThrownBy(() -> small.extract("gross.pdf", DocumentFixtures.PDF, pdf))
                .isInstanceOf(DocumentTextException.class)
                .extracting(e -> ((DocumentTextException) e).reason())
                .isEqualTo(DocumentTextException.Reason.TOO_LARGE);
    }

    @Test
    void longTextsAreCutAtTheCharacterLimit() {
        TikaDocumentText shortTexts = new TikaDocumentText(TikaDocumentText.DEFAULT_MAX_BYTES, 10,
                Duration.ofSeconds(30));

        DocumentText.Extracted extracted = shortTexts.extract("lang.docx", DocumentFixtures.DOCX,
                DocumentFixtures.docx(TEXT));

        assertThat(extracted.text()).hasSize(10).isEqualTo(TEXT.substring(0, 10));
        assertThat(extracted.truncated()).isTrue();
    }

    @Test
    void anExtractionOverTheTimeLimitIsAbandoned() {
        // Schon das Laden von Tika dauert länger als eine Nanosekunde.
        TikaDocumentText hasty = new TikaDocumentText(TikaDocumentText.DEFAULT_MAX_BYTES, 1000, Duration.ofNanos(1));

        assertThatThrownBy(() -> hasty.extract("bericht.pdf", DocumentFixtures.PDF, DocumentFixtures.pdf(TEXT)))
                .isInstanceOf(DocumentTextException.class)
                .extracting(e -> ((DocumentTextException) e).reason())
                .isEqualTo(DocumentTextException.Reason.TIMEOUT);
    }

    @Test
    void brokenDocumentsAndImagesHaveNoTextAndTheMessageNamesNoFile() {
        byte[] broken = "%PDF-1.7\nkaputt".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> documents.extract("geheim-vertrag.pdf", DocumentFixtures.PDF, broken))
                .isInstanceOf(DocumentTextException.class)
                .satisfies(e -> {
                    assertThat(((DocumentTextException) e).reason()).isEqualTo(DocumentTextException.Reason.FAILED);
                    assertThat(e.getMessage()).doesNotContain("geheim");
                });
        assertThatThrownBy(() -> documents.extract("foto.png", "image/png", DocumentFixtures.pngSignature()))
                .isInstanceOf(DocumentTextException.class)
                .satisfies(e -> {
                    assertThat(((DocumentTextException) e).reason())
                            .isEqualTo(DocumentTextException.Reason.UNSUPPORTED);
                    assertThat(e.getMessage()).doesNotContain("foto");
                });
    }

    private record Fixture(String mediaType, Function<String, byte[]> bytes) {

        static Fixture of(String format) {
            return switch (format) {
                case "docx" -> new Fixture(DocumentFixtures.DOCX, DocumentFixtures::docx);
                case "xlsx" -> new Fixture(DocumentFixtures.XLSX, DocumentFixtures::xlsx);
                case "pptx" -> new Fixture(DocumentFixtures.PPTX, DocumentFixtures::pptx);
                case "pdf" -> new Fixture(DocumentFixtures.PDF, DocumentFixtures::pdf);
                case "odt" -> new Fixture(DocumentFixtures.ODT, DocumentFixtures::odt);
                case "rtf" -> new Fixture(DocumentFixtures.RTF, DocumentFixtures::rtf);
                default -> throw new IllegalArgumentException(format);
            };
        }
    }
}
