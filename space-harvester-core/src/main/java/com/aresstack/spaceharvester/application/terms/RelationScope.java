package com.aresstack.spaceharvester.application.terms;

/**
 * Geltungsbereich einer Beziehung: global, eine Quelle oder ein Space einer Quelle. Dieselbe Abkürzung kann je
 * Geltungsbereich etwas anderes bedeuten; eine Sperre gilt nur in ihrem.
 *
 * @param sourceId Quelle oder {@code null} für global
 * @param spaceKey Space oder {@code null}; nur mit {@code sourceId}
 */
public record RelationScope(String sourceId, String spaceKey) {

    /** Überall. */
    public static final RelationScope GLOBAL = new RelationScope(null, null);

    public RelationScope {
        sourceId = blankToNull(sourceId);
        spaceKey = blankToNull(spaceKey);
        if (sourceId == null && spaceKey != null) {
            throw new IllegalArgumentException("spaceKey nur mit sourceId");
        }
    }

    public boolean global() {
        return sourceId == null;
    }

    /** Ob eine Seite dieser Quelle und dieses Space im Geltungsbereich liegt. */
    public boolean covers(String pageSourceId, String pageSpaceKey) {
        return sourceId == null || sourceId.equals(pageSourceId) && (spaceKey == null || spaceKey.equals(pageSpaceKey));
    }

    String key() {
        return (sourceId == null ? "" : sourceId) + '\u0000' + (spaceKey == null ? "" : spaceKey);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
