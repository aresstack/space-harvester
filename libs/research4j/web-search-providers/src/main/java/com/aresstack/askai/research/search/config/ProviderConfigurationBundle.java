package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;

public final class ProviderConfigurationBundle {

    private final BraveSearchConfiguration brave;
    private final BrightDataSearchConfiguration brightData;
    private final DataForSeoSearchConfiguration dataForSeo;
    private final SerpApiSearchConfiguration serpApi;

    public ProviderConfigurationBundle(
            BraveSearchConfiguration brave,
            BrightDataSearchConfiguration brightData,
            DataForSeoSearchConfiguration dataForSeo,
            SerpApiSearchConfiguration serpApi) {

        this.brave = requireNonNull(brave, "brave");
        this.brightData = requireNonNull(
                brightData,
                "brightData");
        this.dataForSeo = requireNonNull(
                dataForSeo,
                "dataForSeo");
        this.serpApi = requireNonNull(serpApi, "serpApi");
    }

    public BraveSearchConfiguration getBrave() {
        return brave;
    }

    public BrightDataSearchConfiguration getBrightData() {
        return brightData;
    }

    public DataForSeoSearchConfiguration getDataForSeo() {
        return dataForSeo;
    }

    public SerpApiSearchConfiguration getSerpApi() {
        return serpApi;
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
