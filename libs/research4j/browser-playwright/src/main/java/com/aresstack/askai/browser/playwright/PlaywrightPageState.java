package com.aresstack.askai.browser.playwright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw page state as reported by the {@link PlaywrightDriver}: final URL (after redirects), title, RENDERED
 * text (post-JavaScript, via the DOM — not the HTTP body) and the anchors with absolute hrefs. This is the
 * only data crossing the driver boundary — no Playwright objects, DOM handles or GraalJS values leak out.
 *
 * <p>A navigation that ends in a PDF document is reported as a DOCUMENT state instead ({@link #isDocument()}):
 * the browser shows it in its PDF viewer, where no page script (and so no add-on read) runs, or it only
 * starts a download. The session then reads the document itself ({@link PdfPageReader}) and keeps the read
 * text as its current page.</p>
 */
final class PlaywrightPageState {

    /** One anchor: visible text + absolute href. */
    static final class Anchor {
        final String text;
        final String href;

        Anchor(String text, String href) {
            this.text = text == null ? "" : text;
            this.href = href == null ? "" : href;
        }
    }

    final String url;
    final String title;
    final String text;
    final List<Anchor> anchors;
    /**
     * {@code null} for a rendered page; for a document the Content-Type the browser received ({@code ""} when
     * it only reported a download and never saw the response headers).
     */
    final String documentType;
    /**
     * The document replaced the tab's page (it is in the tab history, so "back" leaves it with a real history
     * step); {@code false} when the browser only started a download and the tab still shows the previous page.
     */
    final boolean documentInTab;
    /**
     * What the tab shows while a document is the current page: the viewer's URL, or for a download the URL of
     * the page the tab kept. Once the tab shows anything else (the user navigated the visible window), the
     * document is no longer the current page. Equal to {@link #url} for a rendered page.
     */
    final String tabUrl;

    PlaywrightPageState(String url, String title, String text, List<Anchor> anchors) {
        this(url, title, text, anchors, null, false, url);
    }

    private PlaywrightPageState(String url, String title, String text, List<Anchor> anchors,
                                String documentType, boolean documentInTab, String tabUrl) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.anchors = Collections.unmodifiableList(new ArrayList<Anchor>(
                anchors == null ? Collections.<Anchor>emptyList() : anchors));
        this.documentType = documentType;
        this.documentInTab = documentInTab;
        this.tabUrl = tabUrl == null ? "" : tabUrl;
    }

    /** The browser shows {@code url} in its document viewer (the response carried {@code contentType}). */
    static PlaywrightPageState viewedDocument(String url, String contentType) {
        return new PlaywrightPageState(url, "", "", null, contentType == null ? "" : contentType, true, url);
    }

    /** Navigating to {@code url} only started a download; the tab kept its previous page at {@code tabUrl}. */
    static PlaywrightPageState downloadedDocument(String url, String tabUrl) {
        return new PlaywrightPageState(url, "", "", null, "", false, tabUrl);
    }

    boolean isDocument() {
        return documentType != null;
    }

    /** This document state with the text read from it (final URL after the reader's own redirects, no links). */
    PlaywrightPageState withDocumentText(String finalUrl, String documentTitle, String documentText,
                                         String contentType) {
        return new PlaywrightPageState(finalUrl, documentTitle, documentText, null,
                contentType == null ? "" : contentType, documentInTab, tabUrl);
    }
}
