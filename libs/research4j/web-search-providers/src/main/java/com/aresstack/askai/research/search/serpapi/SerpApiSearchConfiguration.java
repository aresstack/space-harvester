package com.aresstack.askai.research.search.serpapi;

import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import com.aresstack.askai.research.search.security.EncryptedSecret;

/**
 * AskAI configuration file {@code serpapi.json}. The search itself runs through Serp4J
 * ({@code serp4j-provider-serpapi}); this class only carries the host-side settings and the AES-GCM encrypted
 * API key. SerpApi expects the key as URL parameter {@code api_key}; Serp4J masks it in every log/error text.
 */
public final class SerpApiSearchConfiguration {

    private int formatVersion = 1;
    private boolean enabled;
    private String baseUrl = "https://serpapi.com";
    private EncryptedSecret apiKey;
    private SerpApiSearchEngine searchEngine = SerpApiSearchEngine.GOOGLE;
    /** ISO country code ({@code gl} for Google, {@code cc} for Bing). */
    private String country = "de";
    /** Language code ({@code hl} for Google; Bing needs a country as well, see Serp4J). */
    private String language = "de";
    /** Optional SerpApi {@code location}, e.g. "Berlin, Germany". */
    private String location;
    /** Optional Google domain, e.g. "google.de" (Google only). */
    private String googleDomain;
    private boolean noCache;
    /** Resolve Google {@code /goto} redirect links to their target URL (Google only, Serp4J default). */
    private boolean resolveGoogleGotoUrls = true;
    /** Overall budget for resolving goto links; 0 keeps the Serp4J default. */
    private int urlResolveTimeoutMillis;
    private HttpTransportConfiguration transport =
            new HttpTransportConfiguration();

    public SerpApiSearchConfiguration() {
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public EncryptedSecret getApiKey() {
        return apiKey;
    }

    public void setApiKey(EncryptedSecret apiKey) {
        this.apiKey = apiKey;
    }

    public SerpApiSearchEngine getSearchEngine() {
        return searchEngine;
    }

    public void setSearchEngine(SerpApiSearchEngine searchEngine) {
        this.searchEngine = searchEngine;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getGoogleDomain() {
        return googleDomain;
    }

    public void setGoogleDomain(String googleDomain) {
        this.googleDomain = googleDomain;
    }

    public boolean isNoCache() {
        return noCache;
    }

    public void setNoCache(boolean noCache) {
        this.noCache = noCache;
    }

    public boolean isResolveGoogleGotoUrls() {
        return resolveGoogleGotoUrls;
    }

    public void setResolveGoogleGotoUrls(boolean resolveGoogleGotoUrls) {
        this.resolveGoogleGotoUrls = resolveGoogleGotoUrls;
    }

    public int getUrlResolveTimeoutMillis() {
        return urlResolveTimeoutMillis;
    }

    public void setUrlResolveTimeoutMillis(int urlResolveTimeoutMillis) {
        this.urlResolveTimeoutMillis = urlResolveTimeoutMillis;
    }

    public HttpTransportConfiguration getTransport() {
        return transport;
    }

    public void setTransport(HttpTransportConfiguration transport) {
        this.transport = transport;
    }
}
