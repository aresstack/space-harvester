package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The mission's source preference in the ranking of a planned query: among the selected hits, a hit from a
 * preferred host (a publisher the mission names, or an authority host suffix such as {@code .bund.de}) moves up by
 * {@code shift} places, a page whose URL carries a vendor marker ({@code /blog}, {@code /produkt}, {@code /solution})
 * moves down by as many. Nothing is dropped: the relevance order stays the base, the preference only weighs it, and
 * the Weidezaun and the blacklist stay the only hard limits. A hit whose target is not resolved yet (a Google
 * {@code /goto} link) is judged by the domain the engine displayed, never by resolving it.
 */
public final class SourcePreferenceRanking {

    /** No preference: the order stays as it is. */
    public static final SourcePreferenceRanking NONE =
            new SourcePreferenceRanking(Collections.<String>emptyList(), Collections.<String>emptyList(), 0);

    private final List<String> preferredHosts;
    private final List<String> demotedMarkers;
    private final int shift;

    /**
     * @param preferredHosts hosts ({@code dguv.de}, matching its subdomains too) and host suffixes ({@code .bund.de})
     * @param demotedMarkers URL parts of vendor pages, matched case-insensitively in the URL
     * @param shift          the places a preferred hit moves up and a vendor page moves down; 0 = none
     */
    public SourcePreferenceRanking(List<String> preferredHosts, List<String> demotedMarkers, int shift) {
        this.preferredHosts = lower(preferredHosts);
        this.demotedMarkers = lower(demotedMarkers);
        this.shift = Math.max(0, shift);
    }

    public boolean isNone() {
        return shift == 0 || (preferredHosts.isEmpty() && demotedMarkers.isEmpty());
    }

    /** Whether a hit comes from a preferred host. */
    public boolean preferred(SearchResultCandidate candidate) {
        return preferredHost(hostOf(candidate));
    }

    /** Whether a hit is a vendor page (and not from a preferred host). */
    public boolean demoted(SearchResultCandidate candidate) {
        return !preferred(candidate) && vendorUrl(candidate.resolvedTargetUrl);
    }

    /**
     * The places a page at this URL moves: up ({@code -shift}) when it comes from a preferred host, down
     * ({@code +shift}) when it is a vendor page, 0 otherwise. The evidence pack of a section weighs its sources by
     * it, as a planned query weighs its hits.
     */
    public int shiftOf(String url) {
        if (isNone()) {
            return 0;
        }
        if (preferredHost(hostOf(url))) {
            return -shift;
        }
        return vendorUrl(url) ? shift : 0;
    }

    private boolean preferredHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (String p : preferredHosts) {
            if (p.startsWith(".") ? host.endsWith(p) || host.equals(p.substring(1))
                    : host.equals(p) || host.endsWith("." + p)) {
                return true;
            }
        }
        return false;
    }

    private boolean vendorUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return false;
        }
        for (String marker : demotedMarkers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The hits in weighed order: each hit's position (0-based) minus the shift when preferred, plus the shift when a
     * vendor page; ties keep the relevance order. The ranks are stamped anew (1-based).
     */
    public List<RerankedSearchResultCandidate> apply(List<RerankedSearchResultCandidate> ranked) {
        if (isNone() || ranked == null || ranked.size() < 2) {
            return ranked;
        }
        final List<RerankedSearchResultCandidate> base = new ArrayList<RerankedSearchResultCandidate>(ranked);
        final int[] weight = new int[base.size()];
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < base.size(); i++) {
            SearchResultCandidate c = base.get(i).candidate;
            weight[i] = i - (preferred(c) ? shift : 0) + (demoted(c) ? shift : 0);
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int byWeight = Integer.compare(weight[a], weight[b]);
                return byWeight != 0 ? byWeight : Integer.compare(a, b);
            }
        });
        List<RerankedSearchResultCandidate> out = new ArrayList<RerankedSearchResultCandidate>(base.size());
        for (int i = 0; i < order.size(); i++) {
            RerankedSearchResultCandidate c = base.get(order.get(i));
            out.add(new RerankedSearchResultCandidate(c.candidate, c.score, i + 1));
        }
        return out;
    }

    private static String hostOf(SearchResultCandidate candidate) {
        String host = hostOf(candidate.resolvedTargetUrl);
        if (host.isEmpty() && candidate.displayedDomain != null) {
            host = candidate.displayedDomain.trim();
            int slash = host.indexOf('/');
            host = slash >= 0 ? host.substring(0, slash) : host;
            host = host.toLowerCase(Locale.ROOT);
            host = host.startsWith("www.") ? host.substring(4) : host;
        }
        return host;
    }

    /** The lower-case host of a URL without "www.", or "" when the URL has none. */
    private static String hostOf(String url) {
        String trimmed = url == null ? "" : url.trim();
        String host = "";
        if (!trimmed.isEmpty()) {
            try {
                String h = new java.net.URI(trimmed).getHost();
                host = h == null ? "" : h;
            } catch (java.net.URISyntaxException malformed) {
                host = "";
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String v : values) {
                String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }
}
