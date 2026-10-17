package com.aresstack.spaceharvester.application.query;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

/**
 * Stabile Sortierungen der Suche (#28). Jede endet mit {@code pageId} und dann {@code sourceId} aufsteigend
 * ({@link String#compareTo}), damit gleiche Schlüssel eine feste Reihenfolge haben und ein Cursor eindeutig
 * weiterführt, auch global über Quellen mit gleichen Seiten-IDs (#114). In einer Quelle ist die pageId schon
 * eindeutig; dort bleibt die Reihenfolge wie vor #114. Texte vergleicht {@link String#compareTo}
 * (UTF-16-Codeeinheiten), ohne Kollation.
 */
public enum SearchSort {

    /** Titel aufsteigend, ohne Beachtung der Groß-/Kleinschreibung ({@code toLowerCase(Locale.ROOT)}), dann pageId. */
    TITLE(Comparator.comparing((ListedPage page) -> page.header().metadata().title().toLowerCase(Locale.ROOT))),

    /** Änderungszeit absteigend, Seiten ohne Änderungszeit zuletzt, dann pageId. */
    MODIFIED_DESC(Comparator.comparing((ListedPage page) -> page.header().metadata().modifiedAt(),
            Comparator.<Instant>nullsLast(Comparator.reverseOrder()))),

    /** pageId aufsteigend, dann sourceId. */
    PAGE_ID((left, right) -> 0);

    private final Comparator<ListedPage> order;

    SearchSort(Comparator<ListedPage> key) {
        this.order = key.thenComparing(ListedPage::pageId).thenComparing(page -> page.header().key().sourceId());
    }

    /** Die Reihenfolge als Vergleich; Maßstab für jede Umsetzung des Index. */
    public Comparator<ListedPage> order() {
        return order;
    }
}
