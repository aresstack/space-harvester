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
    private boolean visible = true;

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
                      baseUrl: https://angelo:geheim@wiki.example.org
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                      options:
                        mediaDownloads: "true"
                        startPage: "4711"
                        spacePath: ${WIKI_PATH:/vorgabe}
                        credentialRef: keepass:wiki
                """.formatted(home.resolve("daten")));
        settings = new AppSettingsLoader(new Placeholders(Map.of("WIKI_TOKEN", "tok-123", "WIKI_PATH", "/pfad-aus-umgebung")::get), home).load(file);
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
        view.showingForTest(() -> visible);
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
        // Die Zeilen in der farbigen Form des Export-Reiters (Angelo 17.11.2026), in drei Kästen.
        assertThat(texts).as("je Space eine Zeile").containsSubsequence("Spaces", "CLUK",
                "305 von 340 Seiten lokal · 5 veraltet · 2 gesperrt · Stand vom 16.11.2026, 18:30", "DEV",
                "6 von 20 Seiten lokal · 1 veraltet · " + ExportPanel.INCOMPLETE);
        assertThat(view.plateTitles()).containsExactly("Kennzahlen", "Spaces", "Einstellungen");
        assertThat(view.subscribed()).isTrue();
    }

    /** Angelo 17.11.2026: „Wissensquellen“ zeigt die Gesamtsicht über alle Quellen statt der zuletzt gewählten. */
    @Test
    void allSourcesSumUpEveryQuelleAndListTheSpacesWithTheirSourceTitle() throws Exception {
        view.all(List.of("wiki", "archiv"));

        assertThat(view.showsAll()).isTrue();
        assertThat(view.editEnabled()).isFalse();
        assertThat(actions.subscriptions).as("je Quelle der Bestand").isEqualTo(2);
        List<String> texts = view.texts();
        assertThat(view.plateTitles()).containsExactly("Kennzahlen", "Spaces", SourcePropertiesView.ALL_SOURCES);
        assertThat(texts).containsSubsequence("Kennzahlen", "Spaces", "4 Spaces, davon 2 mit Inventur", "Seiten", "720",
                "Seit dem letzten Update", "4 geänderte Seiten");
        assertThat(texts).containsSubsequence("Spaces", "archiv › CLUK", "archiv › DEV", "Confluence HaVdB › CLUK",
                "Confluence HaVdB › DEV");
        assertThat(texts).containsSubsequence(SourcePropertiesView.ALL_SOURCES, "Confluence HaVdB",
                "Confluence · Personal Access Token · aktiv", "archiv", "aktiv");
        assertThat(texts).doesNotContain("Einstellungen", "Token");

        // Eine Meldung einer der Quellen liest neu; eine einzelne Quelle beendet die Gesamtsicht.
        int reads = actions.reads;
        actions.changes.changed("archiv");
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reads).isGreaterThan(reads);
        view.source("wiki");
        assertThat(view.showsAll()).isFalse();
        assertThat(view.texts()).contains("Einstellungen").doesNotContain("archiv › CLUK");
    }

    @Test
    void settingsShowTheSourceWithFieldLabelsAndNoSecrets() {
        view.source("wiki");

        List<String> texts = view.texts();
        assertThat(texts).containsSubsequence("Einstellungen", "ID", "wiki", "Aktiv", "ja", "Typ", "Confluence",
                "Anzeigename", "Confluence HaVdB", "Adresse", "https://wiki.example.org", "Bilder laden", "ja",
                "startPage", "4711", "Anmeldung", "Personal Access Token", "Token", "gesetzt (Property/Umgebung)",
                "Client-Zertifikat", "aus");
        assertThat(texts).doesNotContain("tok-123");
        assertThat(view.copyText()).contains("Kennzahlen\nSpaces: 2 Spaces").contains("Token: gesetzt (Property/Umgebung)")
                .doesNotContain("tok-123");
    }

    @Test
    void optionsFromPlaceholdersReferencesAndTheUserPartOfTheAddressAreNeverShownResolved() {
        view.source("wiki");

        List<String> texts = view.texts();
        assertThat(texts).containsSubsequence("Adresse", "https://wiki.example.org", "Zugangsverweis",
                "Verweis keepass:wiki", "spacePath", "${WIKI_PATH}");
        assertThat(String.join("\n", texts)).doesNotContain("/pfad-aus-umgebung").doesNotContain("/vorgabe")
                .doesNotContain("angelo").doesNotContain("geheim");
        assertThat(view.copyText()).contains("spacePath: ${WIKI_PATH}").doesNotContain("/pfad-aus-umgebung")
                .doesNotContain("/vorgabe").doesNotContain("angelo").doesNotContain("geheim");
    }

    @Test
    void atMostOneRunAtATimeWithOneQueuedFollowUp() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        SourcePropertiesView slow = new SourcePropertiesView(() -> actions, () -> settings,
                new SourcePropertiesView.Runner() {
                    @Override
                    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                        waiting.add(() -> {
                            try {
                                onSuccess.accept(work.call());
                            } catch (Exception e) {
                                onFailure.accept(e);
                            }
                        });
                    }
                });
        slow.showingForTest(() -> true);
        slow.source("wiki");
        assertThat(waiting).hasSize(1);

        for (int i = 0; i < 5; i++) {
            actions.holdings.pagesChanged("wiki", "CLUK", Set.of("11"));
            actions.changes.changed("wiki");
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).as("kein zweiter Lauf, solange einer läuft").hasSize(1);
        assertThat(slow.pendingForTest()).isTrue();

        waiting.remove(0).run();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).as("genau ein Nachlauf").hasSize(1);
        waiting.remove(0).run();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).isEmpty();
        assertThat(slow.texts()).contains("360");
    }

    @Test
    void aHiddenTabReadsNothingAndCatchesUpOnceWhenShown() throws Exception {
        view.source("wiki");
        int reads = actions.reads;
        visible = false;

        actions.counts = new HoldingCounts(341, 301, 5, 2);
        actions.holdings.pagesChanged("wiki", "CLUK", Set.of("11"));
        actions.changes.changed("wiki");
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reads).as("verdeckt wird nicht gelesen").isEqualTo(reads);

        visible = true;
        view.becameVisible();
        assertThat(actions.reads).isEqualTo(reads + 1);
        assertThat(view.texts()).contains("361");
        view.becameVisible();
        assertThat(actions.reads).as("nur einmal nachholen").isEqualTo(reads + 1);
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
        int subscriptions;

        @Override
        public List<SpaceSummary> spaces(String sourceId) {
            reads++;
            return List.of(
                    new SpaceSummary(sourceId, "DEV", new CacheGeneration("g0", CacheState.INCOMPLETE, null, null),
                            new HoldingCounts(20, 5, 1, 0)),
                    new SpaceSummary(sourceId, "CLUK", new CacheGeneration("g3", CacheState.CONFIRMED, SYNCED, SYNCED),
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
                    new SourceField("mediaDownloads", "Bilder laden", "", SourceField.Kind.FLAG, false, false, "true"),
                    new SourceField("credentialRef", "Zugangsverweis", "", SourceField.Kind.SECRET_REF, false, true,
                            "")),
                    List.of(SourceType.AuthMode.PAT)));
        }

        @Override
        public Subscription subscribeHoldings(String sourceId, HoldingsListener listener) {
            holdings = listener;
            subscriptions++;
            return () -> closed++;
        }

        @Override
        public Subscription subscribeChangeLog(String sourceId, ChangeLogListener listener) {
            changes = listener;
            return () -> closed++;
        }
    }
}
