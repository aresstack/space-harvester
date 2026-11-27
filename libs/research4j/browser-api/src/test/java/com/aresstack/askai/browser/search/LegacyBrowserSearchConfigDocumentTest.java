package com.aresstack.askai.browser.search;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The versioned sidecar hand-off document: lossless JSON round trip (prompts with newlines and
 * quotes included), strict parsing with readable errors, and the sidecar subset never carries
 * AI/prompt/reranker configuration into the browser process.
 */
public class LegacyBrowserSearchConfigDocumentTest {

    @Test
    public void roundTripsTheFullSettingsLosslessly() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(defaults);
        LegacyBrowserSearchConfigDocument document = new LegacyBrowserSearchConfigDocument(
                LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, 7,
                LegacyBrowserSearchSettingsCodec.digest(defaults), values);
        LegacyBrowserSearchConfigDocument parsed =
                LegacyBrowserSearchConfigDocument.parse(document.toJson());
        assertEquals(7, parsed.settingsRevision);
        assertEquals(document.settingsDigest, parsed.settingsDigest);
        assertEquals(values, parsed.values);
        // Prompts contain newlines — they must survive the JSON escaping.
        assertTrue(parsed.values.get("aiLayoutResolver.systemPromptTemplate").contains("\n"));
    }

    @Test
    public void sidecarSubsetNeverCarriesAiConfiguration() {
        Map<String, String> subset = LegacyBrowserSearchConfigDocument.sidecarSubset(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        for (String key : subset.keySet()) {
            assertFalse("AI key leaked into the sidecar subset: " + key,
                    key.startsWith("aiLayoutResolver.") || key.startsWith("reranker."));
        }
        assertTrue(subset.containsKey("consent.enabled"));
        assertTrue(subset.containsKey("captcha.challengeProbeIntervalMillis"));
        assertTrue(subset.containsKey("navigation.engines"));
    }

    @Test
    public void malformedOrNewerDocumentsFailReadably() {
        try {
            LegacyBrowserSearchConfigDocument.parse("{ nope");
            fail("expected parse failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("browser config"));
        }
        try {
            LegacyBrowserSearchConfigDocument.parse(
                    "{\"schemaVersion\": 99, \"settings\": {}}");
            fail("expected schema-version rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("newer than supported"));
        }
    }
}
