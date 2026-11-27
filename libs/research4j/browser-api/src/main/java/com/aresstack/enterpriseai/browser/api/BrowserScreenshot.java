package com.aresstack.enterpriseai.browser.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A picture of what a tab shows right now (the visible part of the page, or the region around one control), compressed
 * for a model to at most {@link #MAX_BYTES}: the image bytes with their media type, and the controls of the tab's
 * control snapshot that are visible in it. When marks were requested, each of those controls carries its id as a
 * small label in the picture, so a model can answer with an id instead of guessing coordinates. Password fields are
 * masked before the picture is taken.
 */
public final class BrowserScreenshot {

    /** Upper bound for the bytes of one picture. */
    public static final int MAX_BYTES = 1024 * 1024;

    private final String tabId;
    private final String pageUrl;
    private final String pageTitle;
    private final String mediaType;
    private final byte[] image;
    private final boolean marked;
    private final List<BrowserControl> visible;

    public BrowserScreenshot(String tabId, String pageUrl, String pageTitle, String mediaType, byte[] image,
                             boolean marked, List<BrowserControl> visible) {
        if (mediaType == null || !mediaType.startsWith("image/")) {
            throw new IllegalArgumentException("mediaType must be an image type: " + mediaType);
        }
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image must not be empty");
        }
        this.tabId = tabId == null ? "" : tabId;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.mediaType = mediaType;
        this.image = image.clone();
        this.marked = marked;
        this.visible = Collections.unmodifiableList(new ArrayList<BrowserControl>(
                visible == null ? Collections.<BrowserControl>emptyList() : visible));
    }

    public String tabId() {
        return tabId;
    }

    public String pageUrl() {
        return pageUrl;
    }

    public String pageTitle() {
        return pageTitle;
    }

    /** {@code image/png} or {@code image/jpeg}. */
    public String mediaType() {
        return mediaType;
    }

    public byte[] image() {
        return image.clone();
    }

    /** Whether the ids of {@link #visible()} are drawn into the picture. */
    public boolean marked() {
        return marked;
    }

    /** The controls of the snapshot that are visible in the picture, in snapshot order. */
    public List<BrowserControl> visible() {
        return visible;
    }
}
