package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Stern im Explorer (Angelo, 13.11.2026): Spaces und Seiten werden per Kontextmenü oder Stern in der Zeile
 * Favorit; der Umschalter unten rechts zeigt statt des Baums nur die Favoriten als Liste mit ihrem Pfad.
 */
class ExplorerFavoritesTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theStarMarksSpacesAndPagesAndTheToggleListsOnlyFavorites() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("wartung.md"), "# Wartung\n\nText.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        String pageId = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().get(0)
                .page().pageId();

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            Explorer.SpaceItem item = new Explorer.SpaceItem("handbuch", space, space);
            // Kontextmenü: „Favorit“, danach „Favorit entfernen“; auch ganze Quellen (Angelo, 16.11.2026).
            assertThat(labels(explorer[0].menu(item))).contains(Explorer.FAVORITE).doesNotContain(Explorer.UNFAVORITE);
            assertThat(labels(explorer[0].menu(new Explorer.SourceItem("handbuch", "Handbuch"))))
                    .contains(Explorer.FAVORITE, Explorer.REFRESH);
            explorer[0].toggleFavorite(item);
            assertThat(explorer[0].isFavorite(item)).isTrue();
            assertThat(labels(explorer[0].menu(item))).contains(Explorer.UNFAVORITE);
            // Unten rechts der Umschalter: Umriss = Baum, gefüllt = nur Favoriten mit Pfad.
            assertThat(explorer[0].favoritesButton().getIcon()).isSameAs(Glyph.STAR);
            assertThat(explorer[0].favoritesButton().getToolTipText()).isEqualTo(Explorer.ONLY_FAVORITES);
            explorer[0].favoritesButton().doClick();
            assertThat(explorer[0].onlyFavorites()).isTrue();
            assertThat(explorer[0].favoritesButton().getIcon()).isSameAs(Glyph.STAR_FILLED);
            assertThat(explorer[0].favoriteView().component().getParent()).isNotNull();
            assertThat(explorer[0].favoriteView().tree().component().getRowCount()).isEqualTo(1);
            assertThat(explorer[0].favoriteView().shown()).singleElement()
                    .satisfies(favorite -> assertThat(favorite.where()).isEqualTo("Handbuch"));
            // Eine Seite merkt sich ihren Pfad im Hintergrund.
            explorer[0].toggleFavorite(Explorer.PageItem.of("handbuch", space, pageId, "Wartung", false));
        });
        // Der Pfad kommt aus dem Core im Hintergrund, die Liste frischt danach auf dem EDT auf; auf dem CI-Läufer
        // dauert das länger als hier. Auf die Liste warten, nicht nur auf den Speicher, sonst liest die Prüfung vor
        // der Auffrischung.
        long deadline = System.nanoTime() + 120_000_000_000L;
        int[] listed = new int[1];
        do {
            assertThat(System.nanoTime()).as("Zeitlimit: Seiten-Favorit").isLessThan(deadline);
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> listed[0] = explorer[0].favoriteView().tree().component().getRowCount());
        } while (listed[0] < 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(explorer[0].favoriteView().shown()).hasSize(2);
            Favorites.Favorite page = explorer[0].favoriteView().shown().get(1);
            assertThat(page.pageId()).isEqualTo(pageId);
            assertThat(page.where()).startsWith("Handbuch › ");
            // Nichts im Baum gewählt: kein Favorit markiert.
            assertThat(explorer[0].favoriteView().marked()).isNull();
            explorer[0].reveal("handbuch", space);
        });
        // Die Liste markiert den Favoriten des gewählten Knotens, damit ein Klick auf den schon offenen Favoriten
        // nicht wie „nichts passiert“ aussieht.
        Favorites.Favorite[] marked = new Favorites.Favorite[1];
        long revealed = System.nanoTime() + 30_000_000_000L;
        do {
            assertThat(System.nanoTime()).as("Zeitlimit: Space im Baum gewählt").isLessThan(revealed);
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> marked[0] = explorer[0].favoriteView().marked());
        } while (marked[0] == null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(marked[0].spaceKey()).isEqualTo(space);
            assertThat(marked[0].pageId()).isNull();
            explorer[0].favoritesButton().doClick();
            assertThat(explorer[0].onlyFavorites()).isFalse();
        });
        assertThat(Files.isRegularFile(home.resolve("data").resolve(Favorites.FILE))).isTrue();
    }

    @Test
    void foldersAreCreatedMovedAndChosenAsCollectionsAndTheStarLandsInTheChosenFolder() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources: []
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        Favorites favorites = context.favorites();
        Favorites.Favorite ops = Favorites.Favorite.space("wiki", "OPS", "Betrieb", "Confluence");
        Favorites.Favorite jira = Favorites.Favorite.source("jira", "Jira");
        favorites.toggle(ops);
        String projects = favorites.createFolder("Projekte", null);
        java.util.List<String> chosen = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            explorer.onFolder((id, name) -> chosen.add(id + "|" + name));
            FavoritesView view = explorer.favoriteView();
            view.reload();
            com.aresstack.spaceharvester.app.ui.controls.FolderTree<Favorites.Entry> tree = view.tree();
            // Ordner zuerst angelegt? Nein: der Space-Favorit kam zuerst, der Ordner steht dahinter.
            assertThat(tree.component().getRowCount()).isEqualTo(2);
            // „Neuer Ordner“ steht unten in der Fußleiste, nicht in einer eigenen Leiste über den Favoriten, und nur
            // solange die Favoriten gezeigt werden (Angelo 24.11.2026).
            javax.swing.JComponent newFolder = view.newFolderButton();
            assertThat(SwingUtilities.isDescendingFrom(newFolder, view.component())).isFalse();
            assertThat(SwingUtilities.getAncestorOfClass(
                    com.aresstack.spaceharvester.app.ui.controls.SideFoot.class, newFolder)).isNotNull();
            assertThat(newFolder.isVisible()).isFalse();
            explorer.showOnlyFavorites(true);
            assertThat(newFolder.isVisible()).isTrue();
            explorer.showOnlyFavorites(false);
            assertThat(newFolder.isVisible()).isFalse();
            // Ein Ordner gewählt: ein neuer Stern landet darin, gemeldet wird die Sammlung.
            tree.select(java.util.List.of(projects));
            favorites.toggle(jira);
            assertThat(favorites.favoritesIn(projects, false)).containsExactly(jira);
            tree.component().getActionMap().get("favorite-open").actionPerformed(null);
            assertThat(chosen).containsExactly(projects + "|Projekte");
            // Ohne Maus verschieben: der Space-Favorit in den Ordner.
            String opsId = ((Favorites.Bookmark) favorites.tree().get(0)).id();
            tree.select(java.util.List.of(opsId));
            assertThat(tree.moveSelectedTo(projects)).isTrue();
            assertThat(favorites.favoritesIn(projects, false)).containsExactly(jira, ops);
            // Ohne Auswahl: oben.
            tree.component().clearSelection();
            favorites.toggle(Favorites.Favorite.source("wiki", "Wiki"));
            assertThat(favorites.favoritesIn(null, false)).extracting(Favorites.Favorite::sourceId)
                    .containsExactly("wiki");
        });
    }

    @Test
    void aPageFavoriteCarriesSpaceNameAndAncestorsAsItsPath() {
        PageNavigation.Position position = new PageNavigation.Position("wiki", "OPS", "Betrieb",
                List.of(node("100", "Handbücher"), node("200", "Server")), node("4711", "Backup"));
        Favorites.Favorite favorite = Explorer.favorite(position, "Confluence",
                Explorer.PageItem.of("wiki", "OPS", "4711", "Backup (alt)", true));
        assertThat(favorite.title()).isEqualTo("Backup");
        assertThat(favorite.where()).isEqualTo("Confluence › Betrieb › Handbücher › Server");
        assertThat(favorite.ancestorIds()).containsExactly("100", "200");
        assertThat(Explorer.favorite(null, "Confluence", Explorer.PageItem.of("wiki", "OPS", "4711", "Backup",
                true)).where()).isEqualTo("Confluence › OPS");
    }

    private static HarvestSource.PageNode node(String id, String title) {
        return new HarvestSource.PageNode(id, title, "OPS", false);
    }

    private static List<String> labels(javax.swing.JPopupMenu menu) {
        List<String> labels = new java.util.ArrayList<>();
        for (java.awt.Component item : menu.getComponents()) {
            if (item instanceof javax.swing.JMenuItem menuItem) {
                labels.add(menuItem.getText());
            }
        }
        return labels;
    }
}
