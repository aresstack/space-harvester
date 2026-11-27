package com.aresstack.askai.research.search.http;

import org.asynchttpclient.BoundRequestBuilder;

public final class NoAuthentication
        implements RequestAuthentication {

    public static final NoAuthentication INSTANCE =
            new NoAuthentication();

    private NoAuthentication() {
    }

    @Override
    public void apply(BoundRequestBuilder requestBuilder) {
        // Leave the request unchanged.
    }
}
