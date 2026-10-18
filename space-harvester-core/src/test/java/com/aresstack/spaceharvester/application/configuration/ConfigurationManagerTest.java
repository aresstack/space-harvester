package com.aresstack.spaceharvester.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationManagerTest {

    private static final AiServiceSettings AI = new AiServiceSettings(URI.create("https://ki.example.org/v1"), "geheim",
            null, "embed-a", 768, null, null, Set.of("wiki"), "rerank-a");

    private final ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT.withAi(AI));

    @Test
    void startsWithVersionOne() {
        assertThat(manager.current().version()).isEqualTo(1);
        assertThat(manager.current().configuration().ai().embeddingModel()).isEqualTo("embed-a");
    }

    @Test
    void activatesANewModelAsNextVersion() {
        List<ConfigurationChange> seen = new ArrayList<>();
        manager.register(change -> {
            seen.add(change);
            return ConfigurationListener.Preparation.NOTHING;
        });

        var activation = manager.apply(manager.current().configuration().withAi(AI.withRerankModel("rerank-b")));

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.ACTIVATED);
        assertThat(activation.snapshot().version()).isEqualTo(2);
        assertThat(manager.current().configuration().ai().rerankModel()).isEqualTo("rerank-b");
        assertThat(seen).singleElement().satisfies(change -> {
            assertThat(change.rerankerChanged()).isTrue();
            assertThat(change.embeddingChanged()).isFalse();
            assertThat(change.vectorIndex()).isEmpty();
        });
    }

    @Test
    void theNewVersionBecomesVisibleOnlyAfterAllCommits() {
        List<Long> seenDuringCommit = new ArrayList<>();
        manager.register(change -> () -> seenDuringCommit.add(manager.current().version()));

        manager.apply(manager.current().configuration().withAi(AI.withRerankModel("rerank-b")));

        assertThat(seenDuringCommit).containsExactly(1L);
        assertThat(manager.current().version()).isEqualTo(2);
    }

    @Test
    void refreshStrategiesMapToCacheFreshness() {
        assertThat(SearchSettings.DEFAULT.refreshStrategy()).isEqualTo(RefreshStrategy.ALWAYS_CHECK_VERSION);
        assertThat(RefreshStrategy.TIME_BASED.freshness(Duration.ofHours(6)).versionCheck()).isFalse();
        assertThat(RefreshStrategy.of(RefreshStrategy.TIME_BASED.freshness(Duration.ofHours(6))))
                .isEqualTo(RefreshStrategy.TIME_BASED);
        assertThat(RefreshStrategy.parse("time-based-with-version-check"))
                .isEqualTo(RefreshStrategy.TIME_BASED_WITH_VERSION_CHECK);
        assertThat(SyncSettings.DEFAULT.events() || SyncSettings.DEFAULT.catchUpOnStart()
                || SyncSettings.DEFAULT.beforeSearch() || SyncSettings.DEFAULT.mode() != SyncMode.CATALOG_ONLY
                || SyncSettings.DEFAULT.interval() != null).isFalse();
    }

    @Test
    void sourcesOverrideTheGlobalSyncRules() {
        Properties properties = new Properties();
        properties.setProperty("space-harvester.sync.interval", "P1D");
        properties.setProperty("space-harvester.cache.max-age", "PT6H");
        properties.setProperty("space-harvester.sync.sources.wiki.interval", "PT30M");
        properties.setProperty("space-harvester.sync.sources.wiki.refresh-strategy", "time-based");
        properties.setProperty("space-harvester.sync.sources[team].inventory-interval", "P7D");
        properties.setProperty("space-harvester.sync.sources[team].mode", "sync-cache");

        HarvesterConfiguration read = HarvesterConfiguration.fromProperties(properties).orThrow();

        assertThat(read.syncFor("wiki").interval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(read.freshnessFor("wiki").versionCheck()).isFalse();
        assertThat(read.freshnessFor("wiki").maxAge()).isEqualTo(Duration.ofHours(6));
        assertThat(read.syncFor("team").interval()).isEqualTo(Duration.ofDays(1));
        assertThat(read.syncFor("team").inventoryInterval()).isEqualTo(Duration.ofDays(7));
        assertThat(read.syncFor("team").mode()).isEqualTo(SyncMode.SYNC_CACHE);
        assertThat(read.syncFor("andere")).isEqualTo(read.sync().forSource("andere"));
        assertThat(read.freshnessFor("andere")).isEqualTo(read.search().cacheFreshness());

        Properties broken = new Properties();
        broken.setProperty("sync.sources.wiki.interval", "10s");
        broken.setProperty("sync.sources.wiki.refresh-strategy", "time-based");
        broken.setProperty("sync.sources.wiki.farbe", "blau");
        broken.setProperty("sync.sources.team.max-age", "P400D");

        assertThat(HarvesterConfiguration.fromProperties(broken).problems()).extracting(ConfigurationProblem::key)
                .containsExactlyInAnyOrder("sync.sources.wiki.interval", "sync.sources.wiki.max-age",
                        "sync.sources.wiki.farbe", "sync.sources.team.max-age");
    }

    @Test
    void sameConfigurationChangesNothing() {
        var activation = manager.apply(manager.current().configuration());

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.UNCHANGED);
        assertThat(manager.current().version()).isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidConfigurationAndKeepsTheOldOne() {
        var activation = manager.apply(manager.current().configuration()
                .withAi(AI.withEmbedding("embed-b", null)));

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.REJECTED);
        assertThat(activation.problems()).extracting(ConfigurationProblem::key)
                .containsExactly("ai.embedding-dimensions");
        assertThat(manager.current().version()).isEqualTo(1);
        assertThat(manager.current().configuration().ai().embeddingModel()).isEqualTo("embed-a");
    }

    @Test
    void aListenerRejectingRollsBackTheOthers() {
        List<String> events = new ArrayList<>();
        manager.register(change -> new ConfigurationListener.Preparation() {
            @Override
            public void commit() {
                events.add("commit");
            }

            @Override
            public void rollback() {
                events.add("rollback");
            }
        });
        manager.register(change -> {
            throw new ConfigurationRejectedException("ai.rerank-model", "Der KI-Dienst kennt das Modell nicht");
        });

        var activation = manager.apply(manager.current().configuration().withAi(AI.withRerankModel("gibt-es-nicht")));

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.REJECTED);
        assertThat(activation.problems()).extracting(ConfigurationProblem::message)
                .containsExactly("Der KI-Dienst kennt das Modell nicht");
        assertThat(events).containsExactly("rollback");
        assertThat(manager.current().configuration().ai().rerankModel()).isEqualTo("rerank-a");
    }

    @Test
    void aFailingListenerDoesNotLeakItsMessage() {
        manager.register(change -> {
            throw new IllegalStateException("https://intern.example.org?token=geheim");
        });

        var activation = manager.apply(manager.current().configuration().withAi(AI.withRerankModel("x")));

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.REJECTED);
        assertThat(activation.problems().toString()).doesNotContain("geheim").contains("IllegalStateException");
    }

    @Test
    void storageChangesOnlyAfterARestart() {
        var activation = manager.apply(manager.current().configuration()
                .withStorage(new StorageSettings(Path.of("anderswo"))));

        assertThat(activation.outcome()).isEqualTo(ConfigurationManager.Outcome.UNCHANGED);
        assertThat(activation.restartRequired()).containsExactly("cache.directory");
        assertThat(manager.current().configuration().storage()).isEqualTo(StorageSettings.DEFAULT);
    }

    @Test
    void embeddingModelChangeVersionsTheVectorIndex() {
        List<ConfigurationChange> seen = new ArrayList<>();
        manager.register(change -> {
            seen.add(change);
            return ConfigurationListener.Preparation.NOTHING;
        });

        manager.apply(manager.current().configuration().withAi(AI.withEmbedding("embed-b", 1024)));
        manager.apply(manager.current().configuration().withAi(AI.withEmbedding(null, null)));

        assertThat(seen.get(0).vectorIndex()).hasValueSatisfying(change -> {
            assertThat(change.from()).hasValue(new VectorIndexVersion("embed-a", 768));
            assertThat(change.to()).hasValue(new VectorIndexVersion("embed-b", 1024));
        });
        assertThat(seen.get(1).vectorIndex()).hasValueSatisfying(change -> assertThat(change.to()).isEmpty());
    }

    @Test
    void unregisteredListenersAreNotAsked() {
        List<ConfigurationChange> seen = new ArrayList<>();
        var registration = manager.register(change -> {
            seen.add(change);
            return ConfigurationListener.Preparation.NOTHING;
        });
        registration.close();

        manager.apply(manager.current().configuration().withSync(SyncSettings.DEFAULT.withInterval(Duration.ofDays(1))));

        assertThat(seen).isEmpty();
        assertThat(manager.current().configuration().sync().interval()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void semanticSearchNeedsTheCheckboxAndAModel() {
        HarvesterConfiguration withModel = HarvesterConfiguration.DEFAULT.withAi(AI);
        assertThat(withModel.semanticSearch()).isFalse();
        assertThat(withModel.withSearch(SearchSettings.DEFAULT.withSemantic(true)).semanticSearch()).isTrue();
        assertThat(HarvesterConfiguration.DEFAULT.withSearch(SearchSettings.DEFAULT.withSemantic(true))
                .semanticSearch()).isFalse();
    }

    @Test
    void rejectsAnInvalidStartConfiguration() {
        assertThatThrownBy(() -> new ConfigurationManager(HarvesterConfiguration.DEFAULT.withAi(
                new AiServiceSettings(null, null, null, "embed", 3, null, null, Set.of(), null))))
                .isInstanceOf(ConfigurationRejectedException.class)
                .hasMessageContaining("ai.base-url");
    }

    @Test
    void readsPropertiesAndCollectsAllProblems() {
        Properties properties = new Properties();
        properties.setProperty("space-harvester.ai.base-url", "https://ki.example.org/v1");
        properties.setProperty("space-harvester.ai.embedding-model", "embed-a");
        properties.setProperty("space-harvester.ai.embedding-dimensions", "768");
        properties.setProperty("space-harvester.search.semantic", "true");
        properties.setProperty("space-harvester.sync.interval", "1d");
        properties.setProperty("space-harvester.sync.spaces[0]", "wiki/DOC");
        properties.setProperty("cache.max-age", "PT6H");
        properties.setProperty("ai.timeout", "45000ms");
        properties.setProperty("sync.mode", "sync-cache");

        HarvesterConfiguration read = HarvesterConfiguration.fromProperties(properties).orThrow();

        assertThat(read.semanticSearch()).isTrue();
        assertThat(read.sync().interval()).isEqualTo(Duration.ofDays(1));
        assertThat(read.ai().timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(read.sync().spaces()).containsExactly("wiki/DOC");
        assertThat(read.sync().catchUpOnStart()).isFalse();
        assertThat(read.sync().mode()).isEqualTo(SyncMode.SYNC_CACHE);
        assertThat(read.search().refreshStrategy()).isEqualTo(RefreshStrategy.TIME_BASED_WITH_VERSION_CHECK);
        assertThat(read.search().cacheFreshness().maxAge()).isEqualTo(Duration.ofHours(6));

        Properties broken = new Properties();
        broken.setProperty("ai.timeout", "lange");
        broken.setProperty("search.semantic", "vielleicht");
        broken.setProperty("sync.spaces", "ohne-schraegstrich");
        broken.setProperty("sync.interval", "30s");
        broken.setProperty("cache.refresh-strategy", "time-based");
        broken.setProperty("sync.mode", "alles");

        var parsed = HarvesterConfiguration.fromProperties(broken);

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.problems()).extracting(ConfigurationProblem::key)
                .containsExactlyInAnyOrder("ai.timeout", "search.semantic", "sync.spaces", "sync.interval",
                        "cache.max-age", "sync.mode");
    }
}
