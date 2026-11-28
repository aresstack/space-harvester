package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
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
 * mit dem, was Spring Boot daraus an Logback gibt. Schlüssel unter {@code space-harvester-app} gehören nur der App
 * (etwa der Hintergrund des Bildbetrachters); hier steht nur, dass sie den Dienst nicht stören.
 */
class ConfigurationParityTest {

    private static final String YAML = """
            logging:
              threshold:
                console: warn
            space-harvester-app:
              viewer:
                background: dark
              whisper:
                model: small
                dictation-model: tiny
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
                step-thinking: low
                parallel: 6
                chat-max-tokens: 4096
                vision-max-tokens: 32768
                image-model: svg-model
                image-max-tokens: 65536
                vision-thinking: high
                image-thinking: medium
                summary-model: teile-model
                summary-max-tokens: 65536
                summary-thinking: low
                summary-plan-model: plan-model
                summary-plan-max-tokens: 8192
                summary-plan-thinking: high
                podcast-model: podcast-model
                podcast-max-tokens: 32768
                podcast-thinking: none
                image-plan-model: schaubild-plan
                image-plan-max-tokens: 8192
                image-plan-thinking: medium
                deadline-model: fristen-modell
                deadline-max-tokens: 4096
                deadline-thinking: low
                source-code-model: quellcode-modell
                source-code-max-tokens: 2048
                source-code-thinking: low
                diagnosis-enabled: false
                diagnosis-model: diagnose-modell
                diagnosis-max-tokens: 2048
                diagnosis-thinking: high
                summary-timeout: 7m
                label-timeout: 45s
                expansion-timeout: 2m
                podcast-speech-model: tts-podcast
                podcast-voice-host: Jana
                podcast-voice-cohost: Thomas
                read-aloud-model: tts-vorlesen
                read-aloud-voice: Max
                podcast-pause-millis: 500
                podcast-speech-attempts: 4
                podcast-speech-workers: 3
                podcast-section-chars: 4500
                podcast-sections: 14
                external-processing-sources:
                  - confluence
              cache:
                change-log:
                  retention: P2Y
                  show-after-update: true
              sync:
                interval: 30m
                spaces:
                  - confluence/DOC
                mode: sync-cache
                catch-up-on-start: true
              query:
                max-page-limit: 250
                max-term-scan: 70000
              network:
                timeouts:
                  delegated-check: 25s
                  webdav-request: 3m
                  probe: 40s
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
                  automatic: false
                  glossary-terms-only: true
                glossary:
                  automatic: true
                  approved: false
              glossary:
                rules:
                  max-terms: 200
                  technical-heads:
                    add:
                      - Fachverfahren
                    exclude: datei
                  role-words:
                    add: Sachgebiet, amt
              enrichment:
                documents:
                  enabled: false
                  embedding-max-chars: 3000000000
              statements:
                simplification:
                  mode: always
                  parallel: 3
                  model: step-model
                  max-tokens: 4096
                  thinking: none
                standing:
                  outdated-words:
                    - Archiv
                    - abgelöst
                  draft-words: Entwurf, idee
                check:
                  scope: all
                  parallel: 4
                  model: check-model
                  max-tokens: 32768
                  thinking: low
                analyzer: model
                analyzer-model: statement-model
                analyzer-max-tokens: 16384
                analyzer-thinking: high
              images:
                automatic: true
                max-per-run: 120
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
        assertThat(appAi.stepThinking()).isEqualTo(ai.stepThinking()).isEqualTo("low");
        assertThat(appAi.parallel()).isEqualTo(ai.parallel()).isEqualTo(6);
        assertThat(appAi.chatMaxTokens()).isEqualTo(ai.chatMaxTokens()).isEqualTo(4096);
        assertThat(appAi.visionMaxTokens()).isEqualTo(ai.visionMaxTokens()).isEqualTo(32768);
        assertThat(appAi.imageModel()).isEqualTo(ai.imageModel()).isEqualTo("svg-model");
        assertThat(appAi.imageMaxTokens()).isEqualTo(ai.imageMaxTokens()).isEqualTo(65536);
        assertThat(appAi.visionThinking()).isEqualTo(ai.visionThinking()).isEqualTo("high");
        assertThat(appAi.imageThinking()).isEqualTo(ai.imageThinking()).isEqualTo("medium");
        assertThat(appAi.summary()).isEqualTo(ai.summaryUse())
                .isEqualTo(new AiServiceSettings.ModelUse("teile-model", 65536, "low"));
        assertThat(appAi.summaryPlan()).isEqualTo(ai.summaryPlanUse())
                .isEqualTo(new AiServiceSettings.ModelUse("plan-model", 8192, "high"));
        assertThat(appAi.podcast()).isEqualTo(ai.podcastUse())
                .isEqualTo(new AiServiceSettings.ModelUse("podcast-model", 32768, "none"));
        assertThat(appAi.imagePlan()).isEqualTo(ai.imagePlanUse())
                .isEqualTo(new AiServiceSettings.ModelUse("schaubild-plan", 8192, "medium"));
        assertThat(appAi.deadline()).isEqualTo(ai.deadlineUse())
                .isEqualTo(new AiServiceSettings.ModelUse("fristen-modell", 4096, "low"));
        assertThat(appAi.sourceCode()).isEqualTo(ai.sourceCodeUse())
                .isEqualTo(new AiServiceSettings.ModelUse("quellcode-modell", 2048, "low"));
        assertThat(appAi.diagnosis()).isEqualTo(ai.diagnosis()).isEqualTo(new AiServiceSettings.Diagnosis(false,
                new AiServiceSettings.ModelUse("diagnose-modell", 2048, "high")));
        assertThat(appAi.timeouts()).isEqualTo(ai.timeouts()).isEqualTo(new AiServiceSettings.Timeouts(
                java.time.Duration.ofMinutes(7), java.time.Duration.ofSeconds(45), java.time.Duration.ofMinutes(2)));
        assertThat(appAi.speech()).isEqualTo(ai.speech())
                .isEqualTo(new AiServiceSettings.Speech("tts-podcast", "Jana", "Thomas", "tts-vorlesen", "Max", 500, 4,
                        3, 4500, 14));
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
        assertThat(budget.automatic()).as("SY1: automatic aus beiden Loadern").isFalse();
        assertThat(budget.glossaryTermsOnly()).as("K3: glossary-terms-only aus beiden Loadern").isTrue();
        assertThat(app.terms().glossaryAutomatic()).isEqualTo(service2.terms().glossaryAutomatic())
                .as("K4: terms.glossary.automatic aus beiden Loadern").isTrue();
        assertThat(app.terms().glossaryApproved()).isEqualTo(service2.terms().glossaryApproved())
                .as("Geprüfte Glossare: terms.glossary.approved aus beiden Loadern").isFalse();
        // ADR 0032, FL5b: Regeln der Begriffsableitung, als YAML-Liste und als Text mit Kommas.
        com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings rules =
                com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings.DEFAULT.withMaxTerms(200)
                        .with(com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings.Kind
                                .TECHNICAL_HEADS, java.util.List.of("fachverfahren"), java.util.List.of("datei"))
                        .with(com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings.Kind
                                .ROLE_WORDS, java.util.List.of("sachgebiet", "amt"), java.util.List.of());
        assertThat(app.terms().glossaryRules()).isEqualTo(service2.terms().glossaryRules()).isEqualTo(rules);
        assertThat(budget).isEqualTo(new SynonymBudget(300, 100, 50, false, true));

        // ADR 0030: Aufbewahrung des Änderungsprotokolls, beim Dienst über withSyncRules.
        assertThat(app.cache().changeLog()).isEqualTo(service2.changeLog())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.ChangeLogSettings(
                        java.time.Duration.ofDays(730), true));

        // ADR 0026, AP9a: Analysen je Anhang, beim Dienst ebenfalls über withSyncRules.
        assertThat(app.enrichment()).isEqualTo(service2.enrichment())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.EnrichmentSettings(false,
                        3_000_000_000L));

        // ADR 0032, FL3: Vorstufe „Sätze vereinfachen“, beim Dienst über withSyncRules.
        assertThat(app.simplification()).isEqualTo(service2.simplification())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.SimplificationSettings(
                        com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.ALWAYS, 3,
                        "step-model", 4096, "none"));

        // ADR 0032, FL6: Wörter des Stands, als YAML-Liste und als Text mit Kommas.
        assertThat(app.standing()).isEqualTo(service2.standing())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.StandingSettings(
                        java.util.List.of("archiv", "abgelöst"), java.util.List.of("entwurf", "idee")));
        // ADR 0032, FL4: Prüfschritt, beim Dienst über withSyncRules.
        assertThat(app.check()).isEqualTo(service2.check())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.CheckSettings(
                        com.aresstack.spaceharvester.application.check.CheckScope.ALL, 4, "check-model", 32768,
                        "low"));
        // Fahrplan B1: automatische Bildbeschreibung, beim Dienst über withSyncRules.
        assertThat(app.images()).isEqualTo(service2.images())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 120));
        // Fahrplan A2/A3: Aussagenanalyse (Analysator = gelesene Ablage, eigenes Modell).
        assertThat(app.analyzer()).isEqualTo(service2.analyzer())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.AnalyzerSettings(
                        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL, "statement-model", 16384,
                        "high"));

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
        assertThat(extraction.memoryLimit("application/msword")).isEqualTo(40L * 1024 * 1024);
        assertThat(extraction.memoryLimit("message/rfc822")).isZero();
        assertThat(extraction.memoryLimit("application/pdf")).as("Nachweis bleibt").isZero();

        // Stapelgrößen der Abfragen (Angelo 26.11.2026): beim Dienst @Value in QueryConfiguration.
        assertThat(app.storage().queryLimits())
                .isEqualTo(new com.aresstack.spaceharvester.application.query.QueryLimits(
                        environment.getProperty("space-harvester.query.max-page-limit", Integer.class),
                        environment.getProperty("space-harvester.query.max-term-scan", Integer.class)))
                .isEqualTo(new com.aresstack.spaceharvester.application.query.QueryLimits(250, 70_000));

        // Netz-Fristen der Quellen (Angelo 26.11.2026): beim Dienst SpaceHarvesterProperties.Network.
        assertThat(app.storage().sourceTimeouts())
                .isEqualTo(binder.bind("space-harvester.network.timeouts", SpaceHarvesterProperties.Timeouts.class)
                        .get().toSettings())
                .isEqualTo(new com.aresstack.spaceharvester.source.network.SourceTimeouts(
                        java.time.Duration.ofSeconds(25), java.time.Duration.ofMinutes(3),
                        java.time.Duration.ofSeconds(40)));

        // Terminal-Ausgabe (Angelo 12.11.2026): dieselbe Stufe wie die Konsolen-Schwelle, die Spring Boot setzt.
        assertThat(app.consoleOutput()).isEqualTo(ConsoleOutput.WARN);
        assertThat(springConsoleThreshold(environment)).isEqualToIgnoringCase(app.consoleOutput().name());

        // Hintergrund des Bildbetrachters (Angelo 17.11.2026): nur die App unter space-harvester-app; der Dienst zeigt
        // keine Bilder und bindet nichts darunter, der Schlüssel stört ihn nicht.
        assertThat(app.viewerBackground()).isEqualTo(ViewerBackground.DARK);
        assertThat(environment.getProperty("space-harvester-app.viewer.background")).isEqualTo("dark");
        // Whisper-Modelle (Angelo 21.11.2026): Transkripte und Spracheingabe getrennt, nur die App.
        assertThat(app.whisperModel()).isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.SMALL);
        assertThat(app.dictationModel()).isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY);
        assertThat(environment.getProperty("space-harvester-app.whisper.dictation-model")).isEqualTo("tiny");
        assertThat(service).isNotNull();
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

    /** ADR 0032, FL6: „-“ allein heißt in beiden Loadern keine Wörter, als Text und als YAML-Liste. */
    @Test
    void aLoneDashMeansNoStandingWordsInServiceAndApp(@TempDir Path home) throws IOException {
        String yaml = """
                space-harvester:
                  statements:
                    standing:
                      outdated-words: "-"
                      draft-words:
                        - "-"
                """;
        Path file = home.resolve("space-harvester.yml");
        Files.writeString(file, yaml);
        AppSettings app = new AppSettingsLoader(new Placeholders(name -> null), home).load(file);
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("yaml", new ByteArrayResource(yaml.getBytes()))
                .forEach(source -> environment.getPropertySources().addLast(source));
        HarvesterConfiguration service = ConfigurationManagerConfiguration.withSyncRules(
                HarvesterConfiguration.DEFAULT, Binder.get(environment));

        assertThat(app.problems()).isEmpty();
        assertThat(app.standing()).isEqualTo(service.standing())
                .isEqualTo(new com.aresstack.spaceharvester.application.configuration.StandingSettings(
                        List.of(), List.of()));
    }

    /** Was Spring Boot dem Logback-Filter am Konsolen-Appender gibt ({@code CONSOLE_LOG_THRESHOLD}). */
    private static String springConsoleThreshold(StandardEnvironment environment) {
        Map<String, String> properties = new HashMap<>();
        new LoggingSystemProperties(environment, properties::put).apply();
        return properties.get("CONSOLE_LOG_THRESHOLD");
    }
}
