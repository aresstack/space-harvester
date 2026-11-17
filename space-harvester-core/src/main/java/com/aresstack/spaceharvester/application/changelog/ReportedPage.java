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
 *                       und noch da ist und der Bericht mit Unterschieden angefordert wurde; sonst {@code null}
 * @param editors        die bekannten Bearbeiter der Fassungen im Update, ohne Doppelte, in der Reihenfolge der Zeilen
 *                       (ADR 0031). Personenbezug: eigenes Feld, damit es nicht ungefiltert in KI-Material gerät; an
 *                       die KI geht höchstens ihre Zahl
 * @param labels         die Labels der Seite nach der letzten Änderung, soweit die Quelle sie liefert
 */
public record ReportedPage(String sourceId, String spaceKey, String pageId, ChangeKind kind, Set<ChangeKind> kinds,
                           String title, List<String> path, URI url, String oldVersion, String newVersion,
                           String oldTitle, String newTitle, String oldParentId, String newParentId,
                           String oldSpaceKey, String newSpaceKey, boolean contentPending, Instant at,
                           ContentDiff diff, List<String> editors, List<String> labels) {

    /** Ohne Bearbeiter und Labels. */
    public ReportedPage(String sourceId, String spaceKey, String pageId, ChangeKind kind, Set<ChangeKind> kinds,
                        String title, List<String> path, URI url, String oldVersion, String newVersion,
                        String oldTitle, String newTitle, String oldParentId, String newParentId,
                        String oldSpaceKey, String newSpaceKey, boolean contentPending, Instant at, ContentDiff diff) {
        this(sourceId, spaceKey, pageId, kind, kinds, title, path, url, oldVersion, newVersion, oldTitle, newTitle,
                oldParentId, newParentId, oldSpaceKey, newSpaceKey, contentPending, at, diff, List.of(), List.of());
    }

    public ReportedPage {
        kinds = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(kinds));
        path = List.copyOf(path);
        editors = editors == null ? List.of() : List.copyOf(editors);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** Dieselbe Seite mit Pfad und Verweis. */
    ReportedPage withDirectory(List<String> path, URI url) {
        return new ReportedPage(sourceId, spaceKey, pageId, kind, kinds, title, path, url, oldVersion, newVersion,
                oldTitle, newTitle, oldParentId, newParentId, oldSpaceKey, newSpaceKey, contentPending, at, diff,
                editors, labels);
    }

    /** Dieselbe Seite mit dem Unterschied ihres Inhalts. */
    ReportedPage withDiff(ContentDiff diff) {
        return new ReportedPage(sourceId, spaceKey, pageId, kind, kinds, title, path, url, oldVersion, newVersion,
                oldTitle, newTitle, oldParentId, newParentId, oldSpaceKey, newSpaceKey, contentPending, at, diff,
                editors, labels);
    }
}
