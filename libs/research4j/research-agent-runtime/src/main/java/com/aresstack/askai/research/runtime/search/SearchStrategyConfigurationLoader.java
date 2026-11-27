package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchJson;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import java.util.Map;

/**
 * Decodes the initial-search strategy snapshot (JSON) the host publishes at session start. There is no live
 * reconfiguration and NO silent fallback: a present-but-invalid snapshot is a hard error, never a quiet
 * switch back to the legacy browser search. Absent snapshot text is treated as {@link
 * SearchStrategyConfiguration#legacyBrowser()} so nothing changes for existing sessions.
 *
 * <p>Shape (SELECTION only — NO credentials; a legacy {@code provider_settings} object is still tolerated
 * syntactically but its values are ignored entirely, never copied, logged or surfaced):</p>
 * <pre>
 * { "strategy": "API_PROVIDER", "provider": "DATA_FOR_SEO", "engine": "GOOGLE",
 *   "language": "de", "country": "de" }
 * </pre>
 */
public final class SearchStrategyConfigurationLoader {

    /** A contract-invalid snapshot; the caller fails the session start visibly. */
    public static final class InvalidConfigurationException extends RuntimeException {
        public InvalidConfigurationException(String message) {
            super(message);
        }
    }

    private SearchStrategyConfigurationLoader() {
    }

    public static SearchStrategyConfiguration parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return SearchStrategyConfiguration.legacyBrowser();
        }
        Object root;
        try {
            root = SearchJson.parse(json);
        } catch (SearchJson.JsonParseException ex) {
            throw new InvalidConfigurationException(
                    "search strategy snapshot is not valid JSON: " + ex.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new InvalidConfigurationException("search strategy snapshot root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        StrategySelection strategy = parseStrategy(string(object.get("strategy")));
        if (strategy == StrategySelection.LEGACY_BROWSER) {
            return SearchStrategyConfiguration.legacyBrowser();
        }

        SearchProviderId providerId = parseProvider(string(object.get("provider")));
        SearchEngine engine = parseEngine(string(object.get("engine")));
        String language = string(object.get("language"));
        String country = string(object.get("country"));
        if (object.containsKey("provider_settings")) {
            // Legacy credential transport: read but IGNORED. Provider secrets now live only in the provider
            // files; their values are never taken, logged, or surfaced in any exception.
            System.err.println("[research-agent] Legacy provider settings were ignored.");
        }
        return new SearchStrategyConfiguration(strategy, providerId, engine, language, country);
    }

    private static StrategySelection parseStrategy(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("search strategy snapshot has no 'strategy'");
        }
        try {
            return StrategySelection.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown strategy '" + value + "'");
        }
    }

    private static SearchProviderId parseProvider(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("API_PROVIDER strategy has no 'provider'");
        }
        try {
            return SearchProviderId.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown provider '" + value + "'");
        }
    }

    private static SearchEngine parseEngine(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("API_PROVIDER strategy has no 'engine'");
        }
        try {
            return SearchEngine.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown engine '" + value + "'");
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        return s.isEmpty() ? null : s;
    }
}
