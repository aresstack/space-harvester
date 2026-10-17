package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MarkdownConverterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Seiten mit Tiefe in der gegebenen Reihenfolge. */
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
                JSON.createObjectNode().set("storage", JSON.createObjectNode().put("value", storage)
                        .put("representation", "storage"))));
        PageSnapshot snapshot = page("wiki", "DEV", pageId, parentId, payload);
        return new ConvertiblePage(snapshot.header(), snapshot, depth);
    }

    private static String convert(ConvertiblePage... pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MarkdownConverter().convert(new Input(List.of(pages)), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesPagesLinearlyWithHeadingLevelsFromTheTree() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<p>Start</p>"),
                storagePage("101", "100", 1, "<h1>Abschnitt</h1><p>Text</p>"),
                storagePage("102", "101", 7, "<p>tief</p>"));

        assertThat(markdown).contains("<a id=\"page-100\"></a>\n\n# Seite 100\n\n*Seite 100 · Version 3")
                .contains("<a id=\"page-101\"></a>\n\n## Seite 101\n")
                .contains("### Abschnitt\n\nText\n")
                .contains("###### Seite 102\n");
        assertThat(markdown.indexOf("# Seite 100")).isLessThan(markdown.indexOf("## Seite 101"));
    }

    @Test
    void convertsCommonStorageElements() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<p>Ein <strong>fetter</strong> und <em>kursiver</em> "
                + "Text mit <code>code</code>,&nbsp;Sonderzeichen * _ [x] und<br/>Umbruch.</p>"
                + "<ul><li><p>eins</p><ul><li>eins-a</li></ul></li><li>zwei</li></ul>"
                + "<ol><li>erstens</li><li>zweitens</li></ol>"
                + "<ac:task-list><ac:task><ac:task-status>complete</ac:task-status><ac:task-body>erledigt"
                + "</ac:task-body></ac:task><ac:task><ac:task-status>incomplete</ac:task-status><ac:task-body>offen"
                + "</ac:task-body></ac:task></ac:task-list>"
                + "<table><tbody><tr><th>Name</th><th>Wert</th></tr><tr><td>a|b</td><td><p>1</p><p>2</p></td></tr>"
                + "</tbody></table>"
                + "<p><a href=\"https://example.org/x y\">extern</a></p>"));

        assertThat(markdown)
                .contains("Ein **fetter** und *kursiver* Text mit `code`, Sonderzeichen \\* \\_ \\[x\\] und\\\nUmbruch.")
                .contains("- eins\n\n  - eins-a\n- zwei")
                .contains("1. erstens\n2. zweitens")
                .contains("- [x] erledigt\n- [ ] offen")
                .contains("| Name | Wert |\n| --- | --- |\n| a\\|b | 1<br>2 |")
                .contains("[extern](<https://example.org/x y>)");
    }

    @Test
    void keepsReferencesAndPrefersTextualDiagramSources() throws Exception {
        String markdown = convert(
                storagePage("100", null, 0, "<p>Überblick</p>"
                        + "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Nur Testdaten.</p>"
                        + "</ac:rich-text-body></ac:structured-macro>"
                        + "<ac:image><ri:attachment ri:filename=\"diagramm.png\" /></ac:image>"
                        + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">"
                        + "architektur</ac:parameter></ac:structured-macro>"
                        + "<ac:structured-macro ac:name=\"plantuml\"><ac:plain-text-body><![CDATA[@startuml\n"
                        + "A -> B\n@enduml]]></ac:plain-text-body></ac:structured-macro>"
                        + "<p>Siehe <ac:link><ri:page ri:content-title=\"Seite 101\" /></ac:link> und "
                        + "<ac:link><ri:page ri:content-title=\"Fremd\" ri:space-key=\"OPS\" />"
                        + "<ac:plain-text-link-body><![CDATA[anderswo]]></ac:plain-text-link-body></ac:link>.</p>"
                        + "<ac:structured-macro ac:name=\"toc\" />"),
                storagePage("101", "100", 1,
                        "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">json</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[{\"pageId\": \"1002\"}]]></ac:plain-text-body>"
                        + "</ac:structured-macro>"));

        assertThat(markdown)
                .contains("> **Info**\n>\n> Nur Testdaten.")
                .contains("![diagramm.png](attachment:diagramm.png)")
                .contains("draw.io) „architektur“: die Quelle ist der Seitenanhang „architektur“")
                .contains("```plantuml\n@startuml\nA -> B\n@enduml\n```")
                .contains("Siehe [Seite 101](#page-101) und anderswo.")
                .contains("Makro „toc“ wird in Confluence berechnet")
                .contains("```json\n{\"pageId\": \"1002\"}\n```");
    }

    @Test
    void pageWithoutStorageIsMarkedNotDropped() throws Exception {
        PageSnapshot snapshot = page("wiki", "DEV", "100", null);

        String markdown = convert(new ConvertiblePage(snapshot.header(), snapshot, 0));

        assertThat(markdown).contains("# Seite 100").contains("keinen Inhalt im Storage-Format");
    }

    @Test
    void textThatLooksLikeMarkdownStaysText() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<p># keine Überschrift</p><p>1. keine Liste</p>"
                + "<p>&lt;script&gt;</p>"));

        assertThat(markdown).contains("\\# keine Überschrift").contains("1\\. keine Liste")
                .contains("\\<script\\>");
    }

    @Test
    void pageContentNeverBecomesActiveMarkdownOrHtml() throws Exception {
        String markdown = convert(storagePage("100", null, 0,
                "<p><code>a``&lt;img src=x onerror=alert(1)&gt;``b</code></p>"
                        + "<p><a href=\"javascript:alert(1)\">klick</a> <a href=\"data:text/html,x\">daten</a> "
                        + "<a href=\"https://example.org/x\">gut</a></p>"
                        + "<ac:image><ri:url ri:value=\"https://tracker.example/p.gif\" /></ac:image>"
                        + "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">a`b x</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[<script>x</script>]]></ac:plain-text-body></ac:structured-macro>"));

        assertThat(markdown)
                .contains("``` a``<img src=x onerror=alert(1)>``b ```")
                .contains("klick daten [gut](https://example.org/x)")
                .doesNotContain("javascript:", "data:text")
                .doesNotContain("![](https://tracker.example")
                .contains("](https://tracker.example/p.gif)")
                .contains("```abx\n<script>x</script>\n```");
    }

    @Test
    void maliciouslyDeepStorageBecomesText() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<ul><li>".repeat(20_000) + "ganz unten"
                + "</li></ul>".repeat(20_000) + "<p>" + "<em>".repeat(20_000) + "innen" + "</em>".repeat(20_000)
                + "</p>"));

        assertThat(markdown).contains("ganz unten", "innen", "Verschachtelung tiefer als 64 Ebenen");
    }
}
