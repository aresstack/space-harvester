package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Start ohne Aufträge ohne Klick (Angelo 24.11.2026: „Ich starte ihn und er fängt automatisch mit irgendwelchen Jobs
 * an, obwohl ich vorher alle gekillt habe“): Die App baut den Harvester mit {@code autoStart(false)}.
 */
class HarvesterAutoStartTest {

    @TempDir
    Path data;
    @TempDir
    Path directory;

    @Test
    void withoutAutoStartNoBackgroundGroupStartsWithTheHarvester() {
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data).autoStart(false).build()) {
            assertThat(harvester.backgroundJobs().groups()).isEmpty();
        }
    }

    @Test
    void theServiceStillReconcilesAtStart() {
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data).build()) {
            assertThat(harvester.backgroundJobs().groups()).extracting(com.aresstack.spaceharvester.application.background.BackgroundGroup::title)
                    .contains(PageLinks.BACKGROUND_TITLE);
        }
    }

    private SourceSettingsConfig docs() {
        return new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                Map.of("directory", directory.toString()));
    }
}
