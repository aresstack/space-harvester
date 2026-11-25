package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Describe the wiki using its own siteinfo response. */
public final class MediaWikiSiteInfo {
    private final String mainPage;
    private final String siteName;
    private final java.util.List<Integer> contentNamespaces;

    MediaWikiSiteInfo(String mainPage, String siteName, java.util.List<Integer> contentNamespaces) {
        this.mainPage = mainPage;
        this.siteName = siteName;
        this.contentNamespaces = java.util.Collections.unmodifiableList(new java.util.ArrayList<Integer>(contentNamespaces));
    }

    public String mainPage() {
        return mainPage;
    }

    public java.util.List<Integer> contentNamespaces() {
        return contentNamespaces;
    }

    public String siteName() {
        return siteName;
    }
}
