package com.aresstack.spaceharvester.application.quality;

/**
 * Ein Bereich einer Testfrage (Spalte {@code bereich}): alles, eine Quelle, ein Space oder eine Seite, wahlweise mit
 * Unterseiten. Die App macht daraus den Bereich der Suche, wie ihn der Chat bekommt.
 *
 * @param kind         Art des Bereichs
 * @param sourceId     Quelle; {@code null} bei {@link Kind#ALL}
 * @param spaceKey     Space bei {@link Kind#SPACE}, sonst {@code null}
 * @param pageId       Seite bei {@link Kind#PAGE}, sonst {@code null}
 * @param withChildren nur bei {@link Kind#PAGE}: mit allen Unterseiten
 */
public record ScopeSpec(Kind kind, String sourceId, String spaceKey, String pageId, boolean withChildren) {

    public enum Kind {
        ALL,
        SOURCE,
        SPACE,
        PAGE
    }

    public static ScopeSpec all() {
        return new ScopeSpec(Kind.ALL, null, null, null, false);
    }

    public static ScopeSpec source(String sourceId) {
        return new ScopeSpec(Kind.SOURCE, sourceId, null, null, false);
    }

    public static ScopeSpec space(String sourceId, String spaceKey) {
        return new ScopeSpec(Kind.SPACE, sourceId, spaceKey, null, false);
    }

    public static ScopeSpec page(PageRef page, boolean withChildren) {
        return new ScopeSpec(Kind.PAGE, page.sourceId(), null, page.pageId(), withChildren);
    }
}
