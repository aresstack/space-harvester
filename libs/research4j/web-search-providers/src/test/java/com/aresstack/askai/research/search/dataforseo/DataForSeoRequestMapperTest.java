package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class DataForSeoRequestMapperTest {

    @Test
    public void mapLiveAdvancedTask() {
        DataForSeoSearchConfiguration configuration =
                new DataForSeoSearchConfiguration();
        configuration.getRemoveFromUrl().add("srsltid");
        WebSearchRequest request = WebSearchRequest
                .builder("Wearables Forschung")
                .languageCode("de")
                .maximumResults(20)
                .build();

        DataForSeoRequestMapper mapper =
                new DataForSeoRequestMapper(new Gson());
        String body = mapper.createBody(
                configuration,
                request);

        assertTrue(body.contains(
                "\"keyword\":\"Wearables Forschung\""));
        assertTrue(body.contains(
                "\"location_code\":2276"));
        // Effective depth = min(maximumResults, configured depth) = min(20, 10) = 10. The corrected
        // Organic Live Advanced default is 10 (was wrongly 100), and it also caps the request cost.
        assertTrue(body, body.contains("\"depth\":10"));
        assertTrue(mapper.createEndpoint(configuration)
                .endsWith(
                        "/v3/serp/google/organic/live/advanced"));
    }
}
