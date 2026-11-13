package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Lesender Port des Bestands auf den Seiten-Cache: je Space die Rohdaten, aus denen {@link Holdings} Zustände und
 * Zähler rechnet, ohne Payload und ohne Quellenzugriff. Umgesetzt vom Lucene-Cache, in Tests von Fakes.
 */
public interface HoldingsCatalog {

    /**
     * Rohstand eines Space aus einem Lesestand des Cache.
     *
     * @return leer, wenn der Cache von dem Space nichts kennt (keine Seite, keine bestätigte Generation)
     */
    Optional<Stock> stock(String sourceId, String spaceKey);

    /** Spaces einer Quelle, von denen der Cache Seiten oder eine bestätigte Generation kennt, sortiert. */
    List<String> spaceKeys(String sourceId);

    /**
     * Rohstand eines Space.
     *
     * @param generation Generation wie beim Seitenbaum ({@code g0} ohne bestätigte Inventur)
     * @param pages      Seiten des Katalogs nach ihren aktuellen Kopfdaten
     * @param stale      Seiten, die die bestätigte Generation als veraltet führt
     * @param locked     gesperrte Seiten der bestätigten Generation; sie haben kein Seitendokument mehr
     */
    record Stock(CacheGeneration generation, List<CatalogPage> pages, Set<String> stale, List<LockedPage> locked) {

        public Stock {
            Objects.requireNonNull(generation, "generation");
            pages = List.copyOf(pages);
            stale = Set.copyOf(stale);
            locked = List.copyOf(locked);
        }
    }

    /**
     * Eine Seite des Katalogs.
     *
     * @param parentPageId  Elternseite nach den aktuellen Kopfdaten oder {@code null}
     * @param loaded        ob ein Inhalt gespeichert ist
     * @param version       Version nach den aktuellen Kopfdaten oder {@code null}
     * @param loadedVersion Version des gespeicherten Inhalts oder {@code null}
     */
    record CatalogPage(String pageId, String parentPageId, boolean loaded, String version, String loadedVersion) {

        public CatalogPage {
            Objects.requireNonNull(pageId, "pageId");
        }
    }

    /**
     * Eine gesperrte Seite.
     *
     * @param parentPageId Elternseite aus ihrem zurückgehaltenen Stand oder {@code null}, wenn keiner vorliegt
     */
    record LockedPage(String pageId, String parentPageId) {

        public LockedPage {
            Objects.requireNonNull(pageId, "pageId");
        }
    }

    /** Ein Cache ohne Inhalt. */
    HoldingsCatalog EMPTY = new HoldingsCatalog() {
        @Override
        public Optional<Stock> stock(String sourceId, String spaceKey) {
            return Optional.empty();
        }

        @Override
        public List<String> spaceKeys(String sourceId) {
            return List.of();
        }
    };
}
