package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.ParityFtp;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Paritäts-Server im JES-Modus (#193) läuft als Typ {@code jes} durch Abgleich, Cache und Suche: jeder Job des
 * angemeldeten Benutzers mit seiner Ausgabe, angemeldet über den Anbieter des Einbetters.
 */
class ParityJesTest {

    @TempDir
    Path data;

    @Test
    void everyJobOfTheUserArrives() throws Exception {
        List<String> asked = new ArrayList<>();
        try (ParityFtp server = ParityFtp.start(); Harvester harvester = harvester(server, asked, Map.of())) {
            Map<String, String> texts = sync(harvester);
            assertThat(texts.keySet()).containsExactlyInAnyOrder(ParityFtp.JOB_IDS);
            assertThat(String.join("\n", texts.values())).contains(ParityFtp.JOB_SENTENCES);
            assertThat(asked).isNotEmpty();

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParityFtp.SEARCH), null, 10);
            assertThat(found.hits()).singleElement()
                    .satisfies(hit -> assertThat(hit.title()).contains(ParityFtp.JOB_SEARCH_HIT));
        }
    }

    @Test
    void theJobNameFilterNarrowsTheJobs() throws Exception {
        try (ParityFtp server = ParityFtp.start();
             Harvester harvester = harvester(server, new ArrayList<>(), Map.of("startPoints", "ZAUF*"))) {
            assertThat(sync(harvester).keySet()).containsExactlyInAnyOrder("JOB00001", "JOB00002");
        }
    }

    private Harvester harvester(ParityFtp server, List<String> asked, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(extra);
        options.put("host", server.host());
        options.put("port", String.valueOf(server.port()));
        options.put("credentialRef", "keepass:paritaet");
        SourceSecrets secrets = ref -> {
            asked.add(ref);
            return Optional.of(new SourceSecrets.Credential("paritaet", "nur-fuer-den-test".toCharArray()));
        };
        return Harvester.builder().secretProvider(secrets)
                .source(new SourceSettingsConfig("parity", "jes", "Parität", null, null, options))
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

    /** Job-ID aus dem Titel, etwa {@code ZAUF01 (JOB00001)}. */
    private String path(ListedPage page) {
        String title = page.header().metadata().title();
        for (String jobId : ParityFtp.JOB_IDS) {
            if (title.contains(jobId)) {
                return jobId;
            }
        }
        return title;
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
