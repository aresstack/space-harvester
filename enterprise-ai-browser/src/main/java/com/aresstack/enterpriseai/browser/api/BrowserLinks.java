package com.aresstack.enterpriseai.browser.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The links of one page snapshot of a tab. The link ids belong to this snapshot only: any navigation of the tab
 * (navigate, follow, back, a click by the user) makes them invalid.
 */
public final class BrowserLinks {

    /** Upper bound for one snapshot, whatever the caller asks for. */
    public static final int MAX_LINKS = 300;

    private final String tabId;
    private final String pageUrl;
    private final String pageTitle;
    private final List<BrowserLink> links;
    private final int total;

    public BrowserLinks(String tabId, String pageUrl, String pageTitle, List<BrowserLink> links, int total) {
        this.tabId = tabId == null ? "" : tabId;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.links = links == null ? Collections.<BrowserLink>emptyList()
                : Collections.unmodifiableList(new ArrayList<BrowserLink>(links));
        this.total = Math.max(total, this.links.size());
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

    /** The links returned, at most the requested maximum, in page order. */
    public List<BrowserLink> links() {
        return links;
    }

    /** Usable links on the page (after filtering and de-duplication), possibly more than {@link #links()}. */
    public int total() {
        return total;
    }
}
