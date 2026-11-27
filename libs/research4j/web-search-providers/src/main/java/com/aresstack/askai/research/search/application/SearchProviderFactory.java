package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brave.BraveSearchConfigurationValidator;
import com.aresstack.askai.research.search.brave.BraveSearchProvider;
import com.aresstack.askai.research.search.brave.BraveSearchRequestMapper;
import com.aresstack.askai.research.search.brave.BraveSearchResponseMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataRequestMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfigurationValidator;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchProvider;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchResponseMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataTargetUrlFactory;
import com.aresstack.askai.research.search.dataforseo.DataForSeoRequestMapper;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfigurationValidator;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchProvider;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchResponseMapper;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.http.AsyncJsonHttpClient;
import com.aresstack.askai.research.search.http.EncryptedBasicApiKeyAuthentication;
import com.aresstack.askai.research.search.http.EncryptedBearerAuthentication;
import com.aresstack.askai.research.search.http.EncryptedHeaderAuthentication;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.aresstack.askai.research.search.serp4j.Serp4jProviderFactory;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfigurationValidator;
import com.google.gson.Gson;

public final class SearchProviderFactory {

    private final Gson gson;
    private final AsyncHttpClientFactory httpClientFactory;
    private final SecretValueService secretValueService;
    private final Serp4jProviderFactory serp4j;

    public SearchProviderFactory(
            Gson gson,
            AsyncHttpClientFactory httpClientFactory,
            SecretValueService secretValueService) {

        this.gson = requireNonNull(gson, "gson");
        this.httpClientFactory = requireNonNull(
                httpClientFactory,
                "httpClientFactory");
        this.secretValueService = requireNonNull(
                secretValueService,
                "secretValueService");
        this.serp4j = new Serp4jProviderFactory(secretValueService);
    }

    /** Brave via Serp4J ({@code serp4j-provider-brave}); the AskAI configuration file stays authoritative. */
    public WebSearchProvider createBrave(
            BraveSearchConfiguration configuration) {

        new BraveSearchConfigurationValidator()
                .validate(configuration);
        return serp4j.createBrave(configuration);
    }

    /** Bright Data via Serp4J ({@code serp4j-provider-brightdata}, synchronous Google SERP). */
    public WebSearchProvider createBrightData(
            BrightDataSearchConfiguration configuration) {

        new BrightDataSearchConfigurationValidator()
                .validate(configuration);
        return serp4j.createBrightData(configuration);
    }

    /** DataForSEO via Serp4J ({@code serp4j-provider-dataforseo}, live regular/advanced). */
    public WebSearchProvider createDataForSeo(
            DataForSeoSearchConfiguration configuration) {

        new DataForSeoSearchConfigurationValidator()
                .validate(configuration);
        return serp4j.createDataForSeo(configuration);
    }

    /** SerpApi via Serp4J ({@code serp4j-provider-serpapi}, Google or Bing); no AskAI-side implementation. */
    public WebSearchProvider createSerpApi(
            SerpApiSearchConfiguration configuration) {

        new SerpApiSearchConfigurationValidator()
                .validate(configuration);
        return serp4j.createSerpApi(configuration);
    }

    /**
     * Pre-Serp4J implementation, kept only until the Serp4J path has real-response coverage.
     *
     * @deprecated use {@link #createBrave}; removed after the WP5 migration is verified.
     */
    @Deprecated
    public WebSearchProvider createLegacyBrave(
            BraveSearchConfiguration configuration) {

        new BraveSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedHeaderAuthentication(
                        "X-Subscription-Token",
                        "",
                        configuration.getApiKey(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new BraveSearchProvider(
                configuration,
                client,
                new BraveSearchRequestMapper(),
                new BraveSearchResponseMapper());
    }

    /**
     * Pre-Serp4J implementation, kept only until the Serp4J path has real-response coverage.
     *
     * @deprecated use {@link #createBrightData}; removed after the WP5 migration is verified.
     */
    @Deprecated
    public WebSearchProvider createLegacyBrightData(
            BrightDataSearchConfiguration configuration) {

        new BrightDataSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedBearerAuthentication(
                        configuration.getApiKey(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new BrightDataSearchProvider(
                configuration,
                client,
                new BrightDataRequestMapper(
                        gson,
                        new BrightDataTargetUrlFactory()),
                new BrightDataSearchResponseMapper());
    }

    /**
     * Pre-Serp4J implementation, kept only until the Serp4J path has real-response coverage.
     *
     * @deprecated use {@link #createDataForSeo}; removed after the WP5 migration is verified.
     */
    @Deprecated
    public WebSearchProvider createLegacyDataForSeo(
            DataForSeoSearchConfiguration configuration) {

        new DataForSeoSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedBasicApiKeyAuthentication(
                        configuration.getApiKey(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new DataForSeoSearchProvider(
                configuration,
                client,
                new DataForSeoRequestMapper(gson),
                new DataForSeoSearchResponseMapper());
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
