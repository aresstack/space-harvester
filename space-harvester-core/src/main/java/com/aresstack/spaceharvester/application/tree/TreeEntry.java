package com.aresstack.spaceharvester.application.tree;

import java.util.Objects;

/**
 * Ein flacher Eintrag für den Baum: eine Seite und ihre Elternseite, beide innerhalb derselben Quelle.
 *
 * @param pageId       ID der Seite in der Quelle
 * @param parentPageId ID der Elternseite oder {@code null} für eine Wurzelseite
 * @param position     Position unter den Geschwistern, wie die Quelle sie liefert (Confluence {@code position}), oder
 *                     {@code null}, wenn die Seite keine hat
 * @param title        Titel der Seite oder {@code null}; ordnet Geschwister ohne Position
 */
public record TreeEntry(String pageId, String parentPageId, Long position, String title) {

    public TreeEntry {
        Objects.requireNonNull(pageId, "pageId");
    }

    /** Ohne Position und Titel: Geschwister stehen nach Seiten-ID. */
    public TreeEntry(String pageId, String parentPageId) {
        this(pageId, parentPageId, null, null);
    }
}
