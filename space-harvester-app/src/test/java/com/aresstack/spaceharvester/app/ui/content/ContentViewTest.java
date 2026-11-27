package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Leseansicht wie ein Bediener sie erlebt: Eine Seite aus dem Explorer lädt sofort (auch ohne Bestand) und nennt,
 * wo sie liegt; ein anderer Space lässt nichts Altes stehen.
 */
class ContentViewTest {

    @TempDir
    Path home;

    private AppContext context;
    private ContentView view;

    @BeforeEach
    void start() throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("handbuch", "notizen")) {
            Path files = Files.createDirectories(home.resolve(id));
            for (int i = 0; i < 3; i++) {
                Files.writeString(files.resolve("%s-%d.md".formatted(id, i)),
                        "# %s %d\n\nText der Seite %d.\n".formatted(id, i, i));
            }
            sources.append("""
                        - id: %s
                          type: files
                          title: %s
                          options:
                            directory: %s
                    """.formatted(id, id, files));
        }
        // Eine Quelle, die niemand annimmt: Spaces und Seitenliste scheitern am Netz.
        sources.append("""
                    - id: wiki
                      type: confluence
                      base-url: http://127.0.0.1:9
                      options:
                        connectTimeoutMillis: 500
                """);
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                %s""".formatted(home.resolve("data"), sources));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        view = onEdt(() -> new ContentView(context));
    }

    /** Bild für die Abnahme: Quelle links gewählt, „Analysen“ zeigt die drei Unterreiter des Stands. */
    @Test
    void renderSourceAnalyses() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Files.createDirectories(Path.of(directory));
        PageDetailPanel detail = view.detail();
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            detail.tabs().select("Analysen");
            return null;
        });
        Thread.sleep(2000);
        onEdt(() -> {
            javax.swing.JPanel page = new javax.swing.JPanel(new java.awt.BorderLayout());
            page.add(view.tabBar(), java.awt.BorderLayout.NORTH);
            page.add(view.component(), java.awt.BorderLayout.CENTER);
            page.add(detail.footTools(), java.awt.BorderLayout.SOUTH);
            AnalysisTabsScreenshotTest.write(page, Path.of(directory, "analysen-quelle-1280.png"), 640);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void aFolderShowsWithSubpagesInTheWideFootAndResolvesAgainWhenSwitched() throws Exception {
        // ADR 0034 §6: „mit Unterseiten“ am Ordner, Vorgabe an; aus = nur die direkten Favoriten.
        com.aresstack.spaceharvester.app.ui.Favorites favorites = context.favorites();
        String top = favorites.createFolder("Betrieb", null);
        String below = favorites.createFolder("Notfall", top);
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite handbook =
                com.aresstack.spaceharvester.app.ui.Favorites.Favorite.source("handbuch", "handbuch");
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite notes =
                com.aresstack.spaceharvester.app.ui.Favorites.Favorite.source("notizen", "notizen");
        favorites.toggle(handbook, top);
        favorites.toggle(notes, below);
        PageDetailPanel detail = view.detail();

        onEdt(() -> {
            view.folder(top, "Betrieb");
            detail.tabs().select("Analysen");
            return null;
        });

        assertThat(onEdt(() -> detail.folderSubfolders().isSelected())).isTrue();
        assertThat(onEdt(() -> detail.folderSubfolders().isEnabled())).isTrue();
        // Unter „Analysen“ trägt die Leiste die Unterreiter des Stands (Angelo 24.11.2026), die Checkbox rechts daneben.
        assertThat(onEdt(() -> detail.footTools().getComponent(0))).isSameAs(detail.standingFoot());
        assertThat(onEdt(() -> SwingUtilities.isDescendingFrom(detail.folderSubfolders(), detail.standingFoot())))
                .isTrue();
        assertThat(onEdt(() -> SwingUtilities.isDescendingFrom(detail.standing().tabs().button("Aussagen"),
                detail.standingFoot()))).as("Reiterzeile unten, nicht mehr über dem Inhalt").isTrue();
        assertThat(onEdt(() -> detail.export().selection().roots(null)))
                .containsExactly(notes.root(), handbook.root()); // Baumreihenfolge: Unterordner zuerst angelegt

        onEdt(() -> {
            detail.folderSubfolders().doClick();
            return null;
        });
        assertThat(onEdt(() -> detail.export().selection().roots(null))).containsExactly(handbook.root());
        // Gemerkt für die Sitzung: der nächste Ordner kommt mit derselben Stellung.
        onEdt(() -> {
            view.folder(below, "Notfall");
            return null;
        });
        assertThat(onEdt(() -> detail.folderSubfolders().isSelected())).isFalse();
        // Eine Änderung am Ordner gilt sofort (ADR 0034 §3).
        favorites.toggle(com.aresstack.spaceharvester.app.ui.Favorites.Favorite.space("handbuch", space("handbuch"),
                "Handbuch", "handbuch"), below);
        await(() -> detail.export().selection().roots(null).size() == 2);

        // Eine Quelle ist kein Ordner: die Unterreiter ohne Checkbox.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> detail.footTools().getComponent(0))).isSameAs(detail.standingFoot());
        assertThat(onEdt(() -> detail.standingFoot().trailing().isVisible())).isFalse();
        // Für den Fehlerfall, versteckt hinter dem Dreieck: die Kennzahlen des Knotens neu aufbauen.
        assertThat(onEdt(() -> detail.moreButton().isEnabled())).isTrue();
        assertThat(onEdt(() -> java.util.Arrays.stream(detail.moreMenu().getComponents())
                .map(item -> ((javax.swing.JMenuItem) item).getText()).toList()))
                .containsExactly(PageDetailPanel.REBUILD_COUNTS);
    }

    @Test
    void aPageLoadsRightAwayEvenWithoutLocalCopyAndNamesWhereItLies() throws Exception {
        String space = space("handbuch");
        onEdt(() -> {
            view.openPage("handbuch", firstPage("handbuch", space));
            return null;
        });

        await(() -> view.detail().shownPage() != null);
        // Der Stern im Kopf (Angelo, 13.11.2026): dieselbe Markierung wie im Explorer, gefüllt als Favorit.
        await(() -> view.detail().starButton().isEnabled());
        assertThat(onEdt(() -> view.detail().starButton().getIcon()))
                .isSameAs(com.aresstack.spaceharvester.app.ui.Glyph.STAR);
        onEdt(() -> {
            view.detail().starButton().doClick();
            return null;
        });
        assertThat(context.favorites().all()).singleElement().satisfies(favorite -> {
            assertThat(favorite.sourceId()).isEqualTo("handbuch");
            assertThat(favorite.spaceKey()).isEqualTo(space);
            assertThat(favorite.where()).startsWith("handbuch");
        });
        await(() -> view.detail().starButton().getIcon() == com.aresstack.spaceharvester.app.ui.Glyph.STAR_FILLED);
        // Nachgeladen und sofort abgelegt (ADR 0010): die Anzeige kommt bereits aus dem Cache.
        assertThat(onEdt(() -> view.detail().shownPage().live())).isFalse();
        assertThat(new String(onEdt(() -> view.detail().shownPage().snapshot().payload().bytes()),
                StandardCharsets.UTF_8)).contains("Text der Seite");
        // Ort, Version und Herkunft stehen nicht dauerhaft unter dem Titel, sondern im Popup beim Überfahren der Überschrift.
        assertThat(onEdt(() -> view.detail().details())).contains("Space " + space).contains("Aus dem lokalen Bestand");
        // Ein überlanger Titel gibt nach (mit „…“) statt den Kopf zu sprengen.
        assertThat(onEdt(() -> {
            javax.swing.JLabel titleLabel = view.detail().titleLabel();
            java.awt.Container heading = titleLabel.getParent();
            titleLabel.setText("Sehr langer Titel ".repeat(40));
            heading.setSize(300, heading.getPreferredSize().height);
            heading.doLayout();
            // Neben der Überschrift liegt nur noch der (unsichtbare) Chat-Kopf; sichtbar ist allein die Überschrift.
            long visible = java.util.Arrays.stream(heading.getComponents()).filter(java.awt.Component::isVisible)
                    .count();
            return visible == 1 && titleLabel.isVisible() && titleLabel.getWidth() == 300;
        })).isTrue();
        // Eine Kopfzeile (Angelo, 16.11.2026): Überschrift, Stern und das Dreieck stehen in der Zeile der
        // Bereichsanzeige; „Lesezeichen kopieren“ und „Im Browser öffnen“ liegen im Menü am Dreieck.
        assertThat(onEdt(() -> {
            java.awt.Container row = view.detail().titleLabel().getParent().getParent();
            return javax.swing.SwingUtilities.isDescendingFrom(view.detail().starButton(), row)
                    && javax.swing.SwingUtilities.isDescendingFrom(view.detail().moreButton(), row)
                    && !javax.swing.SwingUtilities.isDescendingFrom(row, view.detail());
        })).isTrue();
        assertThat(onEdt(() -> {
            List<String> entries = new java.util.ArrayList<>();
            for (java.awt.Component item : view.detail().moreMenu().getComponents()) {
                entries.add(((javax.swing.JMenuItem) item).getText());
            }
            return entries;
        })).containsExactly("Lesezeichen kopieren", "Im Browser öffnen");
        // Sichtprüfung der Kopfzeile, nur mit der Umgebungsvariable HEADER_SCREENSHOT (Pfad der PNG-Datei).
        String screenshot = System.getenv("HEADER_SCREENSHOT");
        if (screenshot != null && !screenshot.isBlank()) {
            onEdt(() -> {
                javax.swing.JComponent root = view.component();
                root.setSize(1300, 420);
                for (int pass = 0; pass < 3; pass++) {
                    layoutAll(root);
                }
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1300, 420,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                root.paint(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(screenshot));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return null;
            });
        }
        // Jeder Eintrag ist so wählbar wie seine Aktion (die Testseite hat keine Adresse in der Quelle).
        assertThat(onEdt(() -> view.detail().moreMenu().getComponent(0).isEnabled())).isTrue();
        assertThat(onEdt(() -> {
            java.awt.event.MouseListener[] listeners = view.detail().titleLabel().getMouseListeners();
            listeners[listeners.length - 1].mouseEntered(null);
            javax.swing.JPopupMenu popup = view.detail().detailsPopup();
            List<String> items = new java.util.ArrayList<>();
            for (java.awt.Component item : popup.getComponents()) {
                if (item instanceof javax.swing.JMenuItem menuItem) {
                    items.add(menuItem.getText());
                }
            }
            return items;
        })).as("Lesezeichen und Browser stehen schon in der Kopfzeile").containsExactly("Seiten-ID kopieren");
        // Ohne Klick zu: liegt die Maus weder auf der Überschrift noch im Popup, schließt es.
        assertThat(PageDetailPanel.pointerInside(new java.awt.Point(-10_000, -10_000),
                new javax.swing.JLabel("nicht sichtbar"))).isFalse();
        assertThat(onEdt(() -> view.detail().feedback().isVisible())).as("keine Cache-Meldung").isFalse();
        assertThat(onEdt(view::crumbs)).isEqualTo("handbuch › " + space);
    }

    @Test
    void openingASpaceShowsNoPageOfTheOldOne() throws Exception {
        String space = space("handbuch");
        onEdt(() -> {
            view.openPage("handbuch", firstPage("handbuch", space));
            return null;
        });
        await(() -> view.detail().shownPage() != null);

        String other = space("notizen");
        onEdt(() -> {
            view.openSpace("notizen", other);
            return null;
        });

        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(view::chosenSource)).isEqualTo("notizen");
        assertThat(onEdt(view::crumbs)).isEqualTo("notizen › " + other);
    }

    /**
     * Regression (Angelo 26.11.2026, schon einmal bemängelt): Ein Ordner ohne eigenen Inhalt in derselben Quelle, etwa in
     * SharePoint oder BSCW, ließ die vorher geöffnete Seite stehen. Die Mitte zeigt statt der Seite den Ordner mit
     * „Änderungen | Bereich“, auch wenn die Seite noch lädt.
     */
    @Test
    void aFolderOfTheSameSourceShowsAnEmptyPageInsteadOfTheOpenOne() throws Exception {
        String space = space("handbuch");
        String page = firstPage("handbuch", space);
        onEdt(() -> {
            view.openPage("handbuch", page);
            return null;
        });
        await(() -> view.detail().shownPage() != null);

        onEdt(() -> {
            view.openContainer("handbuch", space, "ordner", "Projektordner");
            return null;
        });
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().titleLabel().getText())).isEqualTo("Projektordner");
        assertThat(onEdt(view::chosenSource)).isEqualTo("handbuch");
        assertThat(onEdt(view::crumbs)).isEqualTo("handbuch › " + space);
        // Wie Quelle und Space: „Änderungen | Bereich“, hier für den Teilbaum unter dem Ordner (Angelo 26.11.2026).
        String level = com.aresstack.spaceharvester.application.changelog.ChangeLevel.key(space, "ordner");
        assertThat(onEdt(() -> view.detail().showsLevel("handbuch", level))).isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().spaceKey())).isEqualTo(level);
        assertThat(onEdt(() -> view.detail().areaView().spaceKey())).isEqualTo(space);
        assertThat(onEdt(() -> view.detail().scopeToggles())).allSatisfy(toggle ->
                assertThat(toggle.isVisible()).isTrue());

        // Noch ladend gewechselt: die alte Seite erscheint auch nachträglich nicht.
        onEdt(() -> {
            view.openPage("handbuch", page);
            view.openContainer("handbuch", space, "ordner", "Projektordner");
            return null;
        });
        Thread.sleep(1500);
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
    }

    @Test
    void aPageStillLoadingFromTheOldSpaceNeverShowsUp() throws Exception {
        String space = space("handbuch");
        String page = firstPage("handbuch", space);
        String other = space("notizen");
        onEdt(() -> {
            view.openPage("handbuch", page);
            view.openSpace("notizen", other);
            return null;
        });

        Thread.sleep(1500);
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
    }

    @Test
    void analysesGroupThePageAnalysesAsSubTabsAndLoadTheVisibleOneLazily() throws Exception {
        PageDetailPanel detail = view.detail();
        // #499: Hauptreiter und darunter die zweite Reiterzeile „Analysen“; kein bisheriger Reiter fehlt.
        assertThat(onEdt(() -> detail.tabs().names()))
                .containsExactly("Leseansicht", "Podcast-Ansicht", "Anhänge", "Analysen", "Info", "Export");
        assertThat(onEdt(() -> detail.analysisTabs().names()))
                .containsExactly("Absätze", "Begriffe", "Verweise", "Bilder", "Aussagen", "Graph", "Beziehungen");
        // „Info“ hat die Eigenschaften als ersten Unterreiter (Angelo, 19.11.2026); „Beziehungen“ steht unter
        // „Analysen“ (Angelo, 20.11.2026).
        assertThat(onEdt(() -> detail.advancedTabs().names())).containsExactly("Eigenschaften",
                "Technische Angaben", "Originaldaten");
        assertThat(onEdt(() -> detail.advancedTabs().selected())).isEqualTo("Eigenschaften");
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Leseansicht");
        // Die Hauptreiter sind quadratische Symbolknöpfe für die Icon-Leiste des Hauptfensters; ihr Name steht im
        // Tooltip, die Reiterzeile oben entfällt, die Unterreiter von „Analysen“ stehen ganz oben.
        assertThat(onEdt(() -> {
            List<String> tips = new java.util.ArrayList<>();
            for (java.awt.Component c : ((java.awt.Container) detail.tabIcons()).getComponents()) {
                javax.swing.AbstractButton button = (javax.swing.AbstractButton) c;
                assertThat(button.getText()).isNullOrEmpty();
                assertThat(button.getIcon()).isNotNull();
                assertThat(button.getPreferredSize().width).isEqualTo(button.getPreferredSize().height);
                tips.add(button.getToolTipText());
            }
            return tips;
        })).containsExactly("Leseansicht", "Podcast-Ansicht", "Anhänge", "Analysen", "Info", "Export");
        assertThat(onEdt(() -> detail.tabs().getComponentCount())).as("nur noch der Inhalt").isEqualTo(1);
        // „Export“ zeigt seine Karte auch ohne gezeigte Seite (Angelo, 13.11.2026): Stand und Ausgabe hängen an der
        // Auswahl links, nicht an der Seite; zurück auf „Leseansicht“ steht wieder der Hinweis.
        assertThat(onEdt(() -> detail.export().isVisible())).isFalse();
        onEdt(() -> {
            detail.tabs().select("Export");
            return null;
        });
        assertThat(onEdt(() -> detail.export().isVisible())).isTrue();
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> detail.export().selection().sourceId())).isEqualTo("handbuch");
        // „Wissensquellen“ (Angelo 17.11.2026): keine alte Quelle in der Mitte, die Gesamtsicht unter Eigenschaften,
        // die Bereichsanzeige ohne Quelle; danach wieder eine Quelle.
        onEdt(() -> {
            view.selection(ExportPanel.Selection.ALL);
            return null;
        });
        List<String> all = context.runtime().workspace().sources().stream()
                .map(com.aresstack.spaceharvester.application.sources.HarvestSource::id).toList();
        assertThat(onEdt(() -> detail.showsAllSources(all))).isTrue();
        assertThat(onEdt(() -> detail.sourceProperties().allIds())).contains("handbuch", "notizen");
        assertThat(onEdt(() -> detail.showsAllSources(List.of("handbuch")))).as("andere Quellenliste").isFalse();
        assertThat(onEdt(view::crumbs)).isEqualTo(ContentView.ALL_SOURCES);
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> detail.sourceProperties().showsAll())).isFalse();
        assertThat(onEdt(view::crumbs)).isEqualTo("handbuch");
        // Der Stand (ADR 0032) hat kein eigenes Symbol: „Analysen“ zeigt ihn, solange links keine Seite gewählt ist
        // (Quelle, Space, Wissensquellen-Kopf), mit Hilfe als ?-Knopf oben rechts (Angelo 17.11.2026).
        onEdt(() -> {
            detail.tabs().select("Analysen");
            return null;
        });
        assertThat(onEdt(() -> detail.showsStanding())).isTrue();
        // EINE Reiterzeile (AT 17.11.2026): „Begriffe“ vorn wie auf der Seite, dahinter die Reiter des Stands.
        assertThat(onEdt(() -> detail.standing().isVisible())).isTrue();
        // Fachglossar, Graph und die Beziehungen der Auswahl hinten in derselben Zeile (BG2c).
        assertThat(onEdt(() -> detail.standing().tabs().names())).containsExactly("Begriffe", "Aussagen",
                "Zuerst lesen", "Mögliche Widersprüche", "Fachglossar", "Graph", "Beziehungen");
        assertThat(onEdt(() -> detail.standing().tabs().selected())).isEqualTo("Begriffe");
        assertThat(onEdt(() -> detail.overviewTerms().showsSelection())).isTrue();
        assertThat(onEdt(() -> detail.overviewTerms().title())).isEqualTo("Begriffe in handbuch");
        assertThat(onEdt(() -> detail.selectedTab())).isEqualTo(PageDetailPanel.OVERVIEW_TERMS);
        onEdt(() -> {
            detail.standing().tabs().select("Aussagen");
            return null;
        });
        assertThat(onEdt(() -> detail.selectedTab())).isEqualTo("Stand");
        // Fachglossar: seine Werkzeuge oben rechts, der Papierkorb des Stands nicht; Stand in der Statuszeile getrennt.
        onEdt(() -> {
            detail.standing().tabs().select("Fachglossar");
            return null;
        });
        assertThat(onEdt(() -> detail.selectedTab())).isEqualTo(PageDetailPanel.OVERVIEW_GLOSSARY);
        assertThat(onEdt(() -> detail.overviewGlossary().addButton().isVisible())).isTrue();
        assertThat(onEdt(() -> detail.standing().deleteAnalysesButton().isVisible())).isFalse();
        onEdt(() -> {
            detail.standing().tabs().select("Graph");
            return null;
        });
        assertThat(onEdt(() -> detail.overviewGlossary().addButton().isVisible())).isFalse();
        assertThat(onEdt(() -> detail.overviewGraph().tools().isVisible())).isTrue();
        onEdt(() -> {
            detail.standing().tabs().select("Aussagen");
            return null;
        });
        assertThat(onEdt(() -> detail.overviewGraph().tools().isVisible())).isFalse();
        // Der gewählte Reiter bleibt beim Wechsel der Auswahl.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("notizen", null, null, "notizen"));
            return null;
        });
        assertThat(onEdt(() -> detail.standing().tabs().selected())).isEqualTo("Aussagen");
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> detail.export().isVisible())).isFalse();
        // Eine Seite links: „Analysen“ zeigt wieder die Analysen der Seite.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", "DH", "p1", "Seite"));
            return null;
        });
        assertThat(onEdt(() -> detail.showsStanding())).isFalse();
        assertThat(onEdt(() -> detail.standing().isVisible())).isFalse();
        assertThat(onEdt(() -> detail.selectedTab())).isEqualTo("Absätze");
        // Der Wissensquellen-Kopf: wieder der Stand.
        onEdt(() -> {
            view.selection(ExportPanel.Selection.ALL);
            return null;
        });
        assertThat(onEdt(() -> detail.showsStanding())).isTrue();
        // Ohne Seite (BG2b): die Beziehungen der Auswahl als Reiter „Beziehungen“ beim Stand unter „Analysen“.
        onEdt(() -> {
            detail.tabs().select("Analysen");
            detail.standing().tabs().select("Beziehungen");
            return null;
        });
        assertThat(onEdt(() -> detail.wideAdvanced().names())).containsExactly("Eigenschaften",
                "Technische Angaben", "Originaldaten");
        assertThat(onEdt(() -> detail.wideAnalysis().isVisible() && !detail.wideAnalysis().showsSteps())).isTrue();
        assertThat(onEdt(() -> detail.wideAnalysis().proposeButton().isVisible()
                && detail.wideAnalysis().createButton().isVisible())).isTrue();
        assertThat(onEdt(() -> detail.analysisView().proposeButton().isShowing())).isFalse();
        onEdt(() -> {
            detail.tabs().select("Leseansicht");
            return null;
        });
        assertThat(onEdt(() -> detail.export().isVisible())).isFalse();
        assertThat(onEdt(() -> view.tabBar())).isSameAs(detail.tabIcons());
        // Die Schnellaktionen des Chats stehen in seiner Seitenleiste, nicht mehr unten in der Werkzeugleiste.
        // Unten steht in der Leseansicht höchstens der Platz für „Transkript kopieren“ des Podcasts.
        assertThat(onEdt(() -> {
            javax.swing.JComponent foot = view.detail().footTools();
            if (view.tools() != foot || foot.getComponentCount() > 1) {
                return false;
            }
            // Unten stehen nur Symbolknöpfe des Podcasts: links „Transkript kopieren“, rechts „Untertitel“.
            return find(foot, javax.swing.AbstractButton.class).stream().allMatch(tool ->
                    tool instanceof com.aresstack.spaceharvester.app.ui.controls.CopyButton
                            || tool instanceof com.aresstack.spaceharvester.app.ui.IconButton);
        }))
                .as("unten keine Chat-Chips").isTrue();
        // Ohne gezeigte Mitte verschwindet nur die dunkle Füllung, der gewählte Reiter bleibt.
        onEdt(() -> {
            view.tabsActive(false);
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Leseansicht");
        assertThat(onEdt(() -> detail.tabs().button("Leseansicht").isSelected())).isFalse();
        onEdt(() -> {
            view.tabsActive(true);
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().button("Leseansicht").isSelected())).isTrue();
    }

    @Test
    void choosingTheSourceOrASpaceOfTheOpenPageEmptiesThePageTabs() throws Exception {
        String space = space("handbuch");
        String pageId = firstPage("handbuch", space);
        onEdt(() -> {
            view.openPage("handbuch", pageId);
            return null;
        });
        await(() -> view.detail().shownPage() != null);

        // Die Seite selbst gewählt (etwa beim Aufdecken im Baum): sie bleibt stehen.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", space, pageId, "handbuch 0"));
            return null;
        });
        assertThat(onEdt(() -> view.detail().shownPage())).isNotNull();

        // Die Wurzel derselben Quelle gewählt: Die Seitenreiter zeigen nicht mehr die vorige Seite, Export folgt.
        javax.swing.Icon[] bar = onEdt(() -> barIcons(view.detail()));
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> view.detail().shownPage())).as("keine Seite mehr in den Reitern").isNull();
        // Stattdessen die Quellenseite (ADR 0030): unter „Leseansicht“ die Updates der Quelle, sonst leer.
        assertThat(onEdt(() -> view.detail().showsSource())).isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().sourceId())).isEqualTo("handbuch");
        assertThat(onEdt(() -> view.detail().tabs().button("Leseansicht").getToolTipText()))
                .as("die Reiterleiste bleibt überall gleich").isEqualTo("Leseansicht");
        assertThat(onEdt(() -> view.detail().sourceView().tools().isVisible()))
                .as("Werkzeuge der Updates in der gemeinsamen Kopfzeile").isTrue();
        assertThat(onEdt(() -> barIcons(view.detail()))).as("die Symbole der Reiterleiste bleiben überall gleich")
                .containsExactly(bar);
        // Hinter dem Dreieck steht hier „Zusammenfassung speichern“; ohne Zusammenfassung gesperrt. Der Podcast steht
        // in der Podcast-Ansicht (Angelo 23.11.2026), seine Einträge also dort.
        assertThat(onEdt(() -> view.detail().moreButton().isEnabled())).isTrue();
        assertThat(onEdt(() -> menuEntries(view.detail())))
                .containsExactly(PageDetailPanel.SAVE_SUMMARY + " (gesperrt)", "—", PODCAST_TEXTS, PODCAST_PICTURES, "—", MODEL_ROWS[0], MODEL_ROWS[1], MODEL_ROWS[2], MODEL_ROWS[3]);
        assertThat(onEdt(() -> view.detail().sourceView().isShowing() || view.detail().sourceView().isVisible()))
                .isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().sourceId())).isEqualTo("handbuch");
        onEdt(() -> {
            view.detail().tabs().select("Podcast-Ansicht");
            return null;
        });
        assertThat(onEdt(() -> view.detail().sourceChanges().isVisible())).as("„Text“: die Updates").isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().tools().isVisible())).isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().tools().isVisible())).isFalse();
        // Podcast-Ansicht: Transkript und „Podcast neu erstellen“ hinter dem Dreieck, ohne Podcast gesperrt; neben der
        // Lupe der Platz für „Transkript kopieren“, ohne Podcast bleibt der Knopf weg.
        assertThat(onEdt(() -> menuEntries(view.detail()))).containsExactly(
                PageDetailPanel.SAVE_TRANSCRIPT + " (gesperrt)", PageDetailPanel.RENEW_PODCAST + " (gesperrt)", "—",
                PODCAST_TEXTS, PODCAST_PICTURES, "—", MODEL_ROWS[0], MODEL_ROWS[1], MODEL_ROWS[2], MODEL_ROWS[3]);
        assertThat(onEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(
                view.detail().sourceChanges().podcast().transcriptCopy(), view.tools()))).isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().podcast().transcriptCopy().isVisible())).isFalse();
        onEdt(() -> {
            view.detail().selectTab("Eigenschaften");
            return null;
        });
        assertThat(onEdt(() -> view.detail().tabs().selected())).isEqualTo("Info");
        assertThat(onEdt(() -> view.detail().wideAdvanced().isVisible()
                && view.detail().sourceProperties().isVisible())).as("„Info › Eigenschaften“: die Quelle").isTrue();
        assertThat(onEdt(() -> view.detail().sourceProperties().sourceId())).isEqualTo("handbuch");
        assertThat(onEdt(() -> view.detail().sourceProperties().tools().isVisible())).isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().tools().isVisible())).isFalse();
        onEdt(() -> {
            view.detail().wideAdvanced().select("Technische Angaben");
            return null;
        });
        assertThat(onEdt(() -> view.detail().sourceView().isVisible())).as("übrige Reiter leer").isFalse();
        assertThat(onEdt(() -> view.detail().sourceProperties().isVisible())).isFalse();
        assertThat(onEdt(() -> view.detail().sourceProperties().tools().isVisible())).isFalse();
        assertThat(onEdt(() -> view.detail().sourceChanges().isVisible())).isFalse();
        assertThat(onEdt(() -> view.detail().sourceView().tools().isVisible())).isFalse();
        onEdt(() -> {
            view.detail().tabs().select("Leseansicht");
            return null;
        });
        assertThat(onEdt(() -> view.detail().export().selection().sourceId())).isEqualTo("handbuch");

        // Ebenso ein Space derselben Quelle.
        onEdt(() -> {
            view.openPage("handbuch", pageId);
            return null;
        });
        await(() -> view.detail().shownPage() != null);
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
            return null;
        });
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().showsSource())).as("ein Space hat dieselbe Seite (TZ5)").isTrue();
        assertThat(onEdt(() -> view.detail().showsLevel("handbuch", space))).isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().spaceKey())).isEqualTo(space);
        assertThat(onEdt(() -> view.detail().sourceChanges().spaceKey())).isEqualTo(space);
        assertThat(onEdt(() -> view.shownSource())).as("„Nach dem Update“ öffnet nur die ganze Quelle").isNull();
        assertThat(onEdt(() -> view.detail().sourceProperties().sourceId())).as("Eigenschaften nur für die Quelle")
                .isNull();
        assertThat(onEdt(() -> view.detail().tabs().button("Leseansicht").getToolTipText())).isEqualTo("Leseansicht");
        assertThat(onEdt(() -> barIcons(view.detail()))).containsExactly(bar);
    }

    private static List<String> chipLabels(ChatPanel chat) {
        return find((java.awt.Container) chat.quickActions(), javax.swing.AbstractButton.class).stream()
                .map(javax.swing.AbstractButton::getText).toList();
    }

    private static boolean chipsEnabled(ChatPanel chat) {
        List<javax.swing.AbstractButton> chips = find((java.awt.Container) chat.quickActions(),
                javax.swing.AbstractButton.class);
        return !chips.isEmpty() && chips.stream().allMatch(javax.swing.AbstractButton::isEnabled);
    }

    /** Die Grenzen des Podcasts im Menü am Dreieck beider Reiter (Angelo 24.11.2026), mit ihren Standards. */
    private static final String PODCAST_TEXTS = PodcastParallel.TEXTS + ": " + PodcastParallel.TEXTS_DEFAULT;
    private static final String PODCAST_PICTURES = PodcastParallel.PICTURES + ": " + PodcastParallel.PICTURES_DEFAULT;

    /** Darunter die Modelle der Podcast-Schritte, ohne Eintrag in den Einstellungen „Standard“ (Angelo 24.11.2026). */
    private static final String[] MODEL_ROWS = {"Modell Podcast: Standard", "Modell Bildgenerierung – Plan: Standard",
            "Modell Bildgenerierung – Einzelteile: Standard", "Modell Podcast – Sprache: Standard"};

    private static List<String> menuEntries(PageDetailPanel detail) {
        List<String> entries = new java.util.ArrayList<>();
        for (java.awt.Component item : detail.moreMenu().getComponents()) {
            entries.add(item instanceof javax.swing.JMenuItem entry
                    ? entry.getText() + (item.isEnabled() ? "" : " (gesperrt)") : "—");
        }
        return entries;
    }

    private static javax.swing.Icon[] barIcons(PageDetailPanel detail) {
        return java.util.stream.Stream.of("Leseansicht", "Podcast-Ansicht", "Anhänge", "Analysen", "Info", "Export").map(name -> detail.tabs().button(name).getIcon()).toArray(javax.swing.Icon[]::new);
    }

    @Test
    void theChatFollowsTheSelectionAndSendingWithoutAChatModelSaysSo() throws Exception {
        PageDetailPanel detail = view.detail();
        ChatPanel chat = detail.chat();
        assertThat(onEdt(chat::knowledge)).isEqualTo(ChatPanel.NO_SELECTION);
        // Ohne Auswahl antwortet der Chat trotzdem (Angelo 24.11.2026).
        assertThat(onEdt(() -> chat.sendButton().isEnabled())).isTrue();
        // Der Chat ist kein Reiter mehr, sondern eine Seitenleiste (Angelo, 21.11.2026): die Seitenansicht behält ihre
        // Überschrift, „Chat: ‹Name› ▾“ samt Neuer Chat, Chat löschen und Einklappen steht im Kopf der Leiste.
        ChatSidebar sidebar = onEdt(view::chatSidebar);
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> sidebar.chat() == chat && view.chatSidebar() == sidebar
                && javax.swing.SwingUtilities.isDescendingFrom(chat, sidebar)
                && javax.swing.SwingUtilities.isDescendingFrom(chat.header(), sidebar)
                && javax.swing.SwingUtilities.isDescendingFrom(chat.headerActions(), sidebar)
                && !javax.swing.SwingUtilities.isDescendingFrom(chat, detail)
                && sidebar.moreButton().getParent() == chat.headerActions().getParent()
                && ChatSidebar.COLLAPSE.equals(((javax.swing.JMenuItem) sidebar.moreMenu().getComponent(0))
                        .getText()))).isTrue();
        assertThat(onEdt(() -> sidebar.getMinimumSize().width)).isEqualTo(ChatSidebar.MIN_WIDTH);
        List<String> actions = onEdt(() -> find((java.awt.Container) chat.headerActions(),
                javax.swing.AbstractButton.class).stream()
                .map(button -> button.getAccessibleContext().getAccessibleName() + ":" + button.isEnabled()).toList());
        // Die Mülltonne ist immer aktiv, auch beim leeren Chat (Angelo 24.11.2026).
        assertThat(actions).containsExactly(ChatPanel.NEW_CHAT + ":true", ChatPanel.DELETE + ":true");
        // Den Text zeigt der Chat schon vor dem Auflösen der Auswahl; Senden geht erst danach.
        await(() -> chat.knowledge().startsWith("Wissen: handbuch · alle Spaces") && chat.sendButton().isEnabled());
        // Schnellaktionen über der Eingabe der Leiste, umbrechend; bei einer Quelle „Bereich zusammenfassen“ und
        // „Was ist neu?“.
        assertThat(onEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(chat.quickActions(), chat)
                && chat.quickActions().isVisible()
                && ((javax.swing.JComponent) chat.quickActions()).getLayout()
                        instanceof com.aresstack.spaceharvester.app.ui.controls.WrapLayout)).isTrue();
        assertThat(onEdt(() -> chipLabels(chat))).containsExactly(ChatPanel.SUMMARIZE_AREA, ChatPanel.WHATS_NEW);
        await(() -> chipsEnabled(chat));
        // Bei einer Seite: nur die Seite, die Seite samt Unterseiten, Was ist neu – nie die Eltern.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", space("handbuch"), "p1", "Seite 1"));
            return null;
        });
        assertThat(onEdt(() -> chipLabels(chat))).containsExactly(ChatPanel.SUMMARIZE_PAGE,
                ChatPanel.SUMMARIZE_SUBTREE, ChatPanel.WHATS_NEW);
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        await(() -> chat.knowledge().startsWith("Wissen: handbuch · alle Spaces") && chat.sendButton().isEnabled());
        // Senden ohne Chat-Modell: nichts geht ab, die Statuszeile sagt, was fehlt; Text und Anhang bleiben stehen.
        onEdt(() -> {
            chat.attach(home.resolve("notiz.txt"));
            ((javax.swing.text.JTextComponent) chat.editor()).setText("Was steht im Handbuch?");
            chat.send();
            return null;
        });
        // Anhänge werden außerhalb des EDT gelesen; danach meldet die Statuszeile das fehlende Modell.
        await(() -> ChatPanel.NO_CHAT_MODEL.equals(chat.status()));
        assertThat(onEdt(chat::bubbles)).isZero();
        assertThat(onEdt(chat::attachments)).hasSize(1);
        assertThat(onEdt(() -> ((javax.swing.text.JTextComponent) chat.editor()).getText()))
                .isEqualTo("Was steht im Handbuch?");
        // Leer sendet nichts.
        onEdt(() -> {
            ((javax.swing.text.JTextComponent) chat.editor()).setText("");
            chat.send();
            return null;
        });
        assertThat(onEdt(chat::bubbles)).isZero();
        onEdt(() -> {
            view.selection(null);
            return null;
        });
        await(() -> ChatPanel.NO_SELECTION.equals(chat.knowledge()));
        onEdt(() -> {
            detail.tabs().button("Podcast-Ansicht").doClick();
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Podcast-Ansicht");
        assertThat(onEdt(() -> detail.tabs().button("Podcast-Ansicht").isSelected())).isTrue();
        // Ohne Auswahl gibt es keine Chips; die Seitenüberschrift der Ansicht hat mit dem Chat nichts mehr zu tun.
        assertThat(onEdt(() -> detail.titleLabel().isVisible() && !chat.quickActions().isVisible())).isTrue();
        onEdt(() -> {
            detail.tabs().select("Leseansicht");
            return null;
        });

        // „Aussagen“ zeigt den eingebauten Reiter der Aussagenanalyse; statementsTab ersetzt ihn, null zeigt den Hinweis.
        assertThat(onEdt(() -> detail.statements().getComponent(0))).isInstanceOf(StatementsTab.class);
        javax.swing.JPanel plugged = onEdt(javax.swing.JPanel::new);
        onEdt(() -> {
            view.statementsTab(plugged);
            return null;
        });
        assertThat(onEdt(() -> detail.statements().getComponentCount())).isEqualTo(1);
        assertThat(onEdt(() -> detail.statements().getComponent(0))).isSameAs(plugged);
        onEdt(() -> {
            detail.statementsTab(null);
            return null;
        });
        assertThat(onEdt(() -> labelText(detail.statements()))).isEqualTo(PageDetailPanel.STATEMENTS_PLACEHOLDER);

        // „Graph“ ebenso: eingebauter Wissensgraph; knowledgeGraphTab ersetzt ihn, null zeigt den Hinweis.
        assertThat(onEdt(() -> detail.knowledgeGraph().getComponent(0))).isInstanceOf(KnowledgeGraphTab.class);
        javax.swing.JPanel graph = onEdt(javax.swing.JPanel::new);
        onEdt(() -> {
            view.knowledgeGraphTab(graph);
            return null;
        });
        assertThat(onEdt(() -> detail.knowledgeGraph().getComponentCount())).isEqualTo(1);
        assertThat(onEdt(() -> detail.knowledgeGraph().getComponent(0))).isSameAs(graph);
        assertThat(onEdt(() -> labelText(detail.statements()))).as("Aussagen unberührt")
                .isEqualTo(PageDetailPanel.STATEMENTS_PLACEHOLDER);
        onEdt(() -> {
            detail.knowledgeGraphTab(null);
            return null;
        });
        assertThat(onEdt(() -> labelText(detail.knowledgeGraph()))).isEqualTo(PageDetailPanel.GRAPH_PLACEHOLDER);

        // Verweise laden erst, wenn der Unterreiter sichtbar wird – auch über den Wechsel des Hauptreiters.
        String space = space("handbuch");
        List<String> pages = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().stream()
                .map(entry -> entry.page().pageId()).toList();
        onEdt(() -> {
            detail.analysisTabs().select("Verweise");
            view.openPage("handbuch", pages.get(0));
            return null;
        });
        await(() -> detail.shownPage() != null);
        assertThat(onEdt(() -> detail.references().hint())).as("Unterreiter verdeckt").isBlank();
        onEdt(() -> {
            detail.tabs().select("Analysen");
            return null;
        });
        assertThat(onEdt(() -> detail.references().hint())).isNotBlank();
        assertThat(onEdt(detail::selectedTab)).isEqualTo("Verweise");

        // Haupt- und Unterreiter bleiben beim Laden der nächsten Seite; der sichtbare Unterreiter lädt nach.
        onEdt(() -> {
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> detail.shownPage() != null && pages.get(1).equals(detail.shownPage().header().metadata().pageId()));
        await(() -> !detail.references().hint().isBlank());
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Analysen");
        assertThat(onEdt(() -> detail.analysisTabs().selected())).isEqualTo("Verweise");

        // Ein Unterreiter über seinen Namen wählt auch den Hauptreiter „Analysen“.
        onEdt(() -> {
            detail.selectTab("Podcast-Ansicht");
            detail.selectTab("Absätze");
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Analysen");
        assertThat(onEdt(detail::selectedTab)).isEqualTo("Absätze");
    }

    @Test
    void searchScopeChangesPreserveTheDocumentAndItsBackAndForwardHistory() throws Exception {
        PageDetailPanel detail = view.detail();
        String space = space("handbuch");
        List<String> pages = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().stream()
                .map(entry -> entry.page().pageId()).toList();
        onEdt(() -> {
            view.openPage("handbuch", pages.get(0));
            return null;
        });
        await(() -> shown(detail, pages.get(0)));
        onEdt(() -> {
            assertThat(detail.backButton().isEnabled()).isFalse();
            view.searchFront(true);
            view.selection(null);
            view.selection(ExportPanel.Selection.ALL);
            view.selection(new ExportPanel.Selection.Node("notizen", null, null, "Notizen"));
            assertThat(shown(detail, pages.get(0))).isTrue();
            assertThat(detail.backButton().isEnabled()).isTrue();
            detail.backButton().doClick();
            assertThat(shown(detail, pages.get(0))).isTrue();
            assertThat(detail.canGoBack()).isFalse();
            view.searchFront(false);
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> shown(detail, pages.get(1)));
        onEdt(() -> {
            view.searchFront(true);
            view.selection(ExportPanel.Selection.ALL);
            detail.backButton().doClick();
            assertThat(shown(detail, pages.get(1))).isTrue();
            assertThat(detail.canGoBack()).isTrue();
            detail.backButton().doClick();
            return null;
        });
        await(() -> shown(detail, pages.get(0)));
        assertThat(onEdt(detail::canGoForward)).isTrue();
        onEdt(detail::forward);
        await(() -> shown(detail, pages.get(1)));
    }

    @Test
    void backAndForwardReturnToThePagesShownBeforeAlsoWithTheThumbButtons() throws Exception {
        PageDetailPanel detail = view.detail();
        String space = space("handbuch");
        List<String> pages = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().stream()
                .map(entry -> entry.page().pageId()).toList();
        assertThat(onEdt(() -> detail.canGoBack())).isFalse();
        assertThat(onEdt(() -> detail.canGoForward())).isFalse();
        onEdt(() -> {
            view.openPage("handbuch", pages.get(0));
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> shown(detail, pages.get(1)));
        assertThat(onEdt(() -> detail.canGoBack())).isTrue();

        onEdt(detail::back);
        await(() -> shown(detail, pages.get(0)));
        assertThat(onEdt(() -> detail.canGoBack())).isFalse();
        assertThat(onEdt(() -> detail.canGoForward())).isTrue();

        // Maustaste Vor (5) irgendwo in der Seitenansicht, danach Zurück (4); eine andere Taste tut nichts.
        assertThat(onEdt(() -> detail.mouseButton(PageDetailPanel.MOUSE_FORWARD_BUTTON, detail.tabs()))).isTrue();
        await(() -> shown(detail, pages.get(1)));
        assertThat(onEdt(() -> detail.mouseButton(2, detail.tabs()))).isFalse();
        assertThat(onEdt(() -> detail.mouseButton(PageDetailPanel.MOUSE_BACK_BUTTON, new javax.swing.JLabel())))
                .as("außerhalb der Seitenansicht").isFalse();
        assertThat(onEdt(() -> detail.mouseButton(PageDetailPanel.MOUSE_BACK_BUTTON, detail.tabs()))).isTrue();
        await(() -> shown(detail, pages.get(0)));

        // Eine neu geöffnete Seite verwirft den Vor-Verlauf wie im Browser.
        onEdt(() -> {
            view.openPage("handbuch", pages.get(2));
            return null;
        });
        await(() -> shown(detail, pages.get(2)));
        assertThat(onEdt(() -> detail.canGoForward())).isFalse();
        assertThat(onEdt(() -> detail.canGoBack())).isTrue();

        // Auch nach einem Wechsel des Space (keine Seite gezeigt) verwirft eine neue Seite den Vor-Verlauf.
        onEdt(detail::back);
        await(() -> shown(detail, pages.get(0)));
        onEdt(() -> {
            view.openSpace("notizen", space("notizen"));
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> shown(detail, pages.get(1)));
        assertThat(onEdt(() -> detail.canGoForward())).isFalse();
    }

    @Test
    void theScopeToggleSwitchesBothViewsToTheAreaOverviewAndIsRememberedForEveryPage() throws Exception {
        String before = onEdt(() -> com.aresstack.spaceharvester.app.ui.AppState.shared()
                .get(AreaSummaryView.SCOPE_KEY));
        try {
            onEdt(() -> {
                AreaSummaryView.choose(false);
                view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
                return null;
            });
            assertThat(onEdt(() -> view.detail().showsSource())).isTrue();
            // Vorgabe „Änderungen“: die Leseansicht zeigt die Updates, unten mittig der Umschalter.
            assertThat(onEdt(() -> view.detail().sourceView().isVisible())).isTrue();
            assertThat(onEdt(() -> view.detail().scopeToggles())).hasSize(3)
                    .allSatisfy(toggle -> assertThat(toggle.selected()).isEqualTo(AreaSummaryView.CHANGES));
            assertThat(onEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(view.detail().scopeToggles().get(1),
                    view.tools()))).as("Leseansicht: Umschalter in der Leiste unten").isTrue();

            onEdt(() -> {
                view.detail().scopeToggles().get(1).button(AreaSummaryView.AREA).doClick();
                return null;
            });
            assertThat(onEdt(AreaSummaryView::areaChosen)).isTrue();
            assertThat(onEdt(() -> view.detail().scopeToggles()))
                    .allSatisfy(toggle -> assertThat(toggle.selected()).isEqualTo(AreaSummaryView.AREA));
            assertThat(onEdt(() -> view.detail().areaView().isVisible())).as("Leseansicht: der Überblick").isTrue();
            assertThat(onEdt(() -> view.detail().sourceView().isVisible())).isFalse();
            assertThat(onEdt(() -> view.detail().areaView().sourceId())).isEqualTo("handbuch");
            assertThat(onEdt(() -> view.detail().areaView().tools().isVisible())).isTrue();
            assertThat(onEdt(() -> view.detail().sourceView().tools().isVisible())).isFalse();

            onEdt(() -> {
                view.detail().tabs().select("Podcast-Ansicht");
                return null;
            });
            assertThat(onEdt(() -> view.detail().areaPodcast().isVisible())).as("Podcast zum Überblick").isTrue();
            assertThat(onEdt(() -> view.detail().sourceChanges().isVisible())).isFalse();
            assertThat(onEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(view.detail().scopeToggles().get(2),
                    view.tools()))).isTrue();
            assertThat(onEdt(() -> menuEntries(view.detail())))
                    .containsExactly(PageDetailPanel.SAVE_TRANSCRIPT + " (gesperrt)", "—", PODCAST_TEXTS,
                            PODCAST_PICTURES, "—", MODEL_ROWS[0], MODEL_ROWS[1], MODEL_ROWS[2], MODEL_ROWS[3]);

            // Eine andere Wurzel behält die Wahl (global, nicht je Seite).
            String space = space("handbuch");
            onEdt(() -> {
                view.selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
                return null;
            });
            assertThat(onEdt(() -> view.detail().areaPodcast().spaceKey())).isEqualTo(space);
            assertThat(onEdt(() -> view.detail().areaPodcast().isVisible())).isTrue();

            onEdt(() -> {
                view.detail().scopeToggles().get(2).button(AreaSummaryView.CHANGES).doClick();
                return null;
            });
            assertThat(onEdt(() -> view.detail().sourceChanges().isVisible())).isTrue();
            assertThat(onEdt(AreaSummaryView::areaChosen)).isFalse();
        } finally {
            onEdt(() -> {
                com.aresstack.spaceharvester.app.ui.AppState.shared().put(AreaSummaryView.SCOPE_KEY, before);
                return null;
            });
        }
    }

    @Test
    void aFavoritesFolderShowsItsOverviewInBothViewsWithoutTheScopeToggle() throws Exception {
        String space = space("handbuch");
        ExportPanel.Selection.Collection folder = new ExportPanel.Selection.Collection("ordner-1", "Mein Ordner",
                List.of(com.aresstack.spaceharvester.application.holdings.Selection.Root.space("handbuch", space)),
                false);
        onEdt(() -> {
            view.selection(folder);
            return null;
        });
        assertThat(onEdt(() -> view.detail().showsFolder(folder))).isTrue();
        assertThat(onEdt(() -> view.detail().areaView().folder())).isEqualTo(folder);
        assertThat(onEdt(() -> view.detail().areaView().isVisible())).as("Leseansicht: Überblick des Ordners")
                .isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().isVisible())).isFalse();
        assertThat(onEdt(() -> view.detail().scopeToggles())).as("am Ordner keine Änderungen, kein Umschalter")
                .allSatisfy(toggle -> assertThat(toggle.getParent().isVisible()).isFalse());

        onEdt(() -> {
            view.detail().tabs().select("Podcast-Ansicht");
            return null;
        });
        assertThat(onEdt(() -> view.detail().areaPodcast().isVisible())).isTrue();
        assertThat(onEdt(() -> view.detail().areaPodcast().folder())).isEqualTo(folder);
        assertThat(onEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(view.detail().folderSubfolders(),
                view.tools()))).as("„mit Unterseiten“ auch in der Podcast-Ansicht").isTrue();
        assertThat(onEdt(() -> menuEntries(view.detail())))
                .startsWith(PageDetailPanel.SAVE_TRANSCRIPT + " (gesperrt)");

        // Zurück zu einer Quelle: wieder deren Seite, der Ordner ist weg.
        onEdt(() -> {
            view.detail().tabs().select("Leseansicht");
            view.selection(new ExportPanel.Selection.Node("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> view.detail().areaView().folder())).isNull();
        assertThat(onEdt(() -> view.detail().sourceView().sourceId())).isEqualTo("handbuch");
    }

    @Test
    void backAndForwardAlsoReturnToSourcesAndSpacesChosenInTheTree() throws Exception {
        // Angelo, 17.11.2026: Bereich um Bereich im Baum gewählt, dann Zurück – nichts passierte, weil nur Seiten im
        // Verlauf standen. Jetzt stehen Quelle und Space darin, und Bereichsanzeige und Baum folgen jedem Schritt.
        PageDetailPanel detail = view.detail();
        String handbuch = space("handbuch");
        String notizen = space("notizen");
        String page = firstPage("handbuch", handbuch);
        List<String> located = new java.util.ArrayList<>();
        onEdt(() -> {
            view.onLocate((source, space) -> located.add(source + "/" + space));
            view.onLocatePage((source, pageId) -> located.add(source + "#" + pageId));
            view.openPage("handbuch", page);
            return null;
        });
        await(() -> shown(detail, page));
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("notizen", notizen, null, "notizen"));
            view.selection(new ExportPanel.Selection.Node("notizen", null, null, "notizen"));
            return null;
        });
        assertThat(onEdt(() -> detail.showsLevel("notizen", null))).isTrue();
        assertThat(onEdt(() -> detail.canGoBack())).isTrue();
        assertThat(onEdt(() -> detail.canGoForward())).isFalse();

        assertThat(onEdt(detail::back)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", notizen))).isTrue();
        assertThat(located).containsExactly("notizen/" + notizen);
        assertThat(onEdt(detail::back)).isTrue();
        await(() -> shown(detail, page));
        assertThat(located).containsExactly("notizen/" + notizen, "handbuch#" + page);
        assertThat(onEdt(() -> detail.canGoBack())).isFalse();
        assertThat(onEdt(() -> detail.canGoForward())).isTrue();
        // Der Baum wählt die Seite wieder: die Auswahl kommt an der Mitte an, ohne die gezeigte Seite zu leeren.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", handbuch, page, "handbuch 0"));
            return null;
        });
        assertThat(onEdt(() -> shown(detail, page))).isTrue();
        assertThat(onEdt(() -> detail.canGoForward())).isTrue();

        assertThat(onEdt(detail::forward)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", notizen))).isTrue();
        assertThat(onEdt(detail::forward)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", null))).isTrue();
        assertThat(onEdt(() -> detail.canGoForward())).isFalse();

        // Von der Quellenseite zu einer Seite einer anderen Quelle (Baum: Auswahl, dann Öffnen): Zurück führt zur Quelle.
        String other = context.runtime().workspace().liveMetadata("handbuch", handbuch, null, 10).entries().get(1)
                .page().pageId();
        onEdt(() -> {
            view.selection(new ExportPanel.Selection.Node("handbuch", handbuch, other, "handbuch 1"));
            view.openPage("handbuch", other);
            return null;
        });
        await(() -> shown(detail, other));
        assertThat(onEdt(detail::back)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", null))).isTrue();
        assertThat(onEdt(detail::forward)).isTrue();
        await(() -> shown(detail, other));
    }

    @Test
    void onlyLinksToTheDomainOfThePageStayInTheReader() throws Exception {
        java.net.URI page = java.net.URI.create("https://wiki.example.org/pages/viewpage.action?pageId=1");
        assertThat(PageDetailPanel.sameDomain(java.net.URI.create("https://wiki.example.org/display/DEV/Start").toURL(), page))
                .isTrue();
        assertThat(PageDetailPanel.sameDomain(java.net.URI.create("http://WWW.wiki.example.org/x").toURL(), page)).isTrue();
        assertThat(PageDetailPanel.sameDomain(java.net.URI.create("https://example.org/x").toURL(), page)).isFalse();
        assertThat(PageDetailPanel.sameDomain(java.net.URI.create("https://www.google.com/").toURL(), page)).isFalse();
        assertThat(PageDetailPanel.sameDomain(java.net.URI.create("https://wiki.example.org/x").toURL(), null)).isFalse();
    }

    @Test
    void aForeignLinkOpensInternallyWhenASourceKnowsThePage() {
        var issue = new com.aresstack.spaceharvester.application.query.PageAddresses.Match("jira", "ABC-1", "ABC",
                "ABC-1 Drucker streikt", "1", java.net.URI.create("https://jira.example.org/browse/ABC-1"), null);
        assertThat(PageDetailPanel.internalTarget(new com.aresstack.spaceharvester.application.query.PageAddresses
                .Resolution(List.of(issue), List.of()))).contains(issue);
        assertThat(PageDetailPanel.internalTarget(new com.aresstack.spaceharvester.application.query.PageAddresses
                .Resolution(List.of(), List.of("jira")))).isEmpty();
        assertThat(PageDetailPanel.internalTarget(null)).isEmpty();
    }

    private static boolean shown(PageDetailPanel detail, String pageId) {
        return detail.shownPage() != null && pageId.equals(detail.shownPage().header().metadata().pageId());
    }

    private static <T> List<T> find(java.awt.Container container, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        for (java.awt.Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
            }
            if (component instanceof java.awt.Container inner) {
                found.addAll(find(inner, type));
            }
        }
        return found;
    }

    private static String labelText(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof javax.swing.JLabel label) {
                return label.getText();
            }
        }
        return null;
    }

    private String space(String sourceId) {
        return context.runtime().workspace().spaces(sourceId).get(0).key();
    }

    private String firstPage(String sourceId, String spaceKey) {
        return context.runtime().workspace().liveMetadata(sourceId, spaceKey, null, 10).entries().get(0).page().pageId();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }
}
