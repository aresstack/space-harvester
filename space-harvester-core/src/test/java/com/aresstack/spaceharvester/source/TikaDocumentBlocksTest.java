package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Link;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.testcorpus.LargeFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Blöcke, Fundstellen und Filter aus Tikas XHTML (ADR 0026, Punkte 3 und 4, Abnahme 2). */
class TikaDocumentBlocksTest {

    @TempDir
    Path directory;

    private final TikaDocumentStructure structure = new TikaDocumentStructure();

    @Test
    void pdfBlocksCarryThePhysicalPageAlsoAfterABlankPage() throws IOException {
        Path pdf = pdf(directory.resolve("seiten.pdf"), "MARKE-S1", "MARKE-S2", null, "MARKE-S4");
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument result = structure.extract(pdf, "seiten.pdf", "application/pdf", ExtractionLimits.DEFAULTS,
                sink);

        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        assertThat(pageOf(sink, "MARKE-S1")).contains(1);
        assertThat(pageOf(sink, "MARKE-S2")).contains(2);
        assertThat(pageOf(sink, "MARKE-S4")).as("leere Seite 3 zählt mit").contains(4);
    }

    @Test
    void aTruncatedPdfKeepsTheCorrectPagesForWhatWasRead() throws IOException {
        Path pdf = pdf(directory.resolve("gekuerzt.pdf"), "MARKE-S1 " + "a".repeat(50), "MARKE-S2 " + "b".repeat(50),
                "MARKE-S3 " + "c".repeat(50));
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        ExtractionLimits limits = new ExtractionLimits(0, 70, 100, 100L * 1024 * 1024, 100, 1_000_000, 10, 10_000,
                Duration.ofSeconds(30), Duration.ofMinutes(5), 0, 2, Map.of());

        StructuredDocument result = structure.extract(pdf, "gekuerzt.pdf", "application/pdf", limits, sink);

        assertThat(result.outcome().truncated()).isTrue();
        assertThat(pageOf(sink, "MARKE-S1")).contains(1);
        assertThat(pageOf(sink, "MARKE-S2")).contains(2);
        assertThat(sink.text()).doesNotContain("MARKE-S3");
    }

