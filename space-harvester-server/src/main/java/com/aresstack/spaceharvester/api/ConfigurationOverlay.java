package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Überlagern einzelner Schlüssel für {@code POST /api/v1/configuration} (ADR 0015): die wirksame
 * {@link HarvesterConfiguration} als flache Properties ({@link #toProperties}, die Umkehrung von
 * {@link HarvesterConfiguration#fromProperties}), darüber die Werte des Requests, dann neu gelesen. Proxy und TLS
 * bleiben typisiert und lassen sich hier nicht überlagern; sie wechseln über die Konfigurationsquelle (Datei,
 * Config-Server).
 */
public final class ConfigurationOverlay {

    /** Schlüssel ohne Präfix, die {@link #overlay} annimmt. */
    static final Set<String> KEYS = Set.of(
            "ai." + AiServiceSettings.BASE_URL, "ai." + AiServiceSettings.API_KEY,
            "ai." + AiServiceSettings.CHAT_MODEL, "ai." + AiServiceSettings.EMBEDDING_MODEL,
            "ai." + AiServiceSettings.EMBEDDING_DIMENSIONS, "ai." + AiServiceSettings.TIMEOUT,
            "ai." + AiServiceSettings.TRUST_BUNDLE, "ai." + AiServiceSettings.EXTERNAL_PROCESSING_SOURCES,
            "ai." + AiServiceSettings.RERANK_MODEL, "ai." + AiServiceSettings.VISION_MODEL,
            SearchSettings.SEMANTIC, SearchSettings.RERANK, SearchSettings.PREFETCH_HITS,
            SearchSettings.CACHE_MAX_AGE, SearchSettings.CACHE_VERSION_CHECK, SearchSettings.CACHE_REFRESH_STRATEGY,
            SyncSettings.EVENTS, SyncSettings.INTERVAL, SyncSettings.SPACES, SyncSettings.BEFORE_SEARCH,
            SyncSettings.WITHHELD_RETENTION, SyncSettings.CATCH_UP_ON_START, SyncSettings.MODE,
            SyncSettings.INVENTORY_INTERVAL, SyncSettings.BACKGROUND_FILL, SyncSettings.BACKGROUND_PARALLELISM,
            StorageSettings.DATA_DIRECTORY);

    /** Listen dürfen auch als {@code key[0]}, {@code key[1]}, … kommen, wie Spring sie bindet. */
    private static final Set<String> LISTS = Set.of("ai." + AiServiceSettings.EXTERNAL_PROCESSING_SOURCES,
            SyncSettings.SPACES);

    private static final Pattern INDEXED = Pattern.compile("(.+)\\[\\d+]");

    /** Regeln je Quelle: {@code sync.sources.<Quelle>.<Feld>} oder {@code sync.sources[<Quelle>].<Feld>}. */
    private static final Pattern SOURCE_KEY = Pattern.compile("sync\\.sources(?:\\.([^.\\[\\]]+)|\\[([^\\]]+)])\\.([a-z-]+)");

    private ConfigurationOverlay() {
    }

    /**
     * Die Umkehrung von {@link HarvesterConfiguration#fromProperties}: flache Properties ohne Präfix, aus denen
     * {@code fromProperties} dieselbe Konfiguration ohne Netzwerk liest. Enthält den API-Key, damit er beim
     * Überlagern erhalten bleibt; das Ergebnis gehört deshalb nie in eine Antwort oder ein Log.
     */
    public static Properties toProperties(HarvesterConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Properties properties = new Properties();
        AiServiceSettings ai = configuration.ai();
        put(properties, "ai." + AiServiceSettings.BASE_URL, ai.baseUrl());
        put(properties, "ai." + AiServiceSettings.API_KEY, ai.apiKey());
        put(properties, "ai." + AiServiceSettings.CHAT_MODEL, ai.chatModel());
        put(properties, "ai." + AiServiceSettings.EMBEDDING_MODEL, ai.embeddingModel());
        put(properties, "ai." + AiServiceSettings.EMBEDDING_DIMENSIONS, ai.embeddingDimensions());
        put(properties, "ai." + AiServiceSettings.TIMEOUT, ai.timeout());
        put(properties, "ai." + AiServiceSettings.TRUST_BUNDLE, ai.trustBundle());
        if (!ai.externalProcessingSources().isEmpty()) {
            put(properties, "ai." + AiServiceSettings.EXTERNAL_PROCESSING_SOURCES,
                    String.join(",", new TreeSet<>(ai.externalProcessingSources())));
        }
        put(properties, "ai." + AiServiceSettings.RERANK_MODEL, ai.rerankModel());
        put(properties, "ai." + AiServiceSettings.VISION_MODEL, ai.visionModel());
        SearchSettings search = configuration.search();
        put(properties, SearchSettings.SEMANTIC, search.semantic());
        put(properties, SearchSettings.RERANK, search.rerank());
        put(properties, SearchSettings.PREFETCH_HITS, search.prefetchHits());
        put(properties, SearchSettings.CACHE_MAX_AGE, search.cacheFreshness().maxAge());
        put(properties, SearchSettings.CACHE_VERSION_CHECK, search.cacheFreshness().versionCheck());
        SyncSettings sync = configuration.sync();
        put(properties, SyncSettings.EVENTS, sync.events());
        put(properties, SyncSettings.INTERVAL, sync.interval());
        if (!sync.spaces().isEmpty()) {
            put(properties, SyncSettings.SPACES, String.join(",", sync.spaces()));
        }
        put(properties, SyncSettings.BEFORE_SEARCH, sync.beforeSearch());
        put(properties, SyncSettings.WITHHELD_RETENTION, sync.withheldRetention());
        put(properties, SyncSettings.CATCH_UP_ON_START, sync.catchUpOnStart());
        put(properties, SyncSettings.MODE, sync.mode().key());
        put(properties, SyncSettings.INVENTORY_INTERVAL, sync.inventoryInterval());
        put(properties, SyncSettings.BACKGROUND_FILL, sync.backgroundFill());
        put(properties, SyncSettings.BACKGROUND_PARALLELISM, sync.backgroundParallelism());
        sync.sources().forEach((id, override) -> fields(override)
                .forEach((field, value) -> properties.setProperty(SourceSyncOverride.key(id, field), value)));
        put(properties, StorageSettings.DATA_DIRECTORY, configuration.storage().dataDirectory());
        return properties;
    }

    /**
     * Legt einzelne Schlüssel über die wirksame Konfiguration und liest das Ergebnis neu. Schlüssel mit oder ohne
     * {@code space-harvester.}; ein leerer Wert oder {@code null} setzt zurück. Was der Request nicht nennt, bleibt,
     * auch der API-Key. Proxy und TLS übernimmt das Ergebnis aus {@code current}. Unbekannte Schlüssel und
     * {@code network.*} sind Probleme, nie stillschweigend ignoriert.
     *
     * @param values Schlüssel und Werte; Werte als Text, Zahl, Wahrheitswert oder Liste von Texten
     */
    public static HarvesterConfiguration.Parsed overlay(HarvesterConfiguration current, Map<String, ?> values) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(values, "values");
        Properties properties = toProperties(current);
        List<ConfigurationProblem> problems = new ArrayList<>();
        // Zuerst die Basisschlüssel überlagerter Listen entfernen, damit key[0] die Liste ersetzt statt ergänzt.
        for (String name : values.keySet()) {
            var indexed = INDEXED.matcher(strip(name));
            if (indexed.matches() && LISTS.contains(indexed.group(1))) {
                properties.remove(indexed.group(1));
            }
        }
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = strip(entry.getKey());
            var indexed = INDEXED.matcher(key);
            var source = SOURCE_KEY.matcher(key);
            if (source.matches()) {
                // Einheitlich mit Punkt, damit ein Wert die gespeicherte Abweichung ersetzt statt sie zu verdoppeln.
                key = SourceSyncOverride.key(source.group(1) != null ? source.group(1) : source.group(2),
                        source.group(3));
            }
            boolean known = KEYS.contains(key) || indexed.matches() && LISTS.contains(indexed.group(1))
                    || source.matches();
            if (!known) {
                problems.add(new ConfigurationProblem(key, key.startsWith("network.")
                        ? "Proxy und TLS wechseln über die Konfigurationsquelle, nicht über diese Schnittstelle"
                        : "Unbekannter Schlüssel"));
                continue;
            }
            String text = text(entry.getValue());
            if (text == null) {
                problems.add(new ConfigurationProblem(key, "Erwartet Text, Zahl, true/false oder eine Liste von Texten"));
            } else if (text.isBlank()) {
                properties.remove(key);
            } else {
                properties.setProperty(key, text);
            }
        }
        if (!problems.isEmpty()) {
            return new HarvesterConfiguration.Parsed(null, problems);
        }
        HarvesterConfiguration.Parsed parsed = HarvesterConfiguration.fromProperties(properties);
        return parsed.valid()
                ? new HarvesterConfiguration.Parsed(parsed.configuration().withNetwork(current.network()), List.of())
                : parsed;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return String.join(",", list.stream().map(String.class::cast).toList());
        }
        return null;
    }

    /** Die gesetzten Felder einer Abweichung als Unterschlüssel → Wert. */
    static Map<String, String> fields(SourceSyncOverride o) {
        Map<String, String> fields = new LinkedHashMap<>();
        putField(fields, SourceSyncOverride.CHANGE_SEARCH_INTERVAL, o.changeSearchInterval());
        putField(fields, SourceSyncOverride.INVENTORY_INTERVAL, o.inventoryInterval());
        putField(fields, SourceSyncOverride.EVENTS, o.events());
        putField(fields, SourceSyncOverride.CATCH_UP_ON_START, o.catchUpOnStart());
        putField(fields, SourceSyncOverride.BEFORE_SEARCH, o.beforeSearch());
        putField(fields, SourceSyncOverride.MODE, o.mode() == null ? null : o.mode().key());
        putField(fields, SourceSyncOverride.REFRESH_STRATEGY, o.refreshStrategy() == null ? null
                : o.refreshStrategy().key());
        putField(fields, SourceSyncOverride.MAX_AGE, o.maxAge());
        putField(fields, SourceSyncOverride.WITHHELD_RETENTION, o.withheldRetention());
        putField(fields, SourceSyncOverride.BACKGROUND_FILL, o.backgroundFill());
        return fields;
    }

    private static void putField(Map<String, String> fields, String field, Object value) {
        if (value != null) {
            fields.put(field, value.toString());
        }
    }

    private static String strip(String name) {
        return name.startsWith(HarvesterConfiguration.PREFIX) ? name.substring(HarvesterConfiguration.PREFIX.length())
                : name;
    }

    private static void put(Properties properties, String key, Object value) {
        if (value != null) {
            properties.setProperty(key, value.toString());
        }
    }
}
