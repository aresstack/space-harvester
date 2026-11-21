package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Optional;

/**
 * Was „Zusammenfassen“ über den Bericht hinaus von einer Seite wissen darf (Angelo, 21.11.2026): der Lesetext ihrer
 * jüngsten Fassung im Cache, damit die Zusammenfassung sagt, worum es in neuen Seiten geht, und der Name eines Space.
 * Rechte prüft der Aufrufer; hier wird nichts nachgeladen.
 */
public interface SummaryMaterial {

    /** Lesetext der jüngsten Fassung im Cache, leer wenn nicht lokal oder ohne passenden Projektor. */
    Optional<String> text(PageKey page);

    /**
     * Anzeigename eines Space, leer wenn unbekannt.
     *
     * @param page eine Seite dieses Space, deren gespeicherte Fassung den Namen tragen kann
     */
    Optional<String> spaceName(PageKey page, String spaceKey);

    /** Nichts über den Bericht hinaus. */
    SummaryMaterial NONE = new SummaryMaterial() {
        @Override
        public Optional<String> text(PageKey page) {
            return Optional.empty();
        }

        @Override
        public Optional<String> spaceName(PageKey page, String spaceKey) {
            return Optional.empty();
        }
    };
}
