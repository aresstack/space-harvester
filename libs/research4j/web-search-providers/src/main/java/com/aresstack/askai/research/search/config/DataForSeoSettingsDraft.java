package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;

/**
 * One EDIT DRAFT of the DataForSEO provider settings, held for the lifetime of an open settings dialog: the
 * dialog loads it ONCE from the {@link ProviderConfigurationService}, the UI mutates the wrapped
 * {@link DataForSeoSearchConfiguration} directly (so switching provider cards never reloads or discards it),
 * and {@link #save} persists it through the SAME service on Save. Cancel simply drops the draft.
 *
 * <p>API key handling follows the spec: the stored secret is NEVER decrypted into the UI. The draft only
 * knows whether a credential EXISTS; a new API key is applied only when the user actually typed one, and
 * is encrypted through the existing {@link SecretValueService} on save — otherwise the stored secret is
 * kept untouched.</p>
 */
public final class DataForSeoSettingsDraft {

    private final ProviderConfigurationService service;
    private final DataForSeoSearchConfiguration configuration;
    private final boolean hadStoredApiKey;
    private char[] newApiKey; // null → keep the stored secret; non-null → encrypt + replace on save

    private DataForSeoSettingsDraft(ProviderConfigurationService service,
                                    DataForSeoSearchConfiguration configuration) {
        this.service = service;
        this.configuration = configuration;
        this.hadStoredApiKey = configuration.getApiKey() != null;
    }

    /** Load the draft once from the persisted provider file (creating defaults if none exists). */
    public static DataForSeoSettingsDraft load(ProviderConfigurationService service) {
        return new DataForSeoSettingsDraft(service, service.loadOrCreateDataForSeo());
    }

    /** The live working copy the UI edits directly (depth, engine, location, advanced fields, …). */
    public DataForSeoSearchConfiguration configuration() {
        return configuration;
    }

    /** True when a credential is already stored — the UI shows "Saved credential", never the secret. */
    public boolean hasStoredApiKey() {
        return hadStoredApiKey || newApiKey != null;
    }

    /** Record a newly typed API key (kept only in memory until save); empty/null clears the pending one. */
    public void setNewApiKey(char[] apiKey) {
        if (newApiKey != null) {
            SecretArrays.clear(newApiKey);
        }
        newApiKey = apiKey == null || apiKey.length == 0 ? null : apiKey.clone();
    }

    public boolean hasPendingApiKey() {
        return newApiKey != null;
    }

    /**
     * Encrypt a freshly typed (unsaved) API key INTO the working config so a playground run uses it —
     * without persisting. No-op when nothing new was typed (the run then uses the stored secret). The
     * later {@link #save} re-encrypts from the pending API key, so this stays consistent.
     */
    public void applyPendingApiKeyForRun(SecretValueService secrets) {
        if (newApiKey != null && secrets != null) {
            configuration.setApiKey(secrets.encrypt(newApiKey.clone()));
        }
    }

    /**
     * Persist through the provider service: a pending API key is encrypted and replaces the stored secret;
     * without one the stored secret is preserved. Validation happens inside the service (depth 1..200, …).
     */
    public void save() {
        if (newApiKey != null) {
            service.saveDataForSeo(configuration, newApiKey.clone()); // service clears its copy
        } else {
            service.saveDataForSeo(configuration); // keeps the existing encrypted secret untouched
        }
    }

    /** Wipe the in-memory API key copy (call on dialog close/cancel). */
    public void dispose() {
        if (newApiKey != null) {
            SecretArrays.clear(newApiKey);
            newApiKey = null;
        }
    }
}
