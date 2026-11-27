package com.aresstack.enterpriseai.browser.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The controls of one page snapshot of a tab. The ids stay valid while the document lives and the element is still on
 * the page (a single page app may replace it); any load of a new document makes them invalid.
 */
public final class BrowserControls {

    /** Upper bound for one snapshot, whatever the caller asks for. */
    public static final int MAX_CONTROLS = 150;

    private final String tabId;
    private final String pageUrl;
    private final String pageTitle;
    private final List<BrowserControl> controls;
    private final int total;

    public BrowserControls(String tabId, String pageUrl, String pageTitle, List<BrowserControl> controls, int total) {
        this.tabId = tabId == null ? "" : tabId;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.controls = controls == null ? Collections.<BrowserControl>emptyList()
                : Collections.unmodifiableList(new ArrayList<BrowserControl>(controls));
        this.total = Math.max(total, this.controls.size());
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

    /** Search fields first, then the rest in page order; at most the requested maximum. */
    public List<BrowserControl> controls() {
        return controls;
    }

    /** Controls on the page after filtering, possibly more than {@link #controls()}. */
    public int total() {
        return total;
    }
}
