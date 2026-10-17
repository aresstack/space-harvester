package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Verweise zwischen Seiten desselben Exports: Confluence verlinkt über Space und Titel, der Export über die ID. */
final class PageLinks {

    private PageLinks() {
    }

    /**
     * @param spaceKey Space des Exports, für Verweise ohne Space-Angabe
     * @return Space (oder {@code null}) und Titel → {@code #page-<id>}, oder {@code null} für Seiten außerhalb
     */
    static BiFunction<String, String, String> of(List<PageHeader> headers, String spaceKey) {
        Map<String, String> targets = new HashMap<>();
        for (PageHeader header : headers) {
            String space = header.metadata().partitionKey() == null ? spaceKey : header.metadata().partitionKey();
            targets.putIfAbsent(key(space, header.metadata().title()), "#" + anchor(header.key().pageId()));
        }
        return (space, title) -> title == null ? null : targets.get(key(space == null ? spaceKey : space, title));
    }

    /** Sprungmarke einer Seite; nur Zeichen, die in HTML-IDs und Markdown-Links unverändert bleiben. */
    static String anchor(String pageId) {
        return "page-" + pageId.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String key(String space, String title) {
        return space + "\u0000" + title;
    }
}
