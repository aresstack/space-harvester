package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.PageView;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import java.util.Collections;

/** Project rendered wiki HTML into the shared reader without loading external resources. */
final class WikiReadingView {
    private WikiReadingView() {
    }

    static PageView of(String html, String base) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html, base);
        document.select(".mw-editsection, script, style, noscript, iframe, object, embed").remove();
        for (Element image : document.select("img")) {
            image.replaceWith(new TextNode(image.attr("alt")));
        }
        Safelist allowed = Safelist.relaxed().removeTags("img")
                .addTags("section", "figure", "figcaption", "caption", "thead", "tbody", "tfoot")
                .addAttributes("th", "scope").addAttributes(":all", "id");
        String safe = Jsoup.clean(document.body().html(), base, allowed);
        return new PageView(safe, base, Collections.<PageView.Image>emptyList());
    }
}
