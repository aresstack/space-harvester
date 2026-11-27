package com.aresstack.askai.browser.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The immutable per-session snapshot: lossless persist/restore with digest verification, typed
 * schema migration, and HARD failures for corrupt snapshots — a broken snapshot is never silently
 * replaced by current global defaults.
 */
public class SearchProcessingProfileSnapshotTest {

    @Test
    public void persistsAndRestoresLosslessly() {
        SearchProcessingProfileSnapshot snapshot = SearchProcessingProfileSnapshot.create(
                "session-42", 3L, 1_753_700_000_000L, LegacyBrowserSearchDefaults.create());
        SearchProcessingProfileSnapshot restored =
                SearchProcessingProfileSnapshot.parse(snapshot.toJson());
        assertEquals("session-42", restored.profileId);
        assertEquals(3L, restored.profileRevision);
        assertEquals(1_753_700_000_000L, restored.createdAtEpochMillis);
        assertEquals(snapshot.settingsDigest, restored.settingsDigest);
        assertEquals(snapshot.settings.captcha.challengeProbeIntervalMillis,
                restored.settings.captcha.challengeProbeIntervalMillis);
        // Prompt texts are part of the snapshot — a later AI run stays reproducible.
        assertEquals(snapshot.settings.aiLayoutResolver.userPromptTemplate,
                restored.settings.aiLayoutResolver.userPromptTemplate);
    }

    @Test
    public void corruptDigestFailsHardInsteadOfFallingBackToDefaults() {
        String json = SearchProcessingProfileSnapshot.create(
                        "s", 1L, 1L, LegacyBrowserSearchDefaults.create()).toJson()
                .replace("\"captcha.challengeProbeIntervalMillis\": \"1000\"",
                        "\"captcha.challengeProbeIntervalMillis\": \"9999\"");
        try {
            SearchProcessingProfileSnapshot.parse(json);
            fail("expected digest mismatch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("digest mismatch"));
        }
    }

    @Test
    public void aV5SnapshotWithTheOldShippedChallengeTextsIsLiftedNotRejected() {
        // A session frozen before v6 still carries the shipped list with the bare word "captcha" and a digest
        // over it. It must restore (no digest failure) with the current list.
        String oldList = String.join("\\n", LegacyBrowserSearchSettingsCodec.SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA);
        String current = SearchProcessingProfileSnapshot.create(
                "s", 1L, 1L, LegacyBrowserSearchDefaults.create()).toJson();
        String v5 = current
                .replace("\"schemaVersion\": " + LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION,
                        "\"schemaVersion\": 5")
                .replaceAll("\"captcha\\.challengeTexts\": \"[^\"]*\"",
                        java.util.regex.Matcher.quoteReplacement("\"captcha.challengeTexts\": \"" + oldList + "\""));
        assertTrue("fixture carries the old list", v5.contains("complete the challenge\\ncaptcha\\n"));
        SearchProcessingProfileSnapshot restored = SearchProcessingProfileSnapshot.parse(v5);
        assertEquals(LegacyBrowserSearchDefaults.create().captcha.challengeTexts,
                restored.settings.captcha.challengeTexts);
        assertEquals(LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, restored.schemaVersion);
    }

    @Test
    public void unknownOlderSchemaHasNoGuessedMigration() {
        String json = SearchProcessingProfileSnapshot.create(
                        "s", 1L, 1L, LegacyBrowserSearchDefaults.create()).toJson()
                .replace("\"schemaVersion\": " + LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION,
                        "\"schemaVersion\": 0");
        try {
            SearchProcessingProfileSnapshot.parse(json);
            fail("expected missing migration path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no migration path"));
        }
    }

