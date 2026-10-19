package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.RefreshStrategy;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.ranking.SemanticSearchUnavailableException;
import com.aresstack.spaceharvester.application.ranking.SemanticVariantSearch;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TestProxy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Konfiguration zur Laufzeit im Harvester (ADR 0015): Regeln je Quelle, Zeitplan und Modelle ohne Neustart. */
class HarvesterConfigurationLiveTest {

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

    private static SourceSettingsConfig wiki() {
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));
    }

    private Harvester harvester(ConfigurationManager manager) {
        return Harvester.builder().source(wiki()).dataDirectory(data).configuration(manager).build();
    }

    private static HarvesterConfiguration schedule(Duration interval, List<String> spaces) {
        SyncSettings sync = new SyncSettings(false, interval, spaces, false, null, false, SyncMode.CATALOG_ONLY,
                null, Map.of());
        return HarvesterConfiguration.DEFAULT.withSync(sync);
    }

    private static AiServiceSettings ai(String embeddingModel, String rerankModel) {
        return new AiServiceSettings(URI.create("http://127.0.0.1:9"), null, null, embeddingModel,
                embeddingModel == null ? null : 3, null, null, Set.of("wiki"), rerankModel);
    }

    @Test
    void theRefreshStrategyOfASourceChangesWithoutRebuildingTheHarvester() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (Harvester harvester = harvester(manager)) {
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            int loaded = CONFLUENCE.requestHeaders().size();
            // Standard „Immer Version prüfen“: jeder Abruf fragt die Quelle.
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            assertThat(CONFLUENCE.requestHeaders().size()).isGreaterThan(loaded);

            SourceSyncOverride timeBased = new SourceSyncOverride(null, null, null, null, null, null,
                    RefreshStrategy.TIME_BASED, Duration.ofHours(1), null);
            HarvesterConfiguration next = manager.current().configuration()
                    .withSync(manager.current().configuration().sync().withSource("wiki", timeBased));
            assertThat(manager.apply(next).activated()).isTrue();

            int before = CONFLUENCE.requestHeaders().size();
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            assertThat(CONFLUENCE.requestHeaders().size()).isEqualTo(before);
        }
    }

    @Test
    void theBackgroundFillFollowsTheConfigurationGloballyAndPerSource() {
        HarvesterConfiguration off = HarvesterConfiguration.DEFAULT.withSync(
                HarvesterConfiguration.DEFAULT.sync().withBackground(false, 3));
        ConfigurationManager manager = new ConfigurationManager(off);
        try (Harvester harvester = harvester(manager)) {
            // Die Warteschlange gibt es auch abgeschaltet, damit die Füllung ohne Neustart angeht.
            assertThat(harvester.backgroundFill()).isPresent();
            assertThat(harvester.acquisitionStatus().enabled()).isFalse();
            assertThat(harvester.acquisitionStatus().pause()).isEqualTo(AcquisitionStatus.Pause.DISABLED);
            assertThat(harvester.acquisition().limits().background()).isEqualTo(3);

            // Nur für „wiki“ eingeschaltet: ein eigener Wert je Quelle geht dem globalen vor.
            HarvesterConfiguration wikiOnly = off.withSync(off.sync().withSource("wiki", new SourceSyncOverride(
                    null, null, null, null, null, null, null, null, null, true)));
            assertThat(manager.apply(wikiOnly).activated()).isTrue();
            assertThat(harvester.acquisitionStatus().enabled()).isTrue();

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT).activated()).isTrue();
            assertThat(harvester.acquisitionStatus().enabled()).isTrue();
            assertThat(harvester.acquisition().limits().background())
                    .isEqualTo(SyncSettings.DEFAULT_BACKGROUND_PARALLELISM);
        }

        // Nach configuration() fest eingestellt: folgt der Konfiguration nicht.
        try (Harvester fixed = Harvester.builder().source(wiki()).dataDirectory(data.resolve("fest"))
                .configuration(new ConfigurationManager(HarvesterConfiguration.DEFAULT)).backgroundFill(false)
                .build()) {
            assertThat(fixed.backgroundFill()).isEmpty();
            assertThat(fixed.acquisitionStatus().enabled()).isFalse();
            assertThat(fixed.acquisition().limits().background()).isZero();
        }
    }

    @Test
    void theScheduleFollowsTheConfigurationAndOwnSchedulesStay() {
        ConfigurationManager manager = new ConfigurationManager(schedule(Duration.ofMinutes(10), List.of("wiki/DEV")));
        try (Harvester harvester = harvester(manager)) {
            assertThat(harvester.reconciliations()).extracting(Reconciliation.Schedule::spaceKey,
                    Reconciliation.Schedule::interval).containsExactly(tuple("DEV", Duration.ofMinutes(10)));
            harvester.reconcile("wiki", "OPS", Duration.ofMinutes(5), SyncMode.CATALOG_ONLY);

            HarvesterConfiguration next = schedule(Duration.ofMinutes(20), List.of("wiki/DEV", "wiki/QA"));
            next = next.withSync(next.sync().withSource("wiki", new SourceSyncOverride(null, null, null, null, null,
                    SyncMode.SYNC_CACHE, null, null, null)));
            assertThat(manager.apply(next).activated()).isTrue();
            assertThat(harvester.reconciliations()).extracting(Reconciliation.Schedule::spaceKey,
                    Reconciliation.Schedule::interval, Reconciliation.Schedule::mode).containsExactly(
                    tuple("DEV", Duration.ofMinutes(20), SyncMode.SYNC_CACHE),
                    tuple("OPS", Duration.ofMinutes(5), SyncMode.CATALOG_ONLY),
                    tuple("QA", Duration.ofMinutes(20), SyncMode.SYNC_CACHE));

            assertThat(manager.apply(schedule(null, List.of("wiki/DEV"))).activated()).isTrue();
            assertThat(harvester.reconciliations()).extracting(Reconciliation.Schedule::spaceKey)
                    .containsExactly("OPS");
        }
    }

    @Test
    void aRejectedConfigurationLeavesScheduleAndVersionUnchanged() {
        ConfigurationManager manager = new ConfigurationManager(schedule(Duration.ofMinutes(10), List.of("wiki/DEV")));
        try (Harvester harvester = harvester(manager)) {
            ConfigurationManager.Activation rejected = manager.apply(schedule(Duration.ofMinutes(30),
                    List.of("wiki/DEV", "unbekannt/DEV")));

            assertThat(rejected.outcome()).isEqualTo(ConfigurationManager.Outcome.REJECTED);
            assertThat(rejected.problems()).extracting(p -> p.key()).containsExactly(SyncSettings.SPACES);
            assertThat(manager.current().version()).isEqualTo(1);
            assertThat(harvester.reconciliations()).extracting(Reconciliation.Schedule::interval)
                    .containsExactly(Duration.ofMinutes(10));
        }
    }

    @Test
    void embeddingsAndRerankerSwitchOnChangeAndOffAtRuntime() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (Harvester harvester = harvester(manager)) {
            assertThat(harvester.relevance().semantic()).isFalse();

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withAi(ai("modell-a", "rerank-a"))).activated())
                    .isTrue();
            assertThat(harvester.relevance().semantic()).isTrue();

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withAi(ai("modell-b", null))).activated())
                    .isTrue();
            assertThat(harvester.relevance().semantic()).isTrue();

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT).activated()).isTrue();
            assertThat(harvester.relevance().semantic()).isFalse();
        }
    }

    @Test
    void theProgressiveSearchFollowsTheEmbeddingModelAtRuntime() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (Harvester harvester = harvester(manager)) {
            assertThat(harvester.searchProfile().ranking()).isEqualTo(SearchProfile.Ranking.KEYWORD);
            // Ohne Modell keine semantische Suche, auch nicht ausdrücklich, und kein stiller Rückfall.
            assertThatThrownBy(() -> harvester.progressiveSearch().searchAsync(AccessContext.technicalClient("app"),
                    KeywordSearch.Query.of("Betrieb"), SearchProfile.defaults()
                            .withRanking(SearchProfile.Ranking.SEMANTIC)))
                    .isInstanceOf(SemanticSearchUnavailableException.class);

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withAi(ai("modell-a", null))).activated())
                    .isTrue();
            SearchProfile profile = harvester.searchProfile();
            assertThat(profile.ranking()).isEqualTo(SearchProfile.Ranking.SEMANTIC);
            assertThat(profile.execution().variantTimeout()).isEqualTo(SemanticVariantSearch.VARIANT_TIMEOUT);
            assertThat(profile.timeBudget()).isEqualTo(SemanticVariantSearch.TIME_BUDGET);

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT).activated()).isTrue();
            assertThat(harvester.searchProfile().ranking()).isEqualTo(SearchProfile.Ranking.KEYWORD);
        }
    }

    @Test
    void valuesSetOnTheBuilderAfterTheConfigurationStayFixed() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).configuration(manager)
                .ai(AiServiceSettings.NONE).build()) {
            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withAi(ai("modell-a", null))).activated())
                    .isTrue();
            assertThat(harvester.relevance().semantic()).isFalse();
        }
    }

    @Test
    void theProxyFollowsTheConfigurationAndInvalidNetworkSettingsAreRejected() throws Exception {
        // Ein Name statt 127.0.0.1: Loopback-Ziele gehen immer direkt (test-hosts zeigt ihn auf Loopback).
        SourceSettingsConfig named = new SourceSettingsConfig("wiki", "confluence", "Wiki",
                URI.create(SERVER.baseUrl().toString().replace("127.0.0.1", "wiki.proxied.test")),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (TestProxy proxy = new TestProxy();
             Harvester harvester = Harvester.builder().source(named).dataDirectory(data).configuration(manager)
                     .build()) {
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            assertThat(proxy.requestLines()).isEmpty();

            ProxySettings manual = new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "127.0.0.1", proxy.port(),
                    null, null, null, null);
            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withNetwork(new NetworkSettings(manual,
                    TrustSettings.jvm()))).activated()).isTrue();
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            assertThat(proxy.requestLines()).isNotEmpty()
                    .allSatisfy(line -> assertThat(line).contains("wiki.proxied.test"));

            ConfigurationManager.Activation rejected = manager.apply(HarvesterConfiguration.DEFAULT.withNetwork(
                    new NetworkSettings(new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, null, null, null,
                            null, null, null), TrustSettings.jvm())));
            assertThat(rejected.activated()).isFalse();
            assertThat(rejected.problems()).extracting(ConfigurationProblem::key).containsOnly(NetworkSettings.PROXY);

            proxy.requestLines().clear();
            assertThat(manager.apply(HarvesterConfiguration.DEFAULT).activated()).isTrue();
            harvester.pages().page(harvester.caller(), "wiki", "100", false);
            assertThat(proxy.requestLines()).isEmpty();
        }
    }
}