    @Test
    void excelCellsBecomeTablesUnderTheirSheetWithTheirCellReference() throws IOException {
        Path xlsx = directory.resolve("blaetter.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            var kosten = workbook.createSheet("Kosten");
            kosten.createRow(2).createCell(1).setCellValue("B3-Wert");
            kosten.createRow(16).createCell(3).setCellValue("D17-Wert");
            workbook.createSheet("Personen").createRow(0).createCell(0).setCellValue("Leitstelle");
            workbook.write(out);
        }
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        structure.extract(xlsx, "blaetter.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ExtractionLimits.DEFAULTS, sink);

        List<Block.Table> tables = sink.blocks.stream().filter(Block.Table.class::isInstance)
                .map(Block.Table.class::cast).toList();
        assertThat(tables).hasSize(2);
        assertThat(tables.get(0).location().flatMap(Location::sheet)).contains("Kosten");
        assertThat(tables.get(1).location().flatMap(Location::sheet)).contains("Personen");
        assertThat(tables.get(0).cells()).extracting(Block.Cell::text).contains("B3-Wert", "D17-Wert");
        assertThat(tables).flatExtracting(Block.Table::cells).allSatisfy(cell -> {
            assertThat(cell.cellRef()).as("Zellbezug aus dem Original (TikaXlsxCellRefsTest)").isPresent();
            assertThat(cell.text()).doesNotStartWith("\t");
        });
        assertThat(sink.blocks).filteredOn(Block.Heading.class::isInstance)
                .allSatisfy(block -> assertThat(block.location().flatMap(Location::sheet)).isPresent());
    }

    @Test
    void htmlGivesHeadingsListsTablesCodeAndLinks() throws IOException {
        Path html = Files.writeString(directory.resolve("seite.html"), """
                <html><head><title>Seitentitel</title></head><body>
                <h1>Betriebshandbuch</h1>
                <p>Der Nachtlauf startet, siehe <a href="https://example.org/plan">Ablaufplan</a>.<a name="x"></a></p>
                <h2>Schritte</h2>
                <ol><li>Sicherung prüfen</li><li>Job starten<ul><li>bei Fehler anrufen</li></ul></li></ol>
                <table><tr><th>Job</th><th>Dauer</th></tr><tr><td>DATENH01</td><td>12 min</td></tr></table>
                <pre>//DATENH01 JOB (ACCT)</pre>
                </body></html>""");
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        StructuredDocument result = structure.extract(html, "seite.html", "text/html", ExtractionLimits.DEFAULTS,
                sink);

        assertThat(result.head().title()).contains("Seitentitel");
        assertThat(sink.text()).as("Titel im Kopf, nicht als Block").doesNotContain("Seitentitel");
        assertThat(sink.blocks).filteredOn(Block.Heading.class::isInstance).extracting(
                block -> ((Block.Heading) block).level() + ":" + ((Block.Heading) block).text())
                .containsExactly("1:Betriebshandbuch", "2:Schritte");
        Block.Paragraph paragraph = (Block.Paragraph) sink.blocks.stream()
                .filter(block -> block instanceof Block.Paragraph p && p.text().startsWith("Der Nachtlauf"))
                .findFirst().orElseThrow();
        assertThat(paragraph.links()).as("Anker ohne Ziel fällt weg")
                .containsExactly(new Link("https://example.org/plan", Optional.of("Ablaufplan")));
        assertThat(sink.blocks).filteredOn(Block.ListItem.class::isInstance).extracting(block -> {
            Block.ListItem item = (Block.ListItem) block;
            return item.ordered() + ":" + item.level() + ":" + item.text();
        }).containsExactly("true:1:Sicherung prüfen", "true:1:Job starten", "false:2:bei Fehler anrufen");
        Block.Table table = (Block.Table) sink.blocks.stream().filter(Block.Table.class::isInstance).findFirst()
                .orElseThrow();
        assertThat(table.cells()).extracting(c -> c.row() + "/" + c.column() + "/" + c.header() + "/" + c.text())
                .containsExactly("0/0/true/Job", "0/1/true/Dauer", "1/0/false/DATENH01", "1/1/false/12 min");
        assertThat(sink.blocks).filteredOn(Block.Code.class::isInstance).extracting(b -> ((Block.Code) b).text())
                .containsExactly("//DATENH01 JOB (ACCT)");
    }

    @Test
    void aLargeTableIsSplitAtRowsAndCountsRowsOn() throws IOException {
        StringBuilder rows = new StringBuilder("<html><body><table>");
        for (int i = 0; i < 200; i++) {
            rows.append("<tr><td>Zeile ").append(i).append("</td><td>").append("x".repeat(20)).append("</td></tr>");
        }
        Path html = Files.writeString(directory.resolve("gross.html"), rows.append("</table></body></html>"));
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        new TikaDocumentStructure(1, 500).extract(html, "gross.html", "text/html", ExtractionLimits.DEFAULTS, sink);

        List<Block.Table> tables = sink.blocks.stream().map(Block.Table.class::cast).toList();
        assertThat(tables).hasSizeGreaterThan(1);
        assertThat(tables.get(0).continued()).isFalse();
        assertThat(tables.subList(1, tables.size())).allSatisfy(table -> assertThat(table.continued()).isTrue());
        assertThat(tables).allSatisfy(table -> assertThat(table.chars()).isLessThanOrEqualTo(500));
        List<Block.Cell> cells = tables.stream().flatMap(table -> table.cells().stream()).toList();
        assertThat(cells).hasSize(400);
        assertThat(cells.get(398).row()).isEqualTo(199);
        assertThat(cells.get(398).text()).isEqualTo("Zeile 199");
    }

    @Test
    void captionComesFirstCellBlocksStaySeparateAndLinksStayWithTheirCell() throws IOException {
        Path html = Files.writeString(directory.resolve("zellen.html"), """
                <html><body><table><caption>Einheit kg</caption>
                <tr><td><p>eins</p>zwei</td><td>AAAA</td></tr>
                <tr><td><a href="https://example.org/b">BBB</a>CCCCCCC</td></tr>
                </table></body></html>""");
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        new TikaDocumentStructure(1, 12).extract(html, "zellen.html", "text/html", ExtractionLimits.DEFAULTS, sink);

        assertThat(sink.blocks.get(0)).isInstanceOfSatisfying(Block.Paragraph.class,
                paragraph -> assertThat(paragraph.text()).isEqualTo("Einheit kg"));
        List<Block.Table> tables = sink.blocks.stream().filter(Block.Table.class::isInstance)
                .map(Block.Table.class::cast).toList();
        assertThat(tables.get(0).cells().get(0).text()).matches("eins\\s+zwei");
        assertThat(tables).allSatisfy(table -> {
            boolean hasLabel = table.cells().stream().anyMatch(cell -> cell.text().contains("BBB"));
            assertThat(table.links().isEmpty()).isEqualTo(!hasLabel);
        });
    }

    @Test
    void manyEmptyCellsAreDrainedInBoundedPieces() throws IOException {
        StringBuilder rows = new StringBuilder("<html><body><table><tr>");
        rows.append("<td></td>".repeat(25_000)).append("<td>Ende</td></tr></table></body></html>");
        Path html = Files.writeString(directory.resolve("leer.html"), rows);
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        structure.extract(html, "leer.html", "text/html", ExtractionLimits.DEFAULTS, sink);

        Block.Table last = (Block.Table) sink.blocks.get(sink.blocks.size() - 1);
        assertThat(last.cells()).hasSizeLessThanOrEqualTo(10_000);
        assertThat(last.cells().get(last.cells().size() - 1).text()).isEqualTo("Ende");
        assertThat(last.cells().get(last.cells().size() - 1).column()).isEqualTo(25_000);
    }

    @Test
    void namesOfPackageEntriesAreNoBlocks() {
        Path docx = LargeFiles.docx(directory.resolve("bilder.docx"), 3, 2);
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        structure.extract(docx, "bilder.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ExtractionLimits.DEFAULTS,
                sink);

        assertThat(sink.text()).contains("Abschnitt 1", LargeFiles.paragraphMark(1)).doesNotContain(".png");
    }

    @Test
    void pptSlidesAreNumberedInSlideOrderHiddenSlidesIncluded() throws Exception {
        Path ppt = ppt(directory.resolve("folien.ppt"), 2, "FOLIE-EINS", "FOLIE-ZWEI", "FOLIE-DREI");
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        structure.extract(ppt, "folien.ppt", "application/vnd.ms-powerpoint", ExtractionLimits.DEFAULTS, sink);

        assertThat(slideOf(sink, "FOLIE-EINS")).hasValue(1);
        assertThat(slideOf(sink, "FOLIE-ZWEI")).as("ausgeblendet, zählt mit").hasValue(2);
        assertThat(slideOf(sink, "FOLIE-DREI")).hasValue(3);
    }

    @Test
    void wordListsStayParagraphsWithoutAnUnambiguousMarker() throws Exception {
        Path docx = directory.resolve("liste.docx");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum definition =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();
            definition.setAbstractNumId(java.math.BigInteger.ZERO);
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl level = definition.addNewLvl();
            level.setIlvl(java.math.BigInteger.ZERO);
            level.addNewNumFmt().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.BULLET);
            level.addNewLvlText().setVal("•");
            org.apache.poi.xwpf.usermodel.XWPFNumbering numbering = document.createNumbering();
            java.math.BigInteger list = numbering.addNum(numbering.addAbstractNum(
                    new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(definition)));
            document.createParagraph().createRun().setText("Einleitung");
            for (String item : new String[] {"Punkt A", "Punkt B"}) {
                org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = document.createParagraph();
                paragraph.setNumID(list);
                paragraph.createRun().setText(item);
            }
            try (java.io.OutputStream out = Files.newOutputStream(docx)) {
                document.write(out);
            }
        }
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        structure.extract(docx, "liste.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ExtractionLimits.DEFAULTS,
                sink);

