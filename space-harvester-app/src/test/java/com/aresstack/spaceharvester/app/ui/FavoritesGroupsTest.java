package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.group.GroupColor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Farbige Gruppen als erweiterte Favoriten (Angelo 27.11.2026): Eine Gruppe ist ein Favoritenordner mit Farbe;
 * zugeordnet wird der Knoten Favorit in diesem Ordner, ein Favorit liegt immer nur in einer Gruppe.
 */
class FavoritesGroupsTest {

    @TempDir
    Path home;

    private static final Favorites.Favorite PAGE = new Favorites.Favorite("wiki", "OPS", "4711", "Betriebshandbuch",
            List.of("Wiki", "Betrieb"), List.of("100"));

    @Test
    void assigningAGroupMakesAFavoriteInsideTheColoredFolderAndSurvivesARestart() throws Exception {
        Path file = home.resolve(Favorites.FILE);
        Favorites favorites = new Favorites(() -> file);
        String green = favorites.createFolder("Wichtig", null, GroupColor.GRUEN);
        favorites.createFolder("Ablage", null);

        assertThat(favorites.assign(PAGE, green)).isTrue();
        assertThat(favorites.contains("wiki", "OPS", "4711")).isTrue();
        assertThat(favorites.favoritesIn(green, false)).containsExactly(PAGE);
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).contains(GroupColor.GRUEN);
        assertThat(favorites.groupOf("wiki", "OPS", "4711")).contains(green);
        assertThat(favorites.groups()).extracting(Favorites.Folder::name).containsExactly("Wichtig");
        assertThat(Files.readString(file)).contains("\"color\" : \"gruen\"");

        Favorites again = new Favorites(() -> file);
        assertThat(again.colorOf("wiki", "OPS", "4711")).contains(GroupColor.GRUEN);
        assertThat(again.folder(green)).get().extracting(Favorites.Folder::color).isEqualTo(GroupColor.GRUEN);
    }

    @Test
    void aFavoriteIsOnlyInOneGroupAndMovesWithItsRememberedPath() {
        Favorites favorites = new Favorites(() -> home.resolve(Favorites.FILE));
        String blue = favorites.createFolder("Blau", null, GroupColor.BLAU);
        String orange = favorites.createFolder("Orange", null, GroupColor.ORANGE);
        favorites.toggle(PAGE);
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).isEmpty();

        favorites.assign(PAGE, blue);
        // Neu zugeordnet ohne Pfad: der gemerkte Pfad bleibt, der Favorit verlässt die vorige Gruppe.
        assertThat(favorites.assign(new Favorites.Favorite("wiki", "OPS", "4711", null, List.of(), List.of()),
                orange)).isTrue();
        assertThat(favorites.favoritesIn(blue, true)).isEmpty();
        assertThat(favorites.favoritesIn(orange, true)).containsExactly(PAGE);
        assertThat(favorites.all()).hasSize(1);
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).contains(GroupColor.ORANGE);
        // Schon drin: nichts ändert sich.
        assertThat(favorites.assign(PAGE, orange)).isFalse();
        // Unbekannte Gruppe: abgelehnt.
        assertThat(favorites.assign(PAGE, "gibt-es-nicht")).isFalse();
        // Stern zurücknehmen nimmt ihn auch aus der Gruppe.
        assertThat(favorites.toggle(PAGE)).isFalse();
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).isEmpty();
    }

    @Test
    void aPlainFolderInsideAGroupInheritsItsColorAndRecoloringChangesEveryMember() {
        Favorites favorites = new Favorites(() -> home.resolve(Favorites.FILE));
        String group = favorites.createFolder("Projekt", null, GroupColor.LILA);
        String inner = favorites.createFolder("Unterordner", group);
        favorites.assign(PAGE, inner);
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).contains(GroupColor.LILA);
        assertThat(favorites.groupOf("wiki", "OPS", "4711")).contains(group);

        assertThat(favorites.recolorFolder(group, GroupColor.TUERKIS)).isTrue();
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).contains(GroupColor.TUERKIS);
        assertThat(favorites.recolorFolder(group, null)).isTrue();
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).isEmpty();
        assertThat(favorites.groups()).isEmpty();
    }

    @Test
    void anUnknownColorKeyStaysAGroupInGrey() throws Exception {
        Path file = home.resolve(Favorites.FILE);
        Files.writeString(file, """
                {"version": 2, "entries": [
                  {"id": "g1", "folder": true, "name": "Alt", "color": "magenta", "children": [
                    {"id": "b1", "sourceId": "wiki", "spaceKey": "OPS", "pageId": "4711", "title": "Handbuch"}
                  ]},
                  {"id": "f1", "folder": true, "name": "Ohne Farbe", "children": []}
                ]}
                """);
        Favorites favorites = new Favorites(() -> file);
        assertThat(favorites.colorOf("wiki", "OPS", "4711")).contains(GroupColor.GRAU);
        assertThat(favorites.groups()).extracting(Favorites.Folder::id).containsExactly("g1");
        assertThat(favorites.folder("f1")).get().extracting(Favorites.Folder::isGroup).isEqualTo(false);
    }
}
