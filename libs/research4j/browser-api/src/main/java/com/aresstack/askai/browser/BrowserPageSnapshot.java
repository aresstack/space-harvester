package com.aresstack.askai.browser;

/** A cleaned, size-bounded snapshot of the current page: url, title and readable text — never raw HTML. */
public final class BrowserPageSnapshot {

    private final String url;
    private final String title;
    private final String text;
    private final boolean truncated;

    public BrowserPageSnapshot(String url, String title, String text, boolean truncated) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.truncated = truncated;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public boolean isTruncated() { return truncated; }
}
