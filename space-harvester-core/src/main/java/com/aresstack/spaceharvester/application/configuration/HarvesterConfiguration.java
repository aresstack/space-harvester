package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Konfiguration, die zur Laufzeit wechseln kann (ADR 0015): ein unveränderlicher Wert. Die Modelle (Embeddings,
 * Reranker) und der KI-Dienst gelten global für alle Quellen. Quellen selbst wechseln über
 * {@code Harvester.sourceManagement()}, das ihre Konnektoren schon zur Laufzeit austauscht.
 *
 * @param ai      KI-Dienst, Modelle je Funktion, API-Key
 * @param search  Suchmodus und -optionen
 * @param sync    Synchronisierungsstrategie und Zeitplan
 * @param network Proxy und TLS
 * @param storage Speicherort; nur nach Neustart wirksam
 */
public record HarvesterConfiguration(AiServiceSettings ai, SearchSettings search, SyncSettings sync,
                                     NetworkSettings network, StorageSettings storage) {

    /** Präfix aller Properties in Server und App. */
    public static final String PREFIX = "space-harvester.";

    /** Ohne KI-Dienst, Schlagwortsuche, bedarfsgesteuerter Abgleich, ohne Proxy. */
    public static final HarvesterConfiguration DEFAULT = new HarvesterConfiguration(AiServiceSettings.NONE,
            SearchSettings.DEFAULT, SyncSettings.DEFAULT, NetworkSettings.DEFAULT, StorageSettings.DEFAULT);

    public HarvesterConfiguration {
        ai = ai == null ? AiServiceSettings.NONE : ai;
        search = search == null ? SearchSettings.DEFAULT : search;
        sync = sync == null ? SyncSettings.DEFAULT : sync;
        network = network == null ? NetworkSettings.DEFAULT : network;
        storage = storage == null ? StorageSettings.DEFAULT : storage;
    }

    /** Ob die semantische Suche tatsächlich läuft: Haken gesetzt und Embedding-Modell eingestellt. */
    public boolean semanticSearch() {
        return search.semantic() && ai.embeddings();
    }

    /** Ob Treffer umgeordnet werden: Option an und Rerank-Modell eingestellt. */
    public boolean reranking() {
        return search.rerank() && ai.reranking();
    }

    /** Die wirksamen Synchronisierungsregeln einer Quelle: global, überschrieben von ihren Abweichungen. */
    public SyncSettings syncFor(String sourceId) {
        return sync.forSource(sourceId);
    }

    /**
     * Die Aktualität vorhandener Inhalte einer Quelle: {@link SearchSettings#cacheFreshness()}, überschrieben von
     * {@link SourceSyncOverride#refreshStrategy()} und {@link SourceSyncOverride#maxAge()}. Eine zeitbasierte
     * Strategie ohne eigenes Zeitfenster nimmt das globale.
     */
    public CacheFreshness freshnessFor(String sourceId) {
        SourceSyncOverride o = sync.override(sourceId);
        CacheFreshness global = search.cacheFreshness();
        if (o.refreshStrategy() == null && o.maxAge() == null) {
            return global;
        }
        RefreshStrategy strategy = o.refreshStrategy() != null ? o.refreshStrategy() : search.refreshStrategy();
        return strategy.freshness(o.maxAge() != null ? o.maxAge() : global.maxAge());
    }

    public HarvesterConfiguration withAi(AiServiceSettings value) {
        return new HarvesterConfiguration(value, search, sync, network, storage);
    }

    public HarvesterConfiguration withSearch(SearchSettings value) {
        return new HarvesterConfiguration(ai, value, sync, network, storage);
    }

    public HarvesterConfiguration withSync(SyncSettings value) {
        return new HarvesterConfiguration(ai, search, value, network, storage);
    }

    public HarvesterConfiguration withNetwork(NetworkSettings value) {
        return new HarvesterConfiguration(ai, search, sync, value, storage);
    }

    public HarvesterConfiguration withStorage(StorageSettings value) {
        return new HarvesterConfiguration(ai, search, sync, network, value);
    }

    /**
     * Prüft Abhängigkeiten zwischen Feldern, die ein einzelner Wert nicht sieht, etwa ein Embedding-Modell ohne
     * Dienst. Werte, die ein Record schon im Konstruktor ablehnt, kommen hier nicht mehr an.
     *
     * @return leer, wenn die Konfiguration aktiviert werden kann
     */
    public List<ConfigurationProblem> validate() {
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (ai.embeddingModel() != null && !ai.configured()) {
            problems.add(new ConfigurationProblem("ai." + AiServiceSettings.BASE_URL,
                    "Das Embedding-Modell braucht die Adresse des KI-Dienstes"));
        }
        if (ai.embeddingModel() != null && ai.embeddingDimensions() == null) {
            problems.add(new ConfigurationProblem("ai." + AiServiceSettings.EMBEDDING_DIMENSIONS,
                    "Das Embedding-Modell braucht die Länge seiner Vektoren; die Einstellungen messen sie mit "
                            + "„Modell prüfen“"));
        }
        if (ai.embeddingModel() == null && ai.embeddingDimensions() != null) {
            problems.add(new ConfigurationProblem("ai." + AiServiceSettings.EMBEDDING_MODEL,
                    "Eine Vektorlänge ohne Embedding-Modell"));
        }
        if (ai.rerankModel() != null && !ai.configured()) {
            problems.add(new ConfigurationProblem("ai." + AiServiceSettings.BASE_URL,
                    "Das Rerank-Modell braucht die Adresse des KI-Dienstes"));
        }
        if (ai.chatModel() != null && !ai.configured()) {
            problems.add(new ConfigurationProblem("ai." + AiServiceSettings.BASE_URL,
                    "Das Chat-Modell braucht die Adresse des KI-Dienstes"));
        }
        if (sync.interval() != null && sync.interval().compareTo(SyncSettings.MIN_INTERVAL) < 0) {
            problems.add(new ConfigurationProblem(SyncSettings.INTERVAL, "Der Abstand muss mindestens eine Minute "
                    + "betragen"));
        }
        if (sync.withheldRetention().isNegative()) {
            problems.add(new ConfigurationProblem(SyncSettings.WITHHELD_RETENTION, "Die Frist darf nicht negativ sein"));
        }
        intervalProblem(problems, SyncSettings.INVENTORY_INTERVAL, sync.inventoryInterval());
        for (var entry : sync.sources().entrySet()) {
            String id = entry.getKey();
            SourceSyncOverride o = entry.getValue();
            if (id.isBlank() || !id.equals(id.strip()) || id.contains(".")) {
                problems.add(new ConfigurationProblem(SyncSettings.SOURCES, "Ungültige Quellen-ID „" + id + "“"));
                continue;
            }
            intervalProblem(problems, SourceSyncOverride.key(id, SourceSyncOverride.CHANGE_SEARCH_INTERVAL),
                    o.changeSearchInterval());
            intervalProblem(problems, SourceSyncOverride.key(id, SourceSyncOverride.INVENTORY_INTERVAL),
                    o.inventoryInterval());
            if (o.withheldRetention() != null && o.withheldRetention().isNegative()) {
                problems.add(new ConfigurationProblem(SourceSyncOverride.key(id, SourceSyncOverride.WITHHELD_RETENTION),
                        "Die Frist darf nicht negativ sein"));
            }
            try {
                freshnessFor(id);
            } catch (IllegalArgumentException e) {
                problems.add(new ConfigurationProblem(SourceSyncOverride.key(id, SourceSyncOverride.MAX_AGE),
                        "Die zeitbasierte Aktualisierung braucht ein Zeitfenster zwischen 0 und 365 Tagen"));
            }
        }
        for (String space : sync.spaces()) {
            int slash = space.indexOf('/');
            if (slash < 1 || slash == space.length() - 1 || space.indexOf('/', slash + 1) >= 0) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES,
                        "Jeder Eintrag hat die Form <Quelle>/<Space-Key>, nicht „" + space + "“"));
            }
        }
        return problems.stream().distinct().toList();
    }

    private static void intervalProblem(List<ConfigurationProblem> problems, String key, Duration interval) {
        if (interval != null && interval.compareTo(SyncSettings.MIN_INTERVAL) < 0) {
            problems.add(new ConfigurationProblem(key, "Der Abstand muss mindestens eine Minute betragen"));
        }
    }

    /**
     * Liest KI-Dienst, Suche, Synchronisierung und Speicherort aus flachen Properties mit oder ohne
     * {@link #PREFIX}, so wie Spring sie bindet und die App sie in ihre YAML-Datei schreibt. Proxy und TLS übergeben
     * die Adapter typisiert ({@link #withNetwork}), weil Server und App sie schon so lesen. Anders als die einzelnen
     * Records sammelt das alle Fehler statt beim ersten abzubrechen.
     */
    public static Parsed fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        List<ConfigurationProblem> problems = new ArrayList<>();
        AiServiceSettings ai = AiServiceSettings.NONE;
        Properties aiProperties = new Properties();
        for (String name : properties.stringPropertyNames()) {
            String key = strip(name);
            if (key.startsWith("ai.")) {
                String value = properties.getProperty(name);
                if (key.equals("ai." + AiServiceSettings.TIMEOUT) && value != null) {
                    // Spring-Kurzform wie 45000ms, die auch die App schreibt; AiServiceSettings liest nur ISO-8601.
                    Duration simple = Reader.simpleDuration(value.strip());
                    value = simple == null ? value : simple.toString();
                }
                aiProperties.setProperty(key.substring(3), value);
            }
        }
        try {
            ai = AiServiceSettings.fromProperties(aiProperties);
        } catch (IllegalArgumentException e) {
            problems.add(aiProblem(e));
        }
        Reader read = new Reader(properties, problems);
        CacheFreshness freshness = CacheFreshness.DEFAULT;
        Duration maxAge = read.duration(SearchSettings.CACHE_MAX_AGE);
        boolean versionCheck = read.bool(SearchSettings.CACHE_VERSION_CHECK, CacheFreshness.DEFAULT.versionCheck());
        String strategyValue = read.value(SearchSettings.CACHE_REFRESH_STRATEGY);
        RefreshStrategy strategy = strategyValue == null ? null : RefreshStrategy.parse(strategyValue);
        if (strategyValue != null && strategy == null) {
            problems.add(new ConfigurationProblem(SearchSettings.CACHE_REFRESH_STRATEGY, "Erwartet "
                    + "always-check-version, time-based oder time-based-with-version-check"));
        }
        try {
            freshness = strategy != null ? strategy.freshness(maxAge)
                    : new CacheFreshness(maxAge == null ? CacheFreshness.DEFAULT.maxAge() : maxAge, versionCheck);
        } catch (IllegalArgumentException e) {
            problems.add(new ConfigurationProblem(SearchSettings.CACHE_MAX_AGE, strategy != null && maxAge == null
                    ? "Die zeitbasierte Aktualisierung braucht ein Zeitfenster"
                    : "Das Zeitfenster muss zwischen 0 und 365 Tagen liegen"));
        }
        SearchSettings search = new SearchSettings(read.bool(SearchSettings.SEMANTIC, SearchSettings.DEFAULT.semantic()),
                read.bool(SearchSettings.RERANK, SearchSettings.DEFAULT.rerank()),
                read.bool(SearchSettings.PREFETCH_HITS, SearchSettings.DEFAULT.prefetchHits()), freshness);
        SyncSettings sync = new SyncSettings(read.bool(SyncSettings.EVENTS, false), read.duration(SyncSettings.INTERVAL),
                read.list(SyncSettings.SPACES), read.bool(SyncSettings.BEFORE_SEARCH, false),
                read.duration(SyncSettings.WITHHELD_RETENTION), read.bool(SyncSettings.CATCH_UP_ON_START, false),
                read.mode(SyncSettings.MODE), read.duration(SyncSettings.INVENTORY_INTERVAL),
                sourceOverrides(properties, problems));
        StorageSettings storage = new StorageSettings(read.path(StorageSettings.DATA_DIRECTORY));
        HarvesterConfiguration configuration = new HarvesterConfiguration(ai, search, sync, NetworkSettings.DEFAULT,
                storage);
        problems.addAll(configuration.validate());
        List<ConfigurationProblem> all = problems.stream().distinct().toList();
        return new Parsed(all.isEmpty() ? configuration : null, all);
    }

    /**
     * Ergebnis von {@link #fromProperties}.
     *
     * @param configuration die gelesene Konfiguration oder {@code null}, wenn es Probleme gibt
     * @param problems      alle Probleme, leer bei Erfolg
     */
    public record Parsed(HarvesterConfiguration configuration, List<ConfigurationProblem> problems) {

        public Parsed {
            problems = List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }

        /** Die Konfiguration oder eine {@link ConfigurationRejectedException} mit allen Problemen. */
        public HarvesterConfiguration orThrow() {
            if (!valid()) {
                throw new ConfigurationRejectedException(problems);
            }
            return configuration;
        }
    }

    private static final Pattern SOURCE_KEY = Pattern.compile(
            "sync\\.sources(?:\\.([^.\\[\\]]+)|\\[([^\\]]+)])\\.([a-z-]+)");

    /** {@code sync.sources.<Quelle>.<Feld>} und {@code sync.sources[<Quelle>].<Feld>}, wie Spring Maps bindet. */
    private static Map<String, SourceSyncOverride> sourceOverrides(Properties properties,
                                                                   List<ConfigurationProblem> problems) {
        Map<String, Properties> bySource = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            Matcher matcher = SOURCE_KEY.matcher(strip(name));
            if (matcher.matches()) {
                String id = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                bySource.computeIfAbsent(id, k -> new Properties())
                        .setProperty(SourceSyncOverride.key(id, matcher.group(3)), properties.getProperty(name));
            } else if (strip(name).startsWith(SyncSettings.SOURCES + ".")
                    || strip(name).startsWith(SyncSettings.SOURCES + "[")) {
                problems.add(new ConfigurationProblem(strip(name), "Erwartet sync.sources.<Quelle>.<Feld>"));
            }
        }
        Map<String, SourceSyncOverride> overrides = new LinkedHashMap<>();
        for (var entry : bySource.entrySet()) {
            String id = entry.getKey();
            Reader read = new Reader(entry.getValue(), problems);
            for (String key : entry.getValue().stringPropertyNames()) {
                String field = key.substring(SourceSyncOverride.key(id, "").length());
                if (!SOURCE_FIELDS.contains(field)) {
                    problems.add(new ConfigurationProblem(key, "Unbekannte Einstellung je Quelle"));
                }
            }
            SourceSyncOverride override = new SourceSyncOverride(
                    read.duration(SourceSyncOverride.key(id, SourceSyncOverride.CHANGE_SEARCH_INTERVAL)),
                    read.duration(SourceSyncOverride.key(id, SourceSyncOverride.INVENTORY_INTERVAL)),
                    read.optionalBool(SourceSyncOverride.key(id, SourceSyncOverride.EVENTS)),
                    read.optionalBool(SourceSyncOverride.key(id, SourceSyncOverride.CATCH_UP_ON_START)),
                    read.optionalBool(SourceSyncOverride.key(id, SourceSyncOverride.BEFORE_SEARCH)),
                    read.value(SourceSyncOverride.key(id, SourceSyncOverride.MODE)) == null ? null
                            : read.mode(SourceSyncOverride.key(id, SourceSyncOverride.MODE)),
                    read.strategy(SourceSyncOverride.key(id, SourceSyncOverride.REFRESH_STRATEGY)),
                    read.duration(SourceSyncOverride.key(id, SourceSyncOverride.MAX_AGE)),
                    read.duration(SourceSyncOverride.key(id, SourceSyncOverride.WITHHELD_RETENTION)));
            if (!override.equals(SourceSyncOverride.NONE)) {
                overrides.put(id, override);
            }
        }
        return overrides;
    }

    private static final Set<String> SOURCE_FIELDS = Set.of(SourceSyncOverride.CHANGE_SEARCH_INTERVAL,
            SourceSyncOverride.INVENTORY_INTERVAL, SourceSyncOverride.EVENTS, SourceSyncOverride.CATCH_UP_ON_START,
            SourceSyncOverride.BEFORE_SEARCH, SourceSyncOverride.MODE, SourceSyncOverride.REFRESH_STRATEGY,
            SourceSyncOverride.MAX_AGE, SourceSyncOverride.WITHHELD_RETENTION);

    private static String strip(String name) {
        return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
    }

    /** {@code space-harvester.ai.timeout muss …} wird zu Schlüssel {@code ai.timeout} und dem Rest des Satzes. */
    private static ConfigurationProblem aiProblem(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "Ungültiger Wert" : e.getMessage();
        if (message.startsWith(AiServiceSettings.PREFIX)) {
            int space = message.indexOf(' ');
            String key = strip(space < 0 ? message : message.substring(0, space));
            String rest = space < 0 ? "ist ungültig" : message.substring(space + 1);
            return new ConfigurationProblem(key, "Der Wert " + rest);
        }
        return new ConfigurationProblem("ai", message);
    }

    private record Reader(Properties properties, List<ConfigurationProblem> problems) {

        String value(String key) {
            String value = properties.getProperty(PREFIX + key, properties.getProperty(key));
            return value == null || value.isBlank() ? null : value.strip();
        }

        SyncMode mode(String key) {
            String value = value(key);
            SyncMode mode = value == null ? SyncMode.CATALOG_ONLY : SyncMode.parse(value);
            if (mode == null) {
                problems.add(new ConfigurationProblem(key, "Erwartet catalog-only, sync-cache oder full"));
                return null;
            }
            return mode;
        }

        Boolean optionalBool(String key) {
            return value(key) == null ? null : bool(key, false);
        }

        RefreshStrategy strategy(String key) {
            String value = value(key);
            if (value == null) {
                return null;
            }
            RefreshStrategy strategy = RefreshStrategy.parse(value);
            if (strategy == null) {
                problems.add(new ConfigurationProblem(key, "Erwartet always-check-version, time-based oder "
                        + "time-based-with-version-check"));
            }
            return strategy;
        }

        boolean bool(String key, boolean fallback) {
            String value = value(key);
            if (value == null) {
                return fallback;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> {
                    problems.add(new ConfigurationProblem(key, "Erwartet true oder false"));
                    yield fallback;
                }
            };
        }

        Duration duration(String key) {
            String value = value(key);
            if (value == null) {
                return null;
            }
            try {
                return Duration.parse(value);
            } catch (DateTimeParseException e) {
                Duration simple = simpleDuration(value);
                if (simple == null) {
                    problems.add(new ConfigurationProblem(key, "Erwartet eine Dauer wie PT6H, 30m oder 1d"));
                }
                return simple;
            }
        }

        List<String> list(String key) {
            String value = value(key);
            List<String> values = new ArrayList<>();
            if (value != null) {
                Arrays.stream(value.split(",")).map(String::strip).filter(v -> !v.isEmpty()).forEach(values::add);
            }
            // Spring bindet Listen auch als key[0], key[1], …
            for (int i = 0; ; i++) {
                String item = value(key + "[" + i + "]");
                if (item == null) {
                    break;
                }
                values.add(item);
            }
            return values;
        }

        Path path(String key) {
            String value = value(key);
            if (value == null) {
                return null;
            }
            try {
                return Path.of(value);
            } catch (InvalidPathException e) {
                problems.add(new ConfigurationProblem(key, "Kein gültiger Pfad"));
                return null;
            }
        }

        private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

        /** Kurzform wie in Spring: {@code 500ms}, {@code 30s}, {@code 10m}, {@code 6h}, {@code 1d}. */
        static Duration simpleDuration(String value) {
            var matcher = SIMPLE_DURATION.matcher(value);
            if (!matcher.matches()) {
                return null;
            }
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
    }
}
