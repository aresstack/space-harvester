package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationOverlayTest {

    private static final HarvesterConfiguration FULL = new HarvesterConfiguration(
            new AiServiceSettings(URI.create("https://ki.example.org/v1"), "geheim", "chat", "embed", 768,
                    Duration.ofSeconds(45), "ki-bundle", Set.of("wiki", "docs"), "rerank"),
            new SearchSettings(true, false, false, new CacheFreshness(Duration.ofHours(6), false)),
            new SyncSettings(true, Duration.ofDays(1), List.of("wiki/DEV", "wiki/OPS"), true, Duration.ofDays(7)),
            new NetworkSettings(new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "proxy.example", 3128,
                    List.of("*.intern"), null, null, null), TrustSettings.system()),
            new StorageSettings(Path.of("data", "cache")));

    @Test
    void propertiesRoundTripWithoutNetwork() {
        HarvesterConfiguration.Parsed parsed = HarvesterConfiguration.fromProperties(
                ConfigurationOverlay.toProperties(FULL));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.configuration()).isEqualTo(FULL.withNetwork(NetworkSettings.DEFAULT));
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
