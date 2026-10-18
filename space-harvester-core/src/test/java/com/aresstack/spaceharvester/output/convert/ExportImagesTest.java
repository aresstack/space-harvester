package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Reference;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Status;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bilder und Diagramme in Word, PDF und Markdown (Export-Parität): eingebettet wird nur, was der Datenbestand abgelegt
 * hat; alles andere steht sichtbar als „Abbildung nicht verfügbar“ mit Grund. Mermaid wird aus dem Diagrammtext
 * gezeichnet. Kein Converter fragt die Quelle an (die Testeingabe kennt keine).
 */
class ExportImagesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = png();
    private static final String EXTERNAL = "https://images.example.org/logo.png";
    private static final String UNKNOWN = "https://tracker.example/p.gif";

    private static final String STORAGE = "<p>Vorher</p>"
            + "<ac:image ac:alt=\"Systembild\"><ri:attachment ri:filename=\"system.png\" /></ac:image>"
            + "<ac:image ac:alt=\"Geheim\"><ri:attachment ri:filename=\"geheim.png\" /></ac:image>"
            + "<p><ac:image ac:alt=\"Logo\"><ri:url ri:value=\"" + EXTERNAL + "\" /></ac:image></p>"
            + "<p><ac:image ac:alt=\"Zähler\"><ri:url ri:value=\"" + UNKNOWN + "\" /></ac:image></p>"
            + "<ac:structured-macro ac:name=\"mermaid\"><ac:plain-text-body><![CDATA[graph TD\n  A[Start] --> B[Ende]]]>"
            + "</ac:plain-text-body></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">ablauf</ac:parameter>"
            + "</ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">kaputt</ac:parameter>"
            + "</ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"inc-drawio\"><ac:parameter ac:name=\"diagramName\">fremd</ac:parameter>"
            + "<ac:parameter ac:name=\"pageId\">200</ac:parameter></ac:structured-macro>"
            + "<p>Nachher</p>";

    private static final List<Entry> ENTRIES = List.of(
            entry(Reference.attachment("att1", "system.png"), "system.png", Status.STORED, "sha256:aa", null),
            entry(Reference.attachment("att2", "geheim.png"), "geheim.png", Status.FORBIDDEN, null, null),
            entry(Reference.viewImage(EXTERNAL), "logo.png", Status.STORED, "sha256:bb", null),
            diagram("100", "att3", "ablauf", "application/vnd.jgraph.mxfile", "sha256:cc"),
            diagram("100", "att4", "kaputt.drawio", "application/octet-stream", "sha256:dd"),
            diagram("100", "att5", "kaputt.png", "image/png", "sha256:ee"),
            diagram("200", "att6", "fremd", "application/vnd.jgraph.mxfile", "sha256:cc"));
    private static final byte[] MXFILE = ("<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>").getBytes(StandardCharsets.UTF_8);

    private record Input(List<ConvertiblePage> pages, ExportAssets assets) implements ConversionInput {

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

    /** Die abgelegten Bytes; zählt, wie oft gelesen wird. */
    private static final class Stored implements ExportAssets {
        final List<String> read = new ArrayList<>();

        @Override
        public Optional<ExportAssetManifest> manifest() {
            return Optional.of(new ExportAssetManifest(false, ENTRIES));
        }

        @Override
        public Optional<byte[]> content(Entry entry) {
            read.add(entry.sha256());
            if (!entry.stored()) {
                return Optional.empty();
            }
            return switch (entry.sha256()) {
                case "sha256:cc" -> Optional.of(MXFILE);
                case "sha256:dd" -> Optional.of("kein Diagramm".getBytes(StandardCharsets.UTF_8));
                default -> Optional.of(PNG);
            };
        }
    }

    private static Entry entry(Reference reference, String title, Status status, String sha256, String reason) {
        return new Entry("100", reference, reference.attachmentId(), "1", title, "image/png", PNG.length, sha256,
                status, reason);
    }

    private static Entry diagram(String pageId, String attachmentId, String file, String mediaType, String sha256) {
        return new Entry(pageId, Reference.attachment(attachmentId, file), attachmentId, "1", file, mediaType, 10,
                sha256, Status.STORED, null);
    }

    private static Input input(ExportAssets assets) {
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode().put("id", "100").set("body",
                JSON.createObjectNode().set("storage", JSON.createObjectNode().put("value", STORAGE))));
        PageSnapshot snapshot = page("wiki", "DEV", "100", null, payload);
        return new Input(List.of(new ConvertiblePage(snapshot.header(), snapshot, 0)), assets);
    }

    private static byte[] convert(ExportConverter converter, ExportAssets assets) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.convert(input(assets), out);
        return out.toByteArray();
    }

    private static byte[] png() {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x44, 0x72, 0xC4));
        graphics.fillRect(0, 0, 40, 20);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Test
    void wordEmbedsStoredImagesAndTheMermaidDiagramAndNamesTheMissingOnes() throws Exception {
        Stored assets = new Stored();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(convert(new DocxConverter(), assets)))) {
            String text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(docx).getText();

            // Anhang, Bild der Lese-Ansicht und gezeichnetes Mermaid-Diagramm
            assertThat(docx.getAllPictures()).hasSizeGreaterThanOrEqualTo(2);
            // dazu draw.io: gezeichnet, Vorschau für das nicht darstellbare, eingebunden von einer anderen Seite
            assertThat(docx.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                    .mapToInt(run -> run.getEmbeddedPictures().size()).sum()).isEqualTo(6);
            assertThat(text).contains("Systembild", "Abbildung nicht verfügbar: Geheim",
                    "Abbildung nicht verfügbar: Zähler").doesNotContain("attachment:");
        }
        assertThat(assets.read).doesNotContainNull().doesNotContain("sha256:ff");
    }

    @Test
    void pdfEmbedsStoredImagesAndTheMermaidDiagramAndNamesTheMissingOnes() throws Exception {
        try (PDDocument pdf = Loader.loadPDF(convert(new PdfConverter(), new Stored()))) {
            int pictures = 0;
            for (PDPage page : pdf.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                        pictures++;
                    }
                }
            }
            assertThat(pictures).isEqualTo(6);
            assertThat(new PDFTextStripper().getText(pdf)).contains("Abbildung nicht verfügbar: Geheim",
                    "Abbildung nicht verfügbar: Zähler").doesNotContain("[Bild:");
        }
    }

    @Test
    void markdownEmbedsStoredImagesAsDataUrisAndKeepsTheMermaidSource() throws Exception {
        String markdown = new String(convert(new MarkdownConverter(), new Stored()), StandardCharsets.UTF_8);

        assertThat(markdown).contains("![Systembild](data:image/png;base64,", "![Logo](data:image/png;base64,")
                .contains("```mermaid\ngraph TD", "![Mermaid-Diagramm](data:image/png;base64,")
                .contains("*[Abbildung nicht verfügbar: Geheim]*")
                .contains("*\\[Abbildung nicht verfügbar: Zähler\\]* ([Adresse](" + UNKNOWN + "))")
                .doesNotContain("attachment:", "drawio:", "](" + EXTERNAL + ")", "Seitenanhang");
        assertThat(markdown.split("!\\[Diagramm \\(draw.io\\)", -1)).hasSize(4);
    }

    @Test
    void withoutAssetsNothingIsEmbeddedButMermaidIsStillDrawn() throws Exception {
        String markdown = new String(convert(new MarkdownConverter(), ExportAssets.NONE), StandardCharsets.UTF_8);

        assertThat(markdown).contains("*[Abbildung nicht verfügbar: Systembild]*",
                "*\\[Abbildung nicht verfügbar: Logo\\]* ([Adresse](" + EXTERNAL + "))",
                "![Mermaid-Diagramm](data:image/png;base64,",
                "*[Abbildung nicht verfügbar: Diagramm (draw.io) „ablauf“")
                .doesNotContain("drawio:");
    }

    @Test
    void sameInputGivesTheSameBytesWithPictures() throws Exception {
        assertThat(convert(new DocxConverter(), new Stored())).isEqualTo(convert(new DocxConverter(), new Stored()));
        assertThat(convert(new PdfConverter(), new Stored())).isEqualTo(convert(new PdfConverter(), new Stored()));
    }
}
