package com.aresstack.askai.browser;

/**
 * A worker browsing context tried to reach a search engine (Google, Bing, DuckDuckGo, a provider host). Search
 * engines are reached only through the web search tool of the concept phase, so the navigation is refused before it
 * leaves the process. The message starts with {@link #CODE} so callers and logs can tell it apart from other policy
 * refusals.
 */
public final class EngineHostRefusedException extends BrowserException {

    public static final String CODE = "ENGINE_HOST_REFUSED";

    private final String host;

    public EngineHostRefusedException(String host, String what) {
        super(CODE + ": " + what + " (" + host + ") is a search engine; worker contexts never contact one, "
                + "searches go through the web search tool.");
        this.host = host == null ? "" : host;
    }

    public String getHost() {
        return host;
    }
}
