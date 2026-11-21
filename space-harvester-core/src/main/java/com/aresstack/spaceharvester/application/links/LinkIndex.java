package com.aresstack.spaceharvester.application.links;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Port der Ablage des Verzeichnisses: ein Eintrag je erfasster Seite mit dem Stand der Payload, aus dem er stammt,
 * Space und Titel der Seite und ihren rohen Verweiszielen. Abgeleitet: Was fehlt, berechnet {@link PageLinks} aus dem
 * Cache neu.
 */
public interface LinkIndex {

    /** Der Eintrag einer Seite. */
    Optional<Entry> entry(PageKey page);

    /** Legt den Eintrag einer Seite ab und ersetzt den vorigen. */
    void put(Entry entry);

    /** Entfernt den Eintrag einer Seite; {@code false}, wenn es keinen gab. */
    boolean remove(PageKey page);

    /** Die Seiten-IDs aller Einträge einer Quelle. */
    List<String> pageIds(String sourceId);

    /** Anzahl der Einträge einer Quelle. */
    int count(String sourceId);

    /** Einträge der Quelle, die ein Ziel mit dieser Seiten-ID haben. */
    List<Entry> linkingToId(String sourceId, String pageId);

    /** Einträge der Quelle, die ein Ziel mit diesem {@link LinkTarget#titleKey Titelschlüssel} haben. */
    List<Entry> linkingToTitle(String sourceId, String titleKey);

    /** Einträge der Quelle, deren Seite selbst diesen {@link LinkTarget#titleKey Titelschlüssel} trägt. */
    List<Entry> titled(String sourceId, String titleKey);

    /** Macht die bisherigen Änderungen dauerhaft, wenn die Ablage sie bündelt. */
    default void flush() {
    }

    /**
     * Ein erfasster Stand.
     *
     * @param page      Seite
     * @param spaceKey  Space der Seite beim Erfassen
     * @param title     Titel der Seite beim Erfassen
     * @param rawSha256 Hash der Payload, aus der die Ziele stammen
     * @param targets   Verweisziele in Reihenfolge der Seite
     */
    record Entry(PageKey page, String spaceKey, String title, String rawSha256, List<LinkTarget> targets) {

        public Entry {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(spaceKey, "spaceKey");
            title = title == null ? "" : title;
            Objects.requireNonNull(rawSha256, "rawSha256");
            targets = List.copyOf(targets);
        }

        /** {@link LinkTarget#titleKey(String, String)} der Seite selbst. */
        public String titleKey() {
            return LinkTarget.titleKey(spaceKey, title);
        }
    }
}
