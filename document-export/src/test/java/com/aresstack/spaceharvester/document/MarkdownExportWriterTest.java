package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * Abbildungen, deren Ziel Leerzeichen oder Klammern enthält: der Konverter schreibt sie in spitzen Klammern
 * ({@code ![Plan](<attachment:Entwicklung MoeVe .JPG>)}); die Writer müssen sie trotzdem als Bild erkennen.
 */
class MarkdownExportWriterTest {

    private static final String TEXT = "Vorher\n\n"
            + "![Entwicklung](<attachment:Entwicklungumgebung MoeVe .JPG>)\n\n"
            + "![Diagramm (draw.io) „Stakeholder MoeVe“](<drawio:Stakeholder MoeVe>)\n\n"
            + "![Klammer](<attachment:Plan (alt) Ü.png>)\n\n"
            + "![Fehlt](<attachment:Nicht da.png>)\n\n"
            + "Siehe [Datei](<attachment:Bericht 2024.pdf>).";

    private static byte[] png() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", png);
        return png.toByteArray();
    }

    private static List<ExportImage> images() throws Exception {
        byte[] png = png();
        return List.of(ExportImage.loaded("attachment:Entwicklungumgebung MoeVe .JPG", "Entwicklung", png),
                ExportImage.loaded("drawio:Stakeholder MoeVe", "Stakeholder MoeVe", png),
                ExportImage.loaded("attachment:Plan (alt) Ü.png", "Klammer", png));
    }

    @Test
    void figuresWithSpacesInTheTargetAreLinkedToTheirFiles() throws Exception {
        ExportGraphics graphics = ExportGraphics.standard();
        MarkdownExportWriter writer = new MarkdownExportWriter(graphics,
                (graphic, caption) -> "assets/" + caption.replaceAll("[^A-Za-z]", "") + ".png");

        String markdown = writer.chapter("Seite", null, TEXT, images(), 1);

        assertThat(markdown).contains("![Entwicklung](assets/Entwicklung.png)",
                        "![Diagramm (draw.io) „Stakeholder MoeVe“](assets/DiagrammdrawioStakeholderMoeVe.png)",
                        "![Klammer](assets/Klammer.png)", "*[Abbildung nicht verfügbar: Fehlt]*",
                        "[Datei](<attachment:Bericht 2024.pdf>)")
                .doesNotContain("](<attachment:Entwicklung", "](<drawio:", "](<attachment:Plan", "](<attachment:Nicht");
        assertThat(graphics.problems()).containsExactly(
                "Abbildung „Fehlt“: nicht abgerufen (Adresse außerhalb der Quelle)");
    }

    @Test
    void wordEmbedsFiguresWithSpacesInTheTarget() throws Exception {
        ExportDocumentNode chapter = new ExportDocumentNode("Seite", null, TEXT, images(), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DocxExportWriter(ExportGraphics.standard()).write(chapter, out);
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(docx.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                    .mapToInt(run -> run.getEmbeddedPictures().size()).sum()).isEqualTo(3);
            assertThat(docx.getParagraphs()).noneSatisfy(p -> assertThat(p.getText()).contains("attachment:"));
        }
    }
}
