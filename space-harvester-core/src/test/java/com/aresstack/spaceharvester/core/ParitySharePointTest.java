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

    @Test
    void defaultInventoryAndLiveNavigationUseTheSamePartition() throws Exception {
        try (ParitySharePoint site = ParitySharePoint.start();
             Harvester harvester = Harvester.builder()
                     .source(new SourceSettingsConfig("parity", "sharepoint", "Parity", null, null,
                             Map.of("siteUrl", site.siteUrl())))
                     .dataDirectory(data).build()) {
            assertThat(harvester.sources().partitions("parity")).extracting(partition -> partition.key()).containsExactly("parity");
            var libraries = harvester.navigation().children(harvester.caller(), "parity", "parity", null, null);
            assertThat(libraries.pages()).extracting(node -> node.title()).containsExactly("Dokumente");
            var library = libraries.pages().get(0);
            var children = harvester.navigation().children(harvester.caller(), "parity", "parity",
                    library.pageId(), null);
            assertThat(children.spaceKey()).isEqualTo("parity");
            assertThat(children.pages()).extracting(node -> node.title())
                    .containsExactlyInAnyOrder("Betrieb", "Lies mich.txt", "Notizen.md", "bild.png");
            // Die vollständige Inventur bringt die Ordner mit (leer, Ordner-ID wie im Baum), damit jeder Ordner seinen
            // Teilbaum kennt (Angelo 26.11.2026: Zusammenfassen und Änderungen auf jedem Ordner).
            Map<String, String> synced = sync(harvester);
            List<String> expected = new java.util.ArrayList<>(List.of(ParitySharePoint.DOCUMENTS));
            expected.addAll(List.of("Dokumente", "Betrieb", "Nacht"));
            assertThat(synced.keySet()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(synced.get("Betrieb")).isEmpty();
            var folder = harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages().stream()
                    .filter(page -> page.header().metadata().title().equals("Betrieb")).findFirst().orElseThrow();
            assertThat(folder.header().key().pageId()).isEqualTo(children.pages().stream()
                    .filter(node -> node.title().equals("Betrieb")).findFirst().orElseThrow().pageId());
            assertThat(folder.header().metadata().parentPageId()).isEqualTo(library.pageId());
        }
    }

    @Test
    void changeLogReadsOnlyChangedMetadataUsingNativePost() throws Exception {
        try (ParitySharePoint site = ParitySharePoint.start();
             Harvester harvester = Harvester.builder()
                     .source(new SourceSettingsConfig("parity", "sharepoint", "Parity", null, null,
                             Map.of("siteUrl", site.siteUrl())))
                     .dataDirectory(data).build()) {
            assertThat(harvester.sources().listsChanges("parity")).isTrue();
            var delta = harvester.sources().changedPageMetadata("parity", "parity", Duration.ofHours(1), null, 10);
            assertThat(delta.entries()).extracting(entry -> entry.page().title()).containsExactly("Ablauf.txt");
            assertThat(delta.entries()).extracting(entry -> entry.page().partitionKey()).containsExactly("parity");
        }
    }

    @Test
    void downloadsLargeOriginalThroughHarvesterWithoutCache() throws Exception {
        byte[] bytes = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(bytes, (byte) 'x');
        String path = ParitySharePoint.LIBRARY + "/Large.txt";
        try (ParitySharePoint site = ParitySharePoint.withFile(path, bytes);
             Harvester harvester = harvester(site)) {
            var library = harvester.navigation().children(harvester.caller(), "parity", "parity", null, null).pages().get(0);
            var file = harvester.navigation().children(harvester.caller(), "parity", "parity", library.pageId(), null)
                    .pages().stream().filter(node -> "Large.txt".equals(node.title())).findFirst().orElseThrow();
            var metadata = harvester.sources().pageMetadata("parity", file.pageId()).entry().page();
            Path target = data.resolve("large-original.txt");
            harvester.snapshots().downloadOriginal("parity", metadata, target);
            assertThat(java.nio.file.Files.size(target)).isEqualTo(bytes.length);
            assertThat(java.nio.file.Files.readAllBytes(target)).isEqualTo(bytes);
            assertThat(harvester.snapshots().original("parity", metadata)).isEqualTo(bytes);
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
