package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.content.WordSearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.query.DocumentKind;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Suche der App ist die Schlagwortsuche des Core über den Cache (ADR 0013): Treffer zeigen Titel, Quelle, Space
 * und Auszug, „Weitere laden“ folgt dem Cursor, und eine Quelle ohne eigene Suche oder ohne Verbindung steht unter
 * der Liste, statt still zu fehlen. Ein Bestand ist nicht nötig.
 */
class SearchViewTest {

    private static final int PAGES_PER_SOURCE = 60;

    @TempDir
    Path home;

    private AppContext context;
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        servers.forEach(server -> server.stop(0));
    }

    /** Ein Anhangstreffer (#183, #385) nennt seinen Anhang in der Zeile, ohne eigenes Bedienelement. */
    @Test
    void anAttachmentHitNamesTheAttachmentInItsExcerpt() {
        com.aresstack.spaceharvester.application.query.KeywordSearch.Hit core =
                new com.aresstack.spaceharvester.application.query.KeywordSearch.Hit("wiki", "7", "DEV", "Betrieb",
                        "Die  Wartung\n startet um 6 Uhr", null, "3", null, null,
                        new com.aresstack.spaceharvester.application.query.KeywordSearch.AttachmentMatch("att1",
                                "handbuch.pdf"));
        SearchView.Hit hit = SearchView.Hit.of(core);
        assertThat(hit.excerpt()).isEqualTo("Treffer im Anhang „handbuch.pdf“ · Die Wartung startet um 6 Uhr");
        SearchView.HitsModel model = new SearchView.HitsModel();
        model.set(java.util.List.of(hit));
        assertThat(model.getValueAt(0, 3)).isEqualTo(hit.excerpt());
        assertThat(SearchView.Hit.of(new com.aresstack.spaceharvester.application.query.KeywordSearch.Hit("wiki",
                "8", "DEV", "Seite", "Text", null, "1", null, null)).excerpt()).isEqualTo("Text");
        assertThat(hit.passageId()).isNull();
        assertThat(SearchView.Hit.of(new com.aresstack.spaceharvester.application.query.KeywordSearch.Hit("wiki",
                "8", "DEV", "Seite", "Text", null, "1", null, null, null, "passage:abc")).passageId())
                .isEqualTo("passage:abc");
    }

    /** Eine nicht erreichbare Quelle verhindert die Rückfrage für die übrigen nicht und wird später erneut geprüft. */
    @Test
    void anUnavailableSourceDoesNotSuppressTheIndexOfferForTheOthers() {
        List<String> unavailable = new ArrayList<>();
        List<String> notIndexed = SearchView.notIndexed(List.of("fehlt", "dateien", "fertig"), id -> {
            if (id.equals("fehlt")) {
                throw new IllegalStateException("Verzeichnis fehlt");
            }
            return id.equals("fertig");
        }, unavailable::add);
        assertThat(notIndexed).containsExactly("dateien");
        assertThat(unavailable).containsExactly("fehlt");
    }

    @Test
    void allSourcesIsOneGlobalKeywordSearchWithExcerptsAndLoadMore() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        List<String> offered = new java.util.concurrent.CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].indexOffer = title -> offered.add(title) && false;
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        // ADR 0013: Die Quellen finden, die Seiten werden in den Cache geladen, Treffer kommen aus dem Cache. Was beim
        // Antworten noch lädt, kommt über „Weitere laden“. Die Funde einer Quelle stehen schon da, während die Suche
        // läuft (Ring über der Liste); die Zeile unter der Liste gilt erst mit dem fertigen Stapel.
        await(() -> table.getRowCount() > 0 && !view[0].overlay.isBusy());
        assertThat(view[0].statusText()).contains("Dateien: " + SearchView.NOT_ONLINE_SEARCHABLE).doesNotContain("Bestand");
        // Angelo 28.11.2026: Der Begriff landet im Suchverlauf des Felds und bleibt über einen Neustart gemerkt.
        assertThat(view[0].recentTerms.terms()).startsWith("betrieb");
        assertThat(AppState.shared().get(SearchView.STATE_RECENT_TERMS)).startsWith("betrieb");
        // Angelo 27.11.2026: Eine nicht indexierte Quelle ohne Online-Suche bekommt einmal die Rückfrage; „Nein“ startet
        // nichts, und weitere Stapel fragen nicht erneut.
        await(() -> !offered.isEmpty());
        assertThat(offered).containsExactly("Dateien");
        loadAll(view[0], table, more, 2 * PAGES_PER_SOURCE);
        // Vorläufige Treffer kennen ihren Space erst, wenn ihre Seite geladen ist: bis zum Ende des Ladens warten.
        await(() -> view[0].progressText.getText().startsWith(SearchView.DONE));

        Set<String> keys = new HashSet<>();
        Set<String> sourcesSeen = new HashSet<>();
        String[] first = new String[4];
        SwingUtilities.invokeAndWait(() -> {
            for (int column = 0; column < 4; column++) {
                first[column] = String.valueOf(table.getModel().getValueAt(0, column));
            }
            for (int row = 0; row < table.getRowCount(); row++) {
                String sourceId = (String) table.getModel().getValueAt(row, 1);
                sourcesSeen.add(sourceId);
                keys.add(sourceId + "/" + table.getModel().getValueAt(row, 0));
            }
        });
        assertThat(first[0]).startsWith("Betrieb ");
        assertThat(first[2]).isEqualTo("DEV");
        assertThat(keys).hasSize(2 * PAGES_PER_SOURCE);
        assertThat(sourcesSeen).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(view[0].statusText()).doesNotContain("unvollständig").doesNotContain("Fachbegriffen");
        assertThat(view[0].termSearchesStarted).as("ohne Haken keine Suche nach Seiten zu Fachbegriffen").isZero();
        assertThat(view[0].progressText.getText()).isEqualTo("Die Suche ist abgeschlossen · "
                + (2 * PAGES_PER_SOURCE) + " Treffer");

        // Überschrift „Suche in …“ (Angelo, 23.11.2026): nennt den Bereich, ein Klick wählt ihn links, gelb markiert.
        List<List<Explorer.Scope>> located = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            view[0].onLocate(located::add);
            view[0].heading.select();
        });
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Wissensquellen");
        assertThat(view[0].heading.selected()).isTrue();
        assertThat(view[0].repeat.isEnabled()).isTrue();
        assertThat(located).containsExactly(List.of());
        assertThat(view[0].areaTitle(List.of(new Explorer.Scope("alpha", "DEV", "7", "Betriebshandbuch"))))
                .as("eine Seite als Bereich heißt wie die Seite").isEqualTo("Betriebshandbuch");
        assertThat(SearchView.ISSUE_KEY.matcher("HAVDBAR-86012").matches()).isTrue();
        assertThat(SearchView.ISSUE_KEY.matcher("Betrieb 2026").matches()).isFalse();
        // Projektteil alphanumerisch, Groß-/Kleinschreibung egal (Angelo, 27.11.2026).
        assertThat(SearchView.ISSUE_KEY.matcher("Prio-1234").matches()).isTrue();
        assertThat(SearchView.ISSUE_KEY.matcher("dev2-7").matches()).isTrue();
        assertThat(SearchView.ISSUE_KEY.matcher("4711").matches()).isFalse();

        // Ein Klick (Auswahl) zeigt die Seite des Treffers schon im Explorer, ohne sie zu öffnen.
        List<String> revealed = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        context.setNavigator(new AppContext.Navigator() {
            @Override public void show(AppContext.Section section) { }
            @Override public void openPage(String sourceId, String pageId) { opened.add(pageId); }
            @Override public void openSpace(String sourceId, String spaceKey) { }
            @Override public void showJob(String jobId) { }
            @Override public void showDiagnosis(String jobId) { }
            @Override public void download(String sourceId, List<String> spaceKeys) { }
            @Override public void search(String text) { }
            @Override public void revealPage(String sourceId, String pageId) { revealed.add(sourceId + "/" + pageId); }
        });
        String[] expected = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            table.setRowSelectionInterval(1, 1);
            expected[0] = table.getModel().getValueAt(table.convertRowIndexToModel(1), 1) + "/"
                    + ((SearchView.HitsModel) table.getModel()).rows.get(table.convertRowIndexToModel(1)).pageId();
        });
        assertThat(revealed).containsExactly(expected[0]);
        assertThat(opened).isEmpty();
        assertThat(view[0].heading.selected()).as("ein gewählter Treffer hebt die Markierung auf").isFalse();

        // Angelo 28.11.2026: Der Pfeil unten rechts klappt die Vorschau auf und dreht sich; sie zeigt den gewählten
        // Treffer, ein anderer Klick den nächsten, geöffnet wird dabei nichts.
        assertThat(view[0].help()).isSameAs(view[0].previewToggle());
        assertThat(view[0].previewOpen()).isFalse();
        assertThat(view[0].previewArea().isVisible()).isFalse();
        assertThat(view[0].previewToggle().getToolTipText()).isEqualTo(SearchView.PREVIEW_SHOW);
        javax.swing.Icon up = view[0].previewToggle().getIcon();
        SearchView.HitsModel hits = (SearchView.HitsModel) table.getModel();
        SwingUtilities.invokeAndWait(() -> view[0].previewToggle().doClick());
        assertThat(view[0].previewOpen()).isTrue();
        assertThat(view[0].previewArea().isVisible()).isTrue();
        assertThat(view[0].previewToggle().getToolTipText()).isEqualTo(SearchView.PREVIEW_HIDE);
        assertThat(view[0].previewToggle().getIcon()).isNotSameAs(up)
                .isInstanceOf(com.aresstack.spaceharvester.app.ui.controls.Flipped.class);
        assertThat(view[0].previewPane().titleText())
                .isEqualTo(hits.rows.get(table.convertRowIndexToModel(1)).title());
        SwingUtilities.invokeAndWait(() -> table.setRowSelectionInterval(0, 0));
        assertThat(view[0].previewPane().titleText())
                .isEqualTo(hits.rows.get(table.convertRowIndexToModel(0)).title());
        assertThat(opened).isEmpty();
        assertThat(AppState.shared().getBoolean(SearchView.STATE_PREVIEW, false)).isTrue();
        // Eine geleerte Trefferliste (neue Suche) leert auch die Vorschau.
        SwingUtilities.invokeAndWait(() -> hits.set(List.of()));
        assertThat(view[0].previewPane().titleText()).isBlank();
        SwingUtilities.invokeAndWait(() -> view[0].previewToggle().doClick());
        assertThat(view[0].previewOpen()).isFalse();
        assertThat(view[0].previewArea().isVisible()).isFalse();
        assertThat(AppState.shared().getBoolean(SearchView.STATE_PREVIEW, true)).isFalse();
    }

    @Test
    void aPageNumberBecomesABookmarkForEverySource() {
        assertThat(SearchView.pageIdAddresses(List.of("wiki", "handbuch"), " 4711\u200B"))
                .containsExactly("space-harvester://wiki/page/4711", "space-harvester://handbuch/page/4711");
        assertThat(SearchView.pageIdAddresses(List.of("wiki"), "Plan 4711")).isEmpty();
        assertThat(SearchView.pageIdAddresses(List.of("wiki"), "DEV-4711")).isEmpty();
        assertThat(SearchView.pageIdAddresses(List.of(), "4711")).isEmpty();
    }

    @Test
    void loadedPagesMoveUpButNoHitEverDisappears() {
        SearchView.HitsModel model = new SearchView.HitsModel();
        model.set(List.of(hit("a"), hit("b"), hit("c")));
        // c ist geladen und steht beim Core vorn; b fehlt in der neuen Liste und bleibt trotzdem.
        model.reorder(0, List.of(hit("c"), hit("a")));
        assertThat(pageIds(model)).containsExactly("c", "a", "b");
        // Frühere Stapel bleiben oben; ein Treffer aus ihnen wird nur aktualisiert.
        model.reorder(1, List.of(hit("b"), new SearchView.Hit("s", "c", "neu", "DEV", "", null)));
        assertThat(pageIds(model)).containsExactly("c", "b", "a");
        assertThat(model.getValueAt(0, 0)).isEqualTo("neu");
    }

    /** Die vorläufigen Funde eines Stapels weichen dem fertigen Stapel; frühere Stapel bleiben. */
    @Test
    void provisionalHitsOfABatchAreReplacedByTheFinishedBatch() {
        SearchView.HitsModel model = new SearchView.HitsModel();
        model.set(List.of(hit("a"), hit("b")));
        // Ein vorläufiger Fund für eine schon gezeigte Seite ändert die geprüfte Zeile nicht (Codex-Befund #557).
        model.append(List.of(new SearchView.Hit("s", "a", "nur Quelle", null, "", null), hit("c"), hit("d"),
                hit("c")));
        assertThat(pageIds(model)).containsExactly("a", "b", "c", "d");
        assertThat(model.getValueAt(0, 0)).isEqualTo("Seite a");
        model.truncate(2);
        assertThat(pageIds(model)).containsExactly("a", "b");
        model.add(List.of(hit("d"), hit("e")));
        assertThat(pageIds(model)).containsExactly("a", "b", "d", "e");
        model.truncate(10);
        assertThat(pageIds(model)).hasSize(4);
        model.truncate(0);
        assertThat(model.getRowCount()).isZero();
    }

    private static SearchView.Hit hit(String pageId) {
        return new SearchView.Hit("s", pageId, "Seite " + pageId, "DEV", "", null);
    }

    private static List<String> pageIds(SearchView.HitsModel model) {
        List<String> ids = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            ids.add(model.rows.get(row).pageId());
        }
        return ids;
    }

    @Test
    void waitingForPagesNeverFetchesTheNextBatchOnItsOwn() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        await(() -> table.getRowCount() > 0 && more.isEnabled());
        // Das Nachfragen wiederholt nur den angezeigten Stapel; weitere Treffer holt erst „Weitere laden“.
        Thread.sleep(3 * 1000);
        int[] rows = new int[1];
        SwingUtilities.invokeAndWait(() -> rows[0] = table.getRowCount());
        assertThat(rows[0]).isLessThanOrEqualTo(2 * SearchView.LIMIT);
        assertThat(more.isEnabled()).isTrue();
    }

    private final java.util.Map<SearchView, javax.swing.JPanel> wholes = new java.util.IdentityHashMap<>();

    /** Arbeitsfläche und Stand für die Statuszeile zusammen, wie im Hauptfenster. */
    private javax.swing.JPanel whole(SearchView view) {
        return wholes.computeIfAbsent(view, v -> {
            javax.swing.JPanel[] panel = new javax.swing.JPanel[1];
            Runnable build = () -> {
                panel[0] = new javax.swing.JPanel(new java.awt.BorderLayout());
                panel[0].add(v.component(), java.awt.BorderLayout.CENTER);
                panel[0].add(v.statusInfo(), java.awt.BorderLayout.SOUTH);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                build.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(build);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return panel[0];
        });
    }

    /**
     * Klickt „Weitere laden“, bis alle erwarteten Treffer da sind und nichts mehr folgt. Solange der Ring über der
     * Liste liegt, läuft ein Stapel noch: Seine vorläufigen Funde zählen nicht als Ende.
     */
    private static void loadAll(SearchView view, JTable table, AbstractButton more, int expected) throws Exception {
        for (int i = 0; i < 1200; i++) {
            boolean[] state = new boolean[2];
            SwingUtilities.invokeAndWait(() -> {
                state[0] = table.getRowCount() == expected && !more.isEnabled() && !view.overlay.isBusy();
                state[1] = more.isEnabled();
                if (state[1]) {
                    more.doClick();
                }
            });
            if (state[0]) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Nach „Weitere laden“ nicht alle Treffer da: " + table.getRowCount() + " Zeilen, "
                + (more.isEnabled() ? "weitere" : "keine weiteren"));
    }

    @Test
    void pagesStillLoadingIntoTheCacheAreCounted() {
        assertThat(SearchView.progressLine(12, 25, 7)).isEqualTo("12 von 25 Seiten im Cache · 7 Treffer");
        assertThat(SearchView.progressLine(0, 1, 1)).isEqualTo("0 von 1 Seite im Cache · 1 Treffer");
        assertThat(SearchView.LIMIT).as("ein Bildschirm, nicht der Höchstwert des Core").isLessThan(50);
    }

    @Test
    void anUnreachableSourceIsNamedAndTheResultMarkedIncomplete() throws Exception {
        start(true);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        loadAll(view[0], table, button(whole(view[0]), "Weitere laden"), 2 * PAGES_PER_SOURCE);
        assertThat(view[0].statusText()).contains("Offline: nicht erreichbar", "unvollständig");
    }

    @Test
    void keywordSearchIsTheDefaultAndWithoutAnEmbeddingModelTheSemanticCheckboxIsLocked() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of()));
        AbstractButton semantic = view[0].semantic;
        JTable table = find(whole(view[0]), JTable.class).get(0);
        List<String> columns = new ArrayList<>();
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            columns.add(table.getModel().getColumnName(column));
        }

        assertThat(semantic.isSelected()).as("Vorgabe bleibt die Schlagwortsuche").isFalse();
        // Ohne Embedding-Modell gibt es keine semantische Suche: der Haken ist gesperrt und nennt den Grund.
        assertThat(semantic.isEnabled()).isFalse();
        assertThat(semantic.getToolTipText()).contains("Gesperrt", "Embedding-Modell");
        // Der Grund steht auch lesbar im Popup der Suchoptionen (#515).
        assertThat(view[0].semanticNote.getText()).contains("Gesperrt", "Embedding-Modell");
        assertThat(view[0].modeLabel.getText()).isEqualTo("Modus: Schlagwortsuche");
        assertThat(columns).containsExactly("Titel", "Quelle", "Space", "Auszug", "Geändert");
        assertThat(find(whole(view[0]), AbstractButton.class)).extracting(AbstractButton::getText)
                .doesNotContain("Relevanz", "Titel", "Neueste");
    }

    @Test
    void onlyTechnicalTermsReplacesTheHitsWithTheGlossaryViewAndNeedsNoText() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of());
            // „Nur Fachbegriffe“ gehört zur Wortsuche (Angelo 27.11.2026).
            view[0].switchMode(true);
            view[0].technicalTerms.setSelected(true);
            assertThat(view[0].modeLabel.getText()).isEqualTo(SearchView.TECHNICAL_TERMS_MODE);
            assertThat(view[0].termsPanel.isVisible()).isTrue();
            assertThat(view[0].technicalScope()).isEqualTo(
                    com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope.ALL);
            view[0].search("");
            view[0].scope(List.of(new Explorer.Scope("alpha", null)));
            assertThat(view[0].technicalScope().sourceIds()).containsExactly("alpha");
            // Auflage AT 5: zwei gewählte Knoten weiten nicht auf alle auf.
            view[0].scope(List.of(new Explorer.Scope("alpha", "DEV"), new Explorer.Scope("beta", null)));
            assertThat(view[0].technicalScope().sourceIds()).containsExactlyInAnyOrder("alpha", "beta");
            view[0].technicalTerms.setSelected(false);
            assertThat(view[0].modeLabel.getText()).isEqualTo(SearchView.WORDS_MODE);
            assertThat(view[0].termsPanel.isVisible()).isFalse();
            assertThat(view[0].wordsPanel.isVisible()).isTrue();
            view[0].switchMode(false);
            assertThat(view[0].modeLabel.getText()).isEqualTo("Modus: Schlagwortsuche");
            assertThat(view[0].wordsPanel.isVisible()).isFalse();
        });
    }

    @Test
    void theSemanticCheckboxRunsTheProgressiveSearchOfTheCoreUntilItNamesWhyItEnded() throws Exception {
        start(false, embeddings());
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of()));
        // Mit Embedding-Modell ist der Haken frei; ohne Chat-Modell kommen die Varianten nach Regeln.
        await(view[0].semantic::isEnabled);
        assertThat(view[0].semantic.getToolTipText()).contains("nach Regeln");
        SwingUtilities.invokeAndWait(() -> {
            // Reste einer Schlagwortsuche mit Seiten zu Fachbegriffen (Abnahme AT): gehen mit der neuen Suche.
            view[0].termPagesNoteForTest(SearchView.TERM_PAGES_UNCHECKED);
            view[0].semantic.setSelected(true);
            assertThat(view[0].modeLabel.getText()).isEqualTo("Modus: Semantische Suche");
            view[0].search("Betrieb Plattform Wartung");
        });
        // Sofort sichtbar, dass gesucht wird; am Ende der Grund aus dem Core, ohne „Weitere laden“.
        assertThat(view[0].progressText.getText()).startsWith("Suche läuft");
        await(() -> !view[0].progressText.getText().startsWith("Suche läuft"));
        assertThat(view[0].progressText.getText()).startsWith(SearchView.DONE);
        JTable table = find(whole(view[0]), JTable.class).get(0);
        assertThat(table.getRowCount()).isPositive();
        assertThat(view[0].statusText()).contains("semantisch").doesNotContain("Fachbegriffen");
        assertThat(button(whole(view[0]), "Weitere laden").isVisible()).isFalse();
        // Am Ende „Fertig“ statt „Abbrechen“; die Ansicht hat keine eigene Aktionszeile mehr.
        assertThat(view[0].actions()).isNull();
        assertThat(view[0].endState.isVisible()).isTrue();
        assertThat(view[0].endState.getText()).isEqualTo("Fertig");
        assertThat(view[0].cancel.isVisible()).isFalse();
    }

    @Test
    void aLockedSemanticSearchNamesTheMissingPieceInsteadOfAlwaysTheModel() {
        var none = com.aresstack.spaceharvester.app.settings.AppSettings.AiSettings.NONE;
        assertThat(SearchView.lockedTip(none)).contains("kein KI-Dienst");
        var noDimension = new com.aresstack.spaceharvester.app.settings.AppSettings.AiSettings("http://ai/v1", null,
                "bge-m3", null, null, List.of());
        assertThat(SearchView.lockedTip(noDimension)).contains("bge-m3", "Vektorlänge", "Modell prüfen");
        var noModel = new com.aresstack.spaceharvester.app.settings.AppSettings.AiSettings("http://ai/v1", null,
                null, null, null, List.of());
        assertThat(SearchView.lockedTip(noModel)).contains("kein Embedding-Modell");
    }

    @Test
    void aFailedSemanticSearchNamesTheReasonFromTheCore() {
        var reason = com.aresstack.spaceharvester.ai.AiServiceException.Reason.class;
        assertThat(SearchView.failureText(Enum.valueOf(reason, "UNAUTHORIZED"))).contains("API-Key");
        assertThat(SearchView.failureText(Enum.valueOf(reason, "TIMEOUT"))).contains("nicht rechtzeitig");
        assertThat(SearchView.failureText(null)).contains("Quellen");
    }

    @Test
    void everyEndOfTheProgressiveSearchReadsAsPlainWords() {
        assertThat(SearchView.completion(ProgressiveResult.Completion.QUALITY_REACHED))
                .isEqualTo("Die Suche ist abgeschlossen · genug passende Treffer");
        assertThat(SearchView.completion(ProgressiveResult.Completion.BUDGET_EXHAUSTED))
                .doesNotContain("genug passende");
        assertThat(SearchView.completion(ProgressiveResult.Completion.CANCELLED)).isEqualTo("Suche abgebrochen");
    }

    @Test
    void allSourcesCanBeRestrictedToChosenSources() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        // Der Umfang kommt aus dem Explorer: ohne Auswahl alle Wissensquellen, sonst die gewählten.
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of()));
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Alle Wissensquellen");
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of(new Explorer.Scope("beta", null)));
            view[0].search("betrieb");
        });
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Beta");
        assertThat(view[0].chosenSources()).containsExactly("beta");
        assertThat(view[0].chosenSpaces()).isEmpty();
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        loadAll(view[0], table, more, PAGES_PER_SOURCE);
        Set<String> sourcesSeen = new HashSet<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                sourcesSeen.add((String) table.getModel().getValueAt(row, 1));
            }
        });
        assertThat(sourcesSeen).containsExactly("beta");
        assertThat(view[0].statusText()).doesNotContain("Dateien");

        // Ohne Auswahl im Explorer und mit allen Quellen angehakt: Suche ohne sourceIds.
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of());
            view[0].search("betrieb");
        });
        assertThat(view[0].chosenSources()).isEmpty();
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Alle Wissensquellen");
        loadAll(view[0], table, more, 2 * PAGES_PER_SOURCE);
    }

    /**
     * Lädt die erste Quelle beim Bereichswechsel noch Seiten in den Cache, folgt die Ansicht ihrem Stapel nicht
     * weiter: Sonst sortiert ihre nächste Meldung mit der alten Anfrage neu und nimmt der nachgesuchten Quelle
     * „Weitere laden“ (roter Lauf auf main, 28.11.2026).
     */
    @Test
    void choosingAnotherAreaStopsFollowingThePreviousBatch() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of(new Explorer.Scope("beta", null)));
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        loadAll(view[0], table, more, PAGES_PER_SOURCE);
        String[] followed = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            // Der Zustand des Wettlaufs, fest hergestellt: der Stapel der ersten Quelle lädt noch.
            view[0].progressId = "stapel-der-ersten-quelle";
            view[0].scope(List.of(new Explorer.Scope("alpha", null)));
            followed[0] = view[0].progressId;
        });
        assertThat(followed[0]).as("dem alten Stapel wird nicht mehr gefolgt").isNull();
        loadAll(view[0], table, more, PAGES_PER_SOURCE);
    }

    /** Ein anderer Bereich links (Angelo 21./23.11.2026): die fehlende Quelle wird nachgesucht, die Liste gefiltert. */
    @Test
    void choosingAnotherAreaSearchesTheSameTextThere() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of(new Explorer.Scope("beta", null)));
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        loadAll(view[0], table, more, PAGES_PER_SOURCE);
        // Nur der Bereich wechselt, keine neue Eingabe: der Begriff bleibt, die Treffer kommen aus Alpha.
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of(new Explorer.Scope("alpha", null))));
        assertThat(view[0].queryText()).isEqualTo("betrieb");
        assertThat(view[0].chosenSources()).containsExactly("alpha");
        loadAll(view[0], table, more, PAGES_PER_SOURCE);
        Set<String> sourcesSeen = new HashSet<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                sourcesSeen.add((String) table.getValueAt(row, 1));
            }
        });
        assertThat(sourcesSeen).containsExactly("alpha");
    }

    /**
     * Angelo 23.11.2026: eine Trefferliste, der Baum filtert. Ein Space einer schon durchsuchten Quelle filtert nur,
     * eine neue Quelle wird allein nachgesucht; Zurück und Vor gehen durch die Filter.
     */
    @Test
    void theTreeFiltersOneListAndOnlyMissingSourcesAreSearched() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of(new Explorer.Scope("beta", null)));
            view[0].search("betrieb");
        });
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Beta");
        assertThat(view[0].covered).containsExactly("beta");
        // Erst die Antwort der Suche abwarten: Vorher kennt die Ansicht keine Anfrage (lastQuery), und eine neue
        // Quelle löst dann bewusst eine ganz neue Suche aus statt nachzusuchen (bis 24.11.2026 zeitabhängig rot).
        await(() -> !view[0].overlay.isBusy() && view[0].model.rows.size() > 0);
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of(new Explorer.Scope("beta", "DEV"))));
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Beta › DEV");
        assertThat(view[0].covered).as("nur gefiltert, nicht neu gesucht").containsExactly("beta");
        assertThat(view[0].visits).hasSize(2);
        SwingUtilities.invokeAndWait(() -> view[0].backVisit.doClick());
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Beta");
        assertThat(view[0].canGoForward()).isTrue();
        SwingUtilities.invokeAndWait(() -> view[0].forward());
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Beta › DEV");
        // Eine noch nicht durchsuchte Quelle: nur sie wird nachgesucht.
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of(new Explorer.Scope("alpha", null))));
        assertThat(view[0].heading.titleText()).isEqualTo("Suche in Alpha");
        assertThat(view[0].covered).containsExactly("beta", "alpha");
        assertThat(view[0].areaTitle(List.of(new Explorer.Scope("alpha", null), new Explorer.Scope("beta", "DEV"))))
                .isEqualTo("Alpha, Beta › DEV");
        assertThat(view[0].areaTitle(List.of(new Explorer.Scope("alpha", "A"), new Explorer.Scope("alpha", "B"),
                new Explorer.Scope("beta", "C"), new Explorer.Scope("beta", "D")))).isEqualTo("4 Bereichen");
    }

    @Test
    void mergingTwoSearchesOrdersByTheComparableScore() {
        SearchView.Hit exact = new SearchView.Hit("j", "j1", "Betrieb", "P", "", null);
        SearchView.Hit snippetOnly = new SearchView.Hit("c", "c1", "Release", "S", "zum Betrieb", null);
        SearchView.Hit titleWord = new SearchView.Hit("c", "c2", "Betrieb Teil 2", "S", "", null);
        // Jede Liste behält ihre Reihenfolge; an jeder Stelle gewinnt der höhere Punktwert, Doppelte bleiben einmal.
        List<SearchView.Hit> merged = SearchView.interleave("Betrieb", List.of(snippetOnly, titleWord),
                List.of(exact, snippetOnly));
        assertThat(merged).extracting(SearchView.Hit::pageId).containsExactly("j1", "c1", "c2");
    }

    /**
     * Angelo 20.11.2026: statt „Überall suchen“ ein Häkchen je eingetragener Quelle, Vorgabe alle an; abgehakt fehlt
     * die Quelle in der Suche, auch wenn der Explorer sie wählt; die Häkchen bleiben beim Wechsel der Auswahl.
     */
    @Test
    void everySourceHasACheckboxCheckedByDefaultAndUncheckedSourcesLeaveTheSearch() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].sourceBoxes.keySet()).containsExactly("alpha", "beta", "dateien");
            assertThat(view[0].sourceBoxes.values()).extracting(AbstractButton::getText)
                    .containsExactly("Alpha", "Beta", "Dateien");
            assertThat(view[0].sourceBoxes.values()).allMatch(AbstractButton::isSelected);
            assertThat(view[0].chosenSources()).isEmpty();
            assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Alle Wissensquellen");
            // Eine Quelle abgehakt: Auswahl sind die übrigen ganz, die nächste Suche nennt sie ausdrücklich.
            view[0].sourceBoxes.get("alpha").doClick();
            assertThat(view[0].chosenSources()).containsExactly("beta", "dateien");
            assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Beta, Dateien");
            // Links nur ein Space: die Quelle ist teilweise gewählt, der Space steht hinter ihrem Namen.
            view[0].scope(List.of(new Explorer.Scope("beta", "DEV")));
            assertThat(view[0].chosenSources()).containsExactly("beta");
            assertThat(view[0].chosenSpaces()).containsExactly("DEV");
            assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Beta › DEV");
            assertThat(view[0].sourceBoxes.get("beta").isSelected()).isTrue();
            assertThat(com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(
                    view[0].sourceBoxes.get("beta"))).isTrue();
            assertThat(view[0].sourceBoxes.get("beta").getText()).isEqualTo("Beta · DEV");
            assertThat(view[0].sourceBoxes.get("alpha").isSelected()).isFalse();
            // Ein Klick auf die teilweise gewählte Quelle wählt sie ganz.
            view[0].sourceBoxes.get("beta").doClick();
            assertThat(view[0].sourceBoxes.get("beta").isSelected()).isTrue();
            assertThat(com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(
                    view[0].sourceBoxes.get("beta"))).isFalse();
            assertThat(view[0].sourceBoxes.get("beta").getText()).isEqualTo("Beta");
            assertThat(view[0].chosenSources()).containsExactly("beta");
            assertThat(view[0].chosenSpaces()).isEmpty();
            // Die letzte Quelle lässt sich nicht abhaken.
            view[0].sourceBoxes.get("beta").doClick();
            assertThat(view[0].sourceBoxes.get("beta").isSelected()).isTrue();
            assertThat(view[0].chosenSources()).containsExactly("beta");
            view[0].search("betrieb");
            assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Beta");
        });
    }

    @Test
    void aWholeSourceWithSpacesOfAnotherSearchesTheWholeOneAndOnlyTheChosenSpaces() {
        Map<String, List<String>> spaces = Map.of("wiki", List.of("DEV", "OPS"),
                "akten", List.of("CL1", "CL2", "CL3", "OPS"));
        List<Explorer.Scope> mixed = List.of(new Explorer.Scope("wiki", null), new Explorer.Scope("akten", "CL2"),
                new Explorer.Scope("akten", "CL3"));

        assertThat(SearchView.exclusions(mixed, Set.of("wiki", "akten"), spaces::get))
                .as("aus „akten“ fallen CL1 weg; OPS hat auch die ganze Quelle, er bleibt").containsExactly("CL1");
        assertThat(SearchView.exclusions(List.of(new Explorer.Scope("akten", "CL2")), Set.of("akten"), spaces::get))
                .as("nur Spaces: die Anfrage nennt sie selbst").isEmpty();
        assertThat(SearchView.exclusions(List.of(new Explorer.Scope("wiki", null)), Set.of("wiki"), spaces::get))
                .isEmpty();
    }

    @Test
    void spacesOfSeveralSourcesAreTheScopeAndAreListedBehindTheirSource() {
        assertThat(SearchView.boxText("Confluence HaVdB", List.of("CL2", "CL3")))
                .isEqualTo("Confluence HaVdB · CL2, CL3");
        assertThat(SearchView.boxText("Wiki", List.of("A", "B", "C", "D", "E"))).isEqualTo("Wiki · A, B, C +2");
        assertThat(SearchView.boxText("Wiki", List.of())).isEqualTo("Wiki");
    }

    /** Ein Häkchen je Dateityp, Vorgabe alle; die abgehakten fehlen in der Anfrage, die Zeile nennt die Auswahl. */
    @Test
    void everyDocumentKindHasACheckboxAndTheCheckedOnesGoIntoTheQuery() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].kindBoxes.keySet()).containsExactly(DocumentKind.values());
            assertThat(view[0].kindBoxes.values()).allMatch(AbstractButton::isSelected);
            assertThat(view[0].kindBoxes.get(DocumentKind.PAGE).getText()).isEqualTo("Seiten (HTML)");
            assertThat(view[0].chosenKinds()).isEmpty();
            assertThat(view[0].kindsLabel.getText()).isEqualTo("Dateitypen: alle");
            for (DocumentKind kind : DocumentKind.values()) {
                if (kind != DocumentKind.PDF && kind != DocumentKind.WORD) {
                    view[0].kindBoxes.get(kind).doClick();
                }
            }
            assertThat(view[0].chosenKinds()).containsExactly(DocumentKind.PDF, DocumentKind.WORD);
            assertThat(view[0].kindsLabel.getText()).isEqualTo("Dateitypen: PDF, Word");
            // Ohne jedes Häkchen sucht die Ansicht nicht, statt still nichts zu finden.
            view[0].kindBoxes.get(DocumentKind.PDF).doClick();
            view[0].kindBoxes.get(DocumentKind.WORD).doClick();
            assertThat(view[0].kindsLabel.getText()).isEqualTo("Dateitypen: keiner angehakt");
            view[0].search("betrieb");
            assertThat(view[0].statusText()).doesNotContain("Treffer");
        });
    }

    /** Das Popup am Pfeil des Suchfelds trägt die Optionen und Hinweise; Umschalten lässt den Suchtext stehen. */
    @Test
    void theOptionsPopupHoldsBothCheckboxesAndTheHintsAndKeepsTheQuery() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(find(view[0].options, AbstractButton.class)).extracting(AbstractButton::getText)
                    .contains("Alpha", "Beta", "Dateien", "Seiten (HTML)", "PDF", "Word", "PowerPoint", "Excel",
                            "Bilder", "Videos", SearchView.SEMANTIC);
            assertThat(find(view[0].options, javax.swing.JLabel.class)).extracting(javax.swing.JLabel::getText)
                    .contains("WISSENSQUELLEN", "DATEITYPEN", "SUCHMETHODEN");
            // Suchmethoden als Gruppe: Semantische Suche, darunter Nachbarseiten (R2), Standard aus.
            // K5a: „Synonyme der Fachbegriffe einbeziehen“ direkt unter den Nachbarseiten, Standard aus.
            List<String> methods = find(view[0].options, AbstractButton.class).stream().map(AbstractButton::getText)
                    .filter(text -> text.equals(SearchView.SEMANTIC) || text.equals(SearchView.NEIGHBORS)
                            || text.equals(SearchView.SYNONYMS) || text.equals(SearchView.TERM_PAGES)
                            || text.equals(SearchView.TECHNICAL_TERMS)).toList();
            // W8: „Seiten zu Fachbegriffen einbeziehen“ unter den Synonymen, Standard aus. „Nur Fachbegriffe“ steht
            // seit dem 27.11.2026 nur in der Wortsuche.
            assertThat(methods).containsExactly(SearchView.SEMANTIC, SearchView.NEIGHBORS, SearchView.SYNONYMS,
                    SearchView.TERM_PAGES);
            assertThat(view[0].termPages.isSelected()).as("Seiten zu Fachbegriffen ist Standard aus").isFalse();
            assertThat(view[0].termPages.isEnabled()).isTrue();
            assertThat(view[0].modeLabel.getText()).doesNotContain("Fachbegriffe");
            view[0].termPages.doClick();
            assertThat(view[0].modeLabel.getText()).endsWith(" + Fachbegriffe");
            view[0].termPages.doClick();
            assertThat(view[0].modeLabel.getText()).doesNotContain("Fachbegriffe");
            assertThat(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().termPages()).isFalse();
            assertThat(view[0].technicalTerms.isSelected()).as("„Nur Fachbegriffe“ ist Standard aus").isFalse();
            assertThat(view[0].glossarySynonyms.isSelected()).isFalse();
            assertThat(view[0].glossarySynonyms.isEnabled()).isTrue();
            assertThat(view[0].modeLabel.getText()).doesNotContain("Synonyme");
            view[0].glossarySynonyms.doClick();
            assertThat(view[0].modeLabel.getText()).endsWith(" + Synonyme");
            assertThat(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().glossarySynonyms()).isTrue();
            view[0].glossarySynonyms.doClick();
            assertThat(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().glossarySynonyms()).isFalse();
            assertThat(view[0].neighbors.isEnabled()).isTrue();
            assertThat(view[0].neighbors.getToolTipText()).isEqualTo(SearchView.NEIGHBORS_TIP);
            assertThat(find(view[0].options, javax.swing.JLabel.class)).extracting(javax.swing.JLabel::getText)
                    .anySatisfy(text -> assertThat(text).contains("Hinweise zur Suche", "space-harvester://"));
            assertThat(view[0].optionsButton.getAccessibleContext().getAccessibleName()).isEqualTo("Suchoptionen");
            // Die Optionen sind Menüpunkte mit dem Modell der Häkchen: per Tastatur erreichbar und gleich geschaltet.
            javax.swing.JCheckBoxMenuItem item = find(view[0].options, javax.swing.JCheckBoxMenuItem.class).get(0);
            item.doClick();
            assertThat(view[0].sourceBoxes.get("alpha").isSelected()).isFalse();
            item.doClick();
            assertThat(view[0].sourceBoxes.get("alpha").isSelected()).isTrue();
            // Keine zweite Eingabezeile und keine Umfang-/Modus-Zeile über der Tabelle.
            // (Das Feld „Synonym hinzufügen …“ gehört zur Ansicht „Nur Fachbegriffe“, nicht zur Suchzeile.)
            assertThat(find(whole(view[0]), javax.swing.JTextField.class)).allMatch(field ->
                    SwingUtilities.isDescendingFrom(field, view[0].termsPanel));
            assertThat(SwingUtilities.isDescendingFrom(view[0].scopeLabel, view[0].component())).isFalse();
            view[0].search("betrieb");
            view[0].kindBoxes.get(DocumentKind.IMAGE).doClick();
            assertThat(view[0].queryText()).isEqualTo("betrieb");
        });
    }

    /**
     * Die Lupe wechselt zur Wortsuche (Angelo 27.11.2026): Lupe mit „w“, statt der Seitenmethoden die Worttypen,
     * „Nur Fachbegriffe“ und Synonyme; die Wahl merkt der Application State, auch für die nächste Ansicht.
     */
    @Test
    void theMagnifierSwitchesToTheWordSearchWhichSearchesOnlyTermsAndRemembersTheChoice() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of());
            assertThat(view[0].words).isFalse();
            assertThat(view[0].modeIcon.word()).isFalse();
            assertThat(view[0].modeIcon.getToolTipText()).isEqualTo(SearchView.PAGE_MODE_TIP);
            view[0].modeIcon.doClick();
            assertThat(view[0].words).isTrue();
            assertThat(view[0].modeIcon.word()).isTrue();
            assertThat(view[0].modeIcon.getToolTipText()).isEqualTo(SearchView.WORD_MODE_TIP);
            assertThat(AppState.shared().get(SearchView.STATE_MODE)).isEqualTo(SearchView.STATE_WORDS);
            assertThat(view[0].modeLabel.getText()).isEqualTo(SearchView.WORDS_MODE);
            // Statt der Dateitypen nur Seiten und Anhänge (Angelo 27.11.2026).
            assertThat(find(view[0].options, javax.swing.JLabel.class)).extracting(javax.swing.JLabel::getText)
                    .contains("WISSENSQUELLEN", "SEITEN UND ANHÄNGE", "WORTTYPEN", "SUCHMETHODEN")
                    .doesNotContain("DATEITYPEN");
            List<String> items = find(view[0].options, AbstractButton.class).stream().map(AbstractButton::getText)
                    .toList();
            assertThat(items).doesNotContain("PDF", "Word");
            assertThat(view[0].wordPages.isSelected()).isTrue();
            assertThat(view[0].wordDocuments.isSelected()).isTrue();
            view[0].wordDocuments.doClick();
            assertThat(AppState.shared().getBoolean(SearchView.STATE_WORD_DOCUMENTS, true)).isFalse();
            view[0].wordDocuments.doClick();
            assertThat(items).contains("Alpha", SearchView.WORD_PAGES, SearchView.WORD_DOCUMENTS, "Akronyme", "Bezeichner", "Verben", SearchView.TECHNICAL_TERMS,
                    SearchView.SYNONYMS, SearchView.HEADINGS_ONLY).doesNotContain(SearchView.SEMANTIC, SearchView.NEIGHBORS,
                    SearchView.TERM_PAGES);
            assertThat(view[0].wordKindBoxes.get(WordSearch.Kind.ACRONYMS).isSelected()).isTrue();
            assertThat(view[0].wordKindBoxes.get(WordSearch.Kind.IDENTIFIERS).isSelected()).isTrue();
            assertThat(view[0].wordKindBoxes.get(WordSearch.Kind.VERBS).isSelected())
                    .as("Verben gibt es erst nach der Aussagenanalyse").isFalse();
            view[0].wordSynonyms.doClick();
            assertThat(AppState.shared().getBoolean(SearchView.STATE_WORD_SYNONYMS, false)).isTrue();
            assertThat(view[0].modeLabel.getText()).isEqualTo(SearchView.WORDS_MODE + " + Synonyme");
            view[0].wordSynonyms.doClick();
            assertThat(view[0].wordsPanel.isVisible()).isTrue();
            String shots = System.getenv("SCREENSHOTS_DIR");
            if (shots != null && !shots.isBlank()) {
                // Die Suchoptionen der Wortsuche zur Sichtprüfung.
                view[0].options.setSize(view[0].options.getPreferredSize());
                Screenshots.write(view[0].options, java.nio.file.Path.of(shots, "wortsuche-optionen.png"));
            }
            view[0].search("betrieb");
        });
        // Ohne gespeicherte Seiten gibt es keine Begriffe: die Wortsuche sagt es in der Statuszeile.
        await(() -> WordHitsPanel.NO_WORDS.equals(view[0].statusText()));
        SearchView[] next = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            next[0] = new SearchView(context);
            assertThat(next[0].words).as("die letzte Wahl gilt auch nach dem Neustart").isTrue();
            assertThat(next[0].modeIcon.word()).isTrue();
            view[0].modeIcon.doClick();
            assertThat(view[0].words).isFalse();
            assertThat(AppState.shared().get(SearchView.STATE_MODE)).isEqualTo(SearchView.STATE_PAGES);
            assertThat(find(view[0].options, AbstractButton.class)).extracting(AbstractButton::getText)
                    .contains(SearchView.SEMANTIC).doesNotContain("Akronyme", SearchView.TECHNICAL_TERMS);
        });
    }

    @Test
    void theWordStatusCountsWordsAndTheHintsNameWhatIsMissing() {
        assertThat(WordHitsPanel.status(0, false)).isEqualTo(WordHitsPanel.NO_WORDS);
        assertThat(WordHitsPanel.status(1, false)).isEqualTo("1 Wort");
        assertThat(WordHitsPanel.status(120, true)).isEqualTo("120 Wörter, weitere folgen");
        assertThat(SearchView.technicalMissing(SearchView.TECHNICAL_TERMS, "in „Alpha“", true))
                .contains("Nur Fachbegriffe", "noch keine ermittelt", "findet deshalb nichts");
        assertThat(SearchView.technicalQuestion("in „Alpha“", true)).contains("wirklich jetzt ermitteln?");
        assertThat(SearchView.verbsMissing(new WordSearch.VerbCoverage(0, 5, 0), "Alpha"))
                .contains("noch nichts analysiert");
        assertThat(SearchView.verbsMissing(new WordSearch.VerbCoverage(3, 2, 1), "Alpha"))
                .contains("3 Seiten", "davon 1 veraltet");
        assertThat(SearchView.verbsQuestion(new WordSearch.VerbCoverage(0, 5, 0), "Alpha"))
                .endsWith("Aussagen wirklich jetzt analysieren?");
        // Nomen und Kennung gleichen Wortlauts bleiben zwei Zeilen.
        var noun = new com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary("Order", "order",
                com.aresstack.spaceharvester.application.terms.TermKind.NOUN, 1, 1, 1, List.of(), List.of());
        var identifier = new com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary("order", "order",
                com.aresstack.spaceharvester.application.terms.TermKind.IDENTIFIER, 1, 1, 1, List.of(), List.of());
        assertThat(new WordSearch.Word("Order", WordSearch.Kind.IDENTIFIERS, 1, 1, "", noun, null).key())
                .isNotEqualTo(new WordSearch.Word("order", WordSearch.Kind.IDENTIFIERS, 1, 1, "", identifier, null)
                        .key());
        assertThat(WordSearch.origin(true, false)).isEqualTo(
                com.aresstack.spaceharvester.application.terms.TermOrigin.PAGES);
        assertThat(WordSearch.origin(false, true)).isEqualTo(
                com.aresstack.spaceharvester.application.terms.TermOrigin.DOCUMENTS);
        assertThat(WordSearch.origin(true, true)).isEqualTo(
                com.aresstack.spaceharvester.application.terms.TermOrigin.ALL);
    }

    private void start(boolean withOffline) throws Exception {
        start(withOffline, "");
    }

    /**
     * Ein KI-Dienst nur mit Embedding-Modell (Dimension 3) auf 127.0.0.1, für beide Confluence-Quellen freigegeben;
     * jeder Text bekommt denselben Vektor.
     */
    private String embeddings() throws IOException {
        HttpServer service = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        service.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int inputs = Math.max(1, tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)
                    .path("input").size());
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < inputs; i++) {
                data.append(i == 0 ? "" : ",").append("{\"object\":\"embedding\",\"index\":").append(i)
                        .append(",\"embedding\":[0.6,0.8,0.0]}");
            }
            byte[] answer = ("{\"object\":\"list\",\"data\":[" + data + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        service.start();
        servers.add(service);
        return """
                  ai:
                    base-url: http://127.0.0.1:%d/v1
                    embedding-model: test-embedding
                    embedding-dimensions: 3
                    external-processing-sources:
                      - alpha
                      - beta
                """.formatted(service.getAddress().getPort());
    }

    /** @param ai ein {@code ai:}-Block unter {@code space-harvester} oder leer */
    private void start(boolean withOffline, String ai) throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("alpha", "beta")) {
            FakeConfluence confluence = new FakeConfluence();
            confluence.space("DEV", "Entwicklung");
            for (int i = 0; i < PAGES_PER_SOURCE; i++) {
                String pageId = String.valueOf(1000 + i);
                confluence.page(pageId, "Betrieb %03d".formatted(i), "DEV", "<p>Text</p>").storage("<p>Text</p>");
                confluence.wrappedSearchResult(pageId, "Auszug zu Betrieb %03d".formatted(i));
            }
            sources.append(confluence(id, Character.toUpperCase(id.charAt(0)) + id.substring(1), serve(confluence)));
        }
        Path files = Files.createDirectories(home.resolve("dateien"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n");
        sources.append("""
                    - id: dateien
                      type: files
                      title: Dateien
                      options:
                        directory: %s
                """.formatted(files));
        if (withOffline) {
            int port;
            try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = free.getLocalPort();
            }
            sources.append(confluence("offline", "Offline", URI.create("http://127.0.0.1:" + port + "/wiki")));
        }
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                %s  sources:
                %s""".formatted(home.resolve("data"), ai, sources));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    }

    private static String confluence(String id, String title, URI baseUrl) {
        return """
                    - id: %s
                      type: confluence
                      title: %s
                      base-url: %s
                """.formatted(id, title, baseUrl);
    }

    /** Liefert eine {@link FakeConfluence} über HTTP auf 127.0.0.1 aus. */
    private URI serve(FakeConfluence confluence) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> answer(confluence, exchange));
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    private static void answer(FakeConfluence confluence, HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        ConfluenceHttpResponse response;
        synchronized (confluence) {
            response = confluence.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }

    private static AbstractButton button(Container container, String text) {
        return find(container, AbstractButton.class).stream().filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow();
    }

    private static <T> List<T> find(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                found.addAll(find(nested, type));
            }
        }
        return found;
    }

    @Test
    void neighborsKeepTheRestOfTheStatusLineAndAddUpOverRounds() {
        String first = SearchView.withNeighbors("12 Treffer · Wiki: nicht erreichbar · unvollständig", 12, 14, 2);
        assertThat(first).isEqualTo("14 Treffer · 2 Nachbarseiten dazu · Wiki: nicht erreichbar · unvollständig");

        String second = SearchView.withNeighbors(first, 14, 15, 1);
        assertThat(second).isEqualTo("15 Treffer · 3 Nachbarseiten dazu · Wiki: nicht erreichbar · unvollständig");
    }

    @Test
    void neighborsArrivingDuringLoadMoreWaitForTheBatchAndAreNeverShownTwice() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].model.set(List.of(row("a"), row("b")));
            // „Weitere laden“ läuft: angekommene Nachbarn warten, sonst schnitte der Stapel sie ab.
            view[0].searching = new java.util.concurrent.CompletableFuture<Void>();
            view[0].appendNeighbors(List.of(neighbor("n1")));
        });
        assertThat(view[0].model.rows).extracting(SearchView.Hit::pageId).containsExactly("a", "b");

        SwingUtilities.invokeAndWait(() -> {
            view[0].searching = null;
            view[0].model.truncate(2);
            view[0].model.add(List.of(row("c")));
            view[0].releaseNeighbors();
            view[0].appendNeighbors(List.of(neighbor("n1"), neighbor("n2")));
        });

        assertThat(view[0].model.rows).extracting(SearchView.Hit::pageId).containsExactly("a", "b", "c", "n1", "n2");
    }

    @Test
    void termPagesKeepTheRestOfTheStatusLineAndReplaceTheirOwnPart() {
        String first = SearchView.withTermTail("12 Treffer · Wiki: nicht erreichbar", "", 14,
                " · 2 Seiten zu Fachbegriffen dazu");
        assertThat(first).isEqualTo("14 Treffer · Wiki: nicht erreichbar · 2 Seiten zu Fachbegriffen dazu");

        // Nachbarseiten danach: der alte Teil geht, der neue steht am Ende, die Zahl stimmt.
        String withNeighbors = SearchView.withNeighbors(first, 14, 15, 1);
        String second = SearchView.withTermTail(withNeighbors, " · 2 Seiten zu Fachbegriffen dazu", 15,
                " · 2 Seiten zu Fachbegriffen dazu · " + SearchView.TERM_PAGES_UNCHECKED);
        assertThat(second).isEqualTo("15 Treffer · 1 Nachbarseite dazu · Wiki: nicht erreichbar · 2 Seiten zu "
                + "Fachbegriffen dazu · " + SearchView.TERM_PAGES_UNCHECKED);

        assertThat(SearchView.withTermTail("Keine Treffer", "", 3, " · 3 Seiten zu Fachbegriffen dazu"))
                .isEqualTo("3 Treffer · 3 Seiten zu Fachbegriffen dazu");
    }

    @Test
    void termPagesAreNeverShownTwiceAreCountedAndAYellowNoteSurvivesALaterRow() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].model.set(List.of(row("a"), row("b")));
            view[0].status.setText("2 Treffer");
            // Kommt während eines Stapels: wartet auf ihn.
            view[0].searching = new java.util.concurrent.CompletableFuture<Void>();
            view[0].appendTermPages(List.of(neighbor("t1")));
        });
        assertThat(view[0].model.rows).extracting(SearchView.Hit::pageId).containsExactly("a", "b");

        SwingUtilities.invokeAndWait(() -> {
            view[0].searching = null;
            view[0].releaseNeighbors();
            view[0].appendTermPages(List.of(neighbor("a"), neighbor("t1"), neighbor("t2")));
        });

        assertThat(view[0].model.rows).extracting(SearchView.Hit::pageId).containsExactly("a", "b", "t1", "t2");
        assertThat(view[0].status.getText()).isEqualTo("4 Treffer · 2 Seiten zu Fachbegriffen dazu");

        SwingUtilities.invokeAndWait(() -> {
            view[0].termPagesNoteForTest(SearchView.TERM_PAGES_CAPPED);
            view[0].appendNeighbors(List.of(neighbor("n1")));
        });
        assertThat(view[0].status.getText()).startsWith("5 Treffer · 1 Nachbarseite dazu")
                .endsWith("2 Seiten zu Fachbegriffen dazu · " + SearchView.TERM_PAGES_CAPPED);
        assertThat(view[0].status.getForeground()).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.ORANGE);
    }

    @Test
    void cancelStaysWhileTermPagesRunAndComesBackForTheNextLoad() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].model.set(List.of(row("a")));
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            view[0].termPagesStop = stop;
            // (a) Ein zweites „Fertig“ nach dem Cache-Laden blendet Abbrechen nicht aus, solange die Begriffe laufen.
            view[0].idle("Fertig");
            assertThat(view[0].cancel.isVisible()).isTrue();
            assertThat(view[0].cancel.isEnabled()).isTrue();
            // (b) Nur die Begriffssuche abbrechen: sie hört auf, Abbrechen geht.
            view[0].cancelSearch();
            assertThat(stop.get()).isTrue();
            assertThat(view[0].cancel.isVisible()).isFalse();
            view[0].termPagesStop = null;
            // Das nächste Laden in den Cache schaltet Abbrechen wieder ein.
            view[0].progressId = "lauf";
            view[0].progress(new com.aresstack.spaceharvester.application.query.KeywordSearch.Progress("lauf", 4, 1,
                    0, false));
        });
        assertThat(view[0].cancel.isVisible()).isTrue();
        assertThat(view[0].cancel.isEnabled()).isTrue();
    }

    @Test
    void theSelectionOnTheLeftChecksTheSourcesAndCheckingSelectsThemOnTheLeft() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        await(() -> context.runtime() != null);
        await(() -> view[0].sourceBoxes.size() == 3);
        List<List<Explorer.Scope>> selectedLeft = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            // Wie das Hauptfenster: der Baum wählt und meldet die Auswahl zurück.
            view[0].onSourcesChecked(scopes -> {
                selectedLeft.add(scopes);
                view[0].scope(scopes);
            });
            view[0].scope(List.of(new Explorer.Scope("alpha", null)));
            assertThat(view[0].sourceBoxes.get("alpha").isSelected()).isTrue();
            assertThat(view[0].sourceBoxes.get("beta").isSelected()).as("nur die links gewählte Quelle").isFalse();
            assertThat(view[0].searchedSources()).containsExactly("alpha");

            // Beta anhaken: links werden Alpha und Beta gewählt.
            view[0].sourceBoxes.get("beta").doClick();
            assertThat(selectedLeft).last().isEqualTo(List.of(new Explorer.Scope("alpha", null),
                    new Explorer.Scope("beta", null)));
            // Dateien dazu: alle angehakt heißt links „Wissensquellen“.
            view[0].sourceBoxes.get("dateien").doClick();
            assertThat(selectedLeft).last().isEqualTo(List.of());
            assertThat(view[0].sourceBoxes.values()).allMatch(javax.swing.AbstractButton::isSelected);

            // Alpha abhaken: links bleiben Beta und Dateien gewählt.
            view[0].sourceBoxes.get("alpha").doClick();
            assertThat(selectedLeft).last().isEqualTo(List.of(new Explorer.Scope("beta", null),
                    new Explorer.Scope("dateien", null)));
            assertThat(view[0].searchedSources()).containsExactly("beta", "dateien");

            // Links ein Space aus Beta und einer aus Dateien: beide teilweise, Alpha abgehakt.
            view[0].scope(List.of(new Explorer.Scope("beta", "DEV"), new Explorer.Scope("dateien", "ABL")));
            assertThat(view[0].chosenSpaces()).containsExactlyInAnyOrder("DEV", "ABL");
            assertThat(view[0].sourceBoxes.get("alpha").isSelected()).isFalse();
            assertThat(view[0].sourceBoxes.get("dateien").getText()).isEqualTo("Dateien · ABL");

            // „Wissensquellen“ links: alles angehakt.
            view[0].scope(List.of());
            assertThat(view[0].sourceBoxes.values()).allMatch(javax.swing.AbstractButton::isSelected);
        });
    }

    private static SearchView.Hit row(String pageId) {
        return new SearchView.Hit("wiki", pageId, "Seite " + pageId, "DEV", "Auszug", null);
    }

    private static com.aresstack.spaceharvester.application.query.KeywordSearch.Hit neighbor(String pageId) {
        return new com.aresstack.spaceharvester.application.query.KeywordSearch.Hit("wiki", pageId, "DEV",
                "Seite " + pageId, "Auszug", null, "1", null, null);
    }
}
