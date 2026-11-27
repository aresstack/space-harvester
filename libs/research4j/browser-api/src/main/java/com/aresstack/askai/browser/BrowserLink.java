package com.aresstack.askai.browser;

/** A link on the current page with a stable, session-scoped id the agent can follow. */
public final class BrowserLink {

    private final String id;
    private final String text;
    private final String url;

    public BrowserLink(String id, String text, String url) {
        this.id = id;
        this.text = text == null ? "" : text;
        this.url = url == null ? "" : url;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public String getUrl() { return url; }
}
