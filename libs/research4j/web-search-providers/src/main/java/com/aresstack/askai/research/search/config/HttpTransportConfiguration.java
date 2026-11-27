package com.aresstack.askai.research.search.config;

public final class HttpTransportConfiguration {

    private int connectTimeoutMillis = 15_000;
    private int readTimeoutMillis = 60_000;
    private int requestTimeoutMillis = 60_000;
    private int maxConnections = 100;
    private int maxConnectionsPerHost = 20;
    private boolean followRedirects = true;
    private boolean keepAlive = true;
    private boolean compressionEnabled = true;
    private String userAgent = "AskAI-Research/1.0";

    public HttpTransportConfiguration() {
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(
            int connectTimeoutMillis) {

        this.connectTimeoutMillis =
                connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(
            int readTimeoutMillis) {

        this.readTimeoutMillis =
                readTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(
            int requestTimeoutMillis) {

        this.requestTimeoutMillis =
                requestTimeoutMillis;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(
            int maxConnections) {

        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    public void setMaxConnectionsPerHost(
            int maxConnectionsPerHost) {

        this.maxConnectionsPerHost =
                maxConnectionsPerHost;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setFollowRedirects(
            boolean followRedirects) {

        this.followRedirects = followRedirects;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    public void setCompressionEnabled(
            boolean compressionEnabled) {

        this.compressionEnabled =
                compressionEnabled;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
