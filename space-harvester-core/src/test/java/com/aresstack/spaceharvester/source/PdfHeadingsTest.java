package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Überschriften eines PDFs nach der Schriftgröße; fett allein ist keine Überschrift (erfundene Texte). */
class PdfHeadingsTest {

    @TempDir
    Path directory;

    @Test
    void largerTextBecomesHeadingsByLevelAndBoldBodyTextDoesNot() throws IOException {
        Path pdf = directory.resolve("handbuch.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 1; page <= 2; page++) {
                PDPage p = new PDPage();
                document.addPage(p);
                try (PDPageContentStream content = new PDPageContentStream(document, p)) {
                    float y = 720;
                    if (page == 1) {
                        y = line(content, Standard14Fonts.FontName.HELVETICA, 20, "Betriebshandbuch", y);
                    }
                    y = line(content, Standard14Fonts.FontName.HELVETICA, 16, "Kopfzeile Beispielamt", y);
                    y = line(content, Standard14Fonts.FontName.HELVETICA, 14,
                            page == 1 ? "Rufbereitschaft" : "Notfallplan", y);
                    for (int i = 0; i < 6; i++) {
                        y = line(content, Standard14Fonts.FontName.HELVETICA, 11,
                                "Fließtext der Seite " + page + " in Zeile " + i + " mit einigen Wörtern mehr.", y);
                    }
                    y -= 20;
                    y = line(content, Standard14Fonts.FontName.HELVETICA_BOLD, 11, "Wichtig fett gesetzt", y);
                    y -= 20;
                    line(content, Standard14Fonts.FontName.HELVETICA, 11, "Weiterer Fließtext nach dem fetten Satz.", y);
                }
            }
            document.save(pdf.toFile());
        }

        PdfHeadings headings = PdfHeadings.read(pdf, null);

        assertThat(headings.level("Betriebshandbuch")).contains(1);
        assertThat(headings.level("Rufbereitschaft")).contains(2);
        assertThat(headings.level("Notfallplan")).contains(2);
        assertThat(headings.level("Kopfzeile Beispielamt")).isEmpty();
        assertThat(headings.level("Wichtig fett gesetzt")).isEmpty();

        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        new TikaDocumentStructure().extract(pdf, "handbuch.pdf", "application/pdf", ExtractionLimits.DEFAULTS, sink);
        List<String> found = sink.blocks.stream().filter(Block.Heading.class::isInstance).map(Block.Heading.class::cast)
                .map(h -> h.level() + " " + h.text()).toList();
        assertThat(found).contains("1 Betriebshandbuch", "2 Rufbereitschaft", "2 Notfallplan")
                .noneMatch(h -> h.contains("fett") || h.contains("Kopfzeile"));
    }

    @Test
    void aWrappedHeadingIsOneHeadingAndANumberIsNone() {
        PdfHeadings headings = PdfHeadings.of(List.of(
                new PdfHeadings.Line("Grundsätze der", 14f, 1),
                new PdfHeadings.Line("Datensicherung", 14f, 1),
                new PdfHeadings.Line("Text mit vielen Zeichen im Fließtext der Seite", 11f, 1),
                new PdfHeadings.Line("Noch mehr Fließtext mit vielen Zeichen", 11f, 1),
                new PdfHeadings.Line("12", 14f, 1)));

        assertThat(headings.level("Grundsätze der\nDatensicherung")).contains(1);
        assertThat(headings.level("12")).isEmpty();
        assertThat(PdfHeadings.of(List.of()).isEmpty()).isTrue();
    }

    private static float line(PDPageContentStream content, Standard14Fonts.FontName font, float size, String text,
                              float y) throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(font), size);
        content.newLineAtOffset(72, y);
        content.showText(text);
        content.endText();
        return y - size * 1.6f;
    }
}
