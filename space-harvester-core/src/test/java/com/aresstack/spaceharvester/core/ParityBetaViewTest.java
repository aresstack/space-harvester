package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.ParityBetaView;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Paritäts-Oberfläche der BetaView-Quelle (#193) läuft als Typ {@code betaview} durch Abgleich, Cache und Suche:
 * jedes Dokument der Ergebnisliste mit seinem Satz, angemeldet über den Anbieter des Einbetters.
 */
class ParityBetaViewTest {

    @TempDir
    Path data;

    @Test
    void everyDocumentOfTheFavoriteArrives() throws Exception {
        List<String> asked = new ArrayList<>();
        try (ParityBetaView server = ParityBetaView.start(); Harvester harvester = harvester(server, asked)) {
            Map<String, String> texts = sync(harvester);
            assertThat(texts.keySet()).containsExactlyInAnyOrder(ParityBetaView.DOCUMENT_IDS);
            assertThat(String.join("\n", texts.values())).contains(ParityBetaView.SENTENCES);
            assertThat(asked).isNotEmpty();

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParityBetaView.SEARCH), null, 10);
            assertThat(found.hits()).singleElement().satisfies(hit -> assertThat(hit.title()).contains("NACHT03"));
        }
    }

    private Harvester harvester(ParityBetaView server, List<String> asked) {
        Map<String, String> options = Map.of("baseUrl", server.baseUrl(), "credentialRef", "keepass:paritaet",
                "favoriteId", ParityBetaView.FAVORITE_ID);
        SourceSecrets secrets = ref -> {
            asked.add(ref);
            return Optional.of(new SourceSecrets.Credential("paritaet", "nur-fuer-den-test".toCharArray()));
        };
        return Harvester.builder().secretProvider(secrets)
                .source(new SourceSettingsConfig("parity", "betaview", "Parität", null, null, options))
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

    /** Dokument-ID aus dem Titel; auf der Paritäts-Oberfläche sind die Titel eindeutig. */
    private String path(ListedPage page) {
        String title = page.header().metadata().title();
        for (int i = 0; i < ParityBetaView.TITLES.length; i++) {
            if (title.contains(ParityBetaView.TITLES[i])) {
                return ParityBetaView.DOCUMENT_IDS[i];
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
