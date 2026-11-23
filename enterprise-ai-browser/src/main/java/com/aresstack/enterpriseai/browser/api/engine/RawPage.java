package com.aresstack.enterpriseai.browser.api.engine;

/** Url, title and visible text of a tab as the engine read it. */
public final class RawPage {

    private final String url;
    private final String title;
    private final String text;

    public RawPage(String url, String title, String text) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
    }

    public String url() {
        return url;
    }

    public String title() {
        return title;
    }

    public String text() {
        return text;
    }
}
