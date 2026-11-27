package com.aresstack.askai.browser;

/** One structured search hit. */
public final class WebSearchItem {

    private final String id;
    private final String title;
    private final String url;
    private final String snippet;

    public WebSearchItem(String id, String title, String url, String snippet) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.snippet = snippet == null ? "" : snippet;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public String getSnippet() { return snippet; }
}
