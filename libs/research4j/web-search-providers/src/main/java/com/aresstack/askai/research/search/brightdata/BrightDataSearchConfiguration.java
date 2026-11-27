package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import com.aresstack.askai.research.search.security.EncryptedSecret;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BrightDataSearchConfiguration {

    private int formatVersion = 1;
    private boolean enabled;
    private String synchronousEndpoint =
            "https://api.brightdata.com/request";
    private String asynchronousRequestEndpoint =
            "https://api.brightdata.com/serp/req";
    private String asynchronousResultEndpoint =
            "https://api.brightdata.com/serp/get_result";
    private EncryptedSecret apiKey;
    private String zone = ""; // the user's own SERP API zone; no default
    private String customer;
    private BrightDataExecutionMode executionMode =
            BrightDataExecutionMode.SYNCHRONOUS;
    private BrightDataSearchEngine searchEngine =
            BrightDataSearchEngine.GOOGLE;
    private String searchEngineEndpoint;
    private String country = "de";
    private String language = "de";
    private int resultsPerPage = 10;
    private int startOffset;
    private BrightDataSafeSearch safeSearch =
            BrightDataSafeSearch.DEFAULT;
    private BrightDataResponseFormat responseFormat =
            BrightDataResponseFormat.JSON;
    private BrightDataRequestMethod requestMethod =
            BrightDataRequestMethod.GET;
    private BrightDataDataFormat dataFormat =
            BrightDataDataFormat.NONE;
    private int pollingIntervalMillis = 1_000;
    private int maximumPollAttempts = 60;
    private Map<String, String> additionalSearchParameters =
            new LinkedHashMap<String, String>();
    private HttpTransportConfiguration transport =
            new HttpTransportConfiguration();

    public BrightDataSearchConfiguration() {
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

    public String getSynchronousEndpoint() {
        return synchronousEndpoint;
    }

    public void setSynchronousEndpoint(String synchronousEndpoint) {
        this.synchronousEndpoint = synchronousEndpoint;
    }

    public String getAsynchronousRequestEndpoint() {
        return asynchronousRequestEndpoint;
    }

    public void setAsynchronousRequestEndpoint(
            String asynchronousRequestEndpoint) {

        this.asynchronousRequestEndpoint =
                asynchronousRequestEndpoint;
    }

    public String getAsynchronousResultEndpoint() {
        return asynchronousResultEndpoint;
    }

    public void setAsynchronousResultEndpoint(
            String asynchronousResultEndpoint) {

        this.asynchronousResultEndpoint =
                asynchronousResultEndpoint;
    }

    public EncryptedSecret getApiKey() {
        return apiKey;
    }

    public void setApiKey(EncryptedSecret apiKey) {
        this.apiKey = apiKey;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public BrightDataExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(
            BrightDataExecutionMode executionMode) {

        this.executionMode = executionMode;
    }

    public BrightDataSearchEngine getSearchEngine() {
        return searchEngine;
    }

    public void setSearchEngine(
            BrightDataSearchEngine searchEngine) {

        this.searchEngine = searchEngine;
    }

    public String getSearchEngineEndpoint() {
        return searchEngineEndpoint;
    }

    public void setSearchEngineEndpoint(
            String searchEngineEndpoint) {

        this.searchEngineEndpoint = searchEngineEndpoint;
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

    public int getResultsPerPage() {
        return resultsPerPage;
    }

    public void setResultsPerPage(int resultsPerPage) {
        this.resultsPerPage = resultsPerPage;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(int startOffset) {
        this.startOffset = startOffset;
    }

    public BrightDataSafeSearch getSafeSearch() {
        return safeSearch;
    }

    public void setSafeSearch(
            BrightDataSafeSearch safeSearch) {

        this.safeSearch = safeSearch;
    }

    public BrightDataResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(
            BrightDataResponseFormat responseFormat) {

        this.responseFormat = responseFormat;
    }

    public BrightDataRequestMethod getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(
            BrightDataRequestMethod requestMethod) {

        this.requestMethod = requestMethod;
    }

    public BrightDataDataFormat getDataFormat() {
        return dataFormat;
    }

    public void setDataFormat(
            BrightDataDataFormat dataFormat) {

        this.dataFormat = dataFormat;
    }

    public int getPollingIntervalMillis() {
        return pollingIntervalMillis;
    }

    public void setPollingIntervalMillis(
            int pollingIntervalMillis) {

        this.pollingIntervalMillis = pollingIntervalMillis;
    }

    public int getMaximumPollAttempts() {
        return maximumPollAttempts;
    }

    public void setMaximumPollAttempts(
            int maximumPollAttempts) {

        this.maximumPollAttempts = maximumPollAttempts;
    }

    public Map<String, String> getAdditionalSearchParameters() {
        return additionalSearchParameters;
    }

    public void setAdditionalSearchParameters(
            Map<String, String> additionalSearchParameters) {

        this.additionalSearchParameters =
                additionalSearchParameters;
    }

    public HttpTransportConfiguration getTransport() {
        return transport;
    }

    public void setTransport(
            HttpTransportConfiguration transport) {

        this.transport = transport;
    }
}
