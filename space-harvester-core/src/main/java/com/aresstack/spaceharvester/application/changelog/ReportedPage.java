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
 * @param diff           Unterschied des Inhalts, wenn die Seite unter ihren Arten {@link ChangeKind#CONTENT_CHANGED} hat
 *                       und noch da ist; sonst {@code null}
 */
public record ReportedPage(String sourceId, String spaceKey, String pageId, ChangeKind kind, Set<ChangeKind> kinds,
                           String title, List<String> path, URI url, String oldVersion, String newVersion,
                           String oldTitle, String newTitle, String oldParentId, String newParentId,
                           String oldSpaceKey, String newSpaceKey, boolean contentPending, Instant at,
                           ContentDiff diff) {

    public ReportedPage {
        kinds = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(kinds));
        path = List.copyOf(path);
    }

    /** Dieselbe Seite mit Pfad und Verweis. */
    ReportedPage withDirectory(List<String> path, URI url) {
        return new ReportedPage(sourceId, spaceKey, pageId, kind, kinds, title, path, url, oldVersion, newVersion,
                oldTitle, newTitle, oldParentId, newParentId, oldSpaceKey, newSpaceKey, contentPending, at, diff);
    }

    /** Dieselbe Seite mit dem Unterschied ihres Inhalts. */
    ReportedPage withDiff(ContentDiff diff) {
        return new ReportedPage(sourceId, spaceKey, pageId, kind, kinds, title, path, url, oldVersion, newVersion,
                oldTitle, newTitle, oldParentId, newParentId, oldSpaceKey, newSpaceKey, contentPending, at, diff);
    }
}
