package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Textprojektion des Confluence-Storage-Formats: nur sichtbarer Text, nichts wird ausgeführt oder aufgelöst. */
class ConfluenceStorageTextProjectorTest {

    private static final String FORMAT = "confluence-rest-content+json;expand=body.storage,version";

    private final ConfluenceStorageTextProjector projector = new ConfluenceStorageTextProjector();

    @Test
    void projectsHeadingsParagraphsListsAndTables() {
        PageText text = project("<h1>Überblick</h1><p>Die <strong>API</strong>&nbsp;ist &amp; bleibt"
                + " &quot;stabil&quot; &#8211; &#x263A;.</p><ul><li>eins</li><li>zwei</li></ul>"
                + "<h2>Tabelle</h2><table><tbody><tr><th>Name</th><th>Wert</th></tr>"
                + "<tr><td>a</td><td><p>1</p></td></tr></tbody></table><p>Ende<br/>Zeile</p>");

        assertThat(text.schema()).isEqualTo("confluence-storage-text/v1");
        assertThat(text.text()).isEqualTo("Überblick\nDie API ist & bleibt \"stabil\" – ☺.\neins\nzwei\n"
                + "Tabelle\nName | Wert\na | 1\nEnde\nZeile");
        assertThat(text.sections()).extracting(PageText.Section::heading, PageText.Section::level)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Überblick", 1),
                        org.assertj.core.groups.Tuple.tuple("Tabelle", 2));
        PageText.Section first = text.sections().get(0);
        assertThat(text.text().substring(first.start(), first.end())).startsWith("Überblick\nDie API")
                .endsWith("zwei\n");
        assertThat(text.sections().get(1).end()).isEqualTo(text.text().length());
    }

    @Test
    void dropsMacrosImagesAndResourceReferencesWithoutResolvingThem() {
        PageText text = project("<p>Vor</p><ac:structured-macro ac:name=\"include\"><ac:parameter ac:name=\"\">"
                + "<ri:page ri:content-title=\"Geheim\"/></ac:parameter></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[rm -rf /]]>"
                + "</ac:plain-text-body></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">Netz"
                + "</ac:parameter></ac:structured-macro>"
                + "<p>Bild <ac:image ac:alt=\"Alt\"><ri:attachment ri:filename=\"a.png\"/></ac:image> und "
                + "<ac:emoticon ac:name=\"smile\"/>Text</p>"
                + "<p><ac:link><ri:user ri:userkey=\"u1\"/></ac:link> sah "
                + "<ac:link><ri:page ri:content-title=\"Ziel\"/><ac:plain-text-link-body><![CDATA[den Link]]>"
                + "</ac:plain-text-link-body></ac:link> und <a href=\"https://x.example\">extern</a>.</p>");

        assertThat(text.text()).isEqualTo("Vor\nBild und Text\nsah den Link und extern.");
        assertThat(text.text()).doesNotContain("Geheim", "rm -rf", "Netz", "a.png", "Ziel", "u1", "x.example");
    }

    @Test
    void layoutsAndPanelsKeepTheirVisibleTextOnlyIfNotInsideAMacro() {
        PageText text = project("<ac:layout><ac:layout-section><ac:layout-cell><p>links</p></ac:layout-cell>"
                + "<ac:layout-cell><ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Hinweis</p>"
                + "</ac:rich-text-body></ac:structured-macro><p>rechts</p></ac:layout-cell></ac:layout-section>"
                + "</ac:layout>");

        assertThat(text.text()).isEqualTo("links\nrechts");
    }

    @Test
    void toleratesBrokenMarkupAndIgnoresDeclarationsCommentsAndUnknownEntities() {
        PageText text = project("<!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><?pi ok?>"
                + "<!-- Kommentar --><p>a &xxe; b &unbekannt c < d</p><p>offen<h3>Titel</p>Rest");

        assertThat(text.text()).isEqualTo("a b &unbekannt c < d\noffen\nTitel\nRest");
        assertThat(text.text()).doesNotContain("passwd", "Kommentar");
    }

    @Test
    void theInputHashFollowsTheVisibleTextOnly() {
        assertThat(project("<p>Hallo <em>Welt</em></p>").inputSha256())
                .isEqualTo(project("<p>Hallo  <strong>Welt</strong></p>").inputSha256())
                .isNotEqualTo(project("<p>Hallo Welten</p>").inputSha256());
    }

    @Test
    void theInputHashFollowsTheOutline() {
        String paragraph = project("<p>Titel</p><p>Text</p>").inputSha256();
        String h1 = project("<h1>Titel</h1><p>Text</p>").inputSha256();
        String h2 = project("<h2>Titel</h2><p>Text</p>").inputSha256();

        assertThat(project("<p>Titel</p><p>Text</p>").text()).isEqualTo(project("<h1>Titel</h1><p>Text</p>").text());
        assertThat(paragraph).isNotEqualTo(h1);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void otherFormatsAreNotSupported() {
        PageSnapshot other = EnrichmentTestPages.page("wiki", "101", "1", "{}".getBytes(StandardCharsets.UTF_8),
                "confluence-rest-contentX");

        assertThat(projector.project(other)).isEmpty();
    }

    @Test
    void aConfluencePayloadWithoutStorageBodyIsMalformed() {
        assertThatThrownBy(() -> projector.project(page("{\"id\":\"101\",\"body\":{\"view\":{}}}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projector.project(page("kein json")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PageText project(String storage) {
        String payload = "{\"id\":\"101\",\"body\":{\"storage\":{\"value\":" + quote(storage)
                + ",\"representation\":\"storage\"}}}";
        return projector.project(page(payload)).orElseThrow();
    }

    private static PageSnapshot page(String payload) {
        return EnrichmentTestPages.page("wiki", "101", "1", payload.getBytes(StandardCharsets.UTF_8), FORMAT);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
