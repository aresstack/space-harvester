package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceAdapter;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Was ein einbettendes Programm wie der Enterprise AI Client selbst einhängt (#188). */
class HarvesterExtensionsTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void credentialsMissingFromTheConfigurationComeFromTheProgramOnFirstUse() {
        List<String> asked = new ArrayList<>();
        SourceSecrets vault = ref -> {
            asked.add(ref);
            return Optional.of(new SourceSecrets.Credential(null, "aus-dem-tresor".toCharArray()));
        };
        SourceSettingsConfig withoutToken = new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, null),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));

        try (Harvester harvester = Harvester.builder().source(withoutToken).secretProvider(vault).dataDirectory(data)
                .build()) {
            assertThat(asked).isEmpty();

            assertThat(harvester.sources().partitions("wiki")).isNotEmpty();

            assertThat(asked).containsOnly("source:wiki");
            assertThat(CONFLUENCE.requestHeaders()).last().satisfies(headers -> assertThat(headers)
                    .containsEntry("Authorization", "Bearer aus-dem-tresor"));
        }
        // Ohne Anbieter bleibt das fehlende Token ein Fehler der Konfiguration.
        assertThatThrownBy(() -> Harvester.builder().source(withoutToken).dataDirectory(data.resolve("ohne"))
                .build()).isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("auth.type pat braucht auth.token");
    }

    @Test
    void ownSourceTypesAreAddedAndReplaceTheCoreTypeOfTheSameName() {
        assertThatThrownBy(() -> Harvester.builder().sourceAdapter(probe("probe", "nicht erreichbar"))
                .source(new SourceSettingsConfig("notizen", "probe", null, null, null, Map.of()))
                .dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .containsExactly("sources[0] (id=notizen): nicht erreichbar"));
        assertThatThrownBy(() -> Harvester.builder().sourceAdapter(probe("confluence", "eigener Adapter"))
                .source(new SourceSettingsConfig("wiki", "confluence", null, SERVER.baseUrl(), null, Map.of()))
                .dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .containsExactly("sources[0] (id=wiki): eigener Adapter"));
    }

    @Test
    void anOwnEmbeddingModelEnablesTheSemanticSearchWithoutTheExternalService() {
        EmbeddingProvider local = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "lokal@1";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[]{1, 0, 0}).toList();
            }
        };

        try (Harvester harvester = Harvester.builder().embeddings(local).dataDirectory(data).build()) {
            assertThat(harvester.relevance().semantic()).isTrue();
        }
        try (Harvester harvester = Harvester.builder().dataDirectory(data.resolve("ohne")).build()) {
            assertThat(harvester.relevance().semantic()).isFalse();
        }
    }

    private static SourceAdapter probe(String type, String problem) {
        return new SourceAdapter() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public List<String> check(SourceSettingsConfig config) {
                return List.of();
            }

            @Override
            public OpenedSource open(SourceSettingsConfig config, Context context) {
                throw new InvalidSourceConfigurationException(List.of(problem));
            }
        };
    }
}
