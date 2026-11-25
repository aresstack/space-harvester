package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Describe the wiki using its own siteinfo response. */
public final class MediaWikiSiteInfo {
    private final String mainPage;
    private final String siteName;

    MediaWikiSiteInfo(String mainPage, String siteName) {
        this.mainPage = mainPage;
        this.siteName = siteName;
    }

    public String mainPage() {
        return mainPage;
    }

    public String siteName() {
        return siteName;
    }
}
