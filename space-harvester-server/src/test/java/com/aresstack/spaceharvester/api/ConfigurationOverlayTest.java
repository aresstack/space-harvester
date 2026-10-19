package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationOverlayTest {

    private static final HarvesterConfiguration FULL = new HarvesterConfiguration(
            new AiServiceSettings(URI.create("https://ki.example.org/v1"), "geheim", "chat", "embed", 768,
                    Duration.ofSeconds(45), "ki-bundle", Set.of("wiki", "docs"), "rerank"),
            new SearchSettings(true, false, false, new CacheFreshness(Duration.ofHours(6), false)),
            new SyncSettings(true, Duration.ofDays(1), List.of("wiki/DEV", "wiki/OPS"), true, Duration.ofDays(7), true,
                    SyncMode.SYNC_CACHE),
            new NetworkSettings(new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "proxy.example", 3128,
                    List.of("*.intern"), null, null, null), TrustSettings.system()),
            new StorageSettings(Path.of("data", "cache")))
            .withLocalFiles(new LocalFileSettings(true, List.of(Path.of("/srv/wissen, alt"), Path.of("/mnt/doku"))));

    @Test
    void propertiesRoundTripWithoutNetwork() {
        HarvesterConfiguration.Parsed parsed = HarvesterConfiguration.fromProperties(
                ConfigurationOverlay.toProperties(FULL));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.configuration()).isEqualTo(FULL.withNetwork(NetworkSettings.DEFAULT));
    }

    @Test
    void grantedFoldersAreSeparatedBySemicolonsOrGivenAsAList(@TempDir Path folder) throws IOException {
        Path comma = Files.createDirectory(folder.resolve("Wissen, alt"));
        HarvesterConfiguration text = ConfigurationOverlay.overlay(HarvesterConfiguration.DEFAULT, Map.of(
                "local-files.roots", comma + ";" + folder, "local-files.allow-all", false)).orThrow();
        HarvesterConfiguration list = ConfigurationOverlay.overlay(HarvesterConfiguration.DEFAULT, Map.of(
                "space-harvester.local-files.roots", List.of(comma.toString(), folder.toString()))).orThrow();
        HarvesterConfiguration indexed = ConfigurationOverlay.overlay(text, Map.of(
                "local-files.roots[0]", folder.toString())).orThrow();

        assertThat(text.localFiles().roots()).containsExactly(comma, folder);
        assertThat(list.localFiles()).isEqualTo(text.localFiles());
        assertThat(indexed.localFiles().roots()).containsExactly(folder);
        assertThat(ConfigurationOverlay.overlay(text, Map.of("local-files.roots", "")).orThrow().localFiles())
                .isEqualTo(LocalFileSettings.DEFAULT);
    }

    @Test
    void aGrantedFolderMustBeAbsoluteButMayNotExistYetOrBeAFile(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("geheim-liste.txt"), "x");
        Path missing = folder.resolve("noch-nicht-verbunden");

        HarvesterConfiguration.Parsed relative = ConfigurationOverlay.overlay(FULL,
                Map.of("local-files.roots", "relativ/geheim-ordner"));

        assertThat(relative.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.key()).isEqualTo(LocalFileSettings.ROOTS);
            assertThat(problem.message()).doesNotContain("geheim-ordner");
        });
        // Wie im Client gewährt eine Datei einfach nichts; geprüft wird ohne Dateisystem.
        assertThat(ConfigurationOverlay.overlay(FULL, Map.of("local-files.roots", file.toString())).orThrow()
                .localFiles().roots()).containsExactly(file);
        assertThat(ConfigurationOverlay.overlay(FULL, Map.of("local-files.roots", missing.toString())).orThrow()
                .localFiles().roots()).containsExactly(missing);
    }

    @Test
    void overlayKeepsUnnamedValuesTheApiKeyAndTheNetwork() {
        HarvesterConfiguration next = ConfigurationOverlay.overlay(FULL,
                Map.of("space-harvester.search.semantic", false, "ai.embedding-dimensions", 1024)).orThrow();

        assertThat(next.search().semantic()).isFalse();
        assertThat(next.ai().embeddingDimensions()).isEqualTo(1024);
        assertThat(next.ai().apiKey()).isEqualTo("geheim");
        assertThat(next.network()).isEqualTo(FULL.network());
        assertThat(next.sync()).isEqualTo(FULL.sync());
    }

    @Test
    void emptyOrNullResetsAndIndexedListsReplace() {
        Map<String, Object> values = new HashMap<>();
        values.put("ai.rerank-model", "");
        values.put("sync.interval", null);
        values.put("sync.spaces[0]", "docs/HR");
        HarvesterConfiguration next = ConfigurationOverlay.overlay(FULL, values).orThrow();

        assertThat(next.ai().rerankModel()).isNull();
        assertThat(next.sync().interval()).isNull();
        assertThat(next.sync().spaces()).containsExactly("docs/HR");
    }

    @Test
    void sourceRulesRoundTripAndReplaceInBothSpellings() {
        HarvesterConfiguration first = ConfigurationOverlay.overlay(FULL, Map.of(
                "sync.sources[wiki].interval", "PT30M", "sync.sources.wiki.refresh-strategy", "time-based",
                "sync.sources.wiki.max-age", "PT2H")).orThrow();
        HarvesterConfiguration second = ConfigurationOverlay.overlay(first, Map.of(
                "sync.sources.wiki.interval", "PT1H", "sync.sources[wiki].max-age", "")).orThrow();

        assertThat(HarvesterConfiguration.fromProperties(ConfigurationOverlay.toProperties(first)).orThrow()
                .withNetwork(FULL.network())).isEqualTo(first);
        assertThat(second.syncFor("wiki").interval()).isEqualTo(Duration.ofHours(1));
        assertThat(second.sync().override("wiki").maxAge()).isNull();
        assertThat(second.freshnessFor("wiki").versionCheck()).isFalse();
    }

    @Test
    void backgroundFillRoundTripsGloballyAndPerSource() {
        HarvesterConfiguration next = ConfigurationOverlay.overlay(FULL, Map.of(
                "sync.background-fill", false, "space-harvester.sync.background-parallelism", 3,
                "sync.sources[wiki].background-fill", true)).orThrow();

        assertThat(next.sync().backgroundFill()).isFalse();
        assertThat(next.sync().backgroundParallelism()).isEqualTo(3);
        assertThat(next.syncFor("wiki").backgroundFill()).isTrue();
        assertThat(next.syncFor("docs").backgroundFill()).isFalse();
        assertThat(HarvesterConfiguration.fromProperties(ConfigurationOverlay.toProperties(next)).orThrow()
                .withNetwork(FULL.network())).isEqualTo(next);
        assertThat(ConfigurationOverlay.overlay(next, Map.of("sync.background-parallelism", 0)).problems())
                .extracting(ConfigurationProblem::key).containsExactly("sync.background-parallelism");
    }

    @Test
    void unknownKeysNetworkAndObjectsAreProblems() {
        HarvesterConfiguration.Parsed parsed = ConfigurationOverlay.overlay(FULL, Map.of("ai.model", "x",
                "network.proxy.mode", "disabled", "search.rerank", Map.of("a", "b")));

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.problems()).extracting(ConfigurationProblem::key)
                .containsExactlyInAnyOrder("ai.model", "network.proxy.mode", "search.rerank");
    }

    @Test
    void invalidValuesAreCollectedPerKey() {
        HarvesterConfiguration.Parsed parsed = ConfigurationOverlay.overlay(HarvesterConfiguration.DEFAULT,
                Map.of("ai.embedding-model", "x", "search.semantic", "vielleicht"));

        assertThat(parsed.problems()).extracting(ConfigurationProblem::key)
                .contains("ai.base-url", "ai.embedding-dimensions", "search.semantic");
    }
}
