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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.json.JsonMapper;

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
                    + "<p>Steuerzeichen\u0001 entfernt</p>"),
            storagePage("101", "100", 1, "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                    + "<![CDATA[line 1\n  line <2>]]></ac:plain-text-body></ac:structured-macro>"));

    private static Map<String, byte[]> unzip(byte[] docx) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static byte[] convert() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DocxConverter().convert(new Input(PAGES), out);
        return out.toByteArray();
    }

    @Test
    void writesAWellFormedWordPackage() throws Exception {
        Map<String, byte[]> parts = unzip(convert());

        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml",
                "word/numbering.xml", "word/_rels/document.xml.rels");
        for (byte[] part : parts.values()) {
            xml(part);
        }
    }

    @Test
    void keepsStructureLinksAndReferences() throws Exception {
        Map<String, byte[]> parts = unzip(convert());
        String document = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        String relationships = new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8);
        String numbering = new String(parts.get("word/numbering.xml"), StandardCharsets.UTF_8);

        assertThat(document).contains("<w:pStyle w:val=\"Heading1\"/></w:pPr><w:bookmarkStart w:id=\"0\" "
                        + "w:name=\"page_100\"/>")
                .contains("<w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Teil &amp; Ganzes")
                .contains("<w:pStyle w:val=\"Heading2\"/></w:pPr><w:bookmarkStart w:id=\"1\" w:name=\"page_101\"/>")
                .contains("<w:hyperlink w:anchor=\"page_101\">")
                .contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">fetter")
                .contains("<w:tblHeader/>")
                .contains("<w:pStyle w:val=\"Quote\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">"
                        + "Warnung")
                .contains("[Bild: bild.png – attachment:bild.png]")
                .contains("line 1</w:t></w:r><w:r><w:br/></w:r>")
                .contains("  line &lt;2&gt;")
                .contains("Steuerzeichen entfernt")
                .doesNotContain("\u0001");
        assertThat(relationships).contains("Target=\"https://example.org/?a=1&amp;b=2\" TargetMode=\"External\"");
        // Zwei nummerierte Listen beginnen je bei 1: zwei eigene Nummerierungen neben der für Aufzählungen.
        assertThat(numbering).contains("<w:num w:numId=\"2\">").contains("<w:num w:numId=\"3\">");
        assertThat(document).contains("<w:numId w:val=\"2\"/>").contains("<w:numId w:val=\"3\"/>")
                .contains("<w:ilvl w:val=\"1\"/><w:numId w:val=\"1\"/>");
    }

    @Test
    void sameInputGivesTheSameBytes() throws Exception {
        assertThat(convert()).isEqualTo(convert());
    }
}
