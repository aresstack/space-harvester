package com.aresstack.askai.research.search.brave;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class BraveSearchRequestMapperTest {

    @Test
    public void mapGermanSearchRequest() {
        BraveSearchConfiguration configuration =
                new BraveSearchConfiguration();
        WebSearchRequest request = WebSearchRequest
                .builder("medizinische Wearables")
                .countryCode("DE")
                .languageCode("de")
                .maximumResults(15)
                .build();

        String url = new BraveSearchRequestMapper()
                .createUrl(configuration, request);

        assertTrue(url.contains(
                "q=medizinische+Wearables"));
        assertTrue(url.contains("country=DE"));
        assertTrue(url.contains("search_lang=de"));
        assertTrue(url.contains("count=15"));
    }
}
