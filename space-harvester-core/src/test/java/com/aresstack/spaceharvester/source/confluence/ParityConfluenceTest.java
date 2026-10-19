package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ParityConfluence;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Paritäts-Space (#193) taugt als Testdaten für den Core: Herunterladen, Konvertieren und Leseansicht über den
 * echten Confluence-Adapter gegen den Fake, und die Anhänge sind lesbare Dokumente.
 */
class ParityConfluenceTest {

    @TempDir
    Path data;

    @Test
    void theParitySpaceRunsThroughTheCore() throws Exception {
        try (FakeConfluenceServer server = new FakeConfluenceServer(ParityConfluence.install(new FakeConfluence()));
             Harvester harvester = Harvester.builder()
                     .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", server.baseUrl(),
                             SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                     .dataDirectory(data)
                     .build()) {
            JobStatus export = harvester.await(harvester.export("wiki", ParityConfluence.SPACE, List.of("json")).id(),
                    Duration.ofSeconds(60));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            assertThat(export.progress().written()).isEqualTo(ParityConfluence.PAGES.length);

            ExportConversions.ConvertedFile markdown = harvester.conversions().convert(export.id(), Harvester.OWNER,
                    "markdown", Files.createTempDirectory(data, "md"));
            String text = Files.readString(markdown.path()).replaceAll("\\s+", " ");
            assertThat(text).contains(ParityConfluence.SENTENCES).contains("Ebene 7", "# Anhänge");

            AccessContext caller = AccessContext.technicalClient(Harvester.OWNER);
            for (String page : ParityConfluence.PAGES) {
                assertThat(harvester.readingViews().view(caller, "wiki", page, false).status()).as(page)
                        .isEqualTo(ReadingView.Status.AVAILABLE);
            }
            assertThat(harvester.readingViews().view(caller, "wiki", "1009", false).text())
                    .contains(ParityConfluence.MACRO_SENTENCES);

            // Mit abgelegten Ansichten: Erwähnungen tragen deren Namen, versteckte und eingebundene zählen für die
            // Zuordnung nicht.
            ExportConversions.ConvertedFile withViews = harvester.conversions().convert(export.id(), Harvester.OWNER,
                    "markdown", Files.createTempDirectory(data, "md"));
            String viewed = Files.readString(withViews.path());
            for (String[] mention : ParityConfluence.MENTIONS) {
                assertThat(viewed).contains(mention[1]).doesNotContain(mention[0]);
            }
            assertThat(viewed).contains(ParityConfluence.INCLUDED_MENTION)
                    .doesNotContain(ParityConfluence.HIDDEN_MENTION);
            ReadingView attachments = harvester.readingViews().view(caller, "wiki",
                    ParityConfluence.ATTACHMENTS_PAGE, false);
            // Die Ansicht kommt aus export_view mit dem gerenderten Vorgang; der Lesetext des Adapters liest body.view
            // mit dem Platzhalter von Confluence und nennt nur den Schlüssel.
            assertThat(attachments.html()).contains(ParityConfluence.JIRA_SENTENCE);
            assertThat(attachments.text()).contains(ParityConfluence.JIRA_KEY, ParityConfluence.SENTENCES[11]);

            // draw.io (auch über inc-drawio) und das Inline-Bild sind Bilder der Ansicht, die der Adapter laden kann.
            for (String page : List.of("1004", ParityConfluence.ATTACHMENTS_PAGE)) {
                assertThat(harvester.readingViews().view(caller, "wiki", page, false).images()).as(page)
                        .anyMatch(image -> image.src().contains("drawio=ablauf"));
            }
            assertThat(attachments.images()).anyMatch(image -> image.src().contains("/1011/symbol.svg"));

            HttpClient http = HttpClient.newHttpClient();
            for (String[] document : ParityConfluence.DOCUMENTS) {
                URI download = URI.create(server.baseUrl() + "/download/attachments/"
                        + ParityConfluence.ATTACHMENTS_PAGE + "/" + document[0]);
                byte[] content = http.send(HttpRequest.newBuilder(download).build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                assertThat(TikaDocumentText.shared().detect(content)).as(document[0]).contains(document[1]);
                assertThat(TikaDocumentText.shared().extract(document[0], document[1], content).text())
                        .as(document[0]).contains(document[2]);
            }
            for (String path : List.of("/download/attachments/1011/symbol.svg",
                    "/download/attachments/1004/ablauf.png")) {
                assertThat(http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode()).as(path).isEqualTo(200);
            }
        }
    }

    @Test
    void theDocumentsAreTheSameBytesOnEveryRun() {
        assertThat(ParityConfluence.docx("Satz.")).isEqualTo(ParityConfluence.docx("Satz."));
        assertThat(new String(ParityConfluence.pdf("Satz (a)."), StandardCharsets.US_ASCII))
                .startsWith("%PDF-1.4").contains("(Satz \\(a\\).) Tj").endsWith("%%EOF\n");
    }
}
