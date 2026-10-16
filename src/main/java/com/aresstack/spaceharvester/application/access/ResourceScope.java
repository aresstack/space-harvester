package com.aresstack.spaceharvester.application.access;

import java.util.Objects;

/**
 * Worauf sich eine Prüfung bezieht: eine Quelle, ein Space oder eine Seite.
 *
 * @param sourceId Quellen-ID aus der Konfiguration
 * @param spaceKey Space der Ressource oder {@code null}; bei einer Seite der Space, in dem sie zuletzt gesehen wurde
 * @param pageId   Seiten-ID oder {@code null} für eine Quelle bzw. einen Space
 */
public record ResourceScope(String sourceId, String spaceKey, String pageId) {

    public ResourceScope {
        Objects.requireNonNull(sourceId, "sourceId");
    }

    /** Die Quelle selbst, z. B. für ihren Eintrag in der Quellenliste oder die Liste ihrer Spaces. */
    public static ResourceScope source(String sourceId) {
        return new ResourceScope(sourceId, null, null);
    }

    /** Ein Space und alles darin. */
    public static ResourceScope space(String sourceId, String spaceKey) {
        return new ResourceScope(sourceId, Objects.requireNonNull(spaceKey, "spaceKey"), null);
    }

    /**
     * Eine Seite.
     *
     * @param spaceKey Space der Seite, wenn bekannt (aus Cache oder Metadaten), sonst {@code null}
     */
    public static ResourceScope page(String sourceId, String spaceKey, String pageId) {
        return new ResourceScope(sourceId, spaceKey, Objects.requireNonNull(pageId, "pageId"));
    }

    public boolean isSource() {
        return spaceKey == null && pageId == null;
    }
}
