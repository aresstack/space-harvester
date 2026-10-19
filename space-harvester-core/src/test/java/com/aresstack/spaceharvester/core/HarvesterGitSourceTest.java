package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Git-Quelle des Enterprise AI Client (#187) ist als Typ {@code git} registriert: ein lokales Repository läuft als
 * eine Partition durch Abgleich, Katalog, Cache und Suche; Anmeldung gibt es auf diesem Weg noch nicht.
 */
class HarvesterGitSourceTest {

    @TempDir
    Path data;

    @TempDir
    Path repository;

    @Test
    void aGitRepositoryIsSyncedCachedAndSearchedAsOnePartition() throws Exception {
        Files.writeString(repository.resolve("README.md"), "# Betrieb\n\nDer Nachtlauf startet um zwei Uhr.\n");
        Files.createDirectories(repository.resolve("docs"));
        Files.writeString(repository.resolve("docs/urlaub.md"), "# Urlaub\n\nUrlaubsplanung für das Team.\n");
        try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            // Unabhängig von der Git-Konfiguration des Rechners (Signieren, gpg.format=ssh, das JGit 5 nicht kennt).
            StoredConfig config = git.getRepository().getConfig();
            config.setString("gpg", null, "format", "openpgp");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Start").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").setSign(false).call();
        }

        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("repo", "git", "Repo", null, null,
                        Map.of("url", repository.toString())))
                .dataDirectory(data).build()) {
            assertThat(harvester.sources().partitions("repo")).extracting(Partition::key).containsExactly("repo");

            JobStatus sync = harvester.await(harvester.sync("repo", "repo").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "repo", "repo", null, 100).pages();
            assertThat(pages).extracting(page -> page.header().metadata().title())
                    .contains("README.md", "urlaub.md");
            String readme = pages.stream().filter(page -> page.header().metadata().title().equals("README.md"))
                    .findFirst().orElseThrow().header().key().pageId();
            assertThat(new String(harvester.pages().page(harvester.caller(), "repo", readme, false).snapshot()
                    .payload().bytes(), StandardCharsets.UTF_8)).contains("Der Nachtlauf startet");

            // Den Volltextindex füllt nach dem Abgleich ein eigener Anreicherungsauftrag.
            awaitEnrichments(harvester);
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(readme);
        }
        assertThat(data.resolve("git")).isDirectory();
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

    @Test
    void aCredentialRefIsAcceptedAndResolvedOnlyWhenFetching() throws Exception {
        SourceSettingsConfig withCredential = new SourceSettingsConfig("repo", "git", "Repo", null, null,
                Map.of("url", "https://example.org/repo.git", "credentialRef", "keepass:repo"));
        List<String> asked = new java.util.ArrayList<>();
        try (Harvester harvester = Harvester.builder().source(withCredential).dataDirectory(data)
                .secretProvider(ref -> {
                    asked.add(ref);
                    return java.util.Optional.empty();
                }).build()) {
            assertThat(harvester.sources().sources()).extracting(source -> source.id()).contains("repo");
        }
        // Beim Öffnen wird nichts aufgelöst; erst ein Abruf fragt den Anbieter.
        assertThat(asked).isEmpty();
    }
}
