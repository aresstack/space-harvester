package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class BrightDataTargetUrlFactoryTest {

    @Test
    public void mapGoogleTargetUrl() {
        BrightDataSearchConfiguration configuration =
                new BrightDataSearchConfiguration();
        WebSearchRequest request = WebSearchRequest
                .builder("Forschung Wearables")
                .countryCode("DE")
                .languageCode("de")
                .maximumResults(10)
                .build();

        String url = new BrightDataTargetUrlFactory()
                .create(configuration, request);

        assertTrue(url.startsWith(
                "https://www.google.com/search?"));
        assertTrue(url.contains("q=Forschung+Wearables"));
        assertTrue(url.contains("hl=de"));
        assertTrue(url.contains("gl=de"));
    }
}
