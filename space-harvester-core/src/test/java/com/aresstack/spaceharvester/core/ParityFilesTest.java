package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.ParityFiles;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Paritäts-Ordner der Dateiquelle (#193) läuft als Typ {@code files} durch Abgleich, Cache und Suche: jedes
 * Textdokument mit seinem Satz, {@code exclude} schließt den Entwurf aus.
 */
class ParityFilesTest {

    @TempDir
    Path data;

    @TempDir
    Path directory;

    @Test
    void everyFileAndSentenceArrivesExceptTheExcludedOne() throws Exception {
        ParityFiles.create(directory);
        try (Harvester harvester = harvester(Map.of("exclude", ParityFiles.EXCLUDE))) {
            Map<String, String> texts = sync(harvester);
            // Heute ohne ParityFiles.IMAGE: die Ordner-Quelle des Core führt Bilder noch nicht als Seite. Kommen
            // Bilddateien als Seite mit Asset und leerem Text, gehört IMAGE in diese Erwartung.
            assertThat(texts.keySet()).containsExactlyInAnyOrder(ParityFiles.DOCUMENTS);
            assertThat(String.join("\n", texts.values())).contains(ParityFiles.SENTENCES)
                    .doesNotContain(ParityFiles.EXCLUDED_SENTENCE);

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParityFiles.SEARCH), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::title).containsExactly("ablauf.md");
        }
    }

    private Harvester harvester(Map<String, String> options) {
        Map<String, String> all = new HashMap<>(options);
        all.put("directory", directory.toString());
        return Harvester.builder().source(new SourceSettingsConfig("parity", "files", "Parität", null, null, all))
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

    /** Dateiname aus dem Titel; im Paritäts-Ordner sind die Namen eindeutig. */
    private String path(ListedPage page) {
        String title = page.header().metadata().title();
        return ParityFiles.FILES.keySet().stream().filter(path -> path.endsWith("/" + title) || path.equals(title))
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
