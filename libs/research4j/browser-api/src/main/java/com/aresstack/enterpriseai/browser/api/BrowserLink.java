package com.aresstack.enterpriseai.browser.api;

/**
 * A link of one page snapshot: an id valid only for that snapshot, the visible text, the absolute address and the
 * optional title / aria-label of the anchor, and the page region it sits in.
 */
public final class BrowserLink {

    private final String id;
    private final String text;
    private final String url;
    private final String label;
    private final String region;

    public BrowserLink(String id, String text, String url, String label) {
        this(id, text, url, label, "");
    }

    public BrowserLink(String id, String text, String url, String label, String region) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) {
            throw new IllegalArgumentException("id and url must not be empty");
        }
        this.id = id;
        this.text = text == null ? "" : text;
        this.url = url;
        this.label = label == null ? "" : label;
        this.region = region == null ? "" : region;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public String url() {
        return url;
    }

    /** Title or aria-label of the anchor, empty when it has none or it repeats the text. */
    public String label() {
        return label;
    }

    /**
     * Where the anchor sits: {@code nav}, {@code header}, {@code footer}, {@code aside} or {@code main} (the nearest
     * landmark around it), empty when the page marks no landmarks.
     */
    public String region() {
        return region;
    }
}
