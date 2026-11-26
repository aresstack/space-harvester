package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.configuration.ChangeLogSettings;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
import com.aresstack.spaceharvester.application.configuration.RefreshStrategy;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.configuration.TermSettings;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.application.terms.SynonymBudget;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.configuration.AnalyzerSettings;
import com.aresstack.spaceharvester.application.configuration.CheckSettings;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die YAML-Konfiguration der App. Schlüssel werden wie bei Spring locker verglichen ({@code base-url},
 * {@code baseUrl} und {@code base_url} sind gleich), Platzhalter {@code ${NAME:Standard}} kommen aus
 * Systemeigenschaften und Umgebung. Fehler einzelner Quellen verhindern den Start nicht: die Quelle fehlt dann, und
 * {@link AppSettings#problems()} sagt, was zu tun ist. Fehlt nur das Passwort oder Token einer Quelle, bleibt sie
 * erhalten und die App fragt es für die Sitzung ab.
 */
public final class AppSettingsLoader {

    /** Wurzel der Dienst-Schlüssel. */
    static final String ROOT = "space-harvester";
    /** Wurzel der App-eigenen Schlüssel. */
    static final String APP_ROOT = "space-harvester-app";

    /** Ein ganzer Wert {@code ${NAME}} oder {@code ${NAME:Standard}}; Gruppe 1 ist der Name. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::[^}]*)?}");
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(B|KB|MB|GB|TB)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    private final Placeholders placeholders;
    private final Path home;
    private final boolean windows;

    /**
     * @param home Standard-Basisverzeichnis der App ({@code ~/.space-harvester}); darunter liegen Daten und Konfiguration
     */
    public AppSettingsLoader(Placeholders placeholders, Path home) {
        this(placeholders, home, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** @param windows ob die App unter Windows läuft; bestimmt den Proxy ohne Angabe in der Datei */
    public AppSettingsLoader(Placeholders placeholders, Path home, boolean windows) {
        this.placeholders = placeholders;
        this.home = home;
        this.windows = windows;
    }

    /**
     * Proxy ohne Angabe in der Datei: unter Windows wie im Enterprise AI Client die PAC-Adresse der
     * Interneteinstellungen per PowerShell ({@code pac-url-powershell}), sonst ohne Proxy.
     */
    public ProxySettings defaultProxy() {
        return defaultProxy(windows);
    }

    /** Ob die App unter Windows läuft (Vorgaben für Proxy und Vertrauen neuer Quellen). */
    public boolean windows() {
        return windows;
    }

    static ProxySettings defaultProxy(boolean windows) {
        return windows ? new ProxySettings(ProxySettings.Mode.PAC_URL_POWERSHELL, null, null, null, null, null, null,
                null) : ProxySettings.disabled();
    }

    /** Standard-Basisverzeichnis {@code <user.home>/.space-harvester}. */
    public static Path defaultHome() {
        return Path.of(System.getProperty("user.home"), ".space-harvester");
    }

    /** Standard-Konfigurationsdatei {@code <home>/space-harvester-app.yml}. */
    public static Path defaultFile(Path home) {
        return home.resolve("space-harvester-app.yml");
    }

    /**
     * @param file YAML-Datei; fehlt sie, gelten die Standards ohne Quellen
     */
    public AppSettings load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return read(file, Map.of(), new ArrayList<>());
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(file, reader);
        } catch (IOException e) {
            List<String> problems = new ArrayList<>();
            problems.add("Die Konfigurationsdatei ist nicht lesbar: " + file + " (" + e.getClass().getSimpleName() + ")");
            return read(file, Map.of(), problems);
        }
    }

    /**
     * Liest einen Konfigurationstext, der (noch) nicht in {@code file} steht, z. B. den Entwurf des
     * Quellenformulars vor dem Speichern.
     */
    public AppSettings read(Path file, String text) {
        try {
            return parse(file, new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private AppSettings parse(Path file, Reader reader) throws IOException {
        List<String> problems = new ArrayList<>();
        try {
            Object document = yaml().load(reader);
            if (document == null) {
                return read(file, Map.of(), problems);
            }
            if (!(document instanceof Map<?, ?> map)) {
                problems.add("Die Konfigurationsdatei enthält keine Schlüssel (" + file + ")");
                return read(file, Map.of(), problems);
            }
            return read(file, map, problems);
        } catch (YAMLException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            // Die Meldung von SnakeYAML zitiert Zeilen der Datei; dort könnten Geheimnisse stehen.
            problems.add("Die Konfigurationsdatei ist kein gültiges YAML: " + file + yamlPosition(e));
        }
        return read(file, Map.of(), problems);
    }

    /** Liest ein bereits geladenes YAML-Dokument (für Tests und die Bearbeitung in der App). */
    public AppSettings read(Path file, Map<?, ?> document, List<String> problems) {
        Map<?, ?> root = map(get(document, ROOT));
        Map<?, ?> app = map(get(document, APP_ROOT));
        Map<?, ?> network = map(get(root, "network"));

        ProxySettings proxy = proxy(map(get(network, "proxy")), problems);
        ConfiguredSourceCatalog.AsyncTransport async = enumValue(ConfiguredSourceCatalog.AsyncTransport.class,
                text(get(network, "async-transport"), problems), ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT,
                "space-harvester.network.async-transport", problems);

        List<AppSettings.SourceEntry> sources = new ArrayList<>();
        Object list = get(root, "sources");
        if (list instanceof List<?> entries) {
            int index = 0;
            for (Object entry : entries) {
                AppSettings.SourceEntry source = source(map(entry), index++, problems);
                if (source != null) {
                    sources.add(source);
                }
            }
        } else if (list != null) {
            problems.add("space-harvester.sources muss eine Liste sein");
        }

        Path data = path(get(app, "data-directory"), problems);
        data = data == null ? home.resolve("data") : data;
        Path exports = path(get(map(get(root, "export")), "directory"), problems);
        Path assets = path(get(map(get(root, "assets")), "directory"), problems);
        // 0: ohne Grenze, wie AssetConfiguration des Dienstes (Angelo 11.11.2026).
        long maxAsset = size(get(map(get(root, "assets")), "max-size"), 0, "space-harvester.assets.max-size",
                problems);
        // Wie AssetConfiguration des Dienstes; die Wartezeit (large-file-wait) ist in der Bibliothek fest 30 s.
        long largeAsset = size(get(map(get(root, "assets")), "large-file-threshold"), 20L * 1024 * 1024,
                "space-harvester.assets.large-file-threshold", problems);
        int maxLargeAssets = Math.max(1, integer(get(map(get(root, "assets")), "max-concurrent-large-files"), 2,
                "space-harvester.assets.max-concurrent-large-files", problems));
        Duration searchTimeout = duration(get(map(get(root, "cache")), "search-timeout"), Duration.ofSeconds(5),
                "space-harvester.cache.search-timeout", problems);
        int maxTreeNodes = integer(get(map(get(root, "query")), "max-tree-nodes"), 50_000,
                "space-harvester.query.max-tree-nodes", problems);
        Path pdfFont = path(get(map(get(map(get(root, "export")), "pdf")), "font"), problems);
        long maxPage = size(get(map(get(root, "cache")), "max-page-size"), AppSettings.Storage.DEFAULT_MAX_PAGE_BYTES,
                "space-harvester.cache.max-page-size", problems);
        if (maxPage <= 0 || maxPage > AppSettings.Storage.MAX_PAGE_BYTES_LIMIT) {
            problems.add("space-harvester.cache.max-page-size liegt zwischen 1B und 2047MB: " + maxPage);
            maxPage = AppSettings.Storage.DEFAULT_MAX_PAGE_BYTES;
        }
        AppSettings.Storage storage = new AppSettings.Storage(data,
                exports == null ? data.resolve("exports") : exports, assets == null ? data.resolve("assets") : assets,
                maxAsset, largeAsset, maxLargeAssets, searchTimeout, maxTreeNodes, pdfFont,
                extraction(map(get(map(get(root, "assets")), "extraction")), problems), maxPage,
                size(get(map(get(root, "cache")), "max-text-file-size"), AppSettings.Storage.DEFAULT_MAX_TEXT_FILE_BYTES,
                        "space-harvester.cache.max-text-file-size", problems),
                duration(get(map(get(root, "cache")), "check-timeout"), AppSettings.Storage.DEFAULT_CHECK_TIMEOUT,
                        "space-harvester.cache.check-timeout", problems),
                duration(get(map(get(root, "cache")), "large-file-timeout"),
                        AppSettings.Storage.DEFAULT_LARGE_FILE_TIMEOUT, "space-harvester.cache.large-file-timeout",
                        problems),
                queryLimits(map(get(root, "query")), problems));

        if (get(root, "embedding") != null) {
            problems.add("space-harvester.embedding entfällt (ADR 0011); den KI-Dienst unter space-harvester.ai "
                    + "einrichten");
        }
        AppSettings.Cache cache = cache(map(get(root, "cache")), map(get(root, "sync")), problems);
        return new AppSettings(file, sources, proxy, async, trustStores(map(get(document, "spring")), problems),
                storage, jobLimits(map(get(root, "jobs")), problems), ai(map(get(root, "ai")), problems),
                path(get(app, "output-directory"), problems), problems, ids(get(app, "disabled-sources"), problems),
                tls(get(network, "tls"), problems), cache,
                flag(get(app, "setup-completed"), false, "space-harvester-app.setup-completed", problems),
                syncRules(map(get(map(get(root, "sync")), "sources")), cache.maxAge(), problems),
                sync(map(get(root, "sync")), cache, problems), aiAccess(app, problems),
                localFiles(map(get(root, "local-files")), problems), sourceKeePass(app, problems),
                inactiveRoots(get(app, "inactive-roots"), problems), terms(map(get(root, "terms")), map(get(root, "glossary")), problems),
                consoleOutput(get(map(get(map(get(document, "logging")), "threshold")), "console"), problems),
                enrichment(map(get(root, "enrichment")), problems), analytics(map(get(app, "analytics")), problems),
                simplification(map(get(map(get(root, "statements")), "simplification")), problems),
                standing(map(get(map(get(root, "statements")), "standing")), problems),
                check(map(get(map(get(root, "statements")), "check")), problems),
                viewerBackground(get(map(get(app, "viewer")), "background"), problems),
                video(map(get(app, "video")), problems),
                whisperModel(get(map(get(app, "whisper")), "model"), com.aresstack.spaceharvester.app.transcription.WhisperModel.KEY, problems),
                images(map(get(root, "images")), problems),
                whisperModel(get(map(get(app, "whisper")), "dictation-model"), com.aresstack.spaceharvester.app.transcription.WhisperModel.DICTATION_KEY, problems),
                analyzer(map(get(root, "statements")), problems));
    }

    /**
     * {@code space-harvester.images.*}: automatische Bildbeschreibung nach dem Abgleich (Fahrplan B1), wie beim Dienst
     * ({@link ImageSettings}). Ein unlesbarer Wert ist ein Problem und lässt die Vorgabe stehen.
     */
    private ImageSettings images(Map<?, ?> images, List<String> problems) {
        boolean automatic = flag(get(images, "automatic"), ImageSettings.DEFAULT.automatic(),
                ROOT + "." + ImageSettings.AUTOMATIC, problems);
        String maxKey = ROOT + "." + ImageSettings.MAX_PER_RUN;
        long max = longNumber(get(images, "max-per-run"), ImageSettings.DEFAULT_MAX_PER_RUN, maxKey, problems);
        if (max < 0 || max > Integer.MAX_VALUE) {
            problems.add(maxKey + ": Erwartet 0 (unbegrenzt) oder eine positive ganze Zahl");
            max = ImageSettings.DEFAULT_MAX_PER_RUN;
        }
        return new ImageSettings(automatic, (int) max);
    }

    /**
     * {@code space-harvester.statements.analyzer} und {@code analyzer-model}: Aussagenanalyse (Fahrplan A2/A3), wie
     * beim Dienst ({@link AnalyzerSettings}). Ein unbekannter Analysator ist ein Problem und lässt den MaltParser stehen.
     */
    private AnalyzerSettings analyzer(Map<?, ?> statements, List<String> problems) {
        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice choice =
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER;
        Object value = get(statements, "analyzer");
        try {
            choice = com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.parse(
                    value == null ? null : value.toString());
        } catch (IllegalArgumentException e) {
            problems.add(ROOT + "." + AnalyzerSettings.ANALYZER + ": " + e.getMessage());
        }
        Object model = get(statements, "analyzer-model");
        return new AnalyzerSettings(choice, model == null ? null : model.toString(),
                tokenLength(get(statements, "analyzer-max-tokens"), ROOT + "." + AnalyzerSettings.MAX_TOKENS,
                        problems), thinkingLevel(get(statements, "analyzer-thinking"),
                        ROOT + "." + AnalyzerSettings.THINKING, problems));
    }

    /**
     * {@code space-harvester-app.whisper.model} (Transkripte) und {@code .dictation-model} (Spracheingabe im Chat): ein
     * unbekannter Wert ist ein Problem und lässt Tiny stehen.
     */
    private com.aresstack.spaceharvester.app.transcription.WhisperModel whisperModel(Object raw, String key,
                                                                                    List<String> problems) {
        if (raw == null || raw.toString().isBlank()) {
            return com.aresstack.spaceharvester.app.transcription.WhisperModel.DEFAULT;
        }
        com.aresstack.spaceharvester.app.transcription.WhisperModel model =
                com.aresstack.spaceharvester.app.transcription.WhisperModel.of(raw.toString());
        if (model == null) {
            problems.add(key + " kennt „" + raw
                    + "“ nicht; es gilt " + com.aresstack.spaceharvester.app.transcription.WhisperModel.DEFAULT.id());
            return com.aresstack.spaceharvester.app.transcription.WhisperModel.DEFAULT;
        }
        return model;
    }

    /**
     * {@code space-harvester.query.max-page-limit} und {@code max-term-scan}: Stapelgrößen der Abfragen wie
     * {@code QueryConfiguration} des Dienstes. Ein Wert unter 1 ist ein Problem und lässt die Vorgabe stehen.
     */
    private com.aresstack.spaceharvester.application.query.QueryLimits queryLimits(Map<?, ?> query,
                                                                                   List<String> problems) {
        var d = com.aresstack.spaceharvester.application.query.QueryLimits.DEFAULTS;
        int page = integer(get(query, "max-page-limit"), d.maxPageLimit(), "space-harvester.query.max-page-limit",
                problems);
        int scan = integer(get(query, "max-term-scan"), d.maxTermScan(), "space-harvester.query.max-term-scan",
                problems);
        if (page < 1) {
            problems.add("space-harvester.query.max-page-limit muss mindestens 1 sein; es gilt " + d.maxPageLimit());
            page = d.maxPageLimit();
        }
        if (scan < 1) {
            problems.add("space-harvester.query.max-term-scan muss mindestens 1 sein; es gilt " + d.maxTermScan());
            scan = d.maxTermScan();
        }
        return new com.aresstack.spaceharvester.application.query.QueryLimits(page, scan);
    }

    /**
     * {@code space-harvester-app.video.*}: die Werte für Videos ({@link VideoSettings.Value}). Ein unlesbarer oder zu
     * großer Wert ist ein Problem und lässt den Default stehen.
     */
    private VideoSettings video(Map<?, ?> video, List<String> problems) {
        Map<VideoSettings.Value, Long> values = new java.util.EnumMap<>(VideoSettings.Value.class);
        for (VideoSettings.Value value : VideoSettings.Value.values()) {
            Object raw = get(video, value.key());
            if (raw == null) {
                continue;
            }
            String key = VideoSettings.KEY + "." + value.key();
            long chosen = longNumber(raw, value.defaultValue(), key, problems);
            if (value.accepts(chosen)) {
                values.put(value, chosen);
            } else {
                problems.add(key + " liegt zwischen " + value.min() + " und " + value.max() + "; es gilt "
                        + value.defaultValue());
            }
        }
        return new VideoSettings(values);
    }

    /**
     * {@code space-harvester-app.viewer.background}: Hintergrund des Bildbetrachters (Angelo 17.11.2026). Ohne Angabe
     * oder bei einem unbekannten Wert gilt {@link ViewerBackground#DEFAULT}, die Oberfläche der App.
     */
    private ViewerBackground viewerBackground(Object raw, List<String> problems) {
        if (raw == null) {
            return ViewerBackground.DEFAULT;
        }
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
            problems.add(ViewerBackground.KEY + " ist ein einzelner Wert: " + ViewerBackground.keys());
            return ViewerBackground.DEFAULT;
        }
        String text = text(raw, problems);
        if (text == null || text.isBlank()) {
            // Ein ungelöster Platzhalter steht schon unter den Problemen.
            return ViewerBackground.DEFAULT;
        }
        ViewerBackground background = ViewerBackground.parse(text);
        if (background == null) {
            problems.add(ViewerBackground.KEY + " kennt nur " + ViewerBackground.keys() + ", nicht " + text.strip());
            return ViewerBackground.DEFAULT;
        }
        return background;
    }

    /**
     * {@code space-harvester.statements.standing.*}: Wörter für überholte Seiten und Entwürfe (ADR 0032, FL6), wie beim
     * Dienst ({@link StandingSettings}): YAML-Liste oder Text mit Kommas; fehlend oder leer heißt Vorgabe, „-“ allein
     * heißt keine Wörter. Ein
     * unlesbarer Wert ist ein Problem und lässt die Vorgabe stehen.
     */
    private StandingSettings standing(Map<?, ?> standing, List<String> problems) {
        List<String> outdated = StandingSettings.DEFAULT_OUTDATED_WORDS;
        List<String> draft = StandingSettings.DEFAULT_DRAFT_WORDS;
        String outdatedKey = ROOT + "." + StandingSettings.OUTDATED_WORDS;
        String draftKey = ROOT + "." + StandingSettings.DRAFT_WORDS;
        try {
            outdated = new StandingSettings(words(get(standing, "outdated-words"), outdated, problems), draft)
                    .outdatedWords();
        } catch (IllegalArgumentException e) {
            problems.add(outdatedKey + ": " + e.getMessage());
        }
        try {
            draft = new StandingSettings(outdated, words(get(standing, "draft-words"), draft, problems))
                    .draftWords();
        } catch (IllegalArgumentException e) {
            problems.add(draftKey + ": " + e.getMessage());
        }
        return new StandingSettings(outdated, draft);
    }

    private List<String> words(Object raw, List<String> fallback, List<String> problems) {
        List<String> words = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(word -> {
                String value = text(word, problems);
                if (value != null) {
                    words.add(value);
                }
            });
        } else if (raw != null) {
            String value = text(raw, problems);
            if (value != null) {
                words.addAll(List.of(value.split(",")));
            }
        }
        return StandingSettings.words(words, fallback);
    }

    /**
     * {@code space-harvester.statements.simplification.*}: Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3), wie beim
     * Dienst ({@link SimplificationSettings}). Ein unlesbarer Wert ist ein Problem und lässt die Vorgabe stehen.
     */
    private SimplificationSettings simplification(Map<?, ?> simplification, List<String> problems) {
        SimplificationSettings.Mode mode = SimplificationSettings.Mode.WHERE_NEEDED;
        Object modeValue = get(simplification, "mode");
        try {
            mode = SimplificationSettings.Mode.parse(modeValue == null ? null : modeValue.toString());
        } catch (IllegalArgumentException e) {
            problems.add(ROOT + "." + SimplificationSettings.MODE + ": " + e.getMessage());
        }
        String parallelKey = ROOT + "." + SimplificationSettings.PARALLEL;
        long parallel = longNumber(get(simplification, "parallel"), SimplificationSettings.DEFAULT_PARALLEL,
                parallelKey, problems);
        if (parallel < 1 || parallel > Integer.MAX_VALUE) {
            problems.add(parallelKey + ": Mindestens ein Abschnitt zugleich");
            parallel = SimplificationSettings.DEFAULT_PARALLEL;
        }
        Object model = get(simplification, "model");
        return new SimplificationSettings(mode, (int) parallel, model == null ? null : model.toString(),
                tokenLength(get(simplification, "max-tokens"), ROOT + "." + SimplificationSettings.MAX_TOKENS,
                        problems), thinkingLevel(get(simplification, "thinking"),
                        ROOT + "." + SimplificationSettings.THINKING, problems));
    }

    /**
     * Das Nachdenken eines Schritts ({@code none}, {@code low}, {@code medium}, {@code high}) oder {@code null}, wenn
     * leer; ein unbekannter Wert ist ein Problem und lässt das Nachdenken des Text-Modells gelten.
     */
    private static String thinkingLevel(Object value, String key, List<String> problems) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String level = AiDraft.level(value.toString().strip());
        if (level == null) {
            problems.add(key + ": Nachdenken ist none, low, medium oder high");
        }
        return level;
    }

    /**
     * Die Tokenlänge eines Schritts ({@code max-tokens}, 1 bis 1000000) oder {@code null}, wenn leer; ein unlesbarer
     * Wert ist ein Problem und lässt {@code ai.chat-max-tokens} gelten.
     */
    private static Integer tokenLength(Object value, String key, List<String> problems) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String text = value.toString().strip();
        if (text.matches("\\d{1,7}")) {
            int tokens = Integer.parseInt(text);
            if (tokens >= 1 && tokens <= AiDraft.MAX_CHAT_MAX_TOKENS) {
                return tokens;
            }
        }
        problems.add(key + ": Tokenlänge ist eine ganze Zahl von 1 bis " + AiDraft.MAX_CHAT_MAX_TOKENS);
        return null;
    }

    /**
     * {@code space-harvester.statements.check.*}: Prüfschritt (ADR 0032, FL4), wie beim Dienst ({@link CheckSettings}).
     * Ein unlesbarer Wert ist ein Problem und lässt die Vorgabe stehen.
     */
    private CheckSettings check(Map<?, ?> check, List<String> problems) {
        CheckScope scope = CheckScope.UNCERTAIN_AND_STAGE;
        Object scopeValue = get(check, "scope");
        try {
            scope = CheckScope.parse(scopeValue == null ? null : scopeValue.toString());
        } catch (IllegalArgumentException e) {
            problems.add(ROOT + "." + CheckSettings.SCOPE + ": " + e.getMessage());
        }
        String parallelKey = ROOT + "." + CheckSettings.PARALLEL;
        long parallel = longNumber(get(check, "parallel"), CheckSettings.DEFAULT_PARALLEL, parallelKey, problems);
        if (parallel < 1 || parallel > Integer.MAX_VALUE) {
            problems.add(parallelKey + ": Mindestens ein Satz zugleich");
            parallel = CheckSettings.DEFAULT_PARALLEL;
        }
        Object model = get(check, "model");
        return new CheckSettings(scope, (int) parallel, model == null ? null : model.toString(),
                tokenLength(get(check, "max-tokens"), ROOT + "." + CheckSettings.MAX_TOKENS, problems),
                thinkingLevel(get(check, "thinking"), ROOT + "." + CheckSettings.THINKING, problems));
    }

    /**
     * {@code space-harvester-app.analytics}: Zeitraum ({@code period}, Dauer wie 365d) und die Pfade des
     * Analytics-Plugins ({@code details}, {@code activity-by-date}, {@code content-views}); fehlt ein Wert, gilt die
     * Vorgabe des Core. Ein Pfad muss unter {@code /rest/} liegen, sonst wird er gemeldet und die Vorgabe gilt.
     */
    private AnalyticsSettings analytics(Map<?, ?> raw, List<String> problems) {
        if (raw.isEmpty()) {
            return AnalyticsSettings.DEFAULT;
        }
        Duration period = duration(get(raw, "period"), null, AnalyticsSettings.KEY + ".period", problems);
        if (period != null && !AnalyticsSettings.wholeDays(period)) {
            problems.add(AnalyticsSettings.KEY + ".period muss ganze Tage umfassen, mindestens 1d");
            period = null;
        }
        return new AnalyticsSettings(period, analyticsPath(get(raw, "details"), "details", problems),
                analyticsPath(get(raw, "activity-by-date"), "activity-by-date", problems),
                analyticsPath(get(raw, "content-views"), "content-views", problems));
    }

    private String analyticsPath(Object raw, String key, List<String> problems) {
        String path = text(raw, problems);
        if (path == null || path.isBlank()) {
            return null;
        }
        if (!com.aresstack.spaceharvester.application.metadata.AnalyticsQueries.validPath(path.strip())) {
            problems.add(AnalyticsSettings.KEY + "." + key + " muss ein Pfad unter /rest/ ohne Leerzeichen sein; es "
                    + "gilt die Vorgabe");
            return null;
        }
        return path.strip();
    }

    /**
     * {@code logging.threshold.console}: derselbe Schlüssel wie beim Dienst (Spring Boot). Ein unquotiertes
     * {@code off} liest YAML als {@code false}; das gilt wie dort als aus. Ohne Angabe oder bei einem unbekannten Wert
     * gilt der Standard der App, {@link ConsoleOutput#DEFAULT}.
     */
    private ConsoleOutput consoleOutput(Object raw, List<String> problems) {
        if (raw == null) {
            return ConsoleOutput.DEFAULT;
        }
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
            problems.add(ConsoleOutput.KEY + " ist eine einzelne Stufe: off, error, warn, info oder all");
            return ConsoleOutput.DEFAULT;
        }
        String text = raw instanceof Boolean flag ? flag.toString() : text(raw, problems);
        if (text == null || text.isBlank()) {
            // Ein ungelöster Platzhalter steht schon unter den Problemen.
            return ConsoleOutput.DEFAULT;
        }
        ConsoleOutput output = ConsoleOutput.parse(text);
        if (output == null) {
            problems.add(ConsoleOutput.KEY + " kennt nur off, error, warn, info und all, nicht " + text.strip());
            return ConsoleOutput.DEFAULT;
        }
        return output;
    }

    /** {@code space-harvester.enrichment.documents.*}: Analysen je Anhang (ADR 0026, AP9a), wie beim Dienst. */
    private EnrichmentSettings enrichment(Map<?, ?> enrichment, List<String> problems) {
        Map<?, ?> documents = map(get(enrichment, "documents"));
        EnrichmentSettings d = EnrichmentSettings.DEFAULT;
        boolean enabled = flag(get(documents, "enabled"), d.documents(),
                ROOT + "." + EnrichmentSettings.DOCUMENTS_ENABLED, problems);
        String maxKey = ROOT + "." + EnrichmentSettings.DOCUMENTS_EMBEDDING_MAX_CHARS;
        long maxChars = longNumber(get(documents, "embedding-max-chars"), d.documentEmbeddingMaxChars(), maxKey,
                problems);
        try {
            return new EnrichmentSettings(enabled, maxChars);
        } catch (IllegalArgumentException e) {
            problems.add(maxKey + ": " + e.getMessage());
            return new EnrichmentSettings(enabled, d.documentEmbeddingMaxChars());
        }
    }

    /** {@code space-harvester.terms.synonyms.*}: Budget der Synonymerkennung (#336), dieselben Schlüssel wie beim Dienst. */
    private TermSettings terms(Map<?, ?> terms, Map<?, ?> glossary, List<String> problems) {
        // K4: terms.glossary.automatic, Standard aus, wie beim Dienst.
        boolean glossaryAutomatic = flag(get(map(get(terms, "glossary")), "automatic"), false,
                "space-harvester." + TermSettings.GLOSSARY_AUTOMATIC, problems);
        // Geprüfte Glossare: terms.glossary.approved, Standard an, wie beim Dienst.
        boolean glossaryApproved = flag(get(map(get(terms, "glossary")), "approved"), true,
                "space-harvester." + TermSettings.GLOSSARY_APPROVED, problems);
        return terms(terms, problems).withGlossaryRules(glossaryRules(map(get(glossary, "rules")), problems))
                .withGlossaryAutomatic(glossaryAutomatic).withGlossaryApproved(glossaryApproved);
    }

    /**
     * {@code space-harvester.glossary.rules.*}: Regeln der Begriffsableitung (ADR 0032, FL5b), wie beim Dienst
     * ({@link GlossaryRuleSettings}): Höchstzahl und je Art zusätzliche und ausgeschlossene Wörter als YAML-Liste oder
     * Text mit Kommas. Ein unlesbarer Wert ist ein Problem und lässt die Vorgabe stehen.
     */
    private GlossaryRuleSettings glossaryRules(Map<?, ?> rules, List<String> problems) {
        GlossaryRuleSettings result = GlossaryRuleSettings.DEFAULT;
        try {
            result = result.withMaxTerms(integer(get(rules, "max-terms"), GlossaryRuleSettings.DEFAULT_MAX_TERMS,
                    ROOT + "." + GlossaryRuleSettings.MAX_TERMS, problems));
        } catch (IllegalArgumentException e) {
            problems.add(ROOT + "." + GlossaryRuleSettings.MAX_TERMS + ": " + e.getMessage());
        }
        for (GlossaryRuleSettings.Kind kind : GlossaryRuleSettings.Kind.values()) {
            // Ein unbrauchbares Wort ist ein Problem; die übrigen Wörter seiner Liste gelten.
            Map<?, ?> lists = map(get(rules, kind.key()));
            List<String> add = GlossaryRuleSettings.accepted(kind, true, words(get(lists, "add"), List.of(),
                    problems), message -> problems.add(ROOT + "." + kind.addKey() + ": " + message));
            List<String> exclude = GlossaryRuleSettings.accepted(kind, false, words(get(lists, "exclude"),
                    List.of(), problems), message -> problems.add(ROOT + "." + kind.excludeKey() + ": " + message));
            result = result.with(kind, add, exclude);
        }
        return result;
    }

    private TermSettings terms(Map<?, ?> terms, List<String> problems) {
        Map<?, ?> synonyms = map(get(terms, "synonyms"));
        String prefix = SynonymBudget.PREFIX;
        SynonymBudget fallback = SynonymBudget.DEFAULT;
        int maxTerms = integer(get(synonyms, SynonymBudget.MAX_TERMS), fallback.maxTerms(),
                prefix + SynonymBudget.MAX_TERMS, problems);
        int maxEmbedded = integer(get(synonyms, SynonymBudget.MAX_EMBEDDED_TERMS), fallback.maxEmbeddedTerms(),
                prefix + SynonymBudget.MAX_EMBEDDED_TERMS, problems);
        int maxJudged = integer(get(synonyms, SynonymBudget.MAX_JUDGED_PAIRS), fallback.maxJudgedPairs(),
                prefix + SynonymBudget.MAX_JUDGED_PAIRS, problems);
        boolean automatic = flag(get(synonyms, SynonymBudget.AUTOMATIC), fallback.automatic(),
                prefix + SynonymBudget.AUTOMATIC, problems);
        boolean glossaryOnly = flag(get(synonyms, SynonymBudget.GLOSSARY_TERMS_ONLY), fallback.glossaryTermsOnly(),
                prefix + SynonymBudget.GLOSSARY_TERMS_ONLY, problems);
        try {
            return new TermSettings(new SynonymBudget(maxTerms, maxEmbedded, maxJudged, automatic, glossaryOnly));
        } catch (IllegalArgumentException e) {
            problems.add(prefix + "*: " + e.getMessage());
            return TermSettings.DEFAULT;
        }
    }

    /**
     * {@code space-harvester-app.inactive-roots}: Ordner, die in der Tabelle „Dateizugriff“ stehen, aber vorübergehend
     * abgeschaltet sind; als YAML-Liste oder durch {@code ;} getrennt. Relative Pfade werden gemeldet und übergangen.
     */
    private List<Path> inactiveRoots(Object raw, List<String> problems) {
        List<Object> entries = new ArrayList<>();
        if (raw instanceof List<?> list) {
            entries.addAll(list);
        } else if (raw instanceof Map<?, ?>) {
            problems.add(APP_ROOT + ".inactive-roots ist eine Liste von Ordnern, keine Zuordnung");
        } else if (raw != null) {
            String text = text(raw, problems);
            if (text != null) {
                entries.addAll(List.of(text.split(LocalFileSettings.SEPARATOR)));
            }
        }
        List<Path> inactive = new ArrayList<>();
        for (Object entry : entries) {
            Path root = path(entry, problems);
            if (root == null) {
                continue;
            }
            if (!root.isAbsolute()) {
                problems.add(APP_ROOT + ".inactive-roots: nur absolute Pfade sind erlaubt");
                continue;
            }
            // Normalisiert wie die aktiven Ordner (LocalFileSettings), damit „/x/.“ nach dem Speichern als gleich gilt.
            inactive.add(root.normalize());
        }
        return inactive;
    }

    /** {@code space-harvester-app.source-keepass-entries}: Quellen-ID → Titel des KeePass-Eintrags. */
    private Map<String, String> sourceKeePass(Map<?, ?> app, List<String> problems) {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        map(get(app, "source-keepass-entries")).forEach((id, title) -> {
            String text = text(title, problems);
            if (id != null && text != null && !text.isBlank()) {
                entries.put(String.valueOf(id), text.strip());
            }
        });
        return entries;
    }

    /**
     * {@code space-harvester.local-files}, geprüft wie im Core: {@code roots} als YAML-Liste oder als Text getrennt
     * durch {@code ;}; ein relativer Pfad wird gemeldet (ohne ihn zu nennen) und gewährt nichts.
     */
    private LocalFileSettings localFiles(Map<?, ?> localFiles, List<String> problems) {
        boolean allowAll = flag(get(localFiles, "allow-all"), false, ROOT + "." + LocalFileSettings.ALLOW_ALL,
                problems);
        List<Object> raw = new ArrayList<>();
        Object roots = get(localFiles, "roots");
        if (roots instanceof List<?> list) {
            raw.addAll(list);
        } else if (roots instanceof Map<?, ?>) {
            problems.add(ROOT + "." + LocalFileSettings.ROOTS + " ist eine Liste von Ordnern, keine Zuordnung");
        } else if (roots != null) {
            String text = text(roots, problems);
            if (text != null) {
                raw.addAll(List.of(text.split(LocalFileSettings.SEPARATOR)));
            }
        }
        List<Path> granted = new ArrayList<>();
        for (Object entry : raw) {
            Path root = path(entry, problems);
            if (root == null) {
                continue;
            }
            if (!root.isAbsolute()) {
                problems.add(ROOT + "." + LocalFileSettings.ROOTS + ": nur absolute Pfade sind erlaubt");
                continue;
            }
            granted.add(root);
        }
        return new LocalFileSettings(allowAll, granted);
    }

    /** {@code space-harvester-app.ai-access} und {@code .ai-keepass-entry}; Unbekanntes gilt als nicht gewählt. */
    private AiAccess aiAccess(Map<?, ?> app, List<String> problems) {
        String modeText = text(get(app, "ai-access"), problems);
        AiAccess.Mode mode = AiAccess.Mode.parse(modeText);
        if (modeText != null && mode == null) {
            problems.add("space-harvester-app.ai-access ist keepass oder manual, nicht '" + modeText + "'");
        }
        return new AiAccess(mode, text(get(app, "ai-keepass-entry"), problems));
    }

    /** Die globalen Werte unter {@code space-harvester.sync}, geprüft wie im Core; ungültige gelten wie die Vorgabe. */
    private SyncSettings sync(Map<?, ?> sync, AppSettings.Cache cache, List<String> problems) {
        String prefix = "space-harvester.";
        SyncMode mode = SyncSettings.DEFAULT_MODE;
        String modeText = text(get(sync, "mode"), problems);
        if (modeText != null && !modeText.isBlank()) {
            SyncMode parsed = SyncMode.parse(modeText);
            if (parsed == null) {
                problems.add(prefix + SyncSettings.MODE + ": erwartet catalog-only, sync-cache oder full");
            } else {
                mode = parsed;
            }
        }
        int parallelism = integer(get(sync, "background-parallelism"), SyncSettings.DEFAULT_BACKGROUND_PARALLELISM,
                "space-harvester." + SyncSettings.BACKGROUND_PARALLELISM, problems);
        if (parallelism < 1 || parallelism > SyncSettings.MAX_BACKGROUND_PARALLELISM) {
            problems.add("space-harvester." + SyncSettings.BACKGROUND_PARALLELISM + " liegt zwischen 1 und "
                    + SyncSettings.MAX_BACKGROUND_PARALLELISM);
            parallelism = SyncSettings.DEFAULT_BACKGROUND_PARALLELISM;
        }
        // In der App aus, solange niemand sie einschaltet (Angelo 24.11.2026: synchronisiert wird nur per Klick, je
        // Quelle; der Unterschiedsbericht soll genau das zeigen). Im Dienst bleibt sie an (ADR 0016).
        boolean background = flag(get(sync, "background-fill"), false, "space-harvester." + SyncSettings.BACKGROUND_FILL,
                problems);
        return new SyncSettings(
                flag(get(sync, "events"), false, prefix + SyncSettings.EVENTS, problems),
                interval(optionalDuration(get(sync, "interval"), prefix + SyncSettings.INTERVAL, problems),
                        prefix + SyncSettings.INTERVAL, problems),
                spaces(get(sync, "spaces"), problems),
                flag(get(sync, "before-search"), false, prefix + SyncSettings.BEFORE_SEARCH, problems),
                cache.withheldRetention(),
                flag(get(sync, "catch-up-on-start"), false, prefix + SyncSettings.CATCH_UP_ON_START, problems),
                mode,
                interval(optionalDuration(get(sync, "inventory-interval"), prefix + SyncSettings.INVENTORY_INTERVAL,
                        problems), prefix + SyncSettings.INVENTORY_INTERVAL, problems),
                Map.of()).withBackground(background, parallelism);
    }

    /**
     * {@code space-harvester.sync.spaces} wie im Server ({@code SyncProperties}): Liste oder Komma-Text aus
     * {@code <sourceId>/<spaceKey>}; ungültige Einträge werden gemeldet und weggelassen.
     */
    private List<String> spaces(Object raw, List<String> problems) {
        List<String> entries = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(entry -> entries.add(text(entry, problems)));
        } else if (raw != null) {
            String text = text(raw, problems);
            if (text != null) {
                entries.addAll(List.of(text.split(",")));
            }
        }
        List<String> spaces = new ArrayList<>();
        for (String entry : entries) {
            String space = entry == null ? "" : entry.trim();
            if (space.isEmpty()) {
                continue;
            }
            int slash = space.indexOf('/');
            if (slash < 1 || slash == space.length() - 1 || space.indexOf('/', slash + 1) >= 0) {
                problems.add("space-harvester.sync.spaces erwartet <sourceId>/<spaceKey>, nicht '" + space + "'");
                continue;
            }
            spaces.add(space);
        }
        return List.copyOf(spaces);
    }

    /**
     * Regeln je Quelle unter {@code space-harvester.sync.sources.<Quelle>}, wie {@code HarvesterConfiguration} sie
     * liest und prüft; ein fehlendes Feld gilt global, ein ungültiges wird gemeldet und gilt ebenfalls global.
     */
    private Map<String, SourceSyncOverride> syncRules(Map<?, ?> sources, Duration globalMaxAge,
            List<String> problems) {
        Map<String, SourceSyncOverride> rules = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : sources.entrySet()) {
            String id = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> rule)) {
                problems.add("space-harvester.sync.sources." + id + " braucht Felder wie interval oder mode");
                continue;
            }
            String prefix = "space-harvester." + SourceSyncOverride.key(id, "");
            SyncMode mode = null;
            String modeText = text(get(rule, SourceSyncOverride.MODE), problems);
            if (modeText != null && !modeText.isBlank()) {
                mode = SyncMode.parse(modeText);
                if (mode == null) {
                    problems.add(prefix + "mode: erwartet catalog-only, sync-cache oder full");
                }
            }
            RefreshStrategy strategy = null;
            String strategyText = text(get(rule, SourceSyncOverride.REFRESH_STRATEGY), problems);
            if (strategyText != null && !strategyText.isBlank()) {
                strategy = RefreshStrategy.parse(strategyText);
                if (strategy == null) {
                    problems.add(prefix + "refresh-strategy: erwartet always-check-version, time-based oder "
                            + "time-based-with-version-check");
                }
            }
            if (id.isBlank() || !id.equals(id.strip()) || id.contains(".")) {
                problems.add("space-harvester.sync.sources: ungültige Quellen-ID „" + id + "“");
                continue;
            }
            Duration changeSearch = interval(optionalDuration(get(rule, SourceSyncOverride.CHANGE_SEARCH_INTERVAL),
                    prefix + "interval", problems), prefix + "interval", problems);
            Duration inventory = interval(optionalDuration(get(rule, SourceSyncOverride.INVENTORY_INTERVAL),
                    prefix + "inventory-interval", problems), prefix + "inventory-interval", problems);
            Duration maxAge = optionalDuration(get(rule, SourceSyncOverride.MAX_AGE), prefix + "max-age", problems);
            if (maxAge != null && (maxAge.isNegative() || maxAge.compareTo(CacheFreshness.MAX_AGE) > 0)) {
                problems.add(prefix + "max-age liegt zwischen 0 und 365 Tagen");
                maxAge = null;
            }
            Duration effectiveAge = maxAge == null ? globalMaxAge : maxAge;
            if (strategy != null && strategy != RefreshStrategy.ALWAYS_CHECK_VERSION && effectiveAge.isZero()) {
                problems.add(prefix + "refresh-strategy: die zeitbasierte Aktualisierung braucht eine Frist "
                        + "(max-age)");
                strategy = null;
            }
            Duration retention = optionalDuration(get(rule, SourceSyncOverride.WITHHELD_RETENTION),
                    prefix + "withheld-retention", problems);
            if (retention != null && retention.isNegative()) {
                problems.add(prefix + "withheld-retention darf nicht negativ sein");
                retention = null;
            }
            rules.put(id, new SourceSyncOverride(changeSearch, inventory,
                    optionalFlag(get(rule, SourceSyncOverride.EVENTS), prefix + "events", problems),
                    optionalFlag(get(rule, SourceSyncOverride.CATCH_UP_ON_START), prefix + "catch-up-on-start",
                            problems),
                    optionalFlag(get(rule, SourceSyncOverride.BEFORE_SEARCH), prefix + "before-search", problems),
                    mode, strategy, maxAge, retention,
                    optionalFlag(get(rule, SourceSyncOverride.BACKGROUND_FILL), prefix + "background-fill", problems),
                    optionalFlag(get(rule, SourceSyncOverride.PREFETCH_HITS), prefix + "prefetch-hits", problems)));
        }
        return rules;
    }

    /** Ein Abstand unter einer Minute wird gemeldet und gilt global, wie der Core ihn ablehnt. */
    private Duration interval(Duration value, String key, List<String> problems) {
        if (value != null && value.compareTo(SyncSettings.MIN_INTERVAL) < 0) {
            problems.add(key + " muss mindestens eine Minute betragen");
            return null;
        }
        return value;
    }

    private Boolean optionalFlag(Object raw, String key, List<String> problems) {
        return raw == null ? null : flag(raw, false, key, problems);
    }

    /** Cache-Frische und Sperrfrist wie im Server; ungültige Werte melden und die Vorgabe nehmen. */
    private AppSettings.Cache cache(Map<?, ?> cache, Map<?, ?> sync, List<String> problems) {
        Duration maxAge = duration(get(cache, "max-age"), Duration.ZERO, "space-harvester.cache.max-age", problems);
        if (maxAge.isNegative() || maxAge.compareTo(CacheFreshness.MAX_AGE) > 0) {
            problems.add("space-harvester.cache.max-age liegt zwischen 0 und 365 Tagen");
            maxAge = Duration.ZERO;
        }
        Duration retention = duration(get(sync, "withheld-retention"), AppSettings.Cache.DEFAULT_RETENTION,
                "space-harvester.sync.withheld-retention", problems);
        if (retention.isNegative()) {
            problems.add("space-harvester.sync.withheld-retention darf nicht negativ sein");
            retention = AppSettings.Cache.DEFAULT_RETENTION;
        }
        return new AppSettings.Cache(maxAge, flag(get(cache, "version-check"), true,
                "space-harvester.cache.version-check", problems), retention, changeLog(map(get(cache, "change-log")),
                problems));
    }

    /**
     * Aufbewahrung des Änderungsprotokolls (ADR 0030) wie im Server ({@link ChangeLogSettings#parse}): fehlend die
     * Vorgabe, leer (auch ohne Wert, wie Spring es liest) oder {@code 0} unbegrenzt.
     */
    private ChangeLogSettings changeLog(Map<?, ?> changeLog, List<String> problems) {
        Object raw = get(changeLog, "retention");
        String value = raw == null ? (changeLog.containsKey("retention") ? "" : null) : String.valueOf(raw);
        ChangeLogSettings settings;
        try {
            settings = ChangeLogSettings.parse(value);
        } catch (IllegalArgumentException e) {
            problems.add("space-harvester." + ChangeLogSettings.RETENTION + ": " + e.getMessage());
            settings = ChangeLogSettings.DEFAULT;
        }
        return settings.withShowAfterUpdate(flag(get(changeLog, "show-after-update"), false,
                "space-harvester." + ChangeLogSettings.SHOW_AFTER_UPDATE, problems));
    }

    /**
     * Vertrauen ohne {@code trust-bundle} wie {@code space-harvester.network.tls} beim Dienst (#189). Ohne Angabe gilt
     * unter Windows die Vorgabe des Enterprise AI Client (JVM-Truststore und Windows-Speicher), sonst der
     * JVM-Truststore. Eine unbrauchbare Angabe wird gemeldet und durch die Vorgabe ersetzt.
     */
    private TrustSettings tls(Object raw, List<String> problems) {
        TrustSettings standard = defaultTls();
        if (raw == null) {
            return standard;
        }
        Map<?, ?> tls = map(raw);
        if (tls.isEmpty()) {
            return TrustSettings.jvm();
        }
        Path file = path(get(tls, "ca-certificates-file"), problems);
        TrustSettings settings = new TrustSettings(flag(get(tls, "use-jvm-default"), true, problems),
                flag(get(tls, "use-windows-root"), false, problems),
                flag(get(tls, "use-windows-ca-stores"), false, problems), file);
        List<String> found = TrustPolicy.problems(settings);
        if (found.isEmpty()) {
            return settings;
        }
        found.forEach(problem -> problems.add("space-harvester.network." + problem));
        return standard;
    }

    /** Vertrauen ohne Angabe in der Datei: unter Windows wie im Enterprise AI Client, sonst der JVM-Truststore. */
    public TrustSettings defaultTls() {
        return windows ? TrustSettings.system() : TrustSettings.jvm();
    }

    private boolean flag(Object raw, boolean standard, List<String> problems) {
        return flag(raw, standard, "space-harvester.network.tls", problems);
    }

    private boolean flag(Object raw, boolean standard, String key, List<String> problems) {
        if (raw == null) {
            return standard;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = text(raw, problems);
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        problems.add(key + ": „" + text + "“ ist weder true noch false");
        return standard;
    }

    /** Eine Liste von Kennungen, als YAML-Liste oder durch Kommas getrennt. */
    private Set<String> ids(Object raw, List<String> problems) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            list.forEach(id -> {
                String text = text(id, problems);
                if (text != null && !text.isBlank()) {
                    ids.add(text.strip());
                }
            });
        } else if (raw instanceof Map<?, ?>) {
            problems.add("space-harvester-app.disabled-sources ist eine Liste von Quellen-IDs, keine Zuordnung");
        } else if (raw != null) {
            String text = text(raw, problems);
            if (text != null) {
                for (String id : text.split(",")) {
                    if (!id.isBlank()) {
                        ids.add(id.strip());
                    }
                }
            }
        }
        return ids;
    }

    private AppSettings.SourceEntry source(Map<?, ?> source, int index, List<String> problems) {
        String id = text(get(source, "id"), problems);
        String label = "Quelle " + (id == null ? "Nr. " + (index + 1) : id);
        String type = text(get(source, "type"), problems);
        if (id == null || id.isBlank()) {
            problems.add(label + ": id fehlt");
            return null;
        }
        if (type == null || type.isBlank()) {
            problems.add(label + ": type fehlt (confluence oder files)");
            return null;
        }
        URI baseUrl = null;
        String url = text(get(source, "base-url"), problems);
        if (url != null && !url.isBlank()) {
            try {
                baseUrl = new URI(url.trim());
            } catch (URISyntaxException e) {
                problems.add(label + ": base-url ist keine gültige Adresse");
                return null;
            }
        }
        Map<String, String> options = new TreeMap<>();
        Map<String, String> placeholderOptions = new TreeMap<>();
        for (Map.Entry<?, ?> option : map(get(source, "options")).entrySet()) {
            String value = text(option.getValue(), problems);
            if (value != null) {
                String key = String.valueOf(option.getKey());
                options.put(key, value);
                String shown = placeholderForm(option.getValue());
                if (shown != null) {
                    placeholderOptions.put(key, shown);
                }
            }
        }

        Map<?, ?> auth = map(get(source, "auth"));
        SourceSettingsConfig.AuthType authType = enumValue(SourceSettingsConfig.AuthType.class,
                text(get(auth, "type"), problems), SourceSettingsConfig.AuthType.NONE, label + ": auth.type", problems);
        String username = text(get(auth, "username"), problems);
        String secretKey = authType == SourceSettingsConfig.AuthType.PAT ? "token" : "password";
        Secret secret = authType == SourceSettingsConfig.AuthType.NONE ? Secret.NONE : secret(get(auth, secretKey));
        SourceSettingsConfig.Auth credentials = switch (authType) {
            case NONE -> SourceSettingsConfig.Auth.anonymous();
            case BASIC -> new SourceSettingsConfig.Auth(authType, username, secret.value(), null);
            case PAT -> new SourceSettingsConfig.Auth(authType, null, null, secret.value());
        };

        Map<?, ?> certificate = map(get(source, "client-certificate"));
        SourceSettingsConfig.ClientCertificate clientCertificate = SourceSettingsConfig.ClientCertificate.disabled();
        if (!certificate.isEmpty()) {
            boolean enabled = Boolean.parseBoolean(text(get(certificate, "enabled"), problems));
            SourceSettingsConfig.CertificateStoreType storeType = enumValue(
                    SourceSettingsConfig.CertificateStoreType.class, text(get(certificate, "type"), problems), null,
                    label + ": client-certificate.type", problems);
            Secret password = secret(get(certificate, "password"));
            if (password.missing() && password.variable() != null) {
                problems.add(label + ": Das Passwort des Client-Zertifikats fehlt (Umgebungsvariable "
                        + password.variable() + ")");
            }
            clientCertificate = new SourceSettingsConfig.ClientCertificate(enabled, storeType,
                    path(get(certificate, "path"), problems), text(get(certificate, "alias"), problems),
                    password.value(), text(get(certificate, "thumbprint"), problems));
        }
        SourceSettingsConfig config = new SourceSettingsConfig(id.trim(), type.trim(), text(get(source, "title"),
                problems), baseUrl, credentials, options, clientCertificate, text(get(source, "trust-bundle"), problems),
                !Boolean.FALSE.equals(optionalFlag(get(source, "show-in-explorer"), id + ".show-in-explorer", problems)),
                !Boolean.FALSE.equals(optionalFlag(get(source, "include-in-global-search"),
                        id + ".include-in-global-search", problems)));
        return new AppSettings.SourceEntry(config, secret.missing(), secret.variable(), placeholderOptions);
    }

    /**
     * Die Schreibweise eines Werts mit Platzhaltern, wie sie die Oberfläche zeigen darf: {@code ${NAME}} ohne
     * Vorgabewert, der übrige Text bleibt. {@code null}, wenn der Wert keinen Platzhalter enthält. Aufgelöste Werte aus
     * der Umgebung zeigt die Oberfläche nie (Geheimnisse nur als {@code ${VAR}}).
     */
    static String placeholderForm(Object raw) {
        if (raw == null || raw instanceof Map<?, ?> || raw instanceof List<?>) {
            return null;
        }
        Matcher placeholder = PLACEHOLDER.matcher(String.valueOf(raw));
        if (!placeholder.find()) {
            return null;
        }
        return placeholder.reset().replaceAll(match -> Matcher.quoteReplacement("${" + match.group(1).strip() + "}"));
    }

    private ProxySettings proxy(Map<?, ?> proxy, List<String> problems) {
        if (proxy.isEmpty()) {
            return defaultProxy();
        }
        ProxySettings.Mode mode = enumValue(ProxySettings.Mode.class, text(get(proxy, "mode"), problems),
                ProxySettings.Mode.DISABLED, "space-harvester.network.proxy.mode", problems);
        String pac = text(get(proxy, "pac-url"), problems);
        URI pacUrl = null;
        if (pac != null && !pac.isBlank()) {
            try {
                pacUrl = new URI(pac.trim());
            } catch (URISyntaxException e) {
                problems.add("space-harvester.network.proxy.pac-url ist keine gültige Adresse");
            }
        }
        Object port = get(proxy, "proxy-port");
        List<String> nonProxy = new ArrayList<>();
        if (get(proxy, "non-proxy-hosts") instanceof List<?> hosts) {
            for (Object host : hosts) {
                String value = text(host, problems);
                if (value != null) {
                    nonProxy.add(value);
                }
            }
        }
        return new ProxySettings(mode, pacUrl, text(get(proxy, "proxy-host"), problems),
                port == null ? null : integer(port, 0, "space-harvester.network.proxy.proxy-port", problems), nonProxy,
                optionalDuration(get(proxy, "resolve-timeout"), "space-harvester.network.proxy.resolve-timeout", problems),
                optionalDuration(get(proxy, "cache-ttl"), "space-harvester.network.proxy.cache-ttl", problems),
                optionalDuration(get(proxy, "failure-ttl"), "space-harvester.network.proxy.failure-ttl", problems),
                proxyAuthentication(map(get(proxy, "auth")), problems), script(get(proxy, "pac-discovery-script")));
    }

    /**
     * Nur der Benutzer steht in der Datei; das Passwort liegt im Tresor der App ({@code ProxySettings.SECRET_REF}) und
     * wird beim Start dazugegeben. Ein Passwort in der Datei bleibt unbenutzt.
     */
    private ProxySettings.Authentication proxyAuthentication(Map<?, ?> auth, List<String> problems) {
        if (auth.isEmpty()) {
            return null;
        }
        if (get(auth, "password") != null) {
            problems.add("space-harvester.network.proxy.auth.password wird nicht gelesen: Das Proxy-Passwort gehört "
                    + "in den Tresor (Einstellungen › Proxy)");
        }
        String username = text(get(auth, "username"), problems);
        return username == null || username.isBlank() ? null : ProxySettings.Authentication.basic(username);
    }

    /** Ein Skript wörtlich, ohne Platzhalter aufzulösen: {@code ${…}} ist dort Teil der Skriptsprache. */
    private static String script(Object raw) {
        return raw == null || raw instanceof Map<?, ?> || raw instanceof List<?> ? null : String.valueOf(raw);
    }

    private Map<String, AppSettings.TrustStoreSettings> trustStores(Map<?, ?> spring, List<String> problems) {
        Map<String, AppSettings.TrustStoreSettings> result = new LinkedHashMap<>();
        Map<?, ?> bundle = map(get(map(get(spring, "ssl")), "bundle"));
        for (Map.Entry<?, ?> entry : map(get(bundle, "jks")).entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<?, ?> store = map(get(map(entry.getValue()), "truststore"));
            Path location = path(stripFilePrefix(text(get(store, "location"), problems)), problems);
            if (location == null) {
                problems.add("spring.ssl.bundle.jks." + name + ": truststore.location fehlt");
                continue;
            }
            Secret password = secret(get(store, "password"));
            result.put(name, new AppSettings.TrustStoreSettings(name, location, text(get(store, "type"), problems),
                    password.value(), null));
        }
        for (Map.Entry<?, ?> entry : map(get(bundle, "pem")).entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<?, ?> store = map(get(map(entry.getValue()), "truststore"));
            Path pem = path(stripFilePrefix(text(get(store, "certificate"), problems)), problems);
            if (pem == null) {
                problems.add("spring.ssl.bundle.pem." + name + ": truststore.certificate fehlt");
                continue;
            }
            result.put(name, new AppSettings.TrustStoreSettings(name, null, null, null, pem));
        }
        return result;
    }

    private JobLimits jobLimits(Map<?, ?> jobs, List<String> problems) {
        int queued = integer(get(jobs, "max-queued"), JobLimits.DEFAULT_MAX_QUEUED, "space-harvester.jobs.max-queued",
                problems);
        Duration runTime = duration(get(jobs, "max-run-time"), JobLimits.DEFAULT_MAX_RUN_TIME,
                "space-harvester.jobs.max-run-time", problems);
        int background = integer(get(jobs, "max-open-background"), JobLimits.DEFAULT_MAX_OPEN_BACKGROUND,
                "space-harvester.jobs.max-open-background", problems);
        int waitingPrefetches = integer(get(jobs, "max-waiting-prefetches"), JobLimits.DEFAULT_MAX_WAITING_PREFETCHES,
                "space-harvester.jobs.max-waiting-prefetches", problems);
        Duration retention = duration(get(jobs, "retention"), JobLimits.DEFAULT_RETENTION,
                "space-harvester.jobs.retention", problems);
        RetrySettings retry = retry(map(get(jobs, "retry")), problems);
        int preparations = parallel(jobs, JobLimits.PARALLEL_PREPARATIONS, JobLimits.DEFAULT_PARALLEL_PREPARATIONS,
                JobLimits::requirePreparations, problems);
        int downloads = parallel(jobs, JobLimits.PARALLEL_DOWNLOADS, JobLimits.DEFAULT_PARALLEL_DOWNLOADS,
                JobLimits::requireDownloads, problems);
        int connections = parallel(jobs, JobLimits.DOWNLOAD_CONNECTIONS, JobLimits.DEFAULT_DOWNLOAD_CONNECTIONS,
                JobLimits::requireConnections, problems);
        // Ein Bediener: die Grenze je Auslöser ist die Gesamtgrenze.
        try {
            return new JobLimits(preparations, queued, runTime, retention).withRetry(retry)
                    .withParallel(preparations, downloads).withConnections(connections).withBackground(background)
                    .withWaitingPrefetches(waitingPrefetches);
        } catch (IllegalArgumentException e) {
            problems.add("space-harvester.jobs: " + e.getMessage() + "; es gelten die Standards");
            return JobLimits.DEFAULTS.withRetry(retry).withParallel(preparations, downloads)
                    .withConnections(connections);
        }
    }

    /** {@code jobs.parallel-preparations} und {@code jobs.parallel-downloads}; außerhalb der Grenzen der Standard. */
    private int parallel(Map<?, ?> jobs, String name, int fallback, java.util.function.IntUnaryOperator check,
                         List<String> problems) {
        int number = integer(get(jobs, name), fallback, "space-harvester.jobs." + name, problems);
        try {
            return check.applyAsInt(number);
        } catch (IllegalArgumentException e) {
            problems.add("space-harvester.jobs: " + e.getMessage() + "; es gilt " + fallback);
            return fallback;
        }
    }

    /** {@code space-harvester.jobs.retry.*} (ADR 0025), dieselben Schlüssel wie beim Dienst. */
    private RetrySettings retry(Map<?, ?> retry, List<String> problems) {
        String prefix = "space-harvester." + RetrySettings.PREFIX;
        RetrySettings fallback = RetrySettings.DEFAULT;
        int attempts = integer(get(retry, RetrySettings.MAX_ATTEMPTS), fallback.maxAttempts(),
                prefix + RetrySettings.MAX_ATTEMPTS, problems);
        Duration initial = duration(get(retry, RetrySettings.INITIAL_DELAY), fallback.initialDelay(),
                prefix + RetrySettings.INITIAL_DELAY, problems);
        Duration max = duration(get(retry, RetrySettings.MAX_DELAY), fallback.maxDelay(),
                prefix + RetrySettings.MAX_DELAY, problems);
        int pauseAfter = integer(get(retry, RetrySettings.PAUSE_AFTER_FAILURES), fallback.pauseAfterFailures(),
                prefix + RetrySettings.PAUSE_AFTER_FAILURES, problems);
        Duration resumeInitial = duration(get(retry, RetrySettings.RESUME_INITIAL_DELAY),
                fallback.resumeInitialDelay(), prefix + RetrySettings.RESUME_INITIAL_DELAY, problems);
        Duration resumeMax = duration(get(retry, RetrySettings.RESUME_MAX_DELAY), fallback.resumeMaxDelay(),
                prefix + RetrySettings.RESUME_MAX_DELAY, problems);
        try {
            return new RetrySettings(attempts, initial, max, pauseAfter, resumeInitial, resumeMax);
        } catch (IllegalArgumentException e) {
            problems.add(prefix + "*: " + e.getMessage() + "; es gelten die Standards");
            return fallback;
        }
    }

    private AppSettings.AiSettings ai(Map<?, ?> ai, List<String> problems) {
        List<String> released = new ArrayList<>();
        Object raw = get(ai, "external-processing-sources");
        if (raw instanceof List<?> ids) {
            ids.forEach(id -> released.add(text(id, problems)));
        } else if (raw != null) {
            String text = text(raw, problems);
            if (text != null) {
                released.addAll(List.of(text.split(",")));
            }
        }
        AiDraft draft = new AiDraft(text(get(ai, "base-url"), problems), text(get(ai, "chat-model"), problems),
                text(get(ai, "embedding-model"), problems), text(get(ai, "embedding-dimensions"), problems),
                optionalDuration(get(ai, "timeout"), "space-harvester.ai.timeout", problems), released,
                text(get(ai, "rerank-model"), problems), text(get(ai, "embedding-chunk-tokens"), problems),
                text(get(ai, "embedding-chunk-overlap-sentences"), problems), text(get(ai, "vision-model"), problems),
                text(get(ai, "chat-max-tokens"), problems), provider(text(get(ai, "provider"), problems), problems),
                text(get(ai, "context-tokens"), problems),
                seconds(optionalDuration(get(ai, "load-timeout"), "space-harvester.ai.load-timeout", problems)),
                text(get(ai, "retries"), problems),
                seconds(optionalDuration(get(ai, "load-poll-interval"), "space-harvester.ai.load-poll-interval",
                        problems)), text(get(ai, "step-thinking"), problems), text(get(ai, "parallel"), problems),
                text(get(ai, "vision-max-tokens"), problems), text(get(ai, "image-model"), problems),
                text(get(ai, "image-max-tokens"), problems), text(get(ai, "vision-thinking"), problems),
                text(get(ai, "image-thinking"), problems), use(ai, "summary", problems),
                use(ai, "summary-plan", problems), use(ai, "podcast", problems), use(ai, "image-plan", problems),
                use(ai, "deadline", problems), new AiServiceSettings.Speech(
                        text(get(ai, AiServiceSettings.PODCAST_SPEECH_MODEL), problems),
                        text(get(ai, AiServiceSettings.PODCAST_VOICE_HOST), problems),
                        text(get(ai, AiServiceSettings.PODCAST_VOICE_COHOST), problems),
                        text(get(ai, AiServiceSettings.READ_ALOUD_MODEL), problems),
                        text(get(ai, AiServiceSettings.READ_ALOUD_VOICE), problems),
                        count(ai, AiServiceSettings.PODCAST_PAUSE_MILLIS, 0,
                                AiServiceSettings.Speech.MAX_PODCAST_PAUSE_MILLIS, problems),
                        count(ai, AiServiceSettings.PODCAST_SPEECH_ATTEMPTS, 1,
                                AiServiceSettings.Speech.MAX_PODCAST_ATTEMPTS, problems),
                        count(ai, AiServiceSettings.PODCAST_SPEECH_WORKERS, 1,
                                AiServiceSettings.Speech.MAX_PODCAST_WORKERS, problems)),
                use(ai, "diagnosis", problems), optionalFlag(get(ai, AiServiceSettings.DIAGNOSIS_ENABLED),
                        "space-harvester.ai." + AiServiceSettings.DIAGNOSIS_ENABLED, problems),
                timeouts(ai, problems));
        if (draft.empty()) {
            return AppSettings.AiSettings.NONE;
        }
        draft.problems().forEach(problem -> problems.add("space-harvester.ai: " + problem));
        boolean valid = draft.problems().isEmpty();
        return new AppSettings.AiSettings(valid ? draft.baseUrl() : null, draft.chatModel(), draft.embeddingModel(),
                draft.dimensions(), draft.timeout(), draft.externalProcessingSources(), draft.rerankModel(),
                draft.tokens(), draft.overlap(), draft.visionModel(), draft.maxTokens(), draft.provider(),
                draft.context(), draft.loadDuration(), draft.retryCount(),
                draft.pollDuration(), draft.thinking(), draft.parallelCount(), draft.visionTokens(),
                draft.imageModel(), draft.imageTokens(), draft.visionLevel(), draft.imageLevel(),
                draft.summary().use(), draft.summaryPlan().use(), draft.podcast().use(), draft.imagePlan().use(),
                draft.deadline().use(), draft.speech(), draft.diagnosisSettings(), draft.timeouts());
    }

    /**
     * Eine ganze Zahl der Sprachausgabe ({@code space-harvester.ai.<key>}) von {@code min} bis {@code max} oder
     * {@code null}, wenn leer; ein unlesbarer Wert ist ein Problem und lässt die Vorgabe des Core gelten.
     */
    private Integer count(Map<?, ?> ai, String key, int min, int max, List<String> problems) {
        String text = text(get(ai, key), problems);
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.matches("\\d{1,7}")) {
            int value = Integer.parseInt(stripped);
            if (value >= min && value <= max) {
                return value;
            }
        }
        problems.add("space-harvester.ai." + key + ": ganze Zahl von " + min + " bis " + max);
        return null;
    }

    /** Eine eigene Modellzeile ({@code <prefix>-model}, {@code -max-tokens}, {@code -thinking}) als Eingaben. */
    /** Fristen der KI-Aufgaben; eine ungültige Frist ist ein Problem und lässt dort die Vorgabe des Core gelten. */
    private AiServiceSettings.Timeouts timeouts(Map<?, ?> ai, List<String> problems) {
        Duration summary = taskTimeout(ai, AiServiceSettings.SUMMARY_TIMEOUT, problems);
        Duration label = taskTimeout(ai, AiServiceSettings.LABEL_TIMEOUT, problems);
        Duration expansion = taskTimeout(ai, AiServiceSettings.EXPANSION_TIMEOUT, problems);
        return new AiServiceSettings.Timeouts(summary, label, expansion);
    }

    private Duration taskTimeout(Map<?, ?> ai, String key, List<String> problems) {
        String name = "space-harvester.ai." + key;
        Duration value = optionalDuration(get(ai, key), name, problems);
        if (value != null && (value.isNegative() || value.isZero()
                || value.compareTo(AiServiceSettings.Timeouts.MAX_TASK_TIMEOUT) > 0)) {
            problems.add(name + " muss zwischen 1 ms und " + AiServiceSettings.Timeouts.MAX_TASK_TIMEOUT.toDays()
                    + " Tagen liegen");
            return null;
        }
        return value;
    }

    private AiDraft.Use use(Map<?, ?> ai, String prefix, List<String> problems) {
        return new AiDraft.Use(text(get(ai, prefix + "-model"), problems),
                text(get(ai, prefix + "-max-tokens"), problems), text(get(ai, prefix + "-thinking"), problems));
    }

    /** Eine Dauer als Sekunden, wie das Feld der Einstellungen sie zeigt; {@code null} bleibt. */
    private static String seconds(Duration duration) {
        return duration == null ? null : String.valueOf(duration.toSeconds());
    }

    /** Die Art des KI-Dienstes; ein unbekannter Wert ist ein Problem, und es gilt OpenAI-kompatibel. */
    private static AiProvider provider(String key, List<String> problems) {
        try {
            return AiProvider.fromKey(key);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage() + "; es gilt openai-compatible");
            return AiProvider.OPENAI_COMPATIBLE;
        }
    }

    // ------------------------------------------------------------------ Werte

    /**
     * Ein Geheimnis: aufgelöst, leer oder mit fehlender Umgebungsvariable; {@code variable} ist der Platzhaltername
     * (auch wenn er aufgelöst wurde), damit Sitzung und Tresor einen Wert an genau diesen Platzhalter binden.
     */
    private record Secret(String value, boolean missing, String variable) {

        static final Secret NONE = new Secret(null, false, null);
    }

    private Secret secret(Object raw) {
        if (raw == null) {
            return new Secret(null, true, null);
        }
        try {
            String value = placeholders.resolve(String.valueOf(raw));
            Matcher placeholder = PLACEHOLDER.matcher(String.valueOf(raw).strip());
            String variable = placeholder.matches() ? placeholder.group(1) : null;
            return value == null || value.isEmpty() ? new Secret(null, true, variable)
                    : new Secret(value, false, variable);
        } catch (Placeholders.UnresolvedPlaceholderException e) {
            return new Secret(null, true, e.name());
        }
    }

    private String text(Object raw, List<String> problems) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
            return null;
        }
        try {
            return placeholders.resolve(String.valueOf(raw));
        } catch (Placeholders.UnresolvedPlaceholderException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    private Path path(Object raw, List<String> problems) {
        String value = raw instanceof String s ? text(s, problems) : text(raw, problems);
        if (value == null || value.isBlank()) {
            return null;
        }
        String expanded = value.trim();
        if (expanded.startsWith("~/") || expanded.startsWith("~\\")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        try {
            return Path.of(expanded);
        } catch (InvalidPathException e) {
            problems.add("Ungültiger Pfad: " + value);
            return null;
        }
    }

    private static String stripFilePrefix(String location) {
        return location != null && location.startsWith("file:") ? location.substring("file:".length()) : location;
    }

    private int integer(Object raw, int fallback, String key, List<String> problems) {
        String value = text(raw, problems);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            problems.add(key + " ist keine ganze Zahl: " + value);
            return fallback;
        }
    }

    /** Wie {@code ExtractionProperties} des Dienstes: {@code space-harvester.assets.extraction.*} (ADR 0026). */
    private ExtractionLimits extraction(Map<?, ?> node, List<String> problems) {
        ExtractionLimits d = ExtractionLimits.DEFAULTS;
        String prefix = "space-harvester.assets.extraction.";
        Map<String, Long> memory = new LinkedHashMap<>();
        Object entries = get(node, "memory-limits");
        if (entries instanceof List<?> list) {
            for (Object item : list) {
                Map<?, ?> entry = map(item);
                String type = text(get(entry, "type"), problems);
                Object limit = get(entry, "limit");
                if (type == null || type.isBlank() || limit == null) {
                    problems.add(prefix + "memory-limits braucht type und limit");
                    continue;
                }
                // Eine ungültige Angabe hebt die Schranke nicht auf: Der Eintrag entfällt, der Standard bleibt.
                int before = problems.size();
                long bytes = size(limit, 0, prefix + "memory-limits", problems);
                if (problems.size() == before) {
                    memory.put(type.trim(), bytes);
                }
            }
        } else if (entries != null) {
            problems.add(prefix + "memory-limits muss eine Liste sein");
        }
        try {
            return new ExtractionLimits(
                    size(get(node, "max-file-size"), d.maxFileBytes(), prefix + "max-file-size", problems),
                    longNumber(get(node, "max-chars"), d.maxChars(), prefix + "max-chars", problems),
                    longNumber(get(node, "unpack-ratio"), d.unpackRatio(), prefix + "unpack-ratio", problems),
                    size(get(node, "unpack-ratio-from-size"), d.unpackRatioFromBytes(),
                            prefix + "unpack-ratio-from-size", problems),
                    longNumber(get(node, "text-ratio"), d.textRatio(), prefix + "text-ratio", problems),
                    longNumber(get(node, "text-ratio-from-chars"), d.textRatioFromChars(),
                            prefix + "text-ratio-from-chars", problems),
                    integer(get(node, "max-depth"), d.maxDepth(), prefix + "max-depth", problems),
                    integer(get(node, "max-embedded-files"), d.maxEmbeddedFiles(), prefix + "max-embedded-files",
                            problems),
                    duration(get(node, "idle-timeout"), d.idleTimeout(), prefix + "idle-timeout", problems),
                    duration(get(node, "total-timeout"), d.totalTimeout(), prefix + "total-timeout", problems),
                    size(get(node, "temp-space-reserve"), d.tempSpaceReserve(), prefix + "temp-space-reserve",
                            problems),
                    integer(get(node, "parallel"), d.parallel(), prefix + "parallel", problems),
                    ExtractionLimits.memoryLimits(memory));
        } catch (IllegalArgumentException e) {
            problems.add("space-harvester.assets.extraction: " + e.getMessage());
            return d;
        }
    }

    private long longNumber(Object raw, long fallback, String key, List<String> problems) {
        String value = text(raw, problems);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            problems.add(key + " ist keine ganze Zahl: " + value);
            return fallback;
        }
    }

    private long size(Object raw, long fallback, String key, List<String> problems) {
        String value = text(raw, problems);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Matcher matcher = SIZE.matcher(value.trim());
        if (!matcher.matches()) {
            problems.add(key + " ist keine Größe wie 50MB: " + value);
            return fallback;
        }
        long number = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(Locale.ROOT);
        int shift = switch (unit) {
            case "KB" -> 10;
            case "MB" -> 20;
            case "GB" -> 30;
            case "TB" -> 40;
            default -> 0;
        };
        return number << shift;
    }

    private Duration duration(Object raw, Duration fallback, String key, List<String> problems) {
        Duration value = optionalDuration(raw, key, problems);
        return value == null ? fallback : value;
    }

    private Duration optionalDuration(Object raw, String key, List<String> problems) {
        String value = text(raw, problems);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Matcher simple = SIMPLE_DURATION.matcher(trimmed);
        if (simple.matches()) {
            long amount = Long.parseLong(simple.group(1));
            return switch (simple.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        if (trimmed.matches("\\d+")) {
            return Duration.ofMillis(Long.parseLong(trimmed));
        }
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeParseException e) {
            problems.add(key + " ist keine Dauer wie 5s, 10m oder PT1H: " + value);
            return null;
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback, String key,
                                             List<String> problems) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String name = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                allowed.add(constant.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            problems.add(key + " kennt nur " + String.join(", ", allowed) + ", nicht " + value.trim());
            return fallback;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ------------------------------------------------------------------ YAML-Baum

    static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        return new Yaml(new SafeConstructor(options));
    }

    /** Wert zu einem Schlüssel, locker verglichen wie bei Spring (Groß-/Kleinschreibung, -, _ egal). */
    static Object get(Map<?, ?> map, String key) {
        if (map == null) {
            return null;
        }
        String wanted = normalize(key);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (normalize(String.valueOf(entry.getKey())).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    static String normalize(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String yamlPosition(YAMLException e) {
        if (e instanceof org.yaml.snakeyaml.error.MarkedYAMLException marked && marked.getProblemMark() != null) {
            return " (Zeile " + (marked.getProblemMark().getLine() + 1) + ")";
        }
        return "";
    }
}
