package com.aresstack.spaceharvester.application.holdings;

import java.util.List;
import java.util.Objects;

/**
 * Eine Auswahl im Baum (ADR 0029): eine Menge von Wurzeln, je eine ganze Quelle, ein Space oder eine Seite samt allen
 * Unterseiten. {@link Holdings#resolve} löst sie im Core zu Seiten auf; Begriffe, Suche, Aussagen und Chat filtern nach
 * dieser Menge, statt je Ansicht einen eigenen Umfang zu führen.
 *
 * @param roots die Wurzeln; Überschneidungen sind erlaubt, jede Seite zählt einmal
 */
public record Selection(List<Root> roots) {

    public Selection {
        roots = List.copyOf(roots);
    }

    public static Selection of(Root... roots) {
        return new Selection(List.of(roots));
    }

    /**
     * Eine Wurzel der Auswahl.
     *
     * @param sourceId Quelle
     * @param spaceKey Space oder {@code null} für alle Spaces der Quelle
     * @param pageId   Seite samt Unterseiten oder {@code null} für den ganzen Space
     */
    public record Root(String sourceId, String spaceKey, String pageId) {

        public Root {
            Objects.requireNonNull(sourceId, "sourceId");
            if (pageId != null && spaceKey == null) {
                throw new IllegalArgumentException("Eine Seite braucht ihren Space");
            }
        }

        /** Alle Spaces einer Quelle, die der Aufrufer sehen darf. */
        public static Root source(String sourceId) {
            return new Root(sourceId, null, null);
        }

        /** Alle Seiten eines Space. */
        public static Root space(String sourceId, String spaceKey) {
            return new Root(sourceId, Objects.requireNonNull(spaceKey, "spaceKey"), null);
        }

        /** Eine Seite samt allen Unterseiten, auch nicht aufgeklappten. */
        public static Root subtree(String sourceId, String spaceKey, String pageId) {
            return new Root(sourceId, spaceKey, Objects.requireNonNull(pageId, "pageId"));
        }
    }
}
