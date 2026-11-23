package com.aresstack.enterpriseai.browser.api.engine;

/** A top-level tab as the engine knows it. */
public final class RawTab {

    private final String contextId;
    private final String url;
    private final String title;

    public RawTab(String contextId, String url, String title) {
        if (contextId == null || contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be empty");
        }
        this.contextId = contextId;
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
    }

    public String contextId() {
        return contextId;
    }

    public String url() {
        return url;
    }

    public String title() {
        return title;
    }
}
