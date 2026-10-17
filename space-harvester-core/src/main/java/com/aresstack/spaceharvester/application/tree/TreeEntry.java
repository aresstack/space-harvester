package com.aresstack.spaceharvester.application.tree;

import java.util.Objects;

/**
 * Ein flacher Eintrag für den Baum: eine Seite und ihre Elternseite, beide innerhalb derselben Quelle.
 *
 * @param pageId       ID der Seite in der Quelle
 * @param parentPageId ID der Elternseite oder {@code null} für eine Wurzelseite
 */
public record TreeEntry(String pageId, String parentPageId) {

    public TreeEntry {
        Objects.requireNonNull(pageId, "pageId");
    }
}
