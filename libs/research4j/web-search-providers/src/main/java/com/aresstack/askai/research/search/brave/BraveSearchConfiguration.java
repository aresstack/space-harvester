package com.aresstack.askai.research.search.brave;

import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import com.aresstack.askai.research.search.security.EncryptedSecret;

import java.util.ArrayList;
import java.util.List;

public final class BraveSearchConfiguration {

    private int formatVersion = 1;
    private boolean enabled;
    private String endpoint =
            "https://api.search.brave.com/res/v1/web/search";
    private EncryptedSecret apiKey;
    private String apiVersion;
    private String country = "DE";
    private String searchLanguage = "de";
    private String uiLanguage = "de-DE";
    private int count = 20;
    private int offset;
    private BraveSafeSearch safeSearch =
            BraveSafeSearch.MODERATE;
    private BraveFreshnessConfiguration freshness =
            new BraveFreshnessConfiguration();
    private boolean textDecorations = true;
    private boolean spellcheck = true;
    private List<BraveResultType> resultFilter =
            new ArrayList<BraveResultType>();
    private List<String> goggles =
            new ArrayList<String>();
    private boolean extraSnippets = true;
    private boolean operators = true;
    private BraveUnits units = BraveUnits.METRIC;
    private boolean enableRichCallback;
    private boolean includeFetchMetadata;
    private BraveLocationConfiguration location =
            new BraveLocationConfiguration();
    private HttpTransportConfiguration transport =
            new HttpTransportConfiguration();

    public BraveSearchConfiguration() {
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

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public EncryptedSecret getApiKey() {
        return apiKey;
    }

    public void setApiKey(EncryptedSecret apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getSearchLanguage() {
        return searchLanguage;
    }

    public void setSearchLanguage(String searchLanguage) {
        this.searchLanguage = searchLanguage;
    }

    public String getUiLanguage() {
        return uiLanguage;
    }

    public void setUiLanguage(String uiLanguage) {
        this.uiLanguage = uiLanguage;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public BraveSafeSearch getSafeSearch() {
        return safeSearch;
    }

    public void setSafeSearch(BraveSafeSearch safeSearch) {
        this.safeSearch = safeSearch;
    }

    public BraveFreshnessConfiguration getFreshness() {
        return freshness;
    }

    public void setFreshness(
            BraveFreshnessConfiguration freshness) {

        this.freshness = freshness;
    }

    public boolean isTextDecorations() {
        return textDecorations;
    }

    public void setTextDecorations(
            boolean textDecorations) {

        this.textDecorations = textDecorations;
    }

    public boolean isSpellcheck() {
        return spellcheck;
    }

    public void setSpellcheck(boolean spellcheck) {
        this.spellcheck = spellcheck;
    }

    public List<BraveResultType> getResultFilter() {
        return resultFilter;
    }

    public void setResultFilter(
            List<BraveResultType> resultFilter) {

        this.resultFilter = resultFilter;
    }

    public List<String> getGoggles() {
        return goggles;
    }

    public void setGoggles(List<String> goggles) {
        this.goggles = goggles;
    }

    public boolean isExtraSnippets() {
        return extraSnippets;
    }

    public void setExtraSnippets(
            boolean extraSnippets) {

        this.extraSnippets = extraSnippets;
    }

    public boolean isOperators() {
        return operators;
    }

    public void setOperators(boolean operators) {
        this.operators = operators;
    }

    public BraveUnits getUnits() {
        return units;
    }

    public void setUnits(BraveUnits units) {
        this.units = units;
    }

    public boolean isEnableRichCallback() {
        return enableRichCallback;
    }

    public void setEnableRichCallback(
            boolean enableRichCallback) {

        this.enableRichCallback = enableRichCallback;
    }

    public boolean isIncludeFetchMetadata() {
        return includeFetchMetadata;
    }

    public void setIncludeFetchMetadata(
            boolean includeFetchMetadata) {

        this.includeFetchMetadata =
                includeFetchMetadata;
    }

    public BraveLocationConfiguration getLocation() {
        return location;
    }

    public void setLocation(
            BraveLocationConfiguration location) {

        this.location = location;
    }

    public HttpTransportConfiguration getTransport() {
        return transport;
    }

    public void setTransport(
            HttpTransportConfiguration transport) {

        this.transport = transport;
    }
}
