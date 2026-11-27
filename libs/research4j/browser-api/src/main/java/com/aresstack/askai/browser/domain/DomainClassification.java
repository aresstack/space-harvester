package com.aresstack.askai.browser.domain;

/**
 * The relation between two hosts, decided AFTER redirect resolution on the final target — never on a
 * search engine's wrapper URL. External domains are the strongest organic-result signal on a SERP, but a
 * link inside a confirmed result block may still be a candidate when it points to another subdomain of
 * the same registrable domain (search.yahoo.com → finance.yahoo.com).
 */
public enum DomainClassification {

    SAME_HOST,
    /** One host is a proper subdomain of the other ("news.bbc.co.uk" vs "bbc.co.uk"). */
    SUBDOMAIN,
    /** Different hosts under the same registrable domain ("search.yahoo.com" vs "finance.yahoo.com"). */
    SAME_REGISTRABLE_DOMAIN,
    EXTERNAL_DOMAIN;

    public static DomainClassification classify(DomainIdentity a, DomainIdentity b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return EXTERNAL_DOMAIN;
        }
        if (a.getHost().equals(b.getHost())) {
            return SAME_HOST;
        }
        if (!a.sameFamily(b)) {
            return EXTERNAL_DOMAIN;
        }
        if (a.getHost().endsWith("." + b.getHost()) || b.getHost().endsWith("." + a.getHost())) {
            return SUBDOMAIN;
        }
        return SAME_REGISTRABLE_DOMAIN;
    }
}
