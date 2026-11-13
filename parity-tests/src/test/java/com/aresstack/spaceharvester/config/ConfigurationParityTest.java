package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.application.terms.SynonymBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LoggingSystemProperties;
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
 * Die Terminal-Ausgabe ({@code logging.threshold.console}) liest der Dienst über Spring Boot selbst; verglichen wird
 * mit dem, was Spring Boot daraus an Logback gibt.
 */
class ConfigurationParityTest {

    private static final String YAML = """
            logging:
              threshold:
                console: warn
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
                parallel-preparations: 3
                parallel-downloads: 0
                download-connections: 12
                retry:
                  max-attempts: 4
                  initial-delay: 500ms
                  max-delay: 20s
                  pause-after-failures: 7
                  resume-initial-delay: 5s
                  resume-max-delay: 2m
              assets:
                extraction:
                  max-file-size: 2GB
                  max-chars: 5000000
                  unpack-ratio: 50
                  unpack-ratio-from-size: 200MB
                  max-depth: 4
                  max-embedded-files: 500
                  text-ratio: 50
                  text-ratio-from-chars: 2000000
                  idle-timeout: 45s
                  total-timeout: 1h
                  temp-space-reserve: 512MB
                  parallel: 3
                  memory-limits:
                    - type: "*"
                      limit: 40MB
                    - type: message/rfc822
                      limit: 0
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
                .isEqualTo(new RetrySettings(4, java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(20), 7,
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2)));

        // Grenzen für Bilder- und Anhängeaufträge (ADR 0025 Punkt 18), 0 Downloads bereitet nur vor.
        assertThat(app.jobLimits().parallelPreparations()).isEqualTo(jobs.toLimits().parallelPreparations())
                .isEqualTo(3);
        assertThat(app.jobLimits().parallelDownloads()).isEqualTo(jobs.toLimits().parallelDownloads()).isZero();
        // Gesamtbudget der Verbindungen für Bilder und Anhänge (Angelo 11.11.2026).
        assertThat(app.jobLimits().downloadConnections()).isEqualTo(jobs.toLimits().downloadConnections())
                .isEqualTo(12);

        // #336: Budget der Synonymerkennung geht beim Dienst über withSyncRules in die HarvesterConfiguration.
        HarvesterConfiguration service2 = ConfigurationManagerConfiguration.withSyncRules(
                HarvesterConfiguration.DEFAULT, binder);
        SynonymBudget budget = service2.terms().synonyms();
        assertThat(app.terms().synonyms()).isEqualTo(budget);
        assertThat(budget).isEqualTo(new SynonymBudget(300, 100, 50));

        // Schutzgrenzen beim Lesen von Dokumenten (ADR 0026): nicht genannte Werte und Formate wie die Standards.
        com.aresstack.spaceharvester.application.assets.ExtractionLimits extraction = binder
                .bind("space-harvester.assets.extraction", ExtractionProperties.class).get().toLimits();
        assertThat(app.storage().extraction()).isEqualTo(extraction);
        assertThat(extraction.maxFileBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(extraction.maxChars()).isEqualTo(5_000_000);
        assertThat(extraction.parallel()).isEqualTo(3);
        assertThat(extraction.idleTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(extraction.unpackRatio()).isEqualTo(50);
        assertThat(extraction.unpackRatioFromBytes()).isEqualTo(200L * 1024 * 1024);
        assertThat(extraction.maxDepth()).isEqualTo(4);
        assertThat(extraction.maxEmbeddedFiles()).isEqualTo(500);
        assertThat(extraction.memoryLimit("application/zip")).isEqualTo(40L * 1024 * 1024);
        assertThat(extraction.memoryLimit("message/rfc822")).isZero();
        assertThat(extraction.memoryLimit("application/pdf")).as("Nachweis bleibt").isZero();

        // Terminal-Ausgabe (Angelo 12.11.2026): dieselbe Stufe wie die Konsolen-Schwelle, die Spring Boot setzt.
        assertThat(app.consoleOutput()).isEqualTo(ConsoleOutput.WARN);
        assertThat(springConsoleThreshold(environment)).isEqualToIgnoringCase(app.consoleOutput().name());
    }

    @Test
    void anUnquotedOffSilencesTheTerminalInServiceAndApp(@TempDir Path home) throws IOException {
        String yaml = "logging:\n  threshold:\n    console: off\n";
        Path file = home.resolve("space-harvester.yml");
        Files.writeString(file, yaml);
        AppSettings app = new AppSettingsLoader(new Placeholders(name -> null), home).load(file);
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("yaml", new ByteArrayResource(yaml.getBytes()))
                .forEach(source -> environment.getPropertySources().addLast(source));

        assertThat(app.problems()).isEmpty();
        assertThat(app.consoleOutput()).isEqualTo(ConsoleOutput.OFF);
        // YAML liest ein unquotiertes off als false; Spring Boot macht daraus wieder OFF.
        assertThat(springConsoleThreshold(environment)).isEqualTo("OFF").isEqualTo(app.consoleOutput().name());
    }

    /** Was Spring Boot dem Logback-Filter am Konsolen-Appender gibt ({@code CONSOLE_LOG_THRESHOLD}). */
    private static String springConsoleThreshold(StandardEnvironment environment) {
        Map<String, String> properties = new HashMap<>();
        new LoggingSystemProperties(environment, properties::put).apply();
        return properties.get("CONSOLE_LOG_THRESHOLD");
    }
}
