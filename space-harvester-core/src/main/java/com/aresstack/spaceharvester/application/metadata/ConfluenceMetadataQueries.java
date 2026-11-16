package com.aresstack.spaceharvester.application.metadata;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * Die dokumentierten REST-Abfragen je Seite für den Export „Confluence-Metadaten“ (Confluence Data Center, mit
 * den Rechten der Quelle): Historie samt allen Bearbeitern, Versionsliste, Kommentare (auch Inline-Kommentare
 * mit Erledigt-Status), Content Properties und Restriktionen. Labels, Erstellung, letzte Änderung und letzter
 * Bearbeiter stehen schon im Snapshot und werden nicht erneut geholt.
 *
 * <p>Die Versionsliste liegt unter {@code /rest/experimental/…}; Atlassian kennzeichnet sie als nicht unterstützt,
 * eine Instanz ohne sie liefert {@code NOT_FOUND} für diesen Abschnitt, die übrigen bleiben.
 */
public final class ConfluenceMetadataQueries {

    /** Historie: Erstellung, letzte Änderung, Vorgängerversion und alle Bearbeiter (Benutzer, kein Inhalt). */
    public static final String HISTORY = "/rest/api/content/{id}/history"
            + "?expand=lastUpdated,previousVersion,contributors.publishers.users";
    /** Alle Versionen mit Nummer, Zeit, Autor und Änderungskommentar; experimentell. */
    public static final String VERSIONS = "/rest/experimental/content/{id}/version";
    /** Seiten- und Inline-Kommentare aller Ebenen mit Version, Autor und Erledigt-Status. */
    public static final String COMMENTS = "/rest/api/content/{id}/child/comment?depth=all"
            + "&expand=version,history.lastUpdated,extensions.resolution,extensions.inlineProperties";
    /** Content Properties der Seite (Apps legen hier Werte ab). */
    public static final String PROPERTIES = "/rest/api/content/{id}/property";
    /** Lese- und Schreibrestriktionen mit Benutzern und Gruppen. */
    public static final String RESTRICTIONS = "/rest/api/content/{id}/restriction/byOperation"
            + "?expand=read.restrictions.user,read.restrictions.group,update.restrictions.user,"
            + "update.restrictions.group";

    /** Abschnittsname → Pfadmuster, in der Reihenfolge der Datei. */
    public static final Map<String, String> ENDPOINTS = endpoints();

    private ConfluenceMetadataQueries() {
    }

    private static Map<String, String> endpoints() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("history", HISTORY);
        map.put("versions", VERSIONS);
        map.put("comments", COMMENTS);
        map.put("properties", PROPERTIES);
        map.put("restrictions", RESTRICTIONS);
        return java.util.Collections.unmodifiableMap(map);
    }

    static String path(String template, String pageId) {
        return template.replace("{id}", pageId);
    }

    /** Alle Abschnitte einer Seite; Listen werden vollständig geblättert. */
    static Map<String, RestJson.Result> fetch(HarvestSource source, String pageId) {
        Map<String, RestJson.Result> sections = new LinkedHashMap<>();
        sections.put("history", RestJson.get(source, path(HISTORY, pageId)));
        sections.put("versions", RestJson.list(source, path(VERSIONS, pageId)));
        sections.put("comments", RestJson.list(source, path(COMMENTS, pageId)));
        sections.put("properties", RestJson.list(source, path(PROPERTIES, pageId)));
        sections.put("restrictions", RestJson.get(source, path(RESTRICTIONS, pageId)));
        return sections;
    }

    /** Der Eintrag einer Seite in der Datei. */
    static ObjectNode entry(String pageId, Map<String, RestJson.Result> sections) {
        ObjectNode entry = RestJson.JSON.createObjectNode();
        entry.put("pageId", pageId);
        sections.forEach((name, result) -> entry.set(name, RestJson.section(result)));
        return entry;
    }
}