        // Tika gibt Word-Listenpunkte als Absatz mit vorangestelltem Zeichen aus, ohne Listenelement oder Klasse.
        assertThat(sink.text()).contains("Punkt A", "Punkt B");
        assertThat(sink.blocks).noneMatch(Block.ListItem.class::isInstance);
    }

    /**
     * Eine .ppt mit einer Marke je Folie; {@code hidden} ist die ausgeblendete Folie ab 1. HSLF liegt nur zur Laufzeit
     * (über die Parser der Kopie) auf dem Klassenpfad, deshalb per Reflexion statt einer Zusatzabhängigkeit.
     */
    private static Path ppt(Path target, int hidden, String... slides) throws Exception {
        Class<?> type = Class.forName("org.apache.poi.hslf.usermodel.HSLFSlideShow");
        try (AutoCloseable show = (AutoCloseable) type.getConstructor().newInstance()) {
            for (int i = 0; i < slides.length; i++) {
                Object slide = type.getMethod("createSlide").invoke(show);
                Object box = slide.getClass().getMethod("createTextBox").invoke(slide);
                box.getClass().getMethod("setText", String.class).invoke(box, slides[i]);
                box.getClass().getMethod("setAnchor", java.awt.geom.Rectangle2D.class)
                        .invoke(box, new java.awt.Rectangle(50, 50, 300, 50));
                if (i + 1 == hidden) {
                    slide.getClass().getMethod("setHidden", boolean.class).invoke(slide, true);
                }
            }
            try (java.io.OutputStream out = Files.newOutputStream(target)) {
                type.getMethod("write", java.io.OutputStream.class).invoke(show, out);
            }
        }
        return target;
    }

    private static Optional<Integer> slideOf(TikaDocumentStructureTest.Recording sink, String mark) {
        return sink.blocks.stream().filter(block -> DocumentExtractions.plain(block).contains(mark))
                .findFirst()
                .flatMap(Block::location).flatMap(Location::slide);
    }

    /** Je Seite eine Marke; {@code null} ergibt eine leere Seite. */
    private static Path pdf(Path target, String... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                if (text != null) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(font, 10);
                        content.newLineAtOffset(40, 800);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            document.save(target.toFile());
        }
        return target;
    }

    private static Optional<Integer> pageOf(TikaDocumentStructureTest.Recording sink, String mark) {
        return sink.blocks.stream().filter(block -> DocumentExtractions.plain(block).contains(mark))
                .findFirst()
                .flatMap(Block::location).flatMap(Location::page);
    }
}
