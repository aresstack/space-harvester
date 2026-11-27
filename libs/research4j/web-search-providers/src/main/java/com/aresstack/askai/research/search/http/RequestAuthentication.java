package com.aresstack.askai.research.search.http;

import org.asynchttpclient.BoundRequestBuilder;

public interface RequestAuthentication {

    void apply(BoundRequestBuilder requestBuilder);
}
