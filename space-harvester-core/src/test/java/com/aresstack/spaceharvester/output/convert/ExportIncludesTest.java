package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.convert.ConversionNotice.Kind;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.convert.ExportReadingView.Status;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ausgeführte Makros ({@code include}, {@code excerpt-include}, {@code children}) in Word, PDF und Markdown: der Inhalt
 * kommt aus der abgelegten Lese-Ansicht (#218), nie von der Quelle; ohne Ansicht bleibt der Hinweis, gemeldet als
 * {@link Kind#OMITTED_CONTENT}.
 */
class ExportIncludesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<ExportConverter> CONVERTERS = List.of(new DocxConverter(), new PdfConverter(),
            new MarkdownConverter());

    private static final String STORAGE = "<p>Vorher</p>"
            + "<ac:structured-macro ac:name=\"include\"><ac:parameter ac:name=\"\"><ac:link>"
            + "<ri:page ri:content-title=\"Glossar\" /></ac:link></ac:parameter></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"excerpt-include\"><ac:parameter ac:name=\"\"><ac:link>"
            + "<ri:page ri:content-title=\"Ziele\" /></ac:link></ac:parameter></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"children\" />"
            + "<p>Nachher</p>";

    /** Wie Confluence rendert: Makros mit {@code data-macro-name}, verschachteltes include, versteckte Daten. */
    private static final String VIEW = "<p>Vorher</p>"
            + "<div class=\"conf-macro output-block\" data-macro-name=\"include\"><p>Begriffsliste Alpha</p>"
            + "<div data-macro-name=\"include\"><p>Verschachtelt Beta</p></div>"
            + "<div style=\"display:none\">GEHEIMDATEN</div>"
            + "<p><img src=\"/download/attachments/7/karte.png\" alt=\"Karte\"></p></div>"
            + "<div class=\"conf-macro output-block\" data-macro-name=\"excerpt-include\"><p>Zielbild Gamma</p></div>"
            + "<ul class=\"childpages-macro conf-macro output-block\" data-macro-name=\"children\">"
            + "<li><a href=\"/display/DEV/Delta\">Unterseite Delta</a></li></ul>"
            + "<p>Nachher</p>";

    private record Input(List<ConvertiblePage> pages, Optional<ExportReadingView> view,
                         List<ConversionNotice> reported) implements ConversionInput {

        @Override
        public Optional<ExportReadingView> readingView(String pageId) {
            return view.filter(v -> v.pageId().equals(pageId));
        }

        @Override
        public void report(ConversionNotice notice) {
            reported.add(notice);
        }

        @Override
        public ExportAssets assets() {
            return ExportAssets.NONE;
        }

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

    private static Input input(Optional<ExportReadingView> view) {
        return input(STORAGE, view);
    }

    private static Input input(String storage, Optional<ExportReadingView> view) {
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode().put("id", "100").set("body",
                JSON.createObjectNode().set("storage", JSON.createObjectNode().put("value", storage))));
        PageSnapshot snapshot = page("wiki", "DEV", "100", null, payload);
        return new Input(List.of(new ConvertiblePage(snapshot.header(), snapshot, 0)), view, new ArrayList<>());
    }

    private static Optional<ExportReadingView> available(String html) {
        return Optional.of(ExportReadingView.available("100", "1", html, "https://wiki.example/", "Lesetext"));
    }

    /** Der sichtbare Text der erzeugten Datei. */
    private static String text(ExportConverter converter, Input input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.convert(input, out);
        byte[] bytes = out.toByteArray();
        return switch (converter.format()) {
            case "docx" -> {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    yield extractor.getText();
                }
            }
            case "pdf" -> {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    yield new PDFTextStripper().getText(document);
                }
            }
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    @Test
    void executedMacrosShowTheContentOfTheStoredReadingView() throws Exception {
        for (ExportConverter converter : CONVERTERS) {
            Input input = input(available(VIEW));
            String text = text(converter, input);
            assertThat(text).as(converter.format())
                    .contains("Begriffsliste Alpha", "Verschachtelt Beta", "Zielbild Gamma", "Unterseite Delta",
                            "Vorher", "Nachher")
                    .doesNotContain("GEHEIMDATEN", "wird dort exportiert", "Unterseiten wird in Confluence");
            // Das Bild der Ansicht ist nicht beschafft: gemeldet mit seiner Adresse zur Basis der Ansicht
            assertThat(input.reported()).as(converter.format()).containsExactly(new ConversionNotice("100",
                    Kind.MISSING_IMAGE, "https://wiki.example/download/attachments/7/karte.png", "not-collected"));
        }
    }

    @Test
    void withoutAReadingViewTheHintStaysAndIsReported() throws Exception {
        for (ExportConverter converter : CONVERTERS) {
            Input restricted = input(Optional.of(ExportReadingView.without("100", "1", Status.RESTRICTED,
                    "foreign-content")));
            assertThat(text(converter, restricted)).as(converter.format())
                    .contains("Glossar", "wird dort exportiert", "Unterseiten wird in Confluence")
                    .doesNotContain("Begriffsliste Alpha");
            assertThat(restricted.reported()).as(converter.format()).containsExactly(
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "Glossar", "foreign-content"),
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "Ziele", "foreign-content"),
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "children", "foreign-content"));

            Input none = input(Optional.empty());
            text(converter, none);
            assertThat(none.reported()).as(converter.format()).containsExactly(
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "Glossar", "not-collected"),
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "Ziele", "not-collected"),
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "children", "not-collected"));
        }
    }

    @Test
    void aMacroTheViewDoesNotMarkIsReportedAsNotRendered() throws Exception {
        String withoutChildren = VIEW.replace(" data-macro-name=\"children\"", "");
        for (ExportConverter converter : CONVERTERS) {
            Input input = input(available(withoutChildren));
            assertThat(text(converter, input)).as(converter.format())
                    .contains("Begriffsliste Alpha", "Zielbild Gamma", "Unterseiten wird in Confluence");
            assertThat(input.reported()).as(converter.format()).contains(
                    new ConversionNotice("100", Kind.OMITTED_CONTENT, "children", "not-rendered"));
        }
    }

    /** Erwähnungen mit dem Anzeigenamen der Lese-Ansicht wie im Enterprise AI Client, nicht als Benutzerkennung. */
    @Test
    void mentionsShowTheDisplayNameOfTheReadingView() throws Exception {
        String storage = "<p>Frage an <ac:link><ri:user ri:userkey=\"8a7f0c1d\" /></ac:link> und "
                + "<ac:link><ri:user ri:username=\"bwagner\" /></ac:link>.</p>";
        String view = "<p>Frage an <a class=\"confluence-userlink user-mention\" data-username=\"aschmidt\" "
                + "href=\"/display/~aschmidt\">Anna Schmidt</a> und <a class=\"confluence-userlink user-mention\" "
                + "data-username=\"bwagner\" href=\"/display/~bwagner\">Bernd Wagner</a>.</p>";
        for (ExportConverter converter : CONVERTERS) {
            assertThat(text(converter, input(storage, available(view)))).as(converter.format())
                    .contains("Anna Schmidt", "Bernd Wagner").doesNotContain("8a7f0c1d", "@bwagner");
            // Ohne Ansicht bleibt die Kennung, wie bisher.
            assertThat(text(converter, input(storage, Optional.empty()))).as(converter.format())
                    .contains("@8a7f0c1d", "@bwagner");
        }
    }

    /** Stimmt die Zahl der Erwähnungen nicht überein, ordnet nur der Benutzername zu, nie die Reihenfolge. */
    @Test
    void mentionsAreNotMatchedByOrderWhenTheCountsDiffer() throws Exception {
        String storage = "<p><ac:link><ri:user ri:userkey=\"8a7f0c1d\" /></ac:link></p>";
        String view = "<p><a class=\"user-mention\" data-username=\"x\">Anna Schmidt</a> "
                + "<a class=\"user-mention\" data-username=\"y\">Bernd Wagner</a></p>";
        assertThat(text(new MarkdownConverter(), input(storage, available(view))))
                .contains("@8a7f0c1d").doesNotContain("Anna Schmidt", "Bernd Wagner");
    }

    /**
     * Zuordnung über die Account-ID; Erwähnungen in eingebundenen und unsichtbaren Inhalten zählen nicht; ein eigener
     * Linktext geht vor; ohne Namen bleibt die Account-ID statt eines leeren „@“.
     */
    @Test
    void mentionsMatchTheAccountIdAndIgnoreIncludedOrHiddenNames() throws Exception {
        String storage = "<p><ac:link><ri:user ri:account-id=\"557058:aa\" /></ac:link> "
                + "<ac:link><ri:user ri:userkey=\"k1\" /></ac:link> "
                + "<ac:link><ri:user ri:account-id=\"557058:cc\" /><ac:plain-text-link-body>"
                + "<![CDATA[Team Lead]]></ac:plain-text-link-body></ac:link></p>";
        String view = "<p><a class=\"user-mention\" data-account-id=\"557058:aa\">Anna Schmidt</a> "
                + "<span class=\"hidden\"><a class=\"user-mention\" data-username=\"h\">Versteckt</a></span> "
                + "<span style=\"display: none\"><a class=\"user-mention\">Auch versteckt</a></span> "
                + "<a class=\"user-mention\" data-username=\"k\">Klaus Kern</a> "
                + "<a class=\"user-mention\" data-account-id=\"557058:cc\">Carla Conrad</a></p>"
                + "<div data-macro-name=\"include\"><a class=\"user-mention\">Eingebunden</a></div>";
        String text = text(new MarkdownConverter(), input(storage, available(view)));
        assertThat(text).contains("Anna Schmidt", "Klaus Kern", "Team Lead")
                .doesNotContain("Versteckt", "Eingebunden", "Carla Conrad", "557058");

        String alone = text(new MarkdownConverter(), input(
                "<p><ac:link><ri:user ri:account-id=\"557058:aa\" /></ac:link></p>", Optional.empty()));
        assertThat(alone).contains("@557058:aa");
    }
}
