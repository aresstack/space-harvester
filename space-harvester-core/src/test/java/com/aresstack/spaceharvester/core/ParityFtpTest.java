package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.ParityFtp;
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
 * Der Paritäts-Server der FTP-Quelle (#193) läuft als Typ {@code ftp} durch Abgleich, Cache und Suche: jede Datei
 * unter dem Startpunkt mit ihrem Satz, anonym, ohne Netzdienst.
 */
class ParityFtpTest {

    @TempDir
    Path data;

    @Test
    void everyFileAndSentenceArrives() throws Exception {
        try (ParityFtp server = ParityFtp.start(); Harvester harvester = harvester(server)) {
            Map<String, String> texts = sync(harvester);
            assertThat(texts.keySet()).containsExactlyInAnyOrder(ParityFtp.DOCUMENTS);
            assertThat(String.join("\n", texts.values())).contains(ParityFtp.SENTENCES);

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParityFtp.SEARCH), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::title).containsExactly("ablauf.txt");
        }
    }

    @Test
    void aShallowDepthStopsBeforeTheNestedFiles() throws Exception {
        try (ParityFtp server = ParityFtp.start(); Harvester harvester = harvester(server, ParityFtp.DEPTH - 1)) {
            assertThat(sync(harvester).keySet()).containsExactlyInAnyOrder("/parity/README.txt",
                    "/parity/docs/architektur.txt", "/parity/docs/betrieb/handbuch.txt", "/parity/notizen.txt");
        }
    }

    private Harvester harvester(ParityFtp server) {
        return harvester(server, ParityFtp.DEPTH);
    }

    private Harvester harvester(ParityFtp server, int depth) {
        Map<String, String> options = Map.of("host", server.host(), "port", String.valueOf(server.port()),
                "startPoints", ParityFtp.START_POINT, "maxDepth", String.valueOf(depth));
        return Harvester.builder().source(new SourceSettingsConfig("parity", "ftp", "Parität", null, null, options))
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

    /** Dateiname aus dem Titel; auf dem Paritäts-Server sind die Namen eindeutig. */
    private String path(ListedPage page) {
        String title = page.header().metadata().title();
        return ParityFtp.FILES.keySet().stream().filter(path -> path.endsWith("/" + title) || path.equals(title))
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
