package com.aresstack.enterpriseai.source.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A successful answer of {@link RestQuerySource#restGet}: media type and body exactly as delivered. */
public final class RestResponse {

    private final String contentType;
    private final byte[] body;

    public RestResponse(String contentType, byte[] body) {
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? new byte[0] : body.clone();
    }

    /** The {@code Content-Type} header as sent, possibly empty. */
    public String contentType() {
        return contentType;
    }

    /** The body byte for byte, a copy. */
    public byte[] body() {
        return body.clone();
    }

    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RestResponse)) {
            return false;
        }
        RestResponse that = (RestResponse) other;
        return contentType.equals(that.contentType) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * contentType.hashCode() + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "RestResponse[" + contentType + ", " + body.length + " bytes]";
    }
}
