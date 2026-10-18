package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/** Der übernommene Word-Exporter: Inhaltsverzeichnis, Überschriften nach Tiefe, Tabellen, Abbildungen, Speicher. */
class DocxExportWriterTest {

    private static ExportDocumentNode page(String title, String text, ExportDocumentNode... children) {
        return new ExportDocumentNode(title, text, Arrays.asList(children));
    }

    /** Wie ExportWritersTest im Enterprise AI Client: Konzept → (Einleitung, Bausteine → (Kern, Rand), Betrieb). */
    private static ExportDocumentNode tree() {
        return page("Konzept", "# Zweck\n\nDieses Konzept beschreibt **alles**.",
                page("1. Einleitung", "Text der Einleitung mit [Link](https://example.org/a)."),
                page("2. Bausteine", "## Übersicht\n\n- eins\n- zwei\n\n| A | B |\n|---|---|\n| 1 | 2 |",
                        page("2.1 Kern", "```\ncode → zeile\n```"),
                        page("2.2 Rand", "> Zitat")),
                page("3. Betrieb", "Ende."));
    }

    private static XWPFDocument write(DocxExportWriter writer, ExportDocumentNode root) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(root, out);
        return new XWPFDocument(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void wordUsesHeadingStylesByDepthAndATableOfContentsField() throws Exception {
        try (XWPFDocument docx = write(new DocxExportWriter(), tree())) {
            boolean toc = false;
            List<String> headings = new ArrayList<>();
            for (XWPFParagraph paragraph : docx.getParagraphs()) {
                if (!paragraph.getCTP().getFldSimpleList().isEmpty()) {
                    toc |= paragraph.getCTP().getFldSimpleArray(0).getInstr().startsWith("TOC ");
                }
                if (paragraph.getStyle() != null && paragraph.getStyle().startsWith("Heading")) {
                    headings.add(paragraph.getStyle() + ":" + paragraph.getText());
                }
            }
            assertThat(toc).isTrue();
            assertThat(headings).containsExactly("Heading1:Konzept", "Heading2:Zweck", "Heading2:1. Einleitung",
                    "Heading2:2. Bausteine", "Heading4:Übersicht", "Heading3:2.1 Kern", "Heading3:2.2 Rand",
                    "Heading2:3. Betrieb");
            XWPFTable table = docx.getTables().get(0);
            assertThat(table.getRow(0).isRepeatHeader()).isTrue();
            assertThat(table.getRow(1).getCell(1).getText()).isEqualTo("2");
        }
    }

    @Test
    void aSingleChapterHasNoTableOfContents() throws Exception {
        try (XWPFDocument docx = write(new DocxExportWriter(), page("Allein", "Text"))) {
            assertThat(docx.getParagraphs()).allSatisfy(p -> assertThat(p.getCTP().getFldSimpleList()).isEmpty());
        }
    }

    @Test
    void embedsKnownFiguresAndMarksMissingOnes() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        ExportDocumentNode chapter = new ExportDocumentNode("Seite", null,
                "![Plan](attachment:plan.png)\n\n![Fehlt](attachment:fehlt.png)\n\n![Fremd](https://example.org/x.png)",
                List.of(ExportImage.loaded("attachment:plan.png", "Plan", png.toByteArray()),
                        ExportImage.failed("attachment:fehlt.png", "Fehlt", "keine Berechtigung")), List.of());
        ExportGraphics graphics = ExportGraphics.standard();
        try (XWPFDocument docx = write(new DocxExportWriter(graphics), chapter)) {
            assertThat(docx.getAllPictures()).hasSize(1);
            assertThat(docx.getParagraphs()).anySatisfy(p ->
                    assertThat(p.getText()).isEqualTo("[Abbildung nicht verfügbar: Fehlt]"));
            assertThat(docx.getParagraphs()).anySatisfy(p ->
                    assertThat(p.getText()).isEqualTo("[Abbildung nicht verfügbar: Fremd]"));
        }
        assertThat(graphics.problems()).containsExactly("Abbildung „Fehlt“: keine Berechtigung",
                "Abbildung „Fremd“: nicht abgerufen (Adresse außerhalb der Quelle)");
    }

    @Test
    void mermaidWithoutRendererStaysCodeAndIsReported() throws Exception {
        ExportGraphics graphics = ExportGraphics.standard();
        try (XWPFDocument docx = write(new DocxExportWriter(graphics),
                page("Seite", "```mermaid\ngraph TD; A-->B\n```"))) {
            assertThat(docx.getParagraphs()).anySatisfy(p -> assertThat(p.getText()).isEqualTo("graph TD; A-->B"));
        }
        assertThat(graphics.problems()).containsExactly("Mermaid-Diagramm: nicht darstellbar, als Quelltext übernommen");
    }

    @Test
    void anchorsBecomeBookmarksAndInternalLinksJumpThere() throws Exception {
        ExportDocumentNode root = new ExportDocumentNode("Space", null, "", null, List.of(
                new ExportDocumentNode("A", "page-1", "Siehe [B](#page-2).", null, List.of()),
                new ExportDocumentNode("B", "page-2", "Ziel", null, List.of())));
        try (XWPFDocument docx = write(new DocxExportWriter(), root)) {
            List<String> bookmarks = new ArrayList<>();
            List<String> anchors = new ArrayList<>();
            for (XWPFParagraph paragraph : docx.getParagraphs()) {
                paragraph.getCTP().getBookmarkStartList().forEach(b -> bookmarks.add(b.getName()));
                paragraph.getCTP().getHyperlinkList().forEach(h -> anchors.add(h.getAnchor()));
            }
            assertThat(bookmarks).containsExactly("page_1", "page_2");
            assertThat(anchors).containsExactly("page_2");
        }
    }

    @Test
    void sameChaptersGiveTheSameBytes() throws Exception {
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        new DocxExportWriter().write(tree(), first);
        new DocxExportWriter().write(tree(), second);
        assertThat(first.toByteArray()).isEqualTo(second.toByteArray());
    }

    /**
     * Tausende Seiten: der Inhalt eines Kapitels verlässt das POI-Dokument nach dem Kapitel. Ohne das hielt POI etwa
     * 170 KiB je Seite dieser Größe im Speicher (gemessen: 2000 Seiten, 327 MiB), mit etwa 4 KiB.
     */
    @Test
    void thousandsOfChaptersKeepTheDocumentSmallInMemory() throws Exception {
        String text = "# Abschnitt\n\n" + "Ein Absatz mit **fettem** Text und [Link](https://example.org/). ".repeat(20)
                + "\n\n| A | B | C |\n|---|---|---|\n" + "| 1 | 2 | 3 |\n".repeat(10) + "\n- eins\n- zwei\n";
        int pages = 3000;
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long[] size = {0};
        try (DocxExportWriter.Chapters chapters = new DocxExportWriter().open("Space", true)) {
            chapters.chapter("Space", null, "", List.of(), 1);
            for (int i = 0; i < pages; i++) {
                chapters.chapter("Seite " + i, "page-" + i, text, List.of(), 2 + i % 3);
            }
            System.gc();
            long retained = runtime.totalMemory() - runtime.freeMemory() - before;
            assertThat(retained).isLessThan(96L << 20);
            chapters.finish(new OutputStream() {
                @Override
                public void write(int b) {
                    size[0]++;
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    size[0] += len;
                }
            });
        }
        assertThat(size[0]).isGreaterThan(100_000);
    }
}
