package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Paritäts-Site der SharePoint-Quelle (#193) läuft als Typ {@code sharepoint} durch Abgleich, Cache und Suche:
 * jede Textdatei der sichtbaren Bibliothek mit ihrem Satz; {@code Forms}, das Bild und die versteckte Bibliothek
 * fehlen.
 */
class ParitySharePointTest {

    @TempDir
    Path data;

    @Test
    void everyTextFileOfTheVisibleLibraryArrives() throws Exception {
        try (ParitySharePoint site = ParitySharePoint.start(); Harvester harvester = harvester(site)) {
            Map<String, String> texts = sync(harvester);
            assertThat(texts.keySet()).containsExactlyInAnyOrder(ParitySharePoint.DOCUMENTS);
            assertThat(String.join("\n", texts.values())).contains(ParitySharePoint.SENTENCES)
                    .doesNotContain(ParitySharePoint.HIDDEN_SENTENCE);

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParitySharePoint.SEARCH), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::title).containsExactly("Ablauf.txt");
        }
    }

    @Test
    void aShallowDepthStopsBeforeTheNestedFiles() throws Exception {
        try (ParitySharePoint site = ParitySharePoint.start();
             Harvester harvester = harvester(site, ParitySharePoint.DEPTH - 1)) {
            assertThat(sync(harvester).keySet()).containsExactlyInAnyOrder(ParitySharePoint.LIBRARY + "/Lies mich.txt",
                    ParitySharePoint.LIBRARY + "/Betrieb/Handbuch.txt", ParitySharePoint.LIBRARY + "/Notizen.md");
        }
    }

    private Harvester harvester(ParitySharePoint server) {
        return harvester(server, ParitySharePoint.DEPTH);
    }

    private Harvester harvester(ParitySharePoint server, int depth) {
        Map<String, String> options = Map.of("siteUrl", server.siteUrl(), "maxDepth", String.valueOf(depth));
        return Harvester.builder()
                .source(new SourceSettingsConfig("parity", "sharepoint", "Parität", null, null, options))
                .dataDirectory(data).build();
    }

    /** Pfad je Datei relativ zum Ordner und ihr Text. */
    private Map<String, String> sync(Harvester harvester) throws Exception {
        JobStatus sync = harvester.await(harvester.sync("parity", "parity").id(), Duration.ofSeconds(60));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages();
        return pages.stream().collect(Collectors.toMap(this::path, page -> new String(harvester.pages()
                .page(harvester.caller(), "parity", page.header().key().pageId(), false).snapshot().payload().bytes(),
                StandardCharsets.UTF_8)));
    }

    /** Dateiname aus dem Titel; auf der Paritäts-Site sind die Namen eindeutig. */
    private String path(ListedPage page) {
        String title = page.header().metadata().title();
        return ParitySharePoint.FILES.keySet().stream().filter(path -> path.endsWith("/" + title))
                .findFirst().orElse(title);
    }

    private static void awaitEnrichments(Harvester harvester) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            List<JobStatus> enrichments = harvester.jobs().list(Harvester.OWNER, JobType.ENRICHMENT);
            if (!enrichments.isEmpty() && enrichments.stream().allMatch(job -> job.finishedAt() != null)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Anreicherung läuft nach 30 s noch");
    }
}
