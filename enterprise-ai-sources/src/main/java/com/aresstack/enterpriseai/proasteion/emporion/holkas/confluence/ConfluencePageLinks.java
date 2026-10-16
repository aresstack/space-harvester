package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page links in the rendered body of a page (package-private, issue #97): every anchor that points to a page of this
 * Confluence, in document order, each target once. Confluence marks internal page links with
 * {@code data-linked-resource-type="page"} and the content id; other anchors go through {@link ConfluenceLinks}. Anchors
 * of the page itself (fragments, the own id), attachments, blog posts and foreign addresses are left out.
 */
final class ConfluencePageLinks {

    private ConfluencePageLinks() {
    }

    /**
     * @param html   the rendered body ({@code body.view})
     * @param base   the configured base URL with context path
     * @param pageId the content id of the page itself
     */
    static List<ConfluenceLinks.Target> of(String html, String base, String pageId) {
        Map<String, ConfluenceLinks.Target> targets = new LinkedHashMap<String, ConfluenceLinks.Target>();
        if (html == null || html.trim().isEmpty()) {
            return new ArrayList<ConfluenceLinks.Target>();
        }
        String root = base.endsWith("/") ? base : base + "/";
        for (Element anchor : Jsoup.parseBodyFragment(html).select("a[href]")) {
            ConfluenceLinks.Target target = target(anchor, root);
            if (target == null || pageId.equals(target.pageId)) {
                continue;
            }
            String key = target.pageId != null ? "id:" + target.pageId
                    : "title:" + target.spaceKey + "/" + (target.title == null ? "" : target.title);
            if (!targets.containsKey(key)) {
                targets.put(key, target);
            }
        }
        return new ArrayList<ConfluenceLinks.Target>(targets.values());
    }

    private static ConfluenceLinks.Target target(Element anchor, String root) {
        String linkedType = anchor.attr("data-linked-resource-type");
        String linkedId = anchor.attr("data-linked-resource-id");
        if ("page".equals(linkedType) && ConfluenceIds.isContentId(linkedId)) {
            return ConfluenceLinks.Target.page(linkedId);
        }
        if (!linkedType.isEmpty() && !"page".equals(linkedType)) {
            return null; // attachment, blog post, user profile
        }
        String href = anchor.attr("href").trim();
        if (href.isEmpty() || href.startsWith("#")) {
            return null;
        }
        String absolute;
        try {
            absolute = URI.create(root).resolve(href).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ConfluenceLinks.parse(absolute, root);
    }
}
