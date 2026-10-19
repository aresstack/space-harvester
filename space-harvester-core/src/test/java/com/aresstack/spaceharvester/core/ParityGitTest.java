package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.ParityGit;
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
 * Das Paritäts-Repository der Git-Quelle (#193) läuft als Typ {@code git} durch Abgleich, Cache und Suche: jedes
 * Textdokument mit seinem Satz, der Tag liefert den alten Stand, und die Commit-IDs sind auf jedem Rechner gleich.
 */
class ParityGitTest {

    @TempDir
    Path data;

    @TempDir
    Path repositories;

    @Test
    void theRepositoryIsDeterministic() {
        assertThat(ParityGit.create(repositories.resolve("a"))).isEqualTo(ParityGit.create(repositories.resolve("b")));
    }

    @Test
    void everyDocumentAndSentenceArrivesThroughTheGitSource() throws Exception {
        Path repository = repositories.resolve("parity.git");
        ParityGit.create(repository);
        try (Harvester harvester = harvester(repository, Map.of())) {
            Map<String, String> texts = sync(harvester);
            assertThat(texts.keySet()).containsExactlyInAnyOrderElementsOf(ParityGit.FILES.keySet());
            assertThat(String.join("\n", texts.values())).contains(ParityGit.SENTENCES)
                    .doesNotContain(ParityGit.OLD_SENTENCE);

            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of(ParityGit.SEARCH), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::title).containsExactly("ablauf.md");
        }
    }

    @Test
    void theTagShowsTheFirstVersion() throws Exception {
        Path repository = repositories.resolve("parity.git");
        ParityGit.create(repository);
        try (Harvester harvester = harvester(repository, Map.of("ref", ParityGit.TAG))) {
            assertThat(String.join("\n", sync(harvester).values())).contains(ParityGit.OLD_SENTENCE)
                    .doesNotContain("Satz Charlie zum Betrieb.");
        }
    }

    private Harvester harvester(Path repository, Map<String, String> options) {
        Map<String, String> all = new java.util.HashMap<>(options);
        all.put("url", repository.toString());
        return Harvester.builder().source(new SourceSettingsConfig("parity", "git", "Parität", null, null, all))
                .dataDirectory(data).build();
    }

    /** Pfad je Textdokument (aus der Adresse) und sein Text. */
    private static Map<String, String> sync(Harvester harvester) throws Exception {
        JobStatus sync = harvester.await(harvester.sync("parity", "parity").id(), Duration.ofSeconds(60));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages();
        return pages.stream().collect(Collectors.toMap(ParityGitTest::path, page -> new String(harvester.pages()
                .page(harvester.caller(), "parity", page.header().key().pageId(), false).snapshot().payload().bytes(),
                StandardCharsets.UTF_8)));
    }

    private static String path(ListedPage page) {
        String id = new String(java.util.Base64.getUrlDecoder().decode(page.header().key().pageId()),
                StandardCharsets.UTF_8);
        return id.substring(id.indexOf("/parity/") + "/parity/".length());
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
