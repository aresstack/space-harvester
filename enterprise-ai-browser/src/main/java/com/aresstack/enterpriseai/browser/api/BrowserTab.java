package com.aresstack.enterpriseai.browser.api;

/** A tab as the model sees it: chat-local id, title, url and whether it is the current tab. */
public final class BrowserTab {

    private final String id;
    private final String title;
    private final String url;
    private final boolean current;

    public BrowserTab(String id, String title, String url, boolean current) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        this.id = id;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.current = current;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String url() {
        return url;
    }

    public boolean isCurrent() {
        return current;
    }
}
