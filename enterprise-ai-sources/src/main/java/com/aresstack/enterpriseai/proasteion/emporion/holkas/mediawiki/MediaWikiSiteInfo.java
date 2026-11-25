package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/** Describe the wiki using its own siteinfo response. */
public final class MediaWikiSiteInfo {
    private final String mainPage;
    private final String siteName;
    private final java.util.List<Integer> contentNamespaces;
    private final java.util.Map<Integer, String> subpageNamespaces;
    private final String categoryPrefix;

    MediaWikiSiteInfo(String mainPage, String siteName, java.util.List<Integer> contentNamespaces,
                      java.util.Map<Integer, String> subpageNamespaces, String categoryPrefix) {
        this.mainPage = mainPage;
        this.siteName = siteName;
        this.contentNamespaces = java.util.Collections.unmodifiableList(new java.util.ArrayList<Integer>(contentNamespaces));
        this.subpageNamespaces = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<Integer, String>(subpageNamespaces));
        this.categoryPrefix = categoryPrefix;
    }

    public String mainPage() {
        return mainPage;
    }

    public java.util.List<Integer> contentNamespaces() {
        return contentNamespaces;
    }

    String categoryPrefix() {
        return categoryPrefix;
    }

    Integer subpageNamespace(String title) {
        int colon = title.indexOf(':');
        for (java.util.Map.Entry<Integer, String> namespace : subpageNamespaces.entrySet()) {
            if (namespace.getKey() != 0 && colon > 0
                    && namespace.getValue().equalsIgnoreCase(title.substring(0, colon))) {
                return namespace.getKey();
            }
        }
        return colon < 0 && subpageNamespaces.containsKey(0) ? 0 : null;
    }

    public String siteName() {
        return siteName;
    }
}
