package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.PageView;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Project wiki HTML and route same-origin images through the shared reader. */
final class WikiReadingView {
    private WikiReadingView() {
    }

    static PageView of(String html, String base, URI origin) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html, base);
        document.select(".mw-editsection, script, style, noscript, iframe, object, embed").remove();
        List<PageView.Image> images = new ArrayList<PageView.Image>();
        for (Element image : document.select("img")) {
            URI target = sameOriginImage(image.absUrl("src"), origin);
            if (target == null) {
                image.replaceWith(new TextNode(image.attr("alt")));
            } else {
                image.attr("src", target.toString());
                images.add(new PageView.Image(target.toString(), image.attr("alt"), null));
            }
        }
        Safelist allowed = Safelist.relaxed()
                .addTags("section", "figure", "figcaption", "caption", "thead", "tbody", "tfoot")
                .addAttributes("th", "scope").addAttributes(":all", "id");
        String safe = Jsoup.clean(document.body().html(), base, allowed);
        return new PageView(safe, base, images);
    }

    static URI sameOriginImage(String value, URI origin) {
        try {
            URI target = URI.create(value);
            if (target.getRawUserInfo() == null && target.getHost() != null
                    && origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost().equalsIgnoreCase(target.getHost())
                    && port(origin) == port(target)) {
                return target;
            }
        } catch (IllegalArgumentException invalid) {
            // Reject malformed URLs.
        }
        return null;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
