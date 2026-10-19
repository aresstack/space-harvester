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

    /** Teilbaum ab Seite 100 (#186): Startseite mit Kind 101 und Enkel 102. */
    private record Rooted(List<ConvertiblePage> pages) implements ConversionInput {

        @Override
        public String sourceId() {
            return "wiki";
        }

        @Override
        public String spaceKey() {
            return "DEV";
        }

        @Override
        public String rootPageId() {
            return "100";
        }

        @Override
        public PageTree tree() {
            return new PageTree(List.of(new PageTree.Node("100", List.of(new PageTree.Node("101",
                    List.of(new PageTree.Node("102", List.of())))))), List.of(), 3, 3);
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

    @Test
    void aSubtreeStartsWithTheStartPageAsRootChapterLikeTheEac() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MarkdownConverter().convert(new Rooted(List.of(storagePage("100", "1", 0, "<p>Start</p>"),
                storagePage("101", "100", 1, "<p>Kind</p>"), storagePage("102", "101", 2, "<p>Enkel</p>"))), out);
        String markdown = out.toString(StandardCharsets.UTF_8);

        assertThat(markdown.lines().filter(line -> line.startsWith("#")).toList())
                .containsExactly("# Seite 100", "## Seite 101", "### Seite 102");
        assertThat(markdown).contains("3 Seiten ab Seite 100 des Space DEV der Quelle wiki");
        assertThat(markdown.indexOf("ab Seite 100")).isLessThan(markdown.indexOf("Start"));
    }

    @Test
    void anOrphanedPageOfASubtreeStaysBelowTheStartPage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 102 ist eigene Wurzel, weil 101 nicht lesbar ist; sie steht unter dem Wurzelkapitel, nicht daneben.
        new MarkdownConverter().convert(new ConversionInput() {
            @Override
            public String sourceId() {
                return "wiki";
            }

            @Override
            public String spaceKey() {
                return "DEV";
            }

            @Override
            public String rootPageId() {
                return "100";
            }

            @Override
            public PageTree tree() {
                return new PageTree(List.of(new PageTree.Node("100", List.of()), new PageTree.Node("102", List.of())),
                        List.of(), 2, 1);
            }

            @Override
            public List<PageHeader> headers() {
                return List.of(storagePage("100", "1", 0, "").header(), storagePage("102", "101", 0, "").header());
            }

            @Override
            public void forEachPage(Consumer<ConvertiblePage> action) {
                action.accept(storagePage("100", "1", 0, "<p>Start</p>"));
                action.accept(storagePage("102", "101", 0, "<p>Lose</p>"));
            }
        }, out);

        assertThat(out.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("#")).toList())
                .containsExactly("# Seite 100", "## Seite 102");
    }

    @Test
    void theRootChapterCarriesTheSpaceNameLikeTheSource() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Input input = new Input(List.of(storagePage("100", null, 0, "<p>Start</p>")));
        new MarkdownConverter().convert(new ConversionInput() {
            @Override
            public String sourceId() {
                return input.sourceId();
            }

            @Override
            public String spaceKey() {
                return input.spaceKey();
            }

            @Override
            public String spaceName() {
                return "Entwicklung";
            }

            @Override
            public PageTree tree() {
                return input.tree();
            }

            @Override
            public List<PageHeader> headers() {
                return input.headers();
            }

            @Override
            public void forEachPage(Consumer<ConvertiblePage> action) {
                input.forEachPage(action);
            }
        }, out);

        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("# Entwicklung\n\n*1 Seiten des Space DEV der Quelle wiki,");
    }

    @Test
    void writesPagesLinearlyWithHeadingLevelsFromTheTree() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<p>Start</p>"),
                storagePage("101", "100", 1, "<h1>Abschnitt</h1><p>Text</p>"),
                storagePage("102", "101", 7, "<p>tief</p>"));

        // Kapitelmodell wie Word und PDF: der Space ist # , Wurzelseiten ##, tiefer als sechs Ebenen fett
        assertThat(markdown).startsWith("# Space DEV (wiki)\n\n*3 Seiten des Space DEV der Quelle wiki, erzeugt mit markdown/7")
                .contains("<a id=\"page-100\"></a>\n\n## Seite 100\n\n*Seite 100 · Version 3")
                .contains("<a id=\"page-101\"></a>\n\n### Seite 101\n")
                .contains("#### Abschnitt\n\nText\n")
                .contains("<a id=\"page-102\"></a>\n\n**Seite 102**\n");
        assertThat(markdown.indexOf("## Seite 100")).isLessThan(markdown.indexOf("### Seite 101"));
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

    /** Verbundene Zellen wie im Enterprise AI Client: der Inhalt steht an jeder überdeckten Stelle (#193-Lücke). */
    @Test
    void spannedCellsRepeatTheirTextInEveryCoveredPosition() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<table><tbody>"
                + "<tr><th>Bereich</th><th colspan=\"2\">Werte</th><th>Notiz</th></tr>"
                + "<tr><td rowspan=\"2\">Nacht</td><td>1</td><td>2</td><td>a</td></tr>"
                + "<tr><td>3</td><td>4</td><td>b</td></tr>"
                + "<tr><td>Tag</td><td colspan=\"x\">5</td><td>6</td></tr>"
                + "</tbody></table>"));

        assertThat(markdown)
                .contains("| Bereich | Werte | Werte | Notiz |\n| --- | --- | --- | --- |\n"
                        + "| Nacht | 1 | 2 | a |\n| Nacht | 3 | 4 | b |\n| Tag | 5 | 6 |");
    }

    /** Verschachtelte Tabelle wie im Enterprise AI Client: ihr Inhalt steht als flacher Text in der Zelle. */
    @Test
    void nestedTableKeepsItsTextInTheCell() throws Exception {
        String markdown = convert(storagePage("100", null, 0, "<table><tbody><tr><th>Name</th><th>Werte</th></tr>"
                + "<tr><td>Nacht</td><td><table><tbody><tr><td>Start</td><td>22:00</td></tr>"
                + "<tr><td>Ende</td><td>04:00</td></tr></tbody></table></td></tr></tbody></table>"));

        assertThat(markdown)
                .contains("| Nacht | Start 22:00 Ende 04:00 |")
                .doesNotContain("verschachtelte Tabelle");
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
                // Nur als Anhang vorhanden: nicht eingebettet, sichtbar gemeldet
                .contains("*[Abbildung nicht verfügbar: diagramm.png]*")
                .doesNotContain("attachment:")
                .contains("*[Abbildung nicht verfügbar: Diagramm (draw.io) „architektur“]*")
                .contains("```plantuml\n@startuml\nA -> B\n@enduml\n```")
                .contains("Siehe [Seite 101](#page-101) und anderswo.")
                .contains("Makro „toc“ wird in Confluence berechnet")
                .contains("```json\n{\"pageId\": \"1002\"}\n```");
    }

    @Test
    void codeKeepsBlankLinesAndWebImagesAreNotEmbedded() throws Exception {
        String markdown = convert(storagePage("100", null, 0,
                "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[a\n\n\n\nb]]>"
                        + "</ac:plain-text-body></ac:structured-macro>"
                        + "<p><ac:image><ri:url ri:value=\"https://example.org/logo.png\" /></ac:image></p>"));

        // Externe Bilder: nicht eingebettet, Hinweis mit Link auf die Adresse
        assertThat(markdown).contains("```\na\n\n\n\nb\n```")
                .contains("*\\[Abbildung nicht verfügbar: Bild\\]* ([Adresse](https://example.org/logo.png))")
                .doesNotContain("![");
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
