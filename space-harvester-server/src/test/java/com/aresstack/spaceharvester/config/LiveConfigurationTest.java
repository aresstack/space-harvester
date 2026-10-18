package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.api.ConfigurationOverlay;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.ranking.SwappableReranker;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Konfiguration zur Laufzeit im Server (ADR 0015): Nach dem Aktivieren gelten Regeln je Quelle, Freigabe an den
 * KI-Dienst, Reranker und Zeitplan sofort, ohne Neustart. Jeder Test beginnt mit eigenem Kontext bei Version 1.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LiveConfigurationTest {

    private static final Path CACHE = Path.of("build", "live-configuration-test", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.cache.directory", () -> CACHE.resolve(UUID.randomUUID().toString()).toString());
        registry.add("space-harvester.ai.base-url", () -> "https://ki.example.org/v1");
    }

    @Autowired
    private ConfigurationManager manager;

    @Autowired
    private LiveConfiguration live;

    @Autowired
    private SwappableReranker reranker;

    @Autowired
    private Reconciliation reconciliation;

    @Test
    void rulesPerSourceAndTheReleaseToTheAiServiceFollowTheConfiguration() {
        assertThat(live.freshnessFor("wiki").maxAge()).isEqualTo(Duration.ZERO);
        assertThat(live.mayProcessExternally("wiki")).isFalse();

        activate(Map.of("cache.max-age", "PT10M", "sync.sources.wiki.withheld-retention", "P2D",
                "ai.external-processing-sources", List.of("wiki")));

        assertThat(live.freshnessFor("wiki").maxAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(live.withheldRetentionFor("wiki")).isEqualTo(Duration.ofDays(2));
        assertThat(live.mayProcessExternally("wiki")).isTrue();
    }

    @Test
    void theRerankerSwitchesOnAndOffWithoutRestart() {
        assertThat(reranker.current()).isSameAs(SearchReranker.NONE);

        activate(Map.of("ai.rerank-model", "bge-reranker"));
        assertThat(reranker.current()).isInstanceOf(ModelReranker.class);

        activate(Map.of("search.rerank", "false"));
        assertThat(reranker.current()).isSameAs(SearchReranker.NONE);
    }

    @Test
    void theScheduleFollowsSpacesIntervalAndModePerSource() {
        assertThat(reconciliation.schedules()).isEmpty();

        activate(Map.of("sync.spaces", List.of("wiki/DEV"), "sync.interval", "PT30M"));
        assertThat(reconciliation.schedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.spaceKey()).isEqualTo("DEV");
            assertThat(schedule.interval()).isEqualTo(Duration.ofMinutes(30));
            assertThat(schedule.mode()).isEqualTo(SyncMode.CATALOG_ONLY);
        });

        activate(Map.of("sync.sources.wiki.interval", "PT2H", "sync.sources.wiki.mode", "sync-cache"));
        assertThat(reconciliation.schedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.interval()).isEqualTo(Duration.ofHours(2));
            assertThat(schedule.mode()).isEqualTo(SyncMode.SYNC_CACHE);
        });

        // Eine unbekannte Quelle lehnt die Änderung ab; der bisherige Zeitplan bleibt.
        ConfigurationManager.Activation rejected = manager.apply(ConfigurationOverlay.overlay(
                manager.current().configuration(), Map.of("sync.spaces", List.of("nope/X"))).orThrow());
        assertThat(rejected.activated()).isFalse();
        assertThat(reconciliation.schedules()).extracting(Reconciliation.Schedule::spaceKey).containsExactly("DEV");
    }

    private void activate(Map<String, ?> values) {
        ConfigurationManager.Activation activation = manager.apply(ConfigurationOverlay.overlay(
                manager.current().configuration(), values).orThrow());
        assertThat(activation.activated()).as(activation.problems().toString()).isTrue();
    }
}
