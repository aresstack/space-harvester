package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PdfConverterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Input(List<ConvertiblePage> pages) implements ConversionInput {

        @Override
        public String sourceId() {
            return "wiki";
        }

        @Override
        public String spaceKey() {
            return "DEV";
        }

        @Override
        public PageTree tree() {
            return new PageTree(List.of(), List.of(), pages.size(), 0);
        }

        @Override
        public List<PageHeader> headers() {
            return pages.stream().map(ConvertiblePage::header).toList();
        }

        @Override
        public void forEachPage(Consumer<ConvertiblePage> action) {
            pages.forEach(action);
        }
    }

    private static ConvertiblePage storagePage(String pageId, String parentId, int depth, String storage) {
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode().put("id", pageId).set("body",
                JSON.createObjectNode().set("storage", JSON.createObjectNode().put("value", storage))));
        PageSnapshot snapshot = page("wiki", "DEV", pageId, parentId, payload);
        return new ConvertiblePage(snapshot.header(), snapshot, depth);
    }

    private static final List<ConvertiblePage> PAGES = List.of(
            storagePage("100", null, 0, "<h1>Teil &amp; Ganzes</h1><p>Grüße, ein <strong>fetter</strong> Text, "
                    + "siehe <ac:link><ri:page ri:content-title=\"Seite 101\" /></ac:link> und "
                    + "<a href=\"https://example.org/x\">extern</a>. Chinesisch: 中文.</p>"
                    + "<ol><li>eins</li><li>zwei<ul><li>zwei-a</li></ul></li></ol>"
                    + "<table><tbody><tr><th>Name</th><th>Wert</th></tr><tr><td>a</td><td>"
                    + "sehr langer Zelleninhalt ".repeat(20) + "</td></tr></tbody></table>"
                    + "<ac:structured-macro ac:name=\"warning\"><ac:rich-text-body><p>Achtung</p>"
                    + "</ac:rich-text-body></ac:structured-macro>"
                    + "<ac:image><ri:attachment ri:filename=\"bild.png\" /></ac:image>"
                    + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">architektur"
                    + "</ac:parameter></ac:structured-macro>"
                    + "<p>" + "Wortohneleerzeichen".repeat(30) + "</p>"),
            storagePage("101", "100", 1, "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                    + "<![CDATA[line 1\n\tline <2> " + "x".repeat(300) + "]]></ac:plain-text-body>"
                    + "</ac:structured-macro>"),
            storagePage("200", null, 0, "<p>zweite Wurzel</p>"));

    private static byte[] convert(List<ConvertiblePage> pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfConverter().convert(new Input(pages), out);
        return out.toByteArray();
    }

    @Test
    void writesPagesInOrderWithTextReferencesAndHints() throws Exception {
        try (PDDocument pdf = Loader.loadPDF(convert(PAGES))) {
            String text = new PDFTextStripper().getText(pdf);

            assertThat(text).containsSubsequence("Seite 100", "Teil & Ganzes", "Grüße, ein fetter Text",
                    "1.", "eins", "zwei-a", "Name", "Wert", "Warnung", "Achtung", "[Bild: bild.png",
                    "draw.io) „architektur“", "Seite 101", "line 1", "Seite 200", "zweite Wurzel");
            // Standardschrift ohne chinesische Zeichen: sichtbar ersetzt, nicht verschluckt.
            assertThat(text).contains("Chinesisch: ??.");
            // Jede Wurzel beginnt auf einer neuen PDF-Seite.
            assertThat(new PDFTextStripper() {
                {
                    setStartPage(pdf.getNumberOfPages());
                }
            }.getText(pdf)).contains("zweite Wurzel").doesNotContain("Teil & Ganzes");
        }
    }

    @Test
    void outlineFollowsThePageTreeAndLinksJump() throws Exception {
        try (PDDocument pdf = Loader.loadPDF(convert(PAGES))) {
            PDOutlineItem first = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertThat(first.getTitle()).isEqualTo("Seite 100");
            assertThat(first.getFirstChild().getTitle()).isEqualTo("Seite 101");
            assertThat(first.getNextSibling().getTitle()).isEqualTo("Seite 200");

            List<PDAnnotationLink> links = new ArrayList<>();
            for (var page : pdf.getPages()) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    links.add((PDAnnotationLink) annotation);
                }
            }
            assertThat(links).anySatisfy(link -> assertThat(link.getAction()).isInstanceOf(PDActionGoTo.class));
            assertThat(links).map(PDAnnotationLink::getAction).filteredOn(PDActionURI.class::isInstance)
                    .extracting(action -> ((PDActionURI) action).getURI()).contains("https://example.org/x");
        }
    }

    @Test
    void embeddedFontShowsCharactersOutsideTheStandardFonts() throws Exception {
        Path font = Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        assumeTrue(Files.isRegularFile(font), "DejaVu Sans nicht installiert");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new PdfConverter(font).convert(new Input(List.of(storagePage("100", null, 0, "<p>Ωmega – Łódź</p>"))), out);

        try (PDDocument pdf = Loader.loadPDF(out.toByteArray())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Ωmega – Łódź");
        }
    }

    @Test
    void sameInputGivesTheSameBytes() throws Exception {
        assertThat(convert(PAGES)).isEqualTo(convert(PAGES));
    }

    @Test
    void manyPagesFlowOverManyPdfPages() throws Exception {
        List<ConvertiblePage> pages = IntStream.range(0, 200)
                .mapToObj(i -> storagePage(String.valueOf(1000 + i), i == 0 ? null : "1000", i == 0 ? 0 : 1,
                        "<p>" + "Absatz mit Inhalt. ".repeat(40) + "</p>"))
                .toList();

        try (PDDocument pdf = Loader.loadPDF(convert(pages))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(20);
            assertThat(new PDFTextStripper().getText(pdf)).contains("Seite 1199");
        }
    }
}
