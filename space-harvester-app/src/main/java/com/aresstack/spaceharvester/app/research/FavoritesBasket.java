package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.spaceharvester.app.ui.Favorites;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.research4j.basket.SourceKeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Der Korb in Space Harvester ist ein Favoritenordner (ADR 0034). Der Korb einer Recherche sind die Seiten-Favoriten
 * des Ordners samt Unterordnern; Favoriten einer ganzen Quelle oder eines Space sind keine einzelne Quelle und bleiben
 * draußen. Was die Recherche in den Korb legt, kommt sofort als Seiten-Favorit in den Ordner (Teilergebnisse sofort);
 * eine Seite, die schon irgendwo Favorit ist, bleibt, wo sie ist. Die Seiten liegen schon im Bestand, gespeichert wird
 * nichts weiter.
 *
 * <p>Offen bis ADR 0043: Ein Seiten-Favorit bedeutet heute die Seite samt Unterseiten; die Wurzel „nur diese Seite“
 * kommt mit den Projekten.</p>
 */
public final class FavoritesBasket implements SourceKeeper {

    private final Favorites favorites;
    private final String folderId;
    private final Function<String, String> sourceTitle;

    /** @param sourceTitle Anzeigename einer Quelle für den Pfad des Favoriten; {@code null} = ihre Kennung */
    public FavoritesBasket(Favorites favorites, String folderId, Function<String, String> sourceTitle) {
        this.favorites = Objects.requireNonNull(favorites, "favorites");
        this.folderId = Objects.requireNonNull(folderId, "folderId");
        this.sourceTitle = sourceTitle == null ? id -> id : sourceTitle;
    }

    /** Der Korb: die Seiten-Favoriten des Ordners und aller Unterordner in Baumreihenfolge. */
    public SourceBasket basket() {
        List<SourceRef> refs = new ArrayList<>();
        for (Favorites.Favorite favorite : favorites.favoritesIn(folderId, true)) {
            if (favorite.pageId() != null) {
                refs.add(PageRefs.of(new PageKey(favorite.sourceId(), favorite.pageId()), favorite.title()));
            }
        }
        return new SourceBasket(refs);
    }

    @Override
    public SourceRef keep(FetchedContent content) throws SourceAccessException {
        PageKey key = PageRefs.key(content.ref());
        String space = content.metadata().get("Space");
        if (key == null || space == null || space.isBlank()) {
            throw new SourceAccessException(SourceAccessException.Reason.NOT_FOUND,
                    "keine Seite von Space Harvester mit Space");
        }
        if (!favorites.contains(key.sourceId(), space, key.pageId())) {
            favorites.toggle(Favorites.Favorite.page(key.sourceId(), space, key.pageId(), content.ref().title(), null,
                    sourceTitle.apply(key.sourceId())), folderId);
        }
        return content.ref();
    }
}
