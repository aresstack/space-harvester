package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Favoriten (Angelo, 13.11.2026): Seiten und Spaces mit Stern, gemerkt im Datenordner der App ({@code favorites.json}),
 * nicht im Core und nicht in der Konfigurationsdatei; derselbe Knoten ist einmal drin, egal mit welchem Titel.
 */
class FavoritesTest {

    @TempDir
    Path home;

    @Test
    void favoritesSurviveARestartAndAreToggledByNodeNotByTitle() {
        Path file = home.resolve("data").resolve(Favorites.FILE);
        Favorites favorites = new Favorites(() -> file);
        AtomicInteger changes = new AtomicInteger();
        favorites.onChange(changes::incrementAndGet);
        assertThat(favorites.all()).isEmpty();

        Favorites.Favorite page = new Favorites.Favorite("wiki", "OPS", "4711", "Betriebshandbuch",
                List.of("Confluence", "Betrieb", "Handbücher"), List.of("100"));
        assertThat(favorites.toggle(page)).isTrue();
        assertThat(favorites.toggle(Favorites.Favorite.space("wiki", "OPS", "Betrieb", "Confluence"))).isTrue();
        assertThat(favorites.contains("wiki", "OPS", "4711")).isTrue();
        assertThat(favorites.contains("wiki", "OPS", null)).isTrue();
        assertThat(favorites.contains("wiki", "OPS", "4712")).isFalse();
        assertThat(changes.get()).isEqualTo(2);
        assertThat(Files.isRegularFile(file)).isTrue();

        // Neu gestartet: dieselben Favoriten in derselben Reihenfolge, mit Pfad und Vorfahren.
        Favorites again = new Favorites(() -> file);
        assertThat(again.all()).containsExactly(page, Favorites.Favorite.space("wiki", "OPS", "Betrieb", "Confluence"));
        assertThat(again.all().get(0).where()).isEqualTo("Confluence › Betrieb › Handbücher");
        // Zurücknehmen trifft den Knoten, auch wenn der Titel inzwischen anders heißt.
        assertThat(again.toggle(new Favorites.Favorite("wiki", "OPS", "4711", "Anderer Titel", List.of(),
                List.of()))).isFalse();
        assertThat(again.contains("wiki", "OPS", "4711")).isFalse();
        assertThat(new Favorites(() -> file).all()).hasSize(1);

        // Eine ganze Quelle (Angelo, 16.11.2026): ohne Space, überlebt den Neustart, getrennt vom Space-Favoriten.
        assertThat(again.toggle(Favorites.Favorite.source("wiki", "Confluence"))).isTrue();
        assertThat(again.contains("wiki", null, null)).isTrue();
        assertThat(again.contains("wiki", "OPS", null)).isTrue();
        Favorites third = new Favorites(() -> file);
        assertThat(third.all()).extracting(Favorites.Favorite::isSource).containsExactly(false, true);
        assertThat(third.all().get(1).title()).isEqualTo("Confluence");
        assertThat(third.toggle(Favorites.Favorite.source("wiki", "Anderer Titel"))).isFalse();
        assertThat(third.contains("wiki", null, null)).isFalse();
        assertThat(third.contains("wiki", "OPS", null)).isTrue();
    }

