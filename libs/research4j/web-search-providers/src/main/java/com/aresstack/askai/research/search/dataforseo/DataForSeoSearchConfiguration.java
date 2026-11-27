package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import com.aresstack.askai.research.search.security.EncryptedSecret;

import java.util.ArrayList;
import java.util.List;

public final class DataForSeoSearchConfiguration {

    private int formatVersion =
            1;
    private boolean enabled;
    private String endpointBase =
            "https://api.dataforseo.com";
    /** The DataForSEO API key, i.e. Base64(login:password) exactly as sent in the Basic header. */
    private EncryptedSecret apiKey;
    private DataForSeoSearchEngine searchEngine =
            DataForSeoSearchEngine.GOOGLE;
    private DataForSeoResultFormat resultFormat =
            DataForSeoResultFormat.ADVANCED;
    private Integer locationCode =
            Integer.valueOf(2276);
    private String locationName;
    private String locationCoordinate;
    private String languageCode =
            "de";
    private String languageName;
    // Organic Live Advanced (/v3/serp/{engine}/organic/live/advanced): DataForSEO documents default 10,
    // maximum 200 (NOT 100/700 — that was wrong for this endpoint). DataForSEO bills organic SERPs in
    // result blocks of up to 10, so a depth above 10 can increase the request cost. The request mapper
    // caps the effective depth at Math.min(maximumResults, depth), so this never over-asks.
    private int depth =
            10;
    private DataForSeoDevice device =
            DataForSeoDevice.DESKTOP;
    private DataForSeoOperatingSystem operatingSystem =
            DataForSeoOperatingSystem.WINDOWS;
    private boolean loadAsyncAiOverview;
    private String tag;
    private List<DataForSeoStopCrawlTarget> stopCrawlOnMatch =
            new ArrayList<DataForSeoStopCrawlTarget>();
    private Integer maxCrawlPages;
    private String searchParam;
    private List<String> removeFromUrl =
            new ArrayList<String>();
    private Integer peopleAlsoAskClickDepth;
    private boolean groupOrganicResults;
    private boolean calculateRectangles;
    private Integer browserScreenWidth;
    private Integer browserScreenHeight;
    private Double browserScreenResolutionRatio;
    private String directUrl;
    private String seDomain;
    private String target;
    private DataForSeoTargetSearchMode targetSearchMode =
            DataForSeoTargetSearchMode.ANY;
    private List<DataForSeoSerpElementType> findTargetsIn =
            new ArrayList<DataForSeoSerpElementType>();
    private List<DataForSeoSerpElementType> ignoreTargetsIn =
            new ArrayList<DataForSeoSerpElementType>();
    private HttpTransportConfiguration transport =
            new HttpTransportConfiguration();

