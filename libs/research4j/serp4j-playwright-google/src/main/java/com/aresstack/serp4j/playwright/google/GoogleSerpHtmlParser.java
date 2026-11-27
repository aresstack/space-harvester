package com.aresstack.serp4j.playwright.google;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts the classic Google web result page (desktop, 2026 layout) into a {@link GoogleSerpPage}. Thread-safe.
 *
 * <p>Anchors: a result is an {@code a[href]} with a direct {@code h3} child inside {@code #rso} (falls back to
 * {@code #search}, then the whole document). The block around it ({@code div.MjjYud}) provides the breadcrumb
 * ({@code cite}), the site name ({@code span.VuuXrf}), the snippet ({@code div.VwiC3b}, video: {@code div.ITZIwc}),
 * the date ({@code span.YrbPuc}) and sitelinks ({@code h3 > a}). Related searches come from {@code #bres}. Class
 * names change; the fixture tests pin the current layout and the provider warns when nothing is recognised.</p>
 *
 * <p>URLs: relative URLs are resolved against {@code https://www.google.com}; classic {@code /url?q=…} wrappers are
 * unwrapped; {@code /goto?url=…} stays as is; the host follows it when it opens the hit.</p>
 */
public final class GoogleSerpHtmlParser {

    static final String GOOGLE_ORIGIN = "https://www.google.com";

    /**
     * @param html page HTML; {@code null} or empty yields an unrecognised, empty page
     * @return the parse result, never {@code null}
     */
    public GoogleSerpPage parse(String html) {
        if (html == null || html.trim().isEmpty()) {
            return new GoogleSerpPage(new ArrayList<GoogleSerpPage.Organic>(), new ArrayList<GoogleSerpPage.Link>(),
                    false, false, 0, GoogleObstacle.NONE);
        }
        Document doc = Jsoup.parse(html, GOOGLE_ORIGIN + "/");
        Element root = doc.getElementById("rso");
        if (root == null) {
            root = doc.getElementById("search");
        }
        boolean recognized = root != null;
        if (root == null) {
            root = doc.body();
        }
        List<GoogleSerpPage.Organic> organic = new ArrayList<GoogleSerpPage.Organic>();
        Set<Element> usedBlocks = new LinkedHashSet<Element>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Element anchor : root.select("a[href]:has(> h3)")) {
            if (isInsideAds(anchor) || hasAncestorTag(anchor, "table")) {
                continue; // ads, or sitelinks of the previous result
            }
            String url = absolute(anchor.attr("href"));
            String title = text(anchor.selectFirst("> h3"));
            if (url == null || title == null || !seen.add(url)) {
                continue;
            }
            Element block = block(anchor, root);
            usedBlocks.add(block);
            organic.add(organic(organic.size() + 1, title, url, anchor, block));
        }
        int unrecognized = 0;
        for (Element block : root.select("div.MjjYud")) {
            if (usedBlocks.contains(block) || block.hasClass("vRlJeb") || isInsideAds(block)
                    || hasAncestorId(block, "bres")) {
                continue;
            }
            if (!block.select("a[href]").isEmpty() && !block.text().trim().isEmpty()) {
                unrecognized++;
            }
        }
        List<GoogleSerpPage.Link> related = new ArrayList<GoogleSerpPage.Link>();
        for (Element link : doc.select("#bres a[href*=/search?]")) {
            String text = text(link);
            String url = absolute(link.attr("href"));
            if (text != null && url != null) {
                related.add(new GoogleSerpPage.Link(text, url, null));
            }
        }
        if (organic.isEmpty() && recognized && !root.select("h3, div.MjjYud a[href]").isEmpty()) {
            // Result markup is there but nothing matched: the layout changed, not an empty result.
            recognized = false;
        }
        boolean next = doc.getElementById("pnnext") != null;
        GoogleObstacle obstacle = organic.isEmpty() ? GoogleObstacleDetector.fromHtml(html) : GoogleObstacle.NONE;
        return new GoogleSerpPage(organic, related, recognized, next, unrecognized, obstacle);
    }

    private static GoogleSerpPage.Organic organic(int rank, String title, String url, Element anchor, Element block) {
        String displayUrl = text(anchor.selectFirst("cite"));
        String siteName = text(anchor.selectFirst("span.VuuXrf"));
        boolean video = !block.select("a.rIRoqf").isEmpty();
        Element snippetElement = block.selectFirst("div.VwiC3b");
        if (snippetElement == null) {
            snippetElement = block.selectFirst("div.ITZIwc");
        }
        String dateText = null;
        String snippet = null;
        if (snippetElement != null) {
            Element copy = snippetElement.clone();
            Element date = copy.selectFirst("span.YrbPuc");
            if (date != null) {
                dateText = clean(date.text().replace("—", ""));
                date.remove();
            }
            snippet = text(copy);
        }
        List<GoogleSerpPage.Link> siteLinks = new ArrayList<GoogleSerpPage.Link>();
        for (Element link : block.select("h3 > a[href]")) {
            String linkUrl = absolute(link.attr("href"));
            String linkTitle = text(link);
            if (linkUrl == null || linkTitle == null) {
                continue;
            }
            Element row = link.closest("div.uil1Hd");
            String description = row == null ? null : text(row.selectFirst("div.zz3gNc"));
            siteLinks.add(new GoogleSerpPage.Link(linkTitle, linkUrl, description));
        }
        return new GoogleSerpPage.Organic(rank, title, url, displayUrl, siteName, snippet, dateText, video, siteLinks);
    }

    private static Element block(Element anchor, Element root) {
        Element block = anchor.closest("div.MjjYud");
        if (block != null) {
            return block;
        }
        Element candidate = anchor;
        while (candidate.parent() != null && candidate.parent() != root) {
            candidate = candidate.parent();
        }
        return candidate;
    }

    private static boolean isInsideAds(Element element) {
        return hasAncestorId(element, "tads") || hasAncestorId(element, "tadsb") || hasAncestorId(element, "bottomads")
                || hasAncestorId(element, "taw");
    }

    private static boolean hasAncestorTag(Element element, String tag) {
        for (Element parent : element.parents()) {
            if (tag.equals(parent.normalName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAncestorId(Element element, String id) {
        for (Element parent : element.parents()) {
            if (id.equals(parent.id())) {
                return true;
            }
        }
        return false;
    }

    /** Absolute target URL or {@code null} for anchors, javascript links and unusable values. */
    static String absolute(String href) {
        if (href == null) {
            return null;
        }
        String h = href.trim();
        if (h.isEmpty() || h.startsWith("#") || h.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            return null;
        }
        if (h.startsWith("//")) {
            h = "https:" + h;
        } else if (h.startsWith("/")) {
            h = GOOGLE_ORIGIN + h;
        }
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            return null;
        }
        String unwrapped = unwrapUrlRedirect(h);
        return unwrapped != null ? unwrapped : h;
    }

    /** Classic {@code https://www.google.com/url?q=<target>} wrapper: returns the target, else {@code null}. */
    static String unwrapUrlRedirect(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("www.google.com") || host.equals("google.com")) || !"/url".equals(uri.getPath())
                    || uri.getRawQuery() == null) {
                return null;
            }
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                if ((name.equals("q") || name.equals("url")) && eq > 0) {
                    String target = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    if (target.startsWith("http://") || target.startsWith("https://")) {
                        return target;
                    }
                }
            }
            return null;
        } catch (java.net.URISyntaxException e) {
            return null;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 missing", e);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(Element element) {
        return element == null ? null : clean(element.text());
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public String toString() {
        return "GoogleSerpHtmlParser";
    }
}
