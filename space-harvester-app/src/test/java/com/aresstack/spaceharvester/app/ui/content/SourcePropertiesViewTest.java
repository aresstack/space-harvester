package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SourcePropertiesViewTest {

    private static final Instant SYNCED = Instant.parse("2026-11-16T17:30:00Z");

    @TempDir
    Path home;

    private final FakeActions actions = new FakeActions();
    private final List<String> edited = new ArrayList<>();
    private AppSettings settings;
    private SourcePropertiesView view;

    @BeforeEach
    void setUp() throws Exception {
        Path file = home.resolve("space-harvester-app.yml");
        Files.writeString(file, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Confluence HaVdB
                      baseUrl: https://wiki.example.org
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                      options:
                        mediaDownloads: "true"
                        startPage: "4711"
                """.formatted(home.resolve("daten")));
        settings = new AppSettingsLoader(new Placeholders(Map.of("WIKI_TOKEN", "tok-123")::get), home).load(file);
        view = new SourcePropertiesView(() -> actions, () -> settings, new SourcePropertiesView.Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                try {
                    onSuccess.accept(work.call());
                } catch (Exception e) {
                    onFailure.accept(e);
                }
            }
        });
        view.onEdit(edited::add);
        view.zone(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void figuresSumUpTheSpacesOfTheHoldingsAndTheUpdates() {
        view.source("wiki");

        List<String> texts = view.texts();
        assertThat(texts).containsSubsequence("Kennzahlen", "Spaces", "2 Spaces, davon 1 mit Inventur",
                "Seiten", "360", "Lokal", "305 aktuell, 6 veraltet", "Nicht lokal", "47", "Gesperrt", "2",
                "Stand vom", "16.11.2026, 18:30", "Updates", "1", "Letztes Update", "16.11.2026, 18:30",
                "Seit dem letzten Update", "2 geänderte Seiten");
        assertThat(texts).as("je Space eine Zeile").containsSubsequence("Spaces", "CLUK",
                "340 Seiten · 300 lokal · 5 veraltet · 2 gesperrt · Stand 16.11.2026, 18:30", "DEV",
                "20 Seiten · 5 lokal · 1 veraltet · keine Inventur");
        assertThat(view.subscribed()).isTrue();
    }

    @Test
    void settingsShowTheSourceWithFieldLabelsAndNoSecrets() {
        view.source("wiki");

        List<String> texts = view.texts();
        assertThat(texts).containsSubsequence("Einstellungen", "ID", "wiki", "Aktiv", "ja", "Typ", "Confluence",
                "Anzeigename", "Confluence HaVdB", "Adresse", "https://wiki.example.org", "Bilder laden", "ja",
                "startPage", "4711", "Anmeldung", "Personal Access Token", "Token", "gesetzt",
                "Client-Zertifikat", "aus");
        assertThat(texts).doesNotContain("tok-123");
        assertThat(view.copyText()).contains("Kennzahlen\nSpaces: 2 Spaces").contains("Token: gesetzt")
                .doesNotContain("tok-123");
    }

    @Test
    void messagesFromHoldingsAndChangeLogRereadWithoutPolling() throws Exception {
        view.source("wiki");
        int reads = actions.reads;

        actions.counts = new HoldingCounts(341, 301, 5, 2);
        actions.holdings.pagesChanged("wiki", "CLUK", Set.of("11"));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reads).isEqualTo(reads + 1);
        assertThat(view.texts()).contains("361");

        actions.changes.changed("wiki");
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reads).isEqualTo(reads + 2);
    }

    @Test
    void thePencilOpensTheSourceAndLeavingEndsTheSubscriptions() {
        view.source("wiki");
        view.clickEdit();
        assertThat(edited).containsExactly("wiki");

        view.source(null);
        assertThat(actions.closed).isEqualTo(2);
        assertThat(view.editEnabled()).isFalse();
        assertThat(view.copyText()).isEmpty();
    }

    @Test
    void withoutARuntimeTheViewSaysSo() {
        SourcePropertiesView idle = new SourcePropertiesView(() -> null, () -> settings,
                new SourcePropertiesView.Runner() {
                    @Override
                    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    }
                });
        idle.source("wiki");
        assertThat(idle.texts()).containsExactly(SourcePropertiesView.NOT_RUNNING);
    }

    /** Bildschirmfoto zur Sichtprüfung; nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}. */
    @Test
    void screenshot() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Files.createDirectories(Path.of(directory));
        SwingUtilities.invokeAndWait(() -> {
            view.source("wiki");
            AnalysisTabsScreenshotTest.write(view, out.resolve("quellenseite-eigenschaften.png"));
        });
    }

    private static final class FakeActions implements SourcePropertiesView.Actions {
        HoldingCounts counts = new HoldingCounts(340, 300, 5, 2);
        HoldingsListener holdings;
        ChangeLogListener changes;
        int reads;
        int closed;

        @Override
        public List<SpaceSummary> spaces(String sourceId) {
            reads++;
            return List.of(
                    new SpaceSummary("wiki", "DEV", new CacheGeneration("g0", CacheState.INCOMPLETE, null, null),
                            new HoldingCounts(20, 5, 1, 0)),
                    new SpaceSummary("wiki", "CLUK", new CacheGeneration("g3", CacheState.CONFIRMED, SYNCED, SYNCED),
                            counts));
        }

        @Override
        public List<ChangeUpdate> updates(String sourceId) {
            return List.of(
                    new ChangeUpdate(ChangeUpdate.OPEN, "wiki", SYNCED, null, null, false, Map.of(ChangeKind.NEW, 2),
                            Map.of("CLUK", 2), 2, 0, 0, false),
                    new ChangeUpdate(String.valueOf(SYNCED.toEpochMilli()), "wiki", null, SYNCED,
                            new UpdateMark("wiki", SYNCED, "angelo", List.of("CLUK"), 0, false), false,
                            Map.of(ChangeKind.CONTENT_CHANGED, 1), Map.of("CLUK", 1), 1, 0, 0, false));
        }

        @Override
        public boolean enabled(String sourceId) {
            return true;
        }

        @Override
        public List<SourceType> types() {
            return List.of(new SourceType("confluence", "Confluence", true, null, List.of(
                    new SourceField("mediaDownloads", "Bilder laden", "", SourceField.Kind.FLAG, false, false, "true")),
                    List.of(SourceType.AuthMode.PAT)));
        }

        @Override
        public Subscription subscribeHoldings(String sourceId, HoldingsListener listener) {
            holdings = listener;
            return () -> closed++;
        }

        @Override
        public Subscription subscribeChangeLog(String sourceId, ChangeLogListener listener) {
            changes = listener;
            return () -> closed++;
        }
    }
}
