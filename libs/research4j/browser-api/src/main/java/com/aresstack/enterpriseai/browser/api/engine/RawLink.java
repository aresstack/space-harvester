package com.aresstack.enterpriseai.browser.api.engine;

/**
 * One anchor of a rendered page as the engine read it: visible text, resolved href, title, aria-label and the landmark
 * region around it (see {@code BrowserLink#region}).
 */
public final class RawLink {

    private final String text;
    private final String href;
    private final String title;
    private final String ariaLabel;
    private final String region;

    public RawLink(String text, String href, String title, String ariaLabel) {
        this(text, href, title, ariaLabel, "");
    }

    public RawLink(String text, String href, String title, String ariaLabel, String region) {
        this.text = text == null ? "" : text;
        this.href = href == null ? "" : href;
        this.title = title == null ? "" : title;
        this.ariaLabel = ariaLabel == null ? "" : ariaLabel;
        this.region = region == null ? "" : region;
    }

    public String text() {
        return text;
    }

    public String href() {
        return href;
    }

    public String title() {
        return title;
    }

    public String ariaLabel() {
        return ariaLabel;
    }

    public String region() {
        return region;
    }
}
