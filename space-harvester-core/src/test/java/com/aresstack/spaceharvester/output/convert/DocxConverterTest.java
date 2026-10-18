package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import tools.jackson.databind.json.JsonMapper;

/** Word über den Exporter aus dem Enterprise AI Client: Inhaltsverzeichnis, Kapitelhierarchie, Word-Formate. */
class DocxConverterTest {

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
            storagePage("100", null, 0, "<h1>Teil &amp; Ganzes</h1><p>Ein <strong>fetter</strong> Text, siehe "
                    + "<ac:link><ri:page ri:content-title=\"Seite 101\" /></ac:link> und "
                    + "<a href=\"https://example.org/?a=1&amp;b=2\">extern</a>.</p>"
                    + "<ol><li>eins</li><li>zwei<ul><li>zwei-a</li></ul></li></ol><ol><li>neu</li></ol>"
                    + "<table><tbody><tr><th>Name</th><th>Wert</th></tr><tr><td>a</td><td></td></tr></tbody></table>"
                    + "<ac:structured-macro ac:name=\"warning\"><ac:rich-text-body><p>Achtung</p>"
                    + "</ac:rich-text-body></ac:structured-macro>"
                    + "<ac:image><ri:attachment ri:filename=\"bild.png\" /></ac:image>"
                    + "<p>Steuerzeichen\u0001 entfernt</p>"
                    + "<table><tbody><tr><td><p>oben</p><p>unten</p></td></tr></tbody></table>"),
            storagePage("101", "100", 1, "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                    + "<![CDATA[line 1\n  line <2>]]></ac:plain-text-body></ac:structured-macro>"),
            storagePage("102", "101", 2, "<p>Enkel</p>"),
            storagePage("200", null, 0, "<p>Zweite Wurzel</p>"));

