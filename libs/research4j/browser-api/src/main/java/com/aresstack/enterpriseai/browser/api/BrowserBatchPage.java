package com.aresstack.enterpriseai.browser.api;

/**
 * The outcome for one address of {@link ChatBrowser#openMany}: the address as requested, whether it was loaded,
 * skipped (refused by the access policy, duplicate, no http(s) address) or failed, the reason for the latter two,
 * and for a loaded address the page (tab id no longer valid, the tab is closed) and, for a document address, the
 * downloaded document or {@code null}.
 */
public final class BrowserBatchPage {

    public enum Status { LOADED, SKIPPED, FAILED }

    private final String requestedUrl;
    private final Status status;
    private final String reason;
    private final BrowserPage page;
    private final BrowserDocument document;

    private BrowserBatchPage(String requestedUrl, Status status, String reason, BrowserPage page,
                             BrowserDocument document) {
        this.requestedUrl = requestedUrl == null ? "" : requestedUrl;
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.page = page;
        this.document = document;
    }

    public static BrowserBatchPage loaded(String requestedUrl, BrowserPage page, BrowserDocument document) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        return new BrowserBatchPage(requestedUrl, Status.LOADED, "", page, document);
    }

    public static BrowserBatchPage skipped(String requestedUrl, String reason) {
        return new BrowserBatchPage(requestedUrl, Status.SKIPPED, reason, null, null);
    }

    public static BrowserBatchPage failed(String requestedUrl, String reason) {
        return new BrowserBatchPage(requestedUrl, Status.FAILED, reason, null, null);
    }

    public String requestedUrl() {
        return requestedUrl;
    }

    public Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    /** The loaded page, or {@code null} unless {@link Status#LOADED}. */
    public BrowserPage page() {
        return page;
    }

    /** The document behind a document address, or {@code null}. */
    public BrowserDocument document() {
        return document;
    }
}
