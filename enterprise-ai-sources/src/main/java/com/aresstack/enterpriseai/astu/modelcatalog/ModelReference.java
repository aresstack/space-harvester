package com.aresstack.enterpriseai.astu.modelcatalog;

import java.util.Collection;

/**
 * Verweis auf ein Modell eines bestimmten Katalogs: {@code <katalog>:<modellId>}, zum Beispiel
 * {@code kipitz:<modellId>}. Die Modellkennung ist reine Konfiguration und darf selbst Doppelpunkte enthalten.
 */
public final class ModelReference {

    private final String catalogId;
    private final String modelId;

    public ModelReference(String catalogId, String modelId) {
        if (catalogId == null || catalogId.trim().isEmpty() || catalogId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("catalogId must be a non-empty name without ':'");
        }
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be empty");
        }
        this.catalogId = catalogId.trim();
        this.modelId = modelId.trim();
    }

    /**
     * Liest {@code <katalog>:<modellId>}; ein Präfix zählt nur, wenn es einer der bekannten Kataloge ist. Sonst ist
     * der ganze Text die Modellkennung im Standardkatalog (so bleiben alte Werte wie {@code chat.model} gültig).
     *
     * @return {@code null} bei leerem Text
     */
    public static ModelReference parse(String text, Collection<String> knownCatalogs, String defaultCatalog) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        int colon = value.indexOf(':');
        if (colon > 0 && colon < value.length() - 1 && knownCatalogs != null
                && knownCatalogs.contains(value.substring(0, colon))) {
            return new ModelReference(value.substring(0, colon), value.substring(colon + 1));
        }
        return new ModelReference(defaultCatalog, value);
    }

    public String catalogId() {
        return catalogId;
    }

    public String modelId() {
        return modelId;
    }

    /** {@code <katalog>:<modellId>}, wie es in der Konfiguration steht. */
    public String key() {
        return catalogId + ":" + modelId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelReference)) {
            return false;
        }
        ModelReference that = (ModelReference) other;
        return catalogId.equals(that.catalogId) && modelId.equals(that.modelId);
    }

    @Override
    public int hashCode() {
        return 31 * catalogId.hashCode() + modelId.hashCode();
    }

    @Override
    public String toString() {
        return key();
    }
}
