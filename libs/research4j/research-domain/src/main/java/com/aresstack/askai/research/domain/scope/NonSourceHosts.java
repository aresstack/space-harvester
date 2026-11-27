package com.aresstack.askai.research.domain.scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pages that are never a research source, whatever the query: shop and marketplace listings (a book sold on
 * three sites is one book and zero information) and the search-result pages of other websites (a hit list is a
 * list of promises, not a source). The host entries ride the typed blacklist of the search run as a deterministic
 * host filter ({@link TypedBlacklist#isHostEntry}); the search-result-page rule is a URL shape check.
 * <p>
 * The live run of 2026-11-09 accepted amazon.de, thalia.de and beck-shop.de listings of one handbook as the
 * three "sources" that covered the root node, and two DGUV search-result pages as sources of their own.
 */
public final class NonSourceHosts {

    /**
     * The hosts the search run skips when the setting is on (subdomains included), followed by the exemptions
     * ({@code !host}, see {@link TypedBlacklist#isExemptEntry}): the technical sites of the same companies are
     * sources, their shops are not.
     */
    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(
            // marketplaces
            "amazon.de", "amazon.com", "amazon.co.uk", "amazon.at", "amazon.fr", "amazon.it", "amazon.es",
            "ebay.de", "ebay.com", "ebay.at", "ebay.ch", "ebay-kleinanzeigen.de", "kleinanzeigen.de", "etsy.com",
            "aliexpress.com", "alibaba.com", "temu.com", "wish.com", "otto.de", "zalando.de", "idealo.de",
            "billiger.de", "geizhals.de", "preisvergleich.de", "check24.de",
            // book shops and used-book marketplaces
            "thalia.de", "thalia.at", "hugendubel.de", "buecher.de", "beck-shop.de", "booklooker.de", "zvab.com",
            "abebooks.de", "abebooks.com", "medimops.de", "rebuy.de", "momox.de", "weltbild.de", "weltbild.at",
            "orellfuessli.ch", "osiander.de", "bol.de", "bol.com", "lehmanns.de", "genialokal.de", "buchhandel.de",
            "buch24.de", "buchkatalog.de", "dussmann-das-kulturkaufhaus.de", "mayersche.de", "ebook.de",
            "kobo.com", "barnesandnoble.com", "bookdepository.com", "booktopia.com.au", "waterstones.com",
            "audible.de", "audible.com", "lovelybooks.de", "goodreads.com",
            // document re-upload sites (copies without licence or provenance)
            "scribd.com", "de.scribd.com", "dokumen.pub", "pdfcoffee.com", "yumpu.com", "studocu.com",
            "studylib.net", "docplayer.org", "docplayer.net", "dokumente.pub",
            // exemptions: documentation, developer and engineering sites of the companies above
            "!aws.amazon.com", "!developer.amazon.com", "!developer.ebay.com", "!engineering.zalando.com",
            "!research.zalando.com", "!developer.alibaba.com", "!developer.etsy.com"));

    private NonSourceHosts() {
    }

    /** A search-result page or a shop catalogue page: never a source, whatever the query. */
    public static boolean isNonSourcePage(String url) {
        return isSearchResultPage(url) || isShopCataloguePage(url);
    }

    /**
     * Whether the URL is a shop's catalogue page by its shape, on any host: a {@code shop}/{@code store}/{@code
     * onlineshop} host label ({@code shop.bueroheuser.de}), or a Shopify-style {@code /products/},
     * {@code /collections/}, {@code /product/}, {@code /cart}, {@code /checkout} path ({@code stahlgear.de/products/…}).
     * A shop's editorial pages ({@code /pages/…}, {@code /blogs/…}) are not caught: a manufacturer's guide is a
     * source even when the same host sells the product.
     */
    public static boolean isShopCataloguePage(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        String rest = value.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        String pathAndQuery = slash < 0 ? "" : rest.substring(slash);
        int cut = pathAndQuery.indexOf('?');
        String path = cut < 0 ? pathAndQuery : pathAndQuery.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        String[] steps = path.split("/");
        // editorial sections of a shop (Shopify's /pages/… and /blogs/…) stay eligible, whatever the host
        if (steps.length >= 2 && (steps[1].equals("pages") || steps[1].equals("blogs"))) {
            return false;
        }
        String[] labels = host.split("\\.");
        if (labels.length >= 3) {
            String first = labels[0];
            if (first.equals("shop") || first.equals("store") || first.equals("onlineshop") || first.equals("eshop")
                    || first.equals("webshop")) {
                return true;
            }
        }
        for (String step : steps) {
            if (step.equals("products") || step.equals("product") || step.equals("collections")
                    || step.equals("produkt") || step.equals("produkte") || step.equals("cart")
                    || step.equals("checkout") || step.equals("warenkorb")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the URL is a search-result page of some website: a {@code q}, {@code query}, {@code search},
     * {@code suche} or {@code keyword} parameter with a value, or a {@code /search}, {@code /suche}, {@code /find}
     * or {@code /results} step among the last two path steps together with a query string (a hit list without a
     * query is a search form, a {@code …/Array/find} reference page without one is documentation). A search
     * engine's own result page is handled by the search provider, never here.
     */
    public static boolean isSearchResultPage(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        String pathAndQuery = slash < 0 ? "" : value.substring(slash);
        int hash = pathAndQuery.indexOf('#');
        if (hash >= 0) {
            pathAndQuery = pathAndQuery.substring(0, hash);
        }
        int question = pathAndQuery.indexOf('?');
        String path = question < 0 ? pathAndQuery : pathAndQuery.substring(0, question);
        String query = question < 0 ? "" : pathAndQuery.substring(question + 1);
        if (query.isEmpty()) {
            return false;
        }
        String[] steps = path.split("/");
        for (int i = Math.max(0, steps.length - 2); i < steps.length; i++) {
            String step = steps[i];
            if (step.equals("search") || step.equals("suche") || step.equals("find") || step.equals("results")
                    || step.equals("suchergebnisse") || step.equals("searchresults") || step.equals("search.php")
                    || step.equals("search.html") || step.equals("suche.html") || step.equals("search.jsp")) {
                return true;
            }
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            if (val.isEmpty()) {
                continue;
            }
            if (name.equals("q") || name.equals("query") || name.equals("search") || name.equals("suche")
                    || name.equals("keyword") || name.equals("keywords") || name.equals("searchterm")
                    || name.equals("search_query") || name.equals("suchbegriff") || name.equals("s")) {
                return true;
            }
        }
        return false;
    }
}
