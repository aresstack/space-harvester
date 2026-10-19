package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Treffer aus {@code list=search} (paketintern). */
final class WikiSearchHit {

    private final String title;
    private final long pageId;
    private final String snippet;
    private final String timestamp;

    WikiSearchHit(String title, long pageId, String snippet, String timestamp) {
        this.title = title;
        this.pageId = pageId;
        this.snippet = snippet;
        this.timestamp = timestamp;
    }

    String title() {
        return title;
    }

    long pageId() {
        return pageId;
    }

    /** Snippet als Klartext (Hervorhebungs-Markup entfernt). */
    String snippet() {
        return snippet;
    }

    String timestamp() {
        return timestamp;
    }
}
