package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Metadaten einer Seite aus {@code prop=info} (paketintern). */
final class WikiPageInfo {

    private final String title;
    private final long pageId;
    private final int namespace;
    private final long revisionId;
    private final String touched;
    private final String fullUrl;
    private final String contentModel;
    private final boolean missing;
    private final String requestedTitle;

    WikiPageInfo(String title, long pageId, int namespace, long revisionId, String touched, String fullUrl,
                 String contentModel, boolean missing, String requestedTitle) {
        this.title = title;
        this.pageId = pageId;
        this.namespace = namespace;
        this.revisionId = revisionId;
        this.touched = touched;
        this.fullUrl = fullUrl;
        this.contentModel = contentModel;
        this.missing = missing;
        this.requestedTitle = requestedTitle == null ? title : requestedTitle;
    }

    /** Der angefragte Titel vor Normalisierung und Weiterleitung. */
    String requestedTitle() {
        return requestedTitle;
    }

    String title() {
        return title;
    }

    long pageId() {
        return pageId;
    }

    int namespace() {
        return namespace;
    }

    long revisionId() {
        return revisionId;
    }

    /** ISO-8601-Zeitstempel der letzten Änderung ({@code touched}) oder {@code null}. */
    String touched() {
        return touched;
    }

    String fullUrl() {
        return fullUrl;
    }

    String contentModel() {
        return contentModel;
    }

    boolean missing() {
        return missing;
    }

    @Override
    public String toString() {
        return "WikiPageInfo{" + title + ", pageId=" + pageId + ", rev=" + revisionId
                + (missing ? ", missing" : "") + "}";
    }
}