    public DataForSeoSearchConfiguration() {
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(
            int formatVersion) {

        this.formatVersion = formatVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(
            boolean enabled) {

        this.enabled = enabled;
    }

    public String getEndpointBase() {
        return endpointBase;
    }

    public void setEndpointBase(
            String endpointBase) {

        this.endpointBase = endpointBase;
    }

    public EncryptedSecret getApiKey() {
        return apiKey;
    }

    public void setApiKey(
            EncryptedSecret apiKey) {

        this.apiKey = apiKey;
    }

    public DataForSeoSearchEngine getSearchEngine() {
        return searchEngine;
    }

    public void setSearchEngine(
            DataForSeoSearchEngine searchEngine) {

        this.searchEngine = searchEngine;
    }

    public DataForSeoResultFormat getResultFormat() {
        return resultFormat;
    }

    public void setResultFormat(
            DataForSeoResultFormat resultFormat) {

        this.resultFormat = resultFormat;
    }

    public Integer getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(
            Integer locationCode) {

        this.locationCode = locationCode;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(
            String locationName) {

        this.locationName = locationName;
    }

    public String getLocationCoordinate() {
        return locationCoordinate;
    }

    public void setLocationCoordinate(
            String locationCoordinate) {

        this.locationCoordinate = locationCoordinate;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(
            String languageCode) {

        this.languageCode = languageCode;
    }

    public String getLanguageName() {
        return languageName;
    }

    public void setLanguageName(
            String languageName) {

        this.languageName = languageName;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(
            int depth) {

        this.depth = depth;
    }

    public DataForSeoDevice getDevice() {
        return device;
    }

    public void setDevice(
            DataForSeoDevice device) {

        this.device = device;
    }

    public DataForSeoOperatingSystem getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(
            DataForSeoOperatingSystem operatingSystem) {

        this.operatingSystem = operatingSystem;
    }

    public boolean isLoadAsyncAiOverview() {
        return loadAsyncAiOverview;
    }

    public void setLoadAsyncAiOverview(
            boolean loadAsyncAiOverview) {

        this.loadAsyncAiOverview = loadAsyncAiOverview;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(
            String tag) {

        this.tag = tag;
    }

    public List<DataForSeoStopCrawlTarget> getStopCrawlOnMatch() {
        return stopCrawlOnMatch;
    }

    public void setStopCrawlOnMatch(
            List<DataForSeoStopCrawlTarget> stopCrawlOnMatch) {

        this.stopCrawlOnMatch = stopCrawlOnMatch;
    }

    public Integer getMaxCrawlPages() {
        return maxCrawlPages;
    }

    public void setMaxCrawlPages(
            Integer maxCrawlPages) {

        this.maxCrawlPages = maxCrawlPages;
    }

    public String getSearchParam() {
        return searchParam;
    }

    public void setSearchParam(
            String searchParam) {

        this.searchParam = searchParam;
    }

    public List<String> getRemoveFromUrl() {
        return removeFromUrl;
    }

    public void setRemoveFromUrl(
            List<String> removeFromUrl) {

        this.removeFromUrl = removeFromUrl;
    }

    public Integer getPeopleAlsoAskClickDepth() {
        return peopleAlsoAskClickDepth;
    }

    public void setPeopleAlsoAskClickDepth(
            Integer peopleAlsoAskClickDepth) {

        this.peopleAlsoAskClickDepth = peopleAlsoAskClickDepth;
    }

    public boolean isGroupOrganicResults() {
        return groupOrganicResults;
    }

    public void setGroupOrganicResults(
            boolean groupOrganicResults) {

        this.groupOrganicResults = groupOrganicResults;
    }

    public boolean isCalculateRectangles() {
        return calculateRectangles;
    }

    public void setCalculateRectangles(
            boolean calculateRectangles) {

        this.calculateRectangles = calculateRectangles;
    }

    public Integer getBrowserScreenWidth() {
        return browserScreenWidth;
    }

    public void setBrowserScreenWidth(
            Integer browserScreenWidth) {

        this.browserScreenWidth = browserScreenWidth;
    }

    public Integer getBrowserScreenHeight() {
        return browserScreenHeight;
    }

    public void setBrowserScreenHeight(
            Integer browserScreenHeight) {

        this.browserScreenHeight = browserScreenHeight;
    }

    public Double getBrowserScreenResolutionRatio() {
        return browserScreenResolutionRatio;
    }

    public void setBrowserScreenResolutionRatio(
            Double browserScreenResolutionRatio) {

        this.browserScreenResolutionRatio = browserScreenResolutionRatio;
    }

    public String getDirectUrl() {
        return directUrl;
    }

    public void setDirectUrl(
            String directUrl) {

        this.directUrl = directUrl;
    }

    public String getSeDomain() {
        return seDomain;
    }

    public void setSeDomain(
            String seDomain) {

        this.seDomain = seDomain;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(
            String target) {

        this.target = target;
    }

    public DataForSeoTargetSearchMode getTargetSearchMode() {
        return targetSearchMode;
    }

    public void setTargetSearchMode(
            DataForSeoTargetSearchMode targetSearchMode) {

        this.targetSearchMode = targetSearchMode;
    }

    public List<DataForSeoSerpElementType> getFindTargetsIn() {
        return findTargetsIn;
    }

    public void setFindTargetsIn(
            List<DataForSeoSerpElementType> findTargetsIn) {

        this.findTargetsIn = findTargetsIn;
    }

    public List<DataForSeoSerpElementType> getIgnoreTargetsIn() {
        return ignoreTargetsIn;
    }

    public void setIgnoreTargetsIn(
            List<DataForSeoSerpElementType> ignoreTargetsIn) {

        this.ignoreTargetsIn = ignoreTargetsIn;
    }

    public HttpTransportConfiguration getTransport() {
        return transport;
    }

    public void setTransport(
            HttpTransportConfiguration transport) {

        this.transport = transport;
    }

}