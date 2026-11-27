package com.aresstack.askai.browser.search;

/**
 * The IMMUTABLE per-session settings snapshot (A2c). Global settings are only a TEMPLATE for new
 * sessions: at session start one snapshot is created, validated and persisted with the session; the
 * running session reads exclusively from it and NEVER switches behaviour because global settings
 * changed. Resuming a session reuses the stored snapshot exactly; an older schema is migrated
 * typed; an unreadable snapshot is a clear recovery/configuration error — never silently replaced
 * by current global defaults. Prompt texts and model profile ids are part of the snapshot, so a
 * later AI run stays reproducible.
 */
public final class SearchProcessingProfileSnapshot {

    public final int schemaVersion;
    public final String profileId;
    public final long profileRevision;
    public final long createdAtEpochMillis;
    public final String settingsDigest;
    public final LegacyBrowserSearchSettings settings;

    private SearchProcessingProfileSnapshot(int schemaVersion, String profileId, long profileRevision,
                                            long createdAtEpochMillis, String settingsDigest,
                                            LegacyBrowserSearchSettings settings) {
        this.schemaVersion = schemaVersion;
        this.profileId = profileId;
        this.profileRevision = profileRevision;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.settingsDigest = settingsDigest;
        this.settings = settings;
    }

    /** Freeze the given (already validated) settings into a new snapshot; the digest is computed here. */
    public static SearchProcessingProfileSnapshot create(String profileId, long profileRevision,
                                                         long createdAtEpochMillis,
                                                         LegacyBrowserSearchSettings settings) {
        return new SearchProcessingProfileSnapshot(
                LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, profileId, profileRevision,
                createdAtEpochMillis, LegacyBrowserSearchSettingsCodec.digest(settings), settings);
    }

    public String toJson() {
        return new LegacyBrowserSearchConfigDocument(schemaVersion, profileRevision, settingsDigest,
                profileId, createdAtEpochMillis,
                LegacyBrowserSearchSettingsCodec.toValues(settings)).toJson();
    }

    /**
     * Restore a stored snapshot. HARD failure modes (all {@link IllegalArgumentException} with the
     * concrete reason): malformed JSON, malformed values, digest mismatch (corruption/tampering),
     * unknown newer schema. An OLDER schema goes through the typed migration switch below — schema 1
     * is current, so migration is currently the identity; every future schema bump adds its step
     * here instead of guessing.
     */
    public static SearchProcessingProfileSnapshot parse(String json) {
        LegacyBrowserSearchConfigDocument document = LegacyBrowserSearchConfigDocument.parse(json);
        LegacyBrowserSearchConfigDocument migrated = migrate(document);
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(migrated.values);
        if (!decoded.violations.isEmpty()) {
            throw new IllegalArgumentException("search profile snapshot has invalid values:\n"
                    + new SettingsValidationResult(decoded.violations).describe());
        }
        String digest = LegacyBrowserSearchSettingsCodec.digest(decoded.settings);
        if (document.schemaVersion == LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION
                && !migrated.settingsDigest.isEmpty() && !migrated.settingsDigest.equals(digest)) {
            throw new IllegalArgumentException("search profile snapshot digest mismatch: stored "
                    + migrated.settingsDigest + " but values hash to " + digest
                    + " — the snapshot is corrupt and will not be silently replaced");
        }
        return new SearchProcessingProfileSnapshot(
                LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.profileId,
                migrated.settingsRevision, migrated.createdAtEpochMillis, digest, decoded.settings);
    }

    /** Typed schema migration: one explicit step per historical version, never a heuristic. */
    private static LegacyBrowserSearchConfigDocument migrate(
            LegacyBrowserSearchConfigDocument document) {
        switch (document.schemaVersion) {
            case LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION:
                return document;
            case 7:
                // v7 → v8: the new key navigation.minimumUsableResults takes its central default while decoding
                // (LegacyBrowserSearchDefaults.MINIMUM_USABLE_RESULTS). The stored digest covered the key set
                // without it, so it is recomputed like every older migration.
                return document;
            case 6:
                // v6 → v7: the frozen engine list in the old form (id:on:pages[:delay]) becomes the engine table
                // while decoding (LegacyBrowserSearchSettingsCodec.migrateEngineList): priority = list position
                // under FIRST_USABLE, 0 for every row under ALL_ENABLED, rows of one priority keep their order,
                // languages from the catalog. The search behaves as frozen. The stored digest covered the old
                // encoding, so it is recomputed like every older migration.
                return document;
            case 5:
                // v5 → v6: a frozen profile still carrying the untouched old shipped challenge-text list
                // (with the bare word "captcha") is lifted to the current list while decoding
                // (LegacyBrowserSearchSettingsCodec.liftStaleShippedDefaults); an edited list stays as frozen.
                // The stored digest covered the old list, so it is recomputed like every older migration.
                return document;
            case 1:
            case 2:
            case 3:
            case 4:
                // v1 → v2 (A3 analysis fields) and v2 → v3 (A4 layout-repair ticket-cache settings):
                // every older value stays valid and the new keys take their central defaults during
                // decoding. v3 → v4: the AI layout resolver ships productively enabled — a frozen
                // profile still carrying the stale disabled SHIPPED default is lifted below. The
                // stored digest covered the older key set, so it is recomputed (digest verification
                // only applies to un-migrated current-version snapshots). v4 → v5: the old
                // primaryEngine/fallbackEngineTemplates keys simply stop being read, and the engine
                // selection takes its central default — a frozen profile never carried an engine ORDER
                // to preserve, only a fallback list the user could not order anyway.
                return withProductiveAiLayoutResolver(document);
            default:
                // parse() already rejects NEWER versions; an unknown older one has no migration path.
                throw new IllegalArgumentException("search profile snapshot schema "
                        + document.schemaVersion + " has no migration path");
        }
    }

    /**
     * v3 → v4: lift ONLY the stale shipped default (disabled AND empty model profile — a combination
     * the validator never accepted as a deliberate configuration) to the current productive default.
     * Any other stored value is a real choice and stays exactly as frozen.
     */
    private static LegacyBrowserSearchConfigDocument withProductiveAiLayoutResolver(
            LegacyBrowserSearchConfigDocument document) {
        String enabled = document.values.get("aiLayoutResolver.enabled");
        String profile = document.values.get("aiLayoutResolver.modelProfileId");
        boolean staleShippedDefault = enabled != null && "false".equalsIgnoreCase(enabled.trim())
                && (profile == null || profile.trim().isEmpty());
        if (!staleShippedDefault) {
            return document;
        }
        AiLayoutResolverSettings current = LegacyBrowserSearchDefaults.create().aiLayoutResolver;
        java.util.Map<String, String> values =
                new java.util.LinkedHashMap<String, String>(document.values);
        values.put("aiLayoutResolver.enabled", String.valueOf(current.enabled));
        values.put("aiLayoutResolver.modelProfileId", current.modelProfileId);
        return new LegacyBrowserSearchConfigDocument(document.schemaVersion,
                document.settingsRevision, document.settingsDigest, document.profileId,
                document.createdAtEpochMillis, values);
    }
}
