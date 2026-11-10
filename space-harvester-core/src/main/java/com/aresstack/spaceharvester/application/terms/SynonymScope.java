package com.aresstack.spaceharvester.application.terms;

/**
 * Umfang eines Synonymlaufs (#336): alle Quellen, eine Quelle oder ein Space. Entspricht dem Geltungsbereich der
 * vorgeschlagenen Beziehungen ({@link #relationScope()}); Vorschläge aus einem Space gelten nur dort.
 *
 * @param sourceId Quelle oder {@code null} für alle berechtigten Quellen
 * @param spaceKey Space oder {@code null}; nur mit {@code sourceId}
 */
public record SynonymScope(String sourceId, String spaceKey) {

    /** Alle Quellen, globaler Geltungsbereich. */
    public static final SynonymScope ALL = new SynonymScope(null, null);

    public SynonymScope {
        sourceId = blankToNull(sourceId);
        spaceKey = blankToNull(spaceKey);
        if (sourceId == null && spaceKey != null) {
            throw new IllegalArgumentException("spaceKey nur mit sourceId");
        }
    }

    public static SynonymScope source(String sourceId) {
        return new SynonymScope(sourceId, null);
    }

    public static SynonymScope space(String sourceId, String spaceKey) {
        return new SynonymScope(sourceId, spaceKey);
    }

    public boolean global() {
        return sourceId == null;
    }

    /** Geltungsbereich der Beziehungen dieses Laufs. */
    public RelationScope relationScope() {
        return new RelationScope(sourceId, spaceKey);
    }

    /** Ob eine Seite im Umfang liegt. */
    public boolean covers(String pageSourceId, String pageSpaceKey) {
        return relationScope().covers(pageSourceId, pageSpaceKey);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
