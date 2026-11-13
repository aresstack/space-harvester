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
                .containsExactly("Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen", "Erweitert", "Export");
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
        })).containsExactly("Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen", "Erweitert", "Export");
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
}
