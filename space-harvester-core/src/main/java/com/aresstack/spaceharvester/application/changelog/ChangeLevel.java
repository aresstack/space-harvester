package com.aresstack.spaceharvester.application.changelog;

import java.util.Objects;

/**
 * Die Ebene, auf der das Änderungsprotokoll Updates zeigt und zusammenfasst (ADR 0030/0031): die ganze Quelle, ein
 * Space oder der Teilbaum unter einem Knoten ohne eigenen Inhalt, etwa einem Ordner in SharePoint, BSCW oder lokalen
 * Dateien in jeder Tiefe (Angelo 26.11.2026). Sie reist als ein Schlüssel durch dieselben Aufrufe wie bisher der Space:
 * Für Quelle und Space ist der Schlüssel genau der Space (oder {@code null}), so bleiben abgelegte Zusammenfassungen
 * gültig; erst der Teilbaum hängt seine Wurzel an.
 *
 * @param spaceKey    der Space, {@code null} für die ganze Quelle
 * @param rootPageId  die Wurzel des Teilbaums, {@code null} ohne
 */
public record ChangeLevel(String spaceKey, String rootPageId) {

    /** Trennt Space und Wurzel im Schlüssel; kommt in Space-Schlüsseln und Seiten-IDs nicht vor. */
    static final char SEPARATOR = '\u001f';

    public ChangeLevel {
        spaceKey = spaceKey == null || spaceKey.isEmpty() ? null : spaceKey;
        rootPageId = rootPageId == null || rootPageId.isEmpty() ? null : rootPageId;
    }

    /** Der Schlüssel der Ebene: der Space bzw. {@code null}, für einen Teilbaum mit angehängter Wurzel. */
    public static String key(String spaceKey, String rootPageId) {
        return new ChangeLevel(spaceKey, rootPageId).key();
    }

    /** Die Ebene zu einem Schlüssel aus {@link #key}; ein bloßer Space ist eine Space-Ebene. */
    public static ChangeLevel of(String key) {
        if (key == null) {
            return new ChangeLevel(null, null);
        }
        int separator = key.indexOf(SEPARATOR);
        return separator < 0 ? new ChangeLevel(key, null)
                : new ChangeLevel(key.substring(0, separator), key.substring(separator + 1));
    }

    /** Der Schlüssel dieser Ebene. */
    public String key() {
        if (rootPageId == null) {
            return spaceKey;
        }
        return Objects.requireNonNullElse(spaceKey, "") + SEPARATOR + rootPageId;
    }

    /** Ob dies der Teilbaum unter einem Knoten ist. */
    public boolean subtree() {
        return rootPageId != null;
    }
}
