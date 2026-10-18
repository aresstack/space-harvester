package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.configuration.RefreshStrategy;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * Quellen-Assistenten vor dem Speichern.
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
        long maxAsset = size(get(map(get(root, "assets")), "max-size"), 50L * 1024 * 1024,
                "space-harvester.assets.max-size", problems);
        Duration searchTimeout = duration(get(map(get(root, "cache")), "search-timeout"), Duration.ofSeconds(5),
                "space-harvester.cache.search-timeout", problems);
        int maxTreeNodes = integer(get(map(get(root, "query")), "max-tree-nodes"), 50_000,
                "space-harvester.query.max-tree-nodes", problems);
        Path pdfFont = path(get(map(get(map(get(root, "export")), "pdf")), "font"), problems);
        AppSettings.Storage storage = new AppSettings.Storage(data,
                exports == null ? data.resolve("exports") : exports, assets == null ? data.resolve("assets") : assets,
                maxAsset, searchTimeout, maxTreeNodes, pdfFont);

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
                sync(map(get(root, "sync")), cache, problems));
    }

    /** Die globalen Werte unter {@code space-harvester.sync}, geprüft wie im Core; ungültige gelten wie die Vorgabe. */
    private SyncSettings sync(Map<?, ?> sync, AppSettings.Cache cache, List<String> problems) {
        String prefix = "space-harvester.";
        SyncMode mode = SyncMode.CATALOG_ONLY;
        String modeText = text(get(sync, "mode"), problems);
        if (modeText != null && !modeText.isBlank()) {
            SyncMode parsed = SyncMode.parse(modeText);
            if (parsed == null) {
                problems.add(prefix + SyncSettings.MODE + ": erwartet catalog-only, sync-cache oder full");
            } else {
                mode = parsed;
            }
        }
        return new SyncSettings(
                flag(get(sync, "events"), false, prefix + SyncSettings.EVENTS, problems),
                interval(optionalDuration(get(sync, "interval"), prefix + SyncSettings.INTERVAL, problems),
                        prefix + SyncSettings.INTERVAL, problems),
                List.of(),
                flag(get(sync, "before-search"), false, prefix + SyncSettings.BEFORE_SEARCH, problems),
                cache.withheldRetention(),
                flag(get(sync, "catch-up-on-start"), false, prefix + SyncSettings.CATCH_UP_ON_START, problems),
                mode,
                interval(optionalDuration(get(sync, "inventory-interval"), prefix + SyncSettings.INVENTORY_INTERVAL,
                        problems), prefix + SyncSettings.INVENTORY_INTERVAL, problems),
                Map.of());
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
                    mode, strategy, maxAge, retention));
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
                "space-harvester.cache.version-check", problems), retention);
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
        for (Map.Entry<?, ?> option : map(get(source, "options")).entrySet()) {
            String value = text(option.getValue(), problems);
            if (value != null) {
                options.put(String.valueOf(option.getKey()), value);
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
                problems), baseUrl, credentials, options, clientCertificate, text(get(source, "trust-bundle"), problems));
        return new AppSettings.SourceEntry(config, secret.missing(), secret.variable());
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
        int concurrent = integer(get(jobs, "max-concurrent"), 2, "space-harvester.jobs.max-concurrent", problems);
        int queued = integer(get(jobs, "max-queued"), 20, "space-harvester.jobs.max-queued", problems);
        Duration runTime = duration(get(jobs, "max-run-time"), Duration.ofHours(6), "space-harvester.jobs.max-run-time",
                problems);
        Duration retention = duration(get(jobs, "retention"), JobLimits.DEFAULT_RETENTION,
                "space-harvester.jobs.retention", problems);
        // Ein Bediener: die Grenze je Auslöser ist die Gesamtgrenze.
        try {
            return new JobLimits(concurrent, queued, runTime, retention);
        } catch (IllegalArgumentException e) {
            problems.add("space-harvester.jobs: " + e.getMessage() + "; es gelten die Standards");
            return new JobLimits(2, 20, Duration.ofHours(6));
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
                text(get(ai, "embedding-chunk-overlap-sentences"), problems));
        if (draft.empty()) {
            return AppSettings.AiSettings.NONE;
        }
        draft.problems().forEach(problem -> problems.add("space-harvester.ai: " + problem));
        boolean valid = draft.problems().isEmpty();
        return new AppSettings.AiSettings(valid ? draft.baseUrl() : null, draft.chatModel(), draft.embeddingModel(),
                draft.dimensions(), draft.timeout(), draft.externalProcessingSources(), draft.rerankModel(),
                draft.tokens(), draft.overlap());
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
