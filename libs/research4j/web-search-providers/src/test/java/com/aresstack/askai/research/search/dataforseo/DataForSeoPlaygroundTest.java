package com.aresstack.askai.research.search.dataforseo;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The playground's request preview uses the REAL request mapper and is auth-free by construction: it shows
 * exactly what would be sent (keyword, location, depth, …) and NEVER a credential.
 */
public class DataForSeoPlaygroundTest {

    private final DataForSeoPlayground playground = new DataForSeoPlayground(null, new Gson());

    @Test
    public void theRequestPreviewShowsTheEffectiveParametersAndNeverACredential() {
        DataForSeoSearchConfiguration config = new DataForSeoSearchConfiguration();
        config.setDepth(30);
        String preview = playground.requestPreview(config, "wearables");

        assertTrue(preview, preview.contains("\"keyword\": \"wearables\""));
        assertTrue(preview, preview.contains("\"location_code\": 2276"));
        assertTrue(preview, preview.contains("\"depth\": 10")); // min(maximumResults=10, depth=30)
        assertFalse("no username in the request preview", preview.contains("me@example.com"));
        assertFalse("no auth header in the request preview",
                preview.toLowerCase().contains("authorization"));
        assertFalse("no password field", preview.toLowerCase().contains("password"));
    }

    @Test
    public void theEndpointIsTheProductiveOrganicLiveAdvancedPath() {
        assertTrue(playground.endpoint(new DataForSeoSearchConfiguration())
                .endsWith("/v3/serp/google/organic/live/advanced"));
    }
}
