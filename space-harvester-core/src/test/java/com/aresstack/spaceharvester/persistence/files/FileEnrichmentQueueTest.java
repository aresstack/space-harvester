package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentQueueStore;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentQueueStore.Entry;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentQueueStore.Saved;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Warteschlange der KI-Anreicherung als Dateien: nur Schlüssel, atomar ersetzt. Nur erfundene Quellen. */
class FileEnrichmentQueueTest {

    @TempDir
    Path directory;

    @Test
    void savesOnlyKeysAndReadsThemBack() throws Exception {
        FileEnrichmentQueue queue = new FileEnrichmentQueue(directory.resolve("queue"));
        Saved saved = new Saved("g-1", "wiki space", "job-7",
                List.of(new Entry("remote", "Seite mit Leerzeichen & %", false), new Entry("embedding", "2", true)),
                List.of(new Entry("remote", "kaputt", false)));

        queue.save(saved);

        assertThat(queue.all()).containsExactly(saved);
        try (Stream<Path> files = Files.list(directory.resolve("queue"))) {
            assertThat(files).as("keine temporären Reste").singleElement()
                    .satisfies(file -> assertThat(file.getFileName().toString()).isEqualTo("g-1.queue"));
        }
    }

    @Test
    void aNewStateReplacesTheOldOneWhole() {
        FileEnrichmentQueue queue = new FileEnrichmentQueue(directory);
        queue.save(new Saved("g-1", "wiki", "job-7", List.of(new Entry("remote", "1", false),
                new Entry("remote", "2", false)), List.of()));
        queue.save(new Saved("g-1", "wiki", "job-7", List.of(new Entry("remote", "2", false)), List.of()));

        assertThat(queue.all()).singleElement()
                .satisfies(saved -> assertThat(saved.work()).extracting(Entry::pageId).containsExactly("2"));
    }

    @Test
    void removingASourceDeletesOnlyItsGroups() {
        FileEnrichmentQueue queue = new FileEnrichmentQueue(directory);
        queue.save(new Saved("g-1", "wiki", "job-1", List.of(new Entry("remote", "1", false)), List.of()));
        queue.save(new Saved("g-2", "ops", "job-2", List.of(), List.of(new Entry("remote", "1", false))));
        queue.save(new Saved("g-3", "wiki", "job-3", List.of(), List.of(new Entry("remote", "4", false))));

        queue.removeSource("wiki");

        assertThat(queue.all()).extracting(Saved::groupId).containsExactly("g-2");
        queue.delete("g-2");
        assertThat(queue.all()).isEmpty();
    }

    @Test
    void anUnreadableFileIsLeftOutAndAMissingDirectoryIsEmpty() throws Exception {
        FileEnrichmentQueue missing = new FileEnrichmentQueue(directory.resolve("fehlt"));
        assertThat(missing.all()).isEmpty();

        FileEnrichmentQueue queue = new FileEnrichmentQueue(directory);
        queue.save(new Saved("g-1", "wiki", "job-1", List.of(new Entry("remote", "1", false)), List.of()));
        Files.writeString(directory.resolve("g-2.queue"), "irgendwas\n", StandardCharsets.UTF_8);

        assertThat(queue.all()).extracting(Saved::groupId).containsExactly("g-1");
    }

    @Test
    void theNoneStoreKeepsNothing() {
        EnrichmentQueueStore.NONE.save(new Saved("g-1", "wiki", "job-1", List.of(), List.of()));
        assertThat(EnrichmentQueueStore.NONE.all()).isEmpty();
    }
}
