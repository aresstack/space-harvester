package com.aresstack.spaceharvester.mainframe.diagram;

import java.util.Locale;

/**
 * Ein z/OS-Systemprogramm (IDCAMS, SORT, …) wie {@code SystemFunctionEntry} in MainframeMate: im Diagramm blau
 * hervorgehoben, verlinkt auf seinen Wikipedia-Artikel.
 *
 * @param name        Programmname wie in {@code EXEC PGM=} (immer Großbuchstaben)
 * @param wikiTitleDe Titel des deutschen Wikipedia-Artikels (wie in der URL), leer = Name als Suchbegriff
 * @param wikiTitleEn Titel des englischen Wikipedia-Artikels, leer = Name als Suchbegriff
 * @param description Beschreibung für den Benutzer
 */
public record SystemFunction(String name, String wikiTitleDe, String wikiTitleEn, String description) {

    public SystemFunction {
        name = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        wikiTitleDe = wikiTitleDe == null ? "" : wikiTitleDe.trim();
        wikiTitleEn = wikiTitleEn == null ? "" : wikiTitleEn.trim();
        description = description == null ? "" : description.trim();
    }

    /** Der Wikipedia-Artikel wie in MainframeMate: deutsch, sonst englisch, sonst Suche nach dem Namen. */
    public String wikipediaUrl() {
        if (!wikiTitleDe.isEmpty()) {
            return "https://de.wikipedia.org/wiki/" + title(wikiTitleDe);
        }
        if (!wikiTitleEn.isEmpty()) {
            return "https://en.wikipedia.org/wiki/" + title(wikiTitleEn);
        }
        return "https://de.wikipedia.org/w/index.php?search="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Artikelname für die URL wie MainframeMate: kodiert, Leerzeichen als Unterstrich. */
    private static String title(String title) {
        return java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8).replace("+", "_");
    }
}
