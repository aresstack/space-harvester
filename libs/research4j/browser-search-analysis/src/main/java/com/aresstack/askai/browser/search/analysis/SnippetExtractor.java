package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.search.SearchResultExtractionSettings;

/**
 * Extracts the explanatory snippet FROM THE SAME result block: non-link text near the title link,
 * without the duplicated title, without the displayed URL/domain text and within the configured
 * length bounds. An absent snippet is allowed when {@code minimumSnippetCharacters} permits it —
 * the block score, not this extractor, decides how much a missing snippet costs.
 */
public final class SnippetExtractor {

    private final SearchResultExtractionSettings extraction;

    public SnippetExtractor(SearchResultExtractionSettings extraction) {
        this.extraction = extraction;
    }

    /** @return the snippet, or empty when nothing within the bounds remains. */
    public String extract(RenderedContainerDescriptor block, RenderedLinkDescriptor primary) {
        // Prefer the text measured NEXT to the primary link, fall back to the block excerpt —
        // both come from this block's subtree, never from a neighboring result.
        String source = primary.surroundingTextExcerpt.trim().isEmpty()
                ? block.textExcerpt : primary.surroundingTextExcerpt;
        String snippet = clean(source, primary);
        if (snippet.length() < extraction.minimumSnippetCharacters) {
            return "";
        }
        if (snippet.length() > extraction.maximumSnippetCharacters) {
            snippet = snippet.substring(0, extraction.maximumSnippetCharacters).trim();
        }
        return snippet;
    }

    private String clean(String source, RenderedLinkDescriptor primary) {
        String snippet = source == null ? "" : source.replaceAll("\\s+", " ").trim();
        snippet = strip(snippet, primary.visibleText);
        snippet = strip(snippet, primary.displayedDomainText);
        snippet = strip(snippet, primary.resolvedTargetUrl);
        snippet = strip(snippet, primary.rawHref);
        return snippet.trim();
    }

    /** Remove the fragment wherever it appears — a snippet never repeats title or displayed URL. */
    private static String strip(String text, String fragment) {
        String needle = fragment == null ? "" : fragment.replaceAll("\\s+", " ").trim();
        if (needle.isEmpty()) {
            return text;
        }
        int index = text.indexOf(needle);
        while (index >= 0) {
            text = (text.substring(0, index) + " " + text.substring(index + needle.length()))
                    .replaceAll("\\s+", " ").trim();
            index = text.indexOf(needle);
        }
        return text;
    }
}
