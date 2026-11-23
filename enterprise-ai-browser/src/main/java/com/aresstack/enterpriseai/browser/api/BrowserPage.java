package com.aresstack.enterpriseai.browser.api;

/** The readable state of a tab: chat-local tab id, url, title and visible text. */
public final class BrowserPage {

    private final String tabId;
    private final String url;
    private final String title;
    private final String text;

    public BrowserPage(String tabId, String url, String title, String text) {
        this.tabId = tabId == null ? "" : tabId;
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
    }

    public String tabId() {
        return tabId;
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
