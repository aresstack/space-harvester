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
            // Kontextmenü: „Favorit“, danach „Favorit entfernen“; Quellen haben keinen Stern.
            assertThat(labels(explorer[0].menu(item))).contains(Explorer.FAVORITE).doesNotContain(Explorer.UNFAVORITE);
            assertThat(labels(explorer[0].menu(new Explorer.SourceItem("handbuch", "Handbuch"))))
                    .doesNotContain(Explorer.FAVORITE);
            explorer[0].toggleFavorite(item);
            assertThat(explorer[0].isFavorite(item)).isTrue();
            assertThat(labels(explorer[0].menu(item))).contains(Explorer.UNFAVORITE);
            // Unten rechts der Umschalter: Umriss = Baum, gefüllt = nur Favoriten mit Pfad.
            assertThat(explorer[0].favoritesButton().getIcon()).isSameAs(Glyph.STAR);
            assertThat(explorer[0].favoritesButton().getToolTipText()).isEqualTo(Explorer.ONLY_FAVORITES);
            explorer[0].favoritesButton().doClick();
            assertThat(explorer[0].onlyFavorites()).isTrue();
            assertThat(explorer[0].favoritesButton().getIcon()).isSameAs(Glyph.STAR_FILLED);
            assertThat(explorer[0].favoriteList().isShowing() || explorer[0].favoriteList().getParent() != null).isTrue();
            assertThat(explorer[0].favoriteList().getModel().getSize()).isEqualTo(1);
            assertThat(explorer[0].favoriteList().getModel().getElementAt(0).where()).isEqualTo("Handbuch");
            // Eine Seite merkt sich ihren Pfad im Hintergrund.
            explorer[0].toggleFavorite(new Explorer.PageItem("handbuch", space, pageId, "Wartung", false));
        });
        for (int i = 0; i < 200 && context.favorites().all().size() < 2; i++) {
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            assertThat(explorer[0].favoriteList().getModel().getSize()).isEqualTo(2);
            Favorites.Favorite page = explorer[0].favoriteList().getModel().getElementAt(1);
            assertThat(page.pageId()).isEqualTo(pageId);
            assertThat(page.where()).startsWith("Handbuch › ");
            explorer[0].favoritesButton().doClick();
            assertThat(explorer[0].onlyFavorites()).isFalse();
        });
        assertThat(Files.isRegularFile(home.resolve("data").resolve(Favorites.FILE))).isTrue();
    }

    @Test
    void aPageFavoriteCarriesSpaceNameAndAncestorsAsItsPath() {
        PageNavigation.Position position = new PageNavigation.Position("wiki", "OPS", "Betrieb",
                List.of(node("100", "Handbücher"), node("200", "Server")), node("4711", "Backup"));
        Favorites.Favorite favorite = Explorer.favorite(position, "Confluence",
                new Explorer.PageItem("wiki", "OPS", "4711", "Backup (alt)", true));
        assertThat(favorite.title()).isEqualTo("Backup");
        assertThat(favorite.where()).isEqualTo("Confluence › Betrieb › Handbücher › Server");
        assertThat(favorite.ancestorIds()).containsExactly("100", "200");
        assertThat(Explorer.favorite(null, "Confluence", new Explorer.PageItem("wiki", "OPS", "4711", "Backup",
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
