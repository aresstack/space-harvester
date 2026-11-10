package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.application.terms.SynonymBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

/**
 * Konfigurationsparität zwischen Dienst und App: dieselbe YAML geht durch das Spring-Binding des Dienstes
 * ({@link SpaceHarvesterProperties}, {@link AiProperties}, {@link SyncProperties}) und durch den
 * {@link AppSettingsLoader} der App; beide müssen dieselben Quellen, KI-Einstellungen und Abgleichswerte lesen.
 * Ein neuer gemeinsamer Schlüssel unter {@code space-harvester} gehört in beide Loader und hierher. Der KI-API-Key
 * bleibt bewusst außen vor: die App bezieht ihn aus KeePass oder dem Tresor ({@code AiAccess}), nie aus der YAML.
 */
class ConfigurationParityTest {

    private static final String YAML = """
            space-harvester:
              sources:
                - id: confluence
                  type: confluence
                  title: Confluence
                  base-url: https://confluence.example.org
                  auth:
                    type: pat
                    token: ${CONFLUENCE_TOKEN}
              ai:
                base-url: https://ai.example.org/v1
                chat-model: chat-model
                embedding-model: embedding-model
                embedding-dimensions: 1536
                rerank-model: rerank-model
                vision-model: vision-model
                external-processing-sources:
                  - confluence
              sync:
                interval: 30m
                spaces:
                  - confluence/DOC
                mode: sync-cache
                catch-up-on-start: true
              jobs:
                retry:
                  max-attempts: 4
                  initial-delay: 500ms
                  max-delay: 20s
                  pause-after-failures: 7
              terms:
                synonyms:
                  max-terms: 300
                  max-embedded-terms: 100
                  max-judged-pairs: 50
            """;

    private static final String TOKEN = "parity-token";

    @Test
    void serviceAndAppReadTheSameSharedKeys(@TempDir Path home) throws IOException {
        Path file = home.resolve("space-harvester.yml");
        Files.writeString(file, YAML);

        AppSettings app = new AppSettingsLoader(new Placeholders(name -> "CONFLUENCE_TOKEN".equals(name) ? TOKEN : null),
                home).load(file);
        assertThat(app.problems()).isEmpty();

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("env", Map.of("CONFLUENCE_TOKEN", TOKEN)));
        new YamlPropertySourceLoader().load("yaml", new ByteArrayResource(YAML.getBytes()))
                .forEach(source -> environment.getPropertySources().addLast(source));
        Binder binder = Binder.get(environment);
        SpaceHarvesterProperties service = binder.bind("space-harvester", SpaceHarvesterProperties.class).get();
        AiServiceSettings ai = binder.bind("space-harvester.ai", AiProperties.class).get().toSettings();
        SyncProperties sync = binder.bind("space-harvester.sync", SyncProperties.class).get();

        assertThat(app.sources()).extracting(AppSettings.SourceEntry::config)
                .containsExactlyElementsOf(service.sources().stream().map(SpaceHarvesterProperties.Source::toSettings)
                        .toList());

        AppSettings.AiSettings appAi = app.ai();
        assertThat(appAi.baseUrl()).isEqualTo(ai.baseUrl().toString());
        assertThat(appAi.chatModel()).isEqualTo(ai.chatModel());
        assertThat(appAi.embeddingModel()).isEqualTo(ai.embeddingModel());
        assertThat(appAi.embeddingDimensions()).isEqualTo(ai.embeddingDimensions());
        assertThat(appAi.rerankModel()).isEqualTo(ai.rerankModel());
        assertThat(appAi.visionModel()).isEqualTo(ai.visionModel());
        assertThat(appAi.externalProcessingSources()).containsExactlyInAnyOrderElementsOf(
                ai.externalProcessingSources());
        assertThat(appAi.externalProcessingSources()).isEqualTo(List.of("confluence"));

        assertThat(app.sync().interval()).isEqualTo(sync.interval());
        assertThat(app.sync().spaces()).isEqualTo(sync.spaces()).containsExactly("confluence/DOC");
        assertThat(app.sync().mode()).isEqualTo(sync.mode());
        assertThat(app.sync().catchUpOnStart()).isEqualTo(sync.catchUpOnStart());

        // ADR 0025: Wiederholungen unter jobs.retry, beim Dienst in JobsProperties, in der App in den Auftragsgrenzen.
        JobsProperties jobs = binder.bind("space-harvester.jobs", JobsProperties.class).get();
        assertThat(app.jobLimits().retry()).isEqualTo(jobs.retry().toSettings())
                .isEqualTo(new RetrySettings(4, java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(20), 7));

        // #336: Budget der Synonymerkennung geht beim Dienst über withSyncRules in die HarvesterConfiguration.
        HarvesterConfiguration service2 = ConfigurationManagerConfiguration.withSyncRules(
                HarvesterConfiguration.DEFAULT, binder);
        SynonymBudget budget = service2.terms().synonyms();
        assertThat(app.terms().synonyms()).isEqualTo(budget);
        assertThat(budget).isEqualTo(new SynonymBudget(300, 100, 50));
    }
}
