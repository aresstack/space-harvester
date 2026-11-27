package com.aresstack.askai.browser.search;

/** A secondary (site) link of one result block — metadata of the primary hit, never a candidate. */
public final class SearchResultSiteLink {

    public final String url;
    public final String text;

    public SearchResultSiteLink(String url, String text) {
        this.url = url == null ? "" : url;
        this.text = text == null ? "" : text;
    }
}
