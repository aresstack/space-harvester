package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quellen zur Laufzeit verwalten (#188), gegen eine Fake-Confluence über echtes HTTP. */
class SourceManagementTest {

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
    void aSourceIsTestedAddedAndRemovedWithItsData() throws Exception {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            SourceManagement sources = harvester.sourceManagement();
            assertThat(sources.sources()).isEmpty();

            SourceManagement.ConnectionCheck check = sources.test(wiki(SERVER.baseUrl()), false);
            assertThat(check.successful()).isTrue();
            assertThat(check.partitions()).isEqualTo(1);
            // Der Test nimmt die Quelle nicht auf.
            assertThat(sources.sources()).isEmpty();

            sources.add(wiki(SERVER.baseUrl()));
            assertThat(sources.sources()).extracting(SourceSettingsConfig::id).containsExactly("wiki");
            assertThat(harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10).pages()).hasSize(1);

            assertThatThrownBy(() -> sources.add(wiki(SERVER.baseUrl())))
                    .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("id ist doppelt");

            assertThat(sources.remove("wiki")).isTrue();
            assertThat(sources.remove("wiki")).isFalse();
            assertThatThrownBy(() -> harvester.sources().partitions("wiki"))
                    .isInstanceOf(UnknownSourceException.class);

            // Wieder hinzugefügt ist die Quelle leer: Cache und Abgleichstand sind mit ihr gegangen.
            sources.add(wiki(SERVER.baseUrl()));
            assertThatThrownBy(() -> harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10))
                    .isInstanceOf(CacheMissException.class);
        }
    }

    @Test
    void aChangedTargetDropsTheDataButNewSettingsForTheSameTargetKeepIt() throws Exception {
        try (Harvester harvester = Harvester.builder().source(wiki(SERVER.baseUrl())).dataDirectory(data).build()) {
            SourceManagement sources = harvester.sourceManagement();
            harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));

            SourceSettingsConfig renamed = new SourceSettingsConfig("wiki", "confluence", "Neues Wiki",
                    SERVER.baseUrl(), SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000"));
            assertThat(sources.validate(renamed, true)).isEmpty();
            assertThat(sources.validate(renamed, false)).containsExactly("id ist doppelt vergeben");
            assertThat(sources.change(renamed)).isFalse();
            assertThat(harvester.sources().sources()).singleElement()
                    .satisfies(source -> assertThat(source.title()).isEqualTo("Neues Wiki"));
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10).pages()).hasSize(1);

            URI elsewhere = URI.create(SERVER.baseUrl().toString().replace("127.0.0.1", "localhost"));
            assertThat(sources.change(wiki(elsewhere))).isTrue();
            assertThatThrownBy(() -> harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10))
                    .isInstanceOf(CacheMissException.class);

            // Ungültiges ändert nichts.
            assertThatThrownBy(() -> sources.change(new SourceSettingsConfig("wiki", "confluence", null, null,
                    null, Map.of()))).isInstanceOf(InvalidSourceConfigurationException.class);
            assertThat(sources.sources()).singleElement().satisfies(config -> assertThat(config.baseUrl())
                    .isEqualTo(elsewhere));
            assertThatThrownBy(() -> sources.change(new SourceSettingsConfig("neu", "confluence", null,
                    SERVER.baseUrl(), null, Map.of()))).isInstanceOf(UnknownSourceException.class);
        }
    }

    @Test
    void aFailedTestNamesTheKindOfProblemOnly() {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            SourceManagement sources = harvester.sourceManagement();

            SourceManagement.ConnectionCheck unreachable = sources.test(wiki(URI.create(
                    "http://127.0.0.1:1/wiki")), false);
            assertThat(unreachable.successful()).isFalse();
            assertThat(unreachable.message()).startsWith("Nicht erreichbar");

            SourceManagement.ConnectionCheck invalid = sources.test(new SourceSettingsConfig("wiki", "confluence",
                    null, null, null, Map.of()), false);
            assertThat(invalid.successful()).isFalse();
            assertThat(invalid.message()).startsWith("Einstellungen: ").contains("base-url fehlt");
        }
    }

    private static SourceSettingsConfig wiki(URI baseUrl) {
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", baseUrl, SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "2000"));
    }
}