    private static byte[] convert() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DocxConverter().convert(new Input(PAGES), out);
        return out.toByteArray();
    }

    private static XWPFDocument word() throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(convert()));
    }

    private static String part(byte[] docx, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " fehlt");
    }

    @Test
    void startsWithARealTableOfContentsFieldThatWordUpdatesOnOpening() throws Exception {
        byte[] docx = convert();
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<String> fields = new ArrayList<>();
            for (XWPFParagraph paragraph : word.getParagraphs()) {
                paragraph.getCTP().getFldSimpleList().forEach(field -> fields.add(field.getInstr()));
            }
            assertThat(fields).containsExactly("TOC \\o \"1-9\" \\h \\z \\u");
            assertThat(word.getParagraphs().get(0).getStyle()).isEqualTo("Title");
            assertThat(word.getParagraphs().get(0).getText()).isEqualTo("Inhalt");
        }
        assertThat(part(docx, "word/settings.xml")).containsPattern("updateFields w:val=\"(on|true)\"");
    }

    @Test
    void chaptersFollowSpaceAndPageTreeWithWordHeadingStyles() throws Exception {
        try (XWPFDocument word = word()) {
            List<String> headings = new ArrayList<>();
            for (XWPFParagraph paragraph : word.getParagraphs()) {
                if (paragraph.getStyle() != null && paragraph.getStyle().startsWith("Heading")) {
                    headings.add(paragraph.getStyle() + ":" + paragraph.getText());
                }
            }
            // Space = Überschrift 1, Wurzelseiten 2, Kinder 3, Enkel 4; Überschriften im Inhalt unter ihrer Seite.
            assertThat(headings).containsExactly("Heading1:Space DEV (wiki)", "Heading2:Seite 100",
                    "Heading3:Teil & Ganzes", "Heading3:Seite 101", "Heading4:Seite 102", "Heading2:Seite 200");
            assertThat(word.getStyles().getStyle("Heading4").getCTStyle().getPPr().getOutlineLvl().getVal())
                    .hasToString("3");
        }
    }

    @Test
    void keepsStructureLinksAndJumpTargets() throws Exception {
        try (XWPFDocument word = word()) {
            List<String> bookmarks = new ArrayList<>();
            List<String> anchors = new ArrayList<>();
            List<String> lists = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : word.getParagraphs()) {
                for (CTBookmark bookmark : paragraph.getCTP().getBookmarkStartList()) {
                    bookmarks.add(paragraph.getStyle() + ":" + bookmark.getName());
                }
                for (CTHyperlink link : paragraph.getCTP().getHyperlinkList()) {
                    if (link.getAnchor() != null) {
                        anchors.add(link.getAnchor());
                    }
                }
                if (paragraph.getNumID() != null) {
                    lists.add(paragraph.getNumID() + "/" + paragraph.getNumIlvl() + ":" + paragraph.getText());
                }
                text.append(paragraph.getText()).append('\n');
            }
            assertThat(bookmarks).containsExactly("Heading2:page_100", "Heading3:page_101", "Heading4:page_102",
                    "Heading2:page_200");
            assertThat(anchors).containsExactly("page_101");
            assertThat(word.getHyperlinks()).anySatisfy(link ->
                    assertThat(link.getURL()).isEqualTo("https://example.org/?a=1&b=2"));
            // Zwei nummerierte Listen mit eigener Nummerierung (beide beginnen bei 1), darin eine Aufzählung.
            assertThat(lists).hasSize(4);
            assertThat(lists.get(0).split("/")[0]).isNotEqualTo(lists.get(3).split("/")[0]);
            assertThat(lists.get(2)).endsWith("/1:zwei-a");
            assertThat(text).contains("Seite 100 · Version 3", "Warnung", "Achtung", "Steuerzeichen entfernt",
                    "line 1", "  line <2>", "Enkel", "Zweite Wurzel").doesNotContain("\u0001");

            XWPFTable table = word.getTables().get(0);
            assertThat(table.getRow(0).isRepeatHeader()).isTrue();
            assertThat(table.getRow(0).getCell(0).getText()).isEqualTo("Name");
            assertThat(table.getRow(1).getCell(0).getText()).isEqualTo("a");
            // Tabelle ohne Kopf: keine leere Kopfzeile; Absätze in einer Zelle: Zeilenumbruch, kein "<br>" als Text.
            XWPFTable headless = word.getTables().get(1);
            assertThat(headless.getRows()).hasSize(1);
            assertThat(headless.getRow(0).getCell(0).getText()).doesNotContain("<br>").contains("oben")
                    .contains("unten");
        }
    }

    @Test
    void codeIsMonospacedAndShaded() throws Exception {
        try (XWPFDocument word = word()) {
            XWPFRun code = word.getParagraphs().stream().filter(p -> p.getText().equals("line 1")).findFirst()
                    .orElseThrow().getRuns().get(0);
            assertThat(code.getFontFamily()).isEqualTo("Consolas");
        }
    }

    @Test
    void imagesOnlyReferencedAreNotCountedAsEmbedded() throws Exception {
        try (XWPFDocument word = word()) {
            assertThat(word.getAllPictures()).isEmpty();
            assertThat(word.getParagraphs()).anySatisfy(paragraph ->
                    assertThat(paragraph.getText()).isEqualTo("[Abbildung nicht verfügbar: bild.png]"));
            assertThat(word.getParagraphs()).noneSatisfy(paragraph ->
                    assertThat(paragraph.getText()).contains("[Bild:"));
        }
    }

    @Test
    void aSpaceWithoutPagesHasNoTableOfContents() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DocxConverter().convert(new Input(List.of()), out);
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(word.getParagraphs()).allSatisfy(paragraph ->
                    assertThat(paragraph.getCTP().getFldSimpleList()).isEmpty());
            assertThat(word.getParagraphs().get(0).getText()).isEqualTo("Space DEV (wiki)");
        }
    }

    @Test
    void sameInputGivesTheSameBytes() throws Exception {
        assertThat(convert()).isEqualTo(convert());
    }
}
