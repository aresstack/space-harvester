package com.aresstack.enterpriseai.browser.api.engine;

/** A resource fetched inside a page: address after redirects, declared content type and bytes. */
public final class RawDownload {

    private final String finalUrl;
    private final String contentType;
    private final byte[] content;

    public RawDownload(String finalUrl, String contentType, byte[] content) {
        this.finalUrl = finalUrl == null ? "" : finalUrl;
        this.contentType = contentType == null ? "" : contentType;
        this.content = content == null ? new byte[0] : content;
    }

    public String finalUrl() {
        return finalUrl;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] content() {
        return content;
    }
}
