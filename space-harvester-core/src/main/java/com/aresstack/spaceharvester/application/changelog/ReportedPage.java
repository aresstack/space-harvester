package com.aresstack.spaceharvester.application.changelog;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Eine Seite im Änderungsbericht (ADR 0030, Abschnitt 4): mehrere Änderungen derselben Seite in einem Update zu einer
 * zusammengefasst, erster alter Stand und letzter neuer Stand.
 *
 * @param kind           die Art, unter der die Seite im Bericht steht
 * @param kinds          alle Arten ihrer Änderungen im Update, in der Reihenfolge ihres Entstehens
 * @param title          jüngster bekannter Titel
 * @param path           Titel der Vorfahren, die der Aufrufer sehen darf, von oben
 * @param url            Verweis auf die Seite in der Quelle, {@code null} wenn unbekannt
 * @param contentPending neue Version bekannt, Inhalt noch nicht geladen
 * @param at             Zeitpunkt der letzten Änderung
 */
public record ReportedPage(String sourceId, String spaceKey, String pageId, ChangeKind kind, Set<ChangeKind> kinds,
                           String title, List<String> path, URI url, String oldVersion, String newVersion,
                           String oldTitle, String newTitle, String oldParentId, String newParentId,
                           String oldSpaceKey, String newSpaceKey, boolean contentPending, Instant at) {

    public ReportedPage {
        kinds = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(kinds));
        path = List.copyOf(path);
    }
}
