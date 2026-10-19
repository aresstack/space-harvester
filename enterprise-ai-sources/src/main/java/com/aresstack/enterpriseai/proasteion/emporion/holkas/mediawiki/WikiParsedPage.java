package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Ergebnis von {@code action=parse} (paketintern). */
final class WikiParsedPage {

    private final String title;
    private final long pageId;
    private final long revisionId;
    private final String html;

    WikiParsedPage(String title, long pageId, long revisionId, String html) {
        this.title = title;
        this.pageId = pageId;
        this.revisionId = revisionId;
        this.html = html;
    }

    String title() {
        return title;
    }

    long pageId() {
        return pageId;
    }

    long revisionId() {
        return revisionId;
    }

    String html() {
        return html;
    }
}
