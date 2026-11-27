package com.aresstack.askai.browser.search;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The A2 settings contract: central defaults validate cleanly, the flat codec round-trips
 * losslessly with a stable digest, malformed values are REPORTED (never silently corrected),
 * and the validator names the concrete offending setting key.
 */
public class LegacyBrowserSearchSettingsTest {

    private final LegacyBrowserSearchSettingsValidator validator =
            new DefaultLegacyBrowserSearchSettingsValidator();

    @Test
    public void defaultsAreCompleteAndValid() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        SettingsValidationResult result = validator.validate(defaults);
        assertTrue("defaults must validate cleanly, but: " + result.describe(), result.isValid());
        // The migrated productive constants survive as defaults.
        assertTrue(defaults.consent.positiveButtonSelectors.contains("#onetrust-accept-btn-handler"));
        assertTrue(defaults.captcha.challengeTexts.contains("one last step"));
        assertEquals(1_000, defaults.captcha.challengeProbeIntervalMillis);
        assertEquals(600, defaults.consent.postClickSettleMillis);
        assertEquals(20, defaults.navigation.searchResultLimit);
        assertEquals(20_000, defaults.navigation.navigationCommitTimeoutMillis);
    }

    @Test
    public void theDefaultEnginesStartWithStartpageAndPaceGoogleLast() {
        com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection engines =
                LegacyBrowserSearchDefaults.create().navigation.engineSelection;
        assertEquals(com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE,
                engines.getMode());
        assertEquals("Startpage first, DuckDuckGo, Google last, Bing listed but off",
                "startpage:on:3:0:0:de+en+fr:,duckduckgo:on:3:0:1:de+en:,google:on:1:15:2:de+en+fr+es+it:,"
                        + "bing:off:3:0:3:de+en+fr+es:",
                engines.encodeEntries());
        assertEquals(LegacyBrowserSearchDefaults.GOOGLE_REQUEST_DELAY_MILLIS,
                engines.delayMillisFor(com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.GOOGLE));
        assertTrue("a fresh installation never fires Google requests back to back",
                LegacyBrowserSearchDefaults.GOOGLE_REQUEST_DELAY_MILLIS > 0);
    }

    @Test
    public void theUntouchedOldShippedChallengeTextListIsLiftedOnDecode() {
        // Stored settings that still carry the exact list that shipped with the bare word "captcha" are the
        // untouched default, not a choice: decoding lifts them to the current list (the INNOQ false positive).
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create());
        values.put("captcha.challengeTexts",
                String.join("\n", LegacyBrowserSearchSettingsCodec.SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA));
        LegacyBrowserSearchSettingsCodec.Decoded decoded = LegacyBrowserSearchSettingsCodec.fromValues(values);
        assertTrue(decoded.violations.isEmpty());
        assertEquals(LegacyBrowserSearchDefaults.create().captcha.challengeTexts,
                decoded.settings.captcha.challengeTexts);
        assertFalse(decoded.settings.captcha.challengeTexts.contains("captcha"));
        // Surrounding whitespace / blank lines (how the settings editor may store the list) is still the same list.
        values.put("captcha.challengeTexts", "  " + String.join(" \n\n",
                LegacyBrowserSearchSettingsCodec.SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA) + "\n");
        assertFalse(LegacyBrowserSearchSettingsCodec.fromValues(values)
                .settings.captcha.challengeTexts.contains("captcha"));
    }

    @Test
    public void anEditedChallengeTextListIsTheUsersChoiceAndStaysExactlyAsStored() {
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create());
        List<String> shipped = LegacyBrowserSearchSettingsCodec.SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA;
        // An added entry.
        String added = String.join("\n", shipped) + "\nzugriff verweigert";
        values.put("captcha.challengeTexts", added);
        List<String> kept = LegacyBrowserSearchSettingsCodec.fromValues(values).settings.captcha.challengeTexts;
        assertEquals(java.util.Arrays.asList(added.split("\n")), kept);
        assertTrue("the user's own 'captcha' entry survives", kept.contains("captcha"));
        // A reordered list.
        List<String> reordered = new java.util.ArrayList<String>(shipped);
        java.util.Collections.reverse(reordered);
        values.put("captcha.challengeTexts", String.join("\n", reordered));
        assertEquals(reordered, LegacyBrowserSearchSettingsCodec.fromValues(values).settings.captcha.challengeTexts);
        // A user list that is just "captcha".
        values.put("captcha.challengeTexts", "captcha");
        assertEquals(java.util.Collections.singletonList("captcha"),
                LegacyBrowserSearchSettingsCodec.fromValues(values).settings.captcha.challengeTexts);
    }

    @Test
    public void aiLayoutResolverShipsProductivelyEnabled() {
        // The model-backed SERP layout repair is productive: enabled by default with a non-empty
        // symbolic model profile (the runtime port targets the host-published main-model descriptor).
        // Without an inference port the resolver stays a typed AI_UNAVAILABLE — that honesty lives in
        // the resolver, not in a disabled default.
        AiLayoutResolverSettings ai = LegacyBrowserSearchDefaults.create().aiLayoutResolver;
        assertTrue("the AI layout resolver must ship enabled", ai.enabled);
        assertFalse("an enabled resolver needs its (symbolic) model profile",
                ai.modelProfileId.trim().isEmpty());
        // D3: a semantic violation (e.g. BLOCK_OUTSIDE_REGION) is exactly what the repair suffix
        // can fix — the default policy retries it, bounded by maximumAttempts.
        assertTrue("semantic validation failures must be repairable by default",
                ai.retryPolicy.retryOnSemanticValidationFailure);
        assertEquals(3, ai.retryPolicy.maximumAttempts);
    }

    @Test
    public void codecRoundTripsLosslesslyWithStableDigest() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(defaults);
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);
        assertTrue("round trip must not report violations", decoded.violations.isEmpty());
        assertEquals(LegacyBrowserSearchSettingsCodec.digest(defaults),
                LegacyBrowserSearchSettingsCodec.digest(decoded.settings));
        assertEquals(defaults.consent.positiveButtonSelectors,
                decoded.settings.consent.positiveButtonSelectors);
        assertEquals(defaults.aiLayoutResolver.userPromptTemplate,
                decoded.settings.aiLayoutResolver.userPromptTemplate);
        assertEquals(defaults.reranker.retryPolicy.maximumAttempts,
                decoded.settings.reranker.retryPolicy.maximumAttempts);
    }

    @Test
    public void malformedValuesAreReportedNotSilentlyCorrected() {
        Map<String, String> values = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());
        values.put("consent.enabled", "banana");
        values.put("readiness.pollIntervalMillis", "soon");
        values.put("reranker.implementationType", "MAGIC");
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);
        assertEquals(3, decoded.violations.size());
        assertViolationFor(decoded.violations, "consent.enabled");
        assertViolationFor(decoded.violations, "readiness.pollIntervalMillis");
        assertViolationFor(decoded.violations, "reranker.implementationType");
    }

    @Test
    public void validatorNamesTheConcreteOffendingSetting() {
        Map<String, String> values = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());
        values.put("extraction.minimumSnippetCharacters", "500");   // > maximum (400)
        values.put("navigation.maximumEngineAttempts", "0");
        values.put("reranker.maximumSelectedResults", "50");        // > candidate limit (20)
        values.put("aiLayoutResolver.retry.maximumAttempts", "99"); // unbounded retries forbidden
        values.put("analysis.minimumResultStructuralConfidence", "1.5");
        values.put("aiLayoutResolver.userPromptTemplate", "no variables here");
        LegacyBrowserSearchSettings settings =
                LegacyBrowserSearchSettingsCodec.fromValues(values).settings;
        SettingsValidationResult result = validator.validate(settings);
        assertFalse(result.isValid());
        assertViolationFor(result.violations, "extraction.maximumSnippetCharacters");
        assertViolationFor(result.violations, "navigation.maximumEngineAttempts");
        assertViolationFor(result.violations, "reranker.maximumCandidates");
        assertViolationFor(result.violations, "aiLayoutResolver.retry.maximumAttempts");
        assertViolationFor(result.violations, "analysis.minimumResultStructuralConfidence");
        assertViolationFor(result.violations, "aiLayoutResolver.userPromptTemplate");
    }

    @Test
    public void digestReactsToEveryValueChange() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(defaults);
        values.put("captcha.challengeProbeIntervalMillis", "2000");
        LegacyBrowserSearchSettings changed =
                LegacyBrowserSearchSettingsCodec.fromValues(values).settings;
        assertFalse(LegacyBrowserSearchSettingsCodec.digest(defaults)
                .equals(LegacyBrowserSearchSettingsCodec.digest(changed)));
    }

    @Test
    public void missingKeysFallBackToTheSingleDefaultOrigin() {
        LegacyBrowserSearchSettingsCodec.Decoded decoded = LegacyBrowserSearchSettingsCodec
                .fromValues(java.util.Collections.<String, String>emptyMap());
        assertTrue(decoded.violations.isEmpty());
        assertEquals(LegacyBrowserSearchSettingsCodec
                        .digest(LegacyBrowserSearchDefaults.create()),
                LegacyBrowserSearchSettingsCodec.digest(decoded.settings));
    }

    private static void assertViolationFor(List<SettingsValidationResult.Violation> violations,
                                           String key) {
        for (SettingsValidationResult.Violation violation : violations) {
            if (violation.settingKey.equals(key)) {
                return;
            }
        }
        throw new AssertionError("expected a violation for '" + key + "' but got:\n"
                + new SettingsValidationResult(violations).describe());
    }
}
