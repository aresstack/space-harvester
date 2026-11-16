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
}
