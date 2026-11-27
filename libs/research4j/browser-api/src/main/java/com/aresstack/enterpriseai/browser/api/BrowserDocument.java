package com.aresstack.enterpriseai.browser.api;

/**
 * A document a page links to (PDF, Office file, text), downloaded through the chat's browser with its cookies and
 * logins: the address reached after redirects, a file name derived from it, the content type the server declared and
 * the bytes, at most {@link #MAX_BYTES}.
 */
public final class BrowserDocument {

    /** Upper bound for one download. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private final String url;
    private final String fileName;
    private final String contentType;
    private final byte[] content;

    public BrowserDocument(String url, String fileName, String contentType, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.url = url == null ? "" : url;
        this.fileName = fileName == null || fileName.isEmpty() ? "dokument" : fileName;
        this.contentType = contentType == null ? "" : contentType;
        this.content = content.clone();
    }

    public String url() {
        return url;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] content() {
        return content.clone();
    }
}