    @Test
    public void v1SnapshotsMigrateExplicitlyToV2WithDefaultsForNewFields() {
        // A stored A2-era snapshot: schema 1, WITHOUT the A3 analysis keys and with a digest over
        // the v1 key set. It must migrate (not fail), keep its stored values and give the new
        // fields their central defaults.
        java.util.Map<String, String> v1Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        for (java.util.Iterator<String> it = v1Values.keySet().iterator(); it.hasNext();) {
            String key = it.next();
            if (key.startsWith("analysis.repeatedBlockWeight")
                    || key.startsWith("analysis.maximumCaptured")
                    || key.startsWith("analysis.maximumLinksPerContainer")
                    || key.startsWith("analysis.maximumContainerDomDepth")
                    || key.startsWith("analysis.maximumStructureSignatureDepth")) {
                it.remove();
            }
        }
        v1Values.put("captcha.challengeProbeIntervalMillis", "3000"); // a real v1 override
        String v1Json = new LegacyBrowserSearchConfigDocument(1, 5L, "v1-digest-not-verified",
                "old-session", 42L, v1Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v1Json);
        assertEquals("old-session", migrated.profileId);
        assertEquals(3000, migrated.settings.captcha.challengeProbeIntervalMillis);
        assertEquals("new A3 fields take their central defaults", 1.2,
                migrated.settings.analysis.repeatedBlockWeight, 0.0001);
        assertEquals("new A4 layout-repair fields take their central defaults", 16,
                migrated.settings.layoutRepair.maximumCachedTickets);
        assertEquals(LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    public void aV7SnapshotWithoutTheEnoughResultsSettingTakesItsDefault() {
        java.util.Map<String, String> v7Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        v7Values.remove("navigation.minimumUsableResults");
        String v7Json = new LegacyBrowserSearchConfigDocument(7, 3L, "digest-without-the-new-key",
                "frozen-session", 42L, v7Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v7Json);
        assertEquals(LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
        assertEquals(LegacyBrowserSearchDefaults.MINIMUM_USABLE_RESULTS,
                migrated.settings.navigation.minimumUsableResults);
    }

    @Test
    public void aV6SnapshotWithAnOldEngineListMigratesToTheTableInItsFrozenOrder() {
        // A session frozen before the engine table: old engine form, ALL_ENABLED, and the digest the old build
        // computed over that form. Restoring it must not reject it as corrupt and must keep the frozen order.
        java.util.Map<String, String> v6Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        v6Values.put("navigation.engines", "duckduckgo:on:3,bing:on:3:1.5,google:off:1:15");
        v6Values.put("navigation.engineAcquisitionMode", "ALL_ENABLED");
        String v6Json = new LegacyBrowserSearchConfigDocument(6, 9L, "digest-of-the-old-engine-form",
                "frozen-session", 42L, v6Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v6Json);
        assertEquals(LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry> rows =
                migrated.settings.navigation.engineSelection.getEntries();
        assertEquals("duckduckgo", rows.get(0).getEngineId());
        assertEquals("bing", rows.get(1).getEngineId());
        assertEquals("google", rows.get(2).getEngineId());
        assertEquals(0, rows.get(0).getPriority());
        assertEquals(0, rows.get(1).getPriority());
        assertEquals(1500, rows.get(1).getDelayMillis());
        assertEquals("the restored snapshot round-trips with its recomputed digest", migrated.settingsDigest,
                SearchProcessingProfileSnapshot.parse(migrated.toJson()).settingsDigest);
    }

    @Test
    public void v3StaleDisabledAiResolverDefaultIsLiftedToTheProductiveDefault() {
        // A frozen pre-v4 session profile carrying the stale SHIPPED default (disabled AND empty
        // model profile — the validator never accepted that combination as a deliberate choice):
        // migration lifts exactly this pair to the productive default, everything else stays frozen.
        java.util.Map<String, String> v3Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        v3Values.put("aiLayoutResolver.enabled", "false");
        v3Values.put("aiLayoutResolver.modelProfileId", "");
        v3Values.put("captcha.challengeProbeIntervalMillis", "3000"); // a real frozen override
        String v3Json = new LegacyBrowserSearchConfigDocument(3, 7L, "v3-digest-not-verified",
                "old-session", 42L, v3Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v3Json);
        assertTrue("the stale shipped default must be lifted",
                migrated.settings.aiLayoutResolver.enabled);
        assertEquals(LegacyBrowserSearchDefaults.create().aiLayoutResolver.modelProfileId,
                migrated.settings.aiLayoutResolver.modelProfileId);
        assertEquals("frozen overrides stay exactly as stored", 3000,
                migrated.settings.captcha.challengeProbeIntervalMillis);
        assertEquals(LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
    }

    @Test
    public void v3DeliberatelyDisabledAiResolverStaysDisabled() {
        // enabled=false WITH a configured model profile is a valid deliberate configuration — the
        // migration must never override a real choice.
        java.util.Map<String, String> v3Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        v3Values.put("aiLayoutResolver.enabled", "false");
        v3Values.put("aiLayoutResolver.modelProfileId", "my-deliberate-profile");
        String v3Json = new LegacyBrowserSearchConfigDocument(3, 7L, "v3-digest-not-verified",
                "old-session", 42L, v3Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v3Json);
        assertTrue("a deliberate disable must survive the migration",
                !migrated.settings.aiLayoutResolver.enabled);
        assertEquals("my-deliberate-profile",
                migrated.settings.aiLayoutResolver.modelProfileId);
    }
}
