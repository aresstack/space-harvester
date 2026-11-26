package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import com.aresstack.spaceharvester.application.configuration.AnalyzerSettings;
import com.aresstack.spaceharvester.application.configuration.CheckSettings;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import com.aresstack.spaceharvester.application.configuration.TermSettings;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
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
    static final Set<String> KEYS = withGlossaryRules(true, Set.of(
            "ai." + AiServiceSettings.BASE_URL, "ai." + AiServiceSettings.API_KEY,
            "ai." + AiServiceSettings.CHAT_MODEL, "ai." + AiServiceSettings.EMBEDDING_MODEL,
            "ai." + AiServiceSettings.EMBEDDING_DIMENSIONS, "ai." + AiServiceSettings.TIMEOUT,
            "ai." + AiServiceSettings.TRUST_BUNDLE, "ai." + AiServiceSettings.EXTERNAL_PROCESSING_SOURCES,
            "ai." + AiServiceSettings.RERANK_MODEL, "ai." + AiServiceSettings.VISION_MODEL,
            "ai." + AiServiceSettings.CHAT_MAX_TOKENS, "ai." + AiServiceSettings.VISION_MAX_TOKENS,
            "ai." + AiServiceSettings.IMAGE_MODEL, "ai." + AiServiceSettings.IMAGE_MAX_TOKENS,
            "ai." + AiServiceSettings.VISION_THINKING, "ai." + AiServiceSettings.IMAGE_THINKING,
            "ai." + AiServiceSettings.SUMMARY_MODEL, "ai." + AiServiceSettings.SUMMARY_MAX_TOKENS,
            "ai." + AiServiceSettings.SUMMARY_THINKING, "ai." + AiServiceSettings.PODCAST_MODEL,
            "ai." + AiServiceSettings.PODCAST_MAX_TOKENS, "ai." + AiServiceSettings.PODCAST_THINKING,
            "ai." + AiServiceSettings.SUMMARY_PLAN_MODEL, "ai." + AiServiceSettings.SUMMARY_PLAN_MAX_TOKENS,
            "ai." + AiServiceSettings.SUMMARY_PLAN_THINKING, "ai." + AiServiceSettings.IMAGE_PLAN_MODEL,
            "ai." + AiServiceSettings.IMAGE_PLAN_MAX_TOKENS, "ai." + AiServiceSettings.IMAGE_PLAN_THINKING,
            "ai." + AiServiceSettings.DEADLINE_MODEL, "ai." + AiServiceSettings.DEADLINE_MAX_TOKENS,
            "ai." + AiServiceSettings.DEADLINE_THINKING, "ai." + AiServiceSettings.PODCAST_SPEECH_MODEL,
            "ai." + AiServiceSettings.PODCAST_VOICE_HOST, "ai." + AiServiceSettings.PODCAST_VOICE_COHOST,
            "ai." + AiServiceSettings.READ_ALOUD_MODEL, "ai." + AiServiceSettings.READ_ALOUD_VOICE,
            "ai." + AiServiceSettings.PODCAST_PAUSE_MILLIS, "ai." + AiServiceSettings.PODCAST_SPEECH_ATTEMPTS,
            "ai." + AiServiceSettings.PODCAST_SPEECH_WORKERS,
            SearchSettings.SEMANTIC, SearchSettings.RERANK, SearchSettings.PREFETCH_HITS,
            SearchSettings.CACHE_MAX_AGE, SearchSettings.CACHE_VERSION_CHECK, SearchSettings.CACHE_REFRESH_STRATEGY,
            SyncSettings.EVENTS, SyncSettings.INTERVAL, SyncSettings.SPACES, SyncSettings.BEFORE_SEARCH,
            SyncSettings.WITHHELD_RETENTION, SyncSettings.CATCH_UP_ON_START, SyncSettings.MODE,
            SyncSettings.INVENTORY_INTERVAL, SyncSettings.BACKGROUND_FILL, SyncSettings.BACKGROUND_PARALLELISM,
            StorageSettings.DATA_DIRECTORY, LocalFileSettings.ALLOW_ALL, LocalFileSettings.ROOTS,
            ExportSettings.ATTACHMENTS_ENABLED, ExportSettings.ATTACHMENTS_MEDIA_TYPES,
            ExportSettings.ATTACHMENTS_MAX_SIZE, TermSettings.SYNONYMS_MAX_TERMS,
            TermSettings.SYNONYMS_MAX_EMBEDDED_TERMS, TermSettings.SYNONYMS_MAX_JUDGED_PAIRS,
            TermSettings.SYNONYMS_AUTOMATIC, TermSettings.SYNONYMS_GLOSSARY_TERMS_ONLY,
            TermSettings.GLOSSARY_AUTOMATIC,
            EnrichmentSettings.DOCUMENTS_ENABLED, EnrichmentSettings.DOCUMENTS_EMBEDDING_MAX_CHARS,
            SimplificationSettings.MODE, SimplificationSettings.PARALLEL, SimplificationSettings.MODEL,
            StandingSettings.OUTDATED_WORDS, StandingSettings.DRAFT_WORDS, CheckSettings.SCOPE, CheckSettings.PARALLEL,
            CheckSettings.MODEL, ImageSettings.AUTOMATIC, ImageSettings.MAX_PER_RUN, AnalyzerSettings.ANALYZER,
            AnalyzerSettings.MODEL, SimplificationSettings.MAX_TOKENS, CheckSettings.MAX_TOKENS,
            AnalyzerSettings.MAX_TOKENS, SimplificationSettings.THINKING, CheckSettings.THINKING,
            AnalyzerSettings.THINKING));

    /** Listen dürfen auch als {@code key[0]}, {@code key[1]}, … kommen, wie Spring sie bindet. */
    private static final Set<String> LISTS = withGlossaryRules(false, Set.of(
            "ai." + AiServiceSettings.EXTERNAL_PROCESSING_SOURCES,
            SyncSettings.SPACES, LocalFileSettings.ROOTS, ExportSettings.ATTACHMENTS_MEDIA_TYPES,
            StandingSettings.OUTDATED_WORDS, StandingSettings.DRAFT_WORDS));

    /** Dazu die Schlüssel der Begriffsableitung (ADR 0032, FL5b): Wortlisten je Art, mit {@code all} die Höchstzahl. */
    private static Set<String> withGlossaryRules(boolean all, Set<String> keys) {
        Set<String> result = new java.util.HashSet<>(keys);
        if (all) {
            result.add(GlossaryRuleSettings.MAX_TERMS);
        }
        for (GlossaryRuleSettings.Kind kind : GlossaryRuleSettings.Kind.values()) {
            result.add(kind.addKey());
            result.add(kind.excludeKey());
        }
        return Set.copyOf(result);
    }

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
        put(properties, "ai." + AiServiceSettings.CHAT_MAX_TOKENS, ai.chatMaxTokens());
        put(properties, "ai." + AiServiceSettings.VISION_MAX_TOKENS, ai.visionMaxTokens());
        put(properties, "ai." + AiServiceSettings.IMAGE_MODEL, ai.imageModel());
        put(properties, "ai." + AiServiceSettings.IMAGE_MAX_TOKENS, ai.imageMaxTokens());
        put(properties, "ai." + AiServiceSettings.VISION_THINKING, ai.visionThinking());
        put(properties, "ai." + AiServiceSettings.IMAGE_THINKING, ai.imageThinking());
        put(properties, "ai." + AiServiceSettings.SUMMARY_MODEL, ai.summaryUse().model());
        put(properties, "ai." + AiServiceSettings.SUMMARY_MAX_TOKENS, ai.summaryUse().maxTokens());
        put(properties, "ai." + AiServiceSettings.SUMMARY_THINKING, ai.summaryUse().thinking());
        put(properties, "ai." + AiServiceSettings.PODCAST_MODEL, ai.podcastUse().model());
        put(properties, "ai." + AiServiceSettings.PODCAST_MAX_TOKENS, ai.podcastUse().maxTokens());
        put(properties, "ai." + AiServiceSettings.PODCAST_THINKING, ai.podcastUse().thinking());
        put(properties, "ai." + AiServiceSettings.SUMMARY_PLAN_MODEL, ai.summaryPlanUse().model());
        put(properties, "ai." + AiServiceSettings.SUMMARY_PLAN_MAX_TOKENS, ai.summaryPlanUse().maxTokens());
        put(properties, "ai." + AiServiceSettings.SUMMARY_PLAN_THINKING, ai.summaryPlanUse().thinking());
        put(properties, "ai." + AiServiceSettings.IMAGE_PLAN_MODEL, ai.imagePlanUse().model());
        put(properties, "ai." + AiServiceSettings.IMAGE_PLAN_MAX_TOKENS, ai.imagePlanUse().maxTokens());
        put(properties, "ai." + AiServiceSettings.IMAGE_PLAN_THINKING, ai.imagePlanUse().thinking());
        put(properties, "ai." + AiServiceSettings.DEADLINE_MODEL, ai.deadlineUse().model());
        put(properties, "ai." + AiServiceSettings.DEADLINE_MAX_TOKENS, ai.deadlineUse().maxTokens());
        put(properties, "ai." + AiServiceSettings.DEADLINE_THINKING, ai.deadlineUse().thinking());
        put(properties, "ai." + AiServiceSettings.PODCAST_SPEECH_MODEL, ai.speech().podcastModel());
        put(properties, "ai." + AiServiceSettings.PODCAST_VOICE_HOST, ai.speech().podcastHost());
        put(properties, "ai." + AiServiceSettings.PODCAST_VOICE_COHOST, ai.speech().podcastCohost());
        put(properties, "ai." + AiServiceSettings.READ_ALOUD_MODEL, ai.speech().readAloudModel());
        put(properties, "ai." + AiServiceSettings.READ_ALOUD_VOICE, ai.speech().readAloudVoice());
        put(properties, "ai." + AiServiceSettings.PODCAST_PAUSE_MILLIS, ai.speech().podcastPauseMillis());
        put(properties, "ai." + AiServiceSettings.PODCAST_SPEECH_ATTEMPTS, ai.speech().podcastAttempts());
        put(properties, "ai." + AiServiceSettings.PODCAST_SPEECH_WORKERS, ai.speech().podcastWorkers());
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
        put(properties, LocalFileSettings.ALLOW_ALL, configuration.localFiles().allowAll());
        if (!configuration.localFiles().roots().isEmpty()) {
            put(properties, LocalFileSettings.ROOTS, String.join(LocalFileSettings.SEPARATOR,
                    configuration.localFiles().roots().stream().map(Object::toString).toList()));
        }
        ExportSettings export = configuration.export();
        put(properties, ExportSettings.ATTACHMENTS_ENABLED, export.attachments());
        if (!export.mediaTypes().isEmpty()) {
            put(properties, ExportSettings.ATTACHMENTS_MEDIA_TYPES, String.join(",", export.mediaTypes()));
        }
        put(properties, ExportSettings.ATTACHMENTS_MAX_SIZE, export.maxSize());
        put(properties, TermSettings.SYNONYMS_MAX_TERMS, configuration.terms().synonyms().maxTerms());
        put(properties, TermSettings.SYNONYMS_MAX_EMBEDDED_TERMS, configuration.terms().synonyms().maxEmbeddedTerms());
        put(properties, TermSettings.SYNONYMS_MAX_JUDGED_PAIRS, configuration.terms().synonyms().maxJudgedPairs());
        put(properties, TermSettings.SYNONYMS_AUTOMATIC, configuration.terms().synonyms().automatic());
        put(properties, TermSettings.SYNONYMS_GLOSSARY_TERMS_ONLY,
                configuration.terms().synonyms().glossaryTermsOnly());
        put(properties, TermSettings.GLOSSARY_AUTOMATIC, configuration.terms().glossaryAutomatic());
        put(properties, TermSettings.GLOSSARY_APPROVED, configuration.terms().glossaryApproved());
        put(properties, EnrichmentSettings.DOCUMENTS_ENABLED, configuration.enrichment().documents());
        put(properties, EnrichmentSettings.DOCUMENTS_EMBEDDING_MAX_CHARS,
                configuration.enrichment().documentEmbeddingMaxChars());
        put(properties, SimplificationSettings.MODE, configuration.simplification().mode().value());
        put(properties, SimplificationSettings.PARALLEL, configuration.simplification().parallel());
        put(properties, SimplificationSettings.MODEL, configuration.simplification().model());
        put(properties, StandingSettings.OUTDATED_WORDS, String.join(",",
                StandingSettings.written(configuration.standing().outdatedWords())));
        put(properties, StandingSettings.DRAFT_WORDS, String.join(",",
                StandingSettings.written(configuration.standing().draftWords())));
        put(properties, CheckSettings.SCOPE, configuration.check().scope().value());
        put(properties, CheckSettings.PARALLEL, configuration.check().parallel());
        put(properties, CheckSettings.MODEL, configuration.check().model());
        put(properties, ImageSettings.AUTOMATIC, configuration.images().automatic());
        put(properties, ImageSettings.MAX_PER_RUN, configuration.images().maxPerRun());
        put(properties, AnalyzerSettings.ANALYZER, configuration.analyzer().analyzer().value());
        put(properties, AnalyzerSettings.MODEL, configuration.analyzer().model());
        put(properties, SimplificationSettings.MAX_TOKENS, configuration.simplification().maxTokens());
        put(properties, CheckSettings.MAX_TOKENS, configuration.check().maxTokens());
        put(properties, AnalyzerSettings.MAX_TOKENS, configuration.analyzer().maxTokens());
        put(properties, SimplificationSettings.THINKING, configuration.simplification().thinking());
        put(properties, CheckSettings.THINKING, configuration.check().thinking());
        put(properties, AnalyzerSettings.THINKING, configuration.analyzer().thinking());
        GlossaryRuleSettings rules = configuration.terms().glossaryRules();
        put(properties, GlossaryRuleSettings.MAX_TERMS, rules.maxTerms());
        for (GlossaryRuleSettings.Kind kind : GlossaryRuleSettings.Kind.values()) {
            if (!rules.added(kind).isEmpty()) {
                put(properties, kind.addKey(), String.join(",", rules.added(kind)));
            }
            if (!rules.excluded(kind).isEmpty()) {
                put(properties, kind.excludeKey(), String.join(",", rules.excluded(kind)));
            }
        }
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
            // Ordner trennt ; wie im Client, weil Kommas in Pfaden vorkommen.
            String text = text(entry.getValue(), key.equals(LocalFileSettings.ROOTS) ? LocalFileSettings.SEPARATOR
                    : ",");
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

    private static String text(Object value, String separator) {
        if (value == null) {
            return "";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return String.join(separator, list.stream().map(String.class::cast).toList());
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
        putField(fields, SourceSyncOverride.PREFETCH_HITS, o.prefetchHits());
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