    @Test
    void withoutADataDirectoryFavoritesStayInMemory() {
        Favorites favorites = new Favorites(() -> null);
        assertThat(favorites.toggle(Favorites.Favorite.space("wiki", "OPS", null, null))).isTrue();
        assertThat(favorites.all()).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("OPS");
            assertThat(f.where()).isEqualTo("wiki");
        });
    }

    @Test
    void foldersNestMoveAndSurviveARestart() {
        Path file = home.resolve("data").resolve(Favorites.FILE);
        Favorites favorites = new Favorites(() -> file);
        Favorites.Favorite handbook = new Favorites.Favorite("wiki", "OPS", "4711", "Betriebshandbuch", List.of(),
                List.of());
        Favorites.Favorite ops = Favorites.Favorite.space("wiki", "OPS", "Betrieb", "Confluence");
        Favorites.Favorite source = Favorites.Favorite.source("jira", "Jira");
        String projects = favorites.createFolder("Projekte", null);
        String inner = favorites.createFolder(" Alpha ", projects);
        assertThat(favorites.createFolder("  ", null)).isNull();
        favorites.toggle(handbook, inner);
        favorites.toggle(ops, projects);
        favorites.toggle(source);

        // Der Stern findet den Favoriten in jedem Ordner; Baumreihenfolge: Ordner zuerst angelegt.
        assertThat(favorites.contains("wiki", "OPS", "4711")).isTrue();
        assertThat(favorites.all()).containsExactly(handbook, ops, source);
        Favorites.Folder folder = favorites.folder(projects).orElseThrow();
        assertThat(folder.favorites(false)).containsExactly(ops);
        assertThat(folder.favorites(true)).containsExactly(handbook, ops);
        assertThat(favorites.favoritesIn(projects, true)).containsExactly(handbook, ops);
        assertThat(favorites.favoritesIn(null, false)).containsExactly(source);
        assertThat(favorites.favoritesIn("unbekannt", true)).isEmpty();
        // Ein Favorit steht für dieselbe Menge wie sein Knoten im Baum (ADR 0034 Abschnitt 1).
        assertThat(handbook.root()).isEqualTo(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("wiki", "OPS", "4711"));
        assertThat(ops.root()).isEqualTo(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("wiki", "OPS"));
        assertThat(source.root()).isEqualTo(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.source("jira"));
        assertThat(favorites.folder(inner).orElseThrow().name()).isEqualTo("Alpha");

        // Kein Ordner in sich selbst, kein unbekanntes Ziel; ein Ordner wandert mit Inhalt.
        assertThat(favorites.move(projects, inner, 0)).isFalse();
        assertThat(favorites.move(projects, "gibt-es-nicht", 0)).isFalse();
        assertThat(favorites.move(inner, null, 0)).isTrue();
        assertThat(favorites.tree()).first().isInstanceOfSatisfying(Favorites.Folder.class,
                first -> assertThat(first.favorites(true)).containsExactly(handbook));
        assertThat(favorites.renameFolder(inner, "Beta")).isTrue();

        // Neu gestartet: derselbe Baum mit denselben IDs.
        Favorites again = new Favorites(() -> file);
        assertThat(again.tree()).isEqualTo(favorites.tree());
        assertThat(again.folder(inner).orElseThrow().name()).isEqualTo("Beta");

        // Zurücknehmen trifft den Favoriten im Ordner; Ordner entfernen nimmt den Inhalt mit.
        assertThat(again.toggle(handbook, null)).isFalse();
        assertThat(again.remove(projects)).isTrue();
        assertThat(again.all()).containsExactly(source);
    }

    @Test
    void aFlatFileFromBeforeFoldersBecomesTheTopLevel() throws Exception {
        Path file = home.resolve(Favorites.FILE);
        Files.writeString(file, """
                {"favorites": [
                  {"sourceId": "wiki", "spaceKey": "OPS", "pageId": "4711", "title": "Handbuch",
                   "path": ["Confluence"], "ancestorIds": ["100"]},
                  {"sourceId": "wiki", "spaceKey": "OPS", "pageId": "4711", "title": "doppelt"},
                  {"sourceId": "wiki", "spaceKey": "OPS", "pageId": null, "title": "Betrieb"}
                ]}
                """);
        Favorites favorites = new Favorites(() -> file);
        assertThat(favorites.all()).extracting(Favorites.Favorite::title).containsExactly("Handbuch", "Betrieb");
        assertThat(favorites.all().get(0).ancestorIds()).containsExactly("100");
        assertThat(favorites.tree()).allMatch(entry -> entry instanceof Favorites.Bookmark);
        // Einmal gesichert und im neuen Format geschrieben.
        assertThat(file.resolveSibling(Favorites.FILE + ".v1")).exists();
        assertThat(Files.readString(file)).contains("\"version\"").contains("\"entries\"");
        assertThat(new Favorites(() -> file).all()).hasSize(2);
    }

    @Test
    void anUnreadableFileIsSetAsideAndNeverOverwritten() throws Exception {
        Path file = home.resolve(Favorites.FILE);
        Files.writeString(file, "{ kaputt");
        Favorites favorites = new Favorites(() -> file);
        assertThat(favorites.all()).isEmpty();
        favorites.toggle(Favorites.Favorite.source("wiki", "Wiki"));
        try (var files = Files.list(home)) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith(Favorites.FILE + ".defekt-")))
                    .singleElement().satisfies(aside -> assertThat(Files.readString(aside)).isEqualTo("{ kaputt"));
        }
        assertThat(new Favorites(() -> file).all()).hasSize(1);
    }
}
