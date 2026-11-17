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
            view.selection(new ExportPanel.Selection("handbuch", null, null, "handbuch"));
            detail.tabs().select("Analysen");
            return null;
        });
        Thread.sleep(2000);
        onEdt(() -> {
            javax.swing.JPanel page = new javax.swing.JPanel(new java.awt.BorderLayout());
            page.add(view.tabBar(), java.awt.BorderLayout.NORTH);
            page.add(view.component(), java.awt.BorderLayout.CENTER);
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
            return heading.getComponentCount() == 1 && titleLabel.getWidth() == 300;
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
                .containsExactly("Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen", "Erweitert", "Export", "Chat");
        assertThat(onEdt(() -> detail.analysisTabs().names()))
                .containsExactly("Absätze", "Begriffe", "Verweise", "Bilder", "Aussagen", "Graph");
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
        })).containsExactly("Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen", "Erweitert", "Export", "Chat");
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
            view.selection(new ExportPanel.Selection("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(() -> detail.export().selection().sourceId())).isEqualTo("handbuch");
        // Der Stand (ADR 0032) hat kein eigenes Symbol: „Analysen“ zeigt ihn, solange links keine Seite gewählt ist
        // (Quelle, Space, Wissensquellen-Kopf), mit Hilfe als ?-Knopf oben rechts (Angelo 17.11.2026).
        onEdt(() -> {
            detail.tabs().select("Analysen");
            return null;
        });
        assertThat(onEdt(() -> detail.showsStanding())).isTrue();
        assertThat(onEdt(() -> detail.standing().isVisible())).isTrue();
        assertThat(onEdt(() -> detail.export().isVisible())).isFalse();
        // Eine Seite links: „Analysen“ zeigt wieder die Analysen der Seite.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection("handbuch", "DH", "p1", "Seite"));
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
        onEdt(() -> {
            detail.tabs().select("Leseansicht");
            return null;
        });
        assertThat(onEdt(() -> detail.export().isVisible())).isFalse();
        assertThat(onEdt(() -> view.tabBar())).isSameAs(detail.tabIcons());
        assertThat(onEdt(() -> view.tools())).as("unten in der Werkzeugleiste steht nichts mehr").isNull();
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
            view.selection(new ExportPanel.Selection("handbuch", space, pageId, "handbuch 0"));
            return null;
        });
        assertThat(onEdt(() -> view.detail().shownPage())).isNotNull();

        // Die Wurzel derselben Quelle gewählt: Die Seitenreiter zeigen nicht mehr die vorige Seite, Export folgt.
        javax.swing.Icon[] bar = onEdt(() -> barIcons(view.detail()));
        onEdt(() -> {
            view.selection(new ExportPanel.Selection("handbuch", null, null, "handbuch"));
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
        assertThat(onEdt(() -> view.detail().sourceView().isShowing() || view.detail().sourceView().isVisible()))
                .isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().sourceId())).isEqualTo("handbuch");
        onEdt(() -> {
            view.detail().tabs().select("Text");
            return null;
        });
        assertThat(onEdt(() -> view.detail().sourceChanges().isVisible())).as("„Text“: die Updates").isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().tools().isVisible())).isTrue();
        assertThat(onEdt(() -> view.detail().sourceView().tools().isVisible())).isFalse();
        onEdt(() -> {
            view.detail().tabs().select("Eigenschaften");
            return null;
        });
        assertThat(onEdt(() -> view.detail().sourceProperties().isVisible())).as("„Eigenschaften“: die Quelle")
                .isTrue();
        assertThat(onEdt(() -> view.detail().sourceProperties().sourceId())).isEqualTo("handbuch");
        assertThat(onEdt(() -> view.detail().sourceProperties().tools().isVisible())).isTrue();
        assertThat(onEdt(() -> view.detail().sourceChanges().tools().isVisible())).isFalse();
        onEdt(() -> {
            view.detail().tabs().select("Erweitert");
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
            view.selection(new ExportPanel.Selection("handbuch", space, null, space));
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

    private static javax.swing.Icon[] barIcons(PageDetailPanel detail) {
        return java.util.stream.Stream.of("Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen", "Erweitert",
                "Export", "Chat").map(name -> detail.tabs().button(name).getIcon()).toArray(javax.swing.Icon[]::new);
    }

    @Test
    void theChatFollowsTheSelectionAndSendingWithoutAChatModelSaysSo() throws Exception {
        PageDetailPanel detail = view.detail();
        ChatPanel chat = detail.chat();
        assertThat(onEdt(chat::knowledge)).isEqualTo(ChatPanel.NO_SELECTION);
        assertThat(onEdt(() -> chat.sendButton().isEnabled())).isFalse();
        onEdt(() -> {
            detail.tabs().select("Chat");
            view.selection(new ExportPanel.Selection("handbuch", null, null, "handbuch"));
            return null;
        });
        assertThat(onEdt(chat::isVisible)).isTrue();
        // Den Text zeigt der Chat schon vor dem Auflösen der Auswahl; Senden geht erst danach.
        await(() -> chat.knowledge().startsWith("Wissen: handbuch · alle Spaces") && chat.sendButton().isEnabled());
        // Senden ohne Chat-Modell: nichts geht ab, die Statuszeile sagt, was fehlt; Text und Anhang bleiben stehen.
        onEdt(() -> {
            chat.attach(home.resolve("notiz.txt"));
            ((javax.swing.text.JTextComponent) chat.editor()).setText("Was steht im Handbuch?");
            chat.send();
            return null;
        });
        assertThat(onEdt(chat::bubbles)).isZero();
        assertThat(onEdt(chat::status)).isEqualTo(ChatPanel.NO_CHAT_MODEL);
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
            detail.tabs().button("Text").doClick();
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Text");
        assertThat(onEdt(() -> detail.tabs().button("Text").isSelected())).isTrue();
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
            detail.selectTab("Text");
            detail.selectTab("Absätze");
            return null;
        });
        assertThat(onEdt(() -> detail.tabs().selected())).isEqualTo("Analysen");
        assertThat(onEdt(detail::selectedTab)).isEqualTo("Absätze");
    }

    @Test
    void backAndForwardReturnToThePagesShownBeforeAlsoWithTheThumbButtons() throws Exception {
        PageDetailPanel detail = view.detail();
        String space = space("handbuch");
        List<String> pages = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().stream()
                .map(entry -> entry.page().pageId()).toList();
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isFalse();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isFalse();
        onEdt(() -> {
            view.openPage("handbuch", pages.get(0));
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> shown(detail, pages.get(1)));
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isTrue();

        onEdt(detail::back);
        await(() -> shown(detail, pages.get(0)));
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isFalse();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isTrue();

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
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isFalse();
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isTrue();

        // Auch nach einem Wechsel des Space (keine Seite gezeigt) verwirft eine neue Seite den Vor-Verlauf.
        onEdt(detail::back);
        await(() -> shown(detail, pages.get(0)));
        onEdt(() -> {
            view.openSpace("notizen", space("notizen"));
            view.openPage("handbuch", pages.get(1));
            return null;
        });
        await(() -> shown(detail, pages.get(1)));
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isFalse();
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
            view.selection(new ExportPanel.Selection("notizen", notizen, null, "notizen"));
            view.selection(new ExportPanel.Selection("notizen", null, null, "notizen"));
            return null;
        });
        assertThat(onEdt(() -> detail.showsLevel("notizen", null))).isTrue();
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isTrue();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isFalse();

        assertThat(onEdt(detail::back)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", notizen))).isTrue();
        assertThat(located).containsExactly("notizen/" + notizen);
        assertThat(onEdt(detail::back)).isTrue();
        await(() -> shown(detail, page));
        assertThat(located).containsExactly("notizen/" + notizen, "handbuch#" + page);
        assertThat(onEdt(() -> detail.backButton().isEnabled())).isFalse();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isTrue();
        // Der Baum wählt die Seite wieder: die Auswahl kommt an der Mitte an, ohne die gezeigte Seite zu leeren.
        onEdt(() -> {
            view.selection(new ExportPanel.Selection("handbuch", handbuch, page, "handbuch 0"));
            return null;
        });
        assertThat(onEdt(() -> shown(detail, page))).isTrue();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isTrue();

        assertThat(onEdt(detail::forward)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", notizen))).isTrue();
        assertThat(onEdt(detail::forward)).isTrue();
        assertThat(onEdt(() -> detail.showsLevel("notizen", null))).isTrue();
        assertThat(onEdt(() -> detail.forwardButton().isEnabled())).isFalse();

        // Von der Quellenseite zu einer Seite einer anderen Quelle (Baum: Auswahl, dann Öffnen): Zurück führt zur Quelle.
        String other = context.runtime().workspace().liveMetadata("handbuch", handbuch, null, 10).entries().get(1)
                .page().pageId();
        onEdt(() -> {
            view.selection(new ExportPanel.Selection("handbuch", handbuch, other, "handbuch 1"));
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

    private static boolean shown(PageDetailPanel detail, String pageId) {
        return detail.shownPage() != null && pageId.equals(detail.shownPage().header().metadata().pageId());
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
