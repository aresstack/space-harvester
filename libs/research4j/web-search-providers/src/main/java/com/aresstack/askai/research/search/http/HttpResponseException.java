package com.aresstack.askai.research.search.http;

public final class HttpResponseException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String responseBody;

    public HttpResponseException(
            int statusCode,
            String responseBody) {

        super("HTTP request failed with status "
                + statusCode);

        this.statusCode = statusCode;
        this.responseBody =
                responseBody == null ? "" : responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
