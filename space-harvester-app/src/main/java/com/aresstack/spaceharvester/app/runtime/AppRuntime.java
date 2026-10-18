package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.core.SourceManagement;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Baut aus den gelesenen Einstellungen und den nur in dieser Sitzung eingegebenen Geheimnissen den Harvester und die
 * fachlichen Aktionen. Quellen, deren Passwort oder Token noch fehlt, bleiben bis zur Eingabe außen vor.
 */
public final class AppRuntime implements AutoCloseable {

    private volatile AppSettings settings;
    private volatile List<String> lastRejection = List.of();
    private final Harvester harvester;
    /** Laufzeitkonfiguration des Harvesters (ADR 0015); eine aktivierte Version wirkt ohne Neustart. */
    private final ConfigurationManager configuration;
    /** {@link SessionSecrets#sourceFingerprint()} beim Start. */
    private final int startedSecrets;
    private final Workspace workspace;
    private static final System.Logger LOG = System.getLogger(AppRuntime.class.getName());
    private static final Pattern SOURCE_POSITION = Pattern.compile("sources\\[(\\d+)]");

    private final Set<String> waitingForSecret;
    private final List<String> rejectedSources;

    private AppRuntime(AppSettings settings, Harvester harvester, ConfigurationManager configuration,
                       Workspace workspace, Set<String> waitingForSecret, List<String> rejectedSources,
                       int startedSecrets) {
        this.settings = settings;
        this.startedSecrets = startedSecrets;
        this.configuration = configuration;
        this.harvester = harvester;
        this.workspace = workspace;
        this.waitingForSecret = waitingForSecret;
        this.rejectedSources = rejectedSources;
    }

    /**
     * @param sessionSecrets Passwort bzw. Token je Quellen-ID, nur im Speicher
     * @param version        App-Version für den User-Agent
     */
    public static AppRuntime start(AppSettings settings, SessionSecrets sessionSecrets, String version) {
        List<SourceSettingsConfig> sources = new ArrayList<>();
        Set<String> waiting = new LinkedHashSet<>();
        for (AppSettings.SourceEntry entry : settings.sources()) {
            // Ein in der App eingegebener (oder aus dem Tresor geladener) Wert geht einer Property oder Variable vor.
            if (sessionSecrets.has(entry)) {
                sources.add(sessionSecrets.apply(entry.config()));
            } else if (!entry.secretMissing()) {
                sources.add(entry.config());
            } else {
                waiting.add(entry.id());
            }
        }
        // Eine fehlerhafte Quelle soll die übrigen nicht verhindern: Der Core prüft alle Quellen und nennt jede mit
        // ihrer Position; die genannten fallen weg, der Rest startet. Andere Fehler (Proxy, Ablage) brechen ab.
        List<String> rejected = new ArrayList<>();
        Harvester harvester = null;
        AccessPolicy policy = null;
        ConfigurationManager configuration = new ConfigurationManager(configuration(settings, sessionSecrets, version));
        while (harvester == null) {
            Set<String> ids = new LinkedHashSet<>();
            sources.forEach(source -> ids.add(source.id()));
            // Der Aufrufer des Harvesters ist der Bediener; nur er ist freigegeben, nur für konfigurierte Quellen.
            policy = new LocalOperatorPolicy(AccessContext.technicalClient(Harvester.OWNER), ids);
            try {
                harvester = builder(settings, settings.storage(), sources, policy, version, sessionSecrets, true)
                        .configuration(configuration).build();
            } catch (InvalidSourceConfigurationException e) {
                Set<Integer> invalid = new TreeSet<>(Comparator.reverseOrder());
                for (String problem : e.problems()) {
                    Matcher position = SOURCE_POSITION.matcher(problem);
                    if (!position.lookingAt()) {
                        throw e;
                    }
                    invalid.add(Integer.parseInt(position.group(1)));
                    rejected.add(problem);
                }
                invalid.forEach(index -> sources.remove((int) index));
            }
        }
        // Abgeschaltete Quellen bleiben samt Bestand eingerichtet; der Core kennt sie nicht (#188, wie im Enterprise AI
        // Client). Den Schalter legt die App in ihrer Datei ab.
        for (SourceSettingsConfig source : sources) {
            if (settings.disabled(source.id())) {
                harvester.sourceManagement().setEnabled(source.id(), false);
            }
        }
        ActivityLog activities = new ActivityLog(settings.storage().dataDirectory().resolve("app")
                .resolve("activities.txt"));
        return new AppRuntime(settings, harvester, configuration, new Workspace(harvester, new AccessGuard(policy), activities),
                Collections.unmodifiableSet(waiting), List.copyOf(rejected), sessionSecrets.sourceFingerprint());
    }

    /**
     * Prüft eine noch nicht gespeicherte Quelle mit „Verbindung testen“ des Core ({@code SourceManagement.test},
     * #188): in einem eigenen Harvester ohne Quellen in einem leeren Arbeitsordner, damit auch ein noch nicht
     * gespeicherter Proxy gilt (der Datenordner der laufenden App bleibt gesperrt und unberührt).
     *
     * @param settings       die Konfiguration mit der neuen Quelle, z. B. aus {@code ConfigFileEditor.draft}
     * @param sessionSecrets Passwort bzw. Token aus dem Assistenten; geht einer Property oder Variable vor
     * @param scratch        leerer Arbeitsordner; wird danach gelöscht
     * @return Ergebnis und Meldung des Core
     */
    public static SourceManagement.ConnectionCheck probe(AppSettings settings, String sourceId,
                                                         SessionSecrets sessionSecrets, String version, Path scratch) {
        SourceSettingsConfig source = config(settings, sourceId, sessionSecrets);
        AppSettings.Storage storage = new AppSettings.Storage(scratch.resolve("data"), scratch.resolve("exports"),
                scratch.resolve("assets"), settings.storage().maxAssetBytes(), settings.storage().searchTimeout(),
                settings.storage().maxTreeNodes(), null);
        AccessPolicy policy = new LocalOperatorPolicy(AccessContext.technicalClient(Harvester.OWNER), Set.of(sourceId));
        try (Harvester harvester = builder(settings, storage, List.of(), policy, version, sessionSecrets, false)
                .build()) {
            return harvester.sourceManagement().test(source, false);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Die Einstellungen einer Quelle aus {@code settings} mit dem Passwort bzw. Token der Sitzung.
     *
     * @throws IllegalStateException wenn das Geheimnis fehlt
     */
    public static SourceSettingsConfig config(AppSettings settings, String sourceId, SessionSecrets sessionSecrets) {
        AppSettings.SourceEntry entry = settings.sources().stream().filter(s -> s.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Die Quelle " + sourceId + " ist nicht eingerichtet."));
        if (sessionSecrets.has(entry)) {
            return sessionSecrets.apply(entry.config());
        }
        if (!entry.secretMissing()) {
            return entry.config();
        }
        throw new IllegalStateException("Für den Test fehlt " + (entry.config().auth().type()
                == SourceSettingsConfig.AuthType.PAT ? "das Token" : "das Passwort") + " (Platzhalter "
                + entry.secretVariable() + "). Bitte im Schritt Anmeldung eingeben.");
    }

    private static void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Ein Rest im Temp-Ordner ist harmlos; er enthält keine Geheimnisse.
                }
            });
        } catch (IOException ignored) {
            // wie oben
        }
    }

    private static Harvester.Builder builder(AppSettings settings, AppSettings.Storage storage,
                                             List<SourceSettingsConfig> sources, AccessPolicy policy, String version,
                                             SessionSecrets secrets, boolean withAi) {
        Harvester.Builder builder = Harvester.builder()
                .sources(sources)
                // Die App bedient Einmalcodes (SignInSlot); ohne Wartezeit fehlten solche Dateien sofort.
                .signInWait(SignInRequests.DEFAULT_WAIT)
                .dataDirectory(storage.dataDirectory())
                .exportDirectory(storage.exportDirectory())
                .assetDirectory(storage.assetDirectory())
                .maxAssetBytes(storage.maxAssetBytes())
                .userAgent("space-harvester-app/" + version)
                .proxy(proxy(settings.proxy(), secrets))
                // Vertrauen ohne Bundle regelt der Core (network.tls, #189); das frühere Bundle „windows“ meint dasselbe.
                .trust(settings.tls())
                .trustBundles(TrustStores.of(settings.trustStores(), TrustPolicy.deferred(settings.tls())))
                .asyncTransport(settings.asyncTransport())
                .jobLimits(settings.jobLimits())
                .searchTimeout(storage.searchTimeout())
                .maxTreeNodes(storage.maxTreeNodes())
                .pdfFont(storage.pdfFont())
                .cacheFreshness(new CacheFreshness(settings.cache().maxAge(), settings.cache().versionCheck()))
                .withheldRetention(settings.cache().withheldRetention());
        AppSettings.AiSettings ai = settings.ai();
        if (ai.chunkTokens() != null || ai.chunkOverlapSentences() != null) {
            builder.embeddingChunking(ai.chunkTokens() == null ? KnowledgeTextSegmentation.DEFAULT_MAX_TOKENS
                    : ai.chunkTokens(), ai.chunkOverlapSentences() == null
                    ? KnowledgeTextSegmentation.DEFAULT_OVERLAP_SENTENCES : ai.chunkOverlapSentences());
        }
        builder.accessPolicy(policy);
        return builder;
    }

    /**
     * Die Laufzeitkonfiguration aus der Datei der App (ADR 0015): KI-Dienst mit dem Key der Sitzung, Aktualität,
     * Synchronisierung samt Regeln je Quelle und Freigaben an den KI-Dienst. Proxy und TLS übergibt die App typisiert
     * beim Bauen; ein anderer Datenordner wirkt erst nach einem Neustart.
     */
    static HarvesterConfiguration configuration(AppSettings settings, SessionSecrets secrets, String version) {
        SyncSettings sync = settings.sync();
        SyncSettings withRules = new SyncSettings(sync.events(), sync.interval(), sync.spaces(), sync.beforeSearch(),
                settings.cache().withheldRetention(), sync.catchUpOnStart(), sync.mode(), sync.inventoryInterval(),
                settings.syncRules()).withBackground(sync.backgroundFill(), sync.backgroundParallelism());
        SearchSettings search = SearchSettings.DEFAULT.withCacheFreshness(
                new CacheFreshness(settings.cache().maxAge(), settings.cache().versionCheck()));
        return new HarvesterConfiguration(aiService(settings.ai(), secrets.aiKey(), version), search, withRules,
                NetworkSettings.DEFAULT, new StorageSettings(settings.storage().dataDirectory()));
    }

    /**
     * Ob {@code next} sich von {@code current} nur in Werten unterscheidet, die der Harvester zur Laufzeit übernimmt:
     * KI-Dienst und Modelle, Freigaben, Aktualität, Frist, Synchronisierung und Regeln je Quelle. Quellen, Proxy, TLS,
     * Ablage und die Zerlegung für Embeddings verlangen einen Neustart.
     */
    public static boolean liveChange(AppSettings current, AppSettings next) {
        AppSettings.AiSettings a = current.ai();
        AppSettings.AiSettings b = next.ai();
        if (!java.util.Objects.equals(a.chunkTokens(), b.chunkTokens())
                || !java.util.Objects.equals(a.chunkOverlapSentences(), b.chunkOverlapSentences())) {
            return false;
        }
        return next.equals(current.withLive(next));
    }

    /** Ausgang von {@link #reconfigure}. */
    public enum Reconfiguration {
        /** Der Core hat die neue Version aktiviert, oder es war nichts zu ändern. */
        APPLIED,
        /** Mehr als Laufzeitwerte geändert; nur ein Neustart übernimmt sie. */
        RESTART,
        /** Der Core hat die neue Version abgelehnt; die bisherige gilt unverändert weiter. */
        REJECTED
    }

    /**
     * Übernimmt eine geänderte Datei ohne Neustart, wenn sich nur Laufzeitwerte geändert haben ({@link #liveChange}).
     * Lehnt der Core ab, bleibt die bisherige Konfiguration aktiv (ADR 0015); ein Neustart mit derselben Datei
     * scheiterte genauso und beendete dabei laufende Aufträge.
     */
    public Reconfiguration reconfigure(AppSettings next, SessionSecrets secrets, String version) {
        if (!liveChange(settings, next) || secrets.sourceFingerprint() != startedSecrets) {
            return Reconfiguration.RESTART;
        }
        ConfigurationManager.Activation activation = configuration.apply(configuration(next, secrets, version));
        if (activation.outcome() == ConfigurationManager.Outcome.REJECTED) {
            LOG.log(System.Logger.Level.WARNING, "Neue Konfiguration abgelehnt: {0}", activation.problems());
            lastRejection = activation.problems().stream()
                    .map(problem -> "Nicht übernommen, die bisherige Einstellung gilt weiter: " + problem.key() + ": "
                            + problem.message()).toList();
            return Reconfiguration.REJECTED;
        }
        settings = next;
        lastRejection = List.of();
        return Reconfiguration.APPLIED;
    }

    /** Was der Core bei der letzten abgelehnten Änderung bemängelt hat; leer, wenn die letzte übernommen wurde. */
    public List<String> lastRejection() {
        return lastRejection;
    }

    /** Die wirksame Version der Laufzeitkonfiguration. */
    public long configurationVersion() {
        return configuration.current().version();
    }

    /**
     * Der Proxy aus der Datei mit dem Proxy-Passwort aus Tresor oder Sitzung (#189); die Datei nennt nur den Benutzer.
     * Fehlt das Passwort, meldet sich der Core nicht an und der Proxy antwortet mit 407.
     */
    static ProxySettings proxy(ProxySettings proxy, SessionSecrets secrets) {
        ProxySettings.Authentication auth = proxy.authentication();
        char[] password = secrets.proxyPassword();
        if (auth == null || auth.password() != null || password == null) {
            return proxy;
        }
        try {
            return proxy.withAuthentication(new ProxySettings.Authentication(auth.username(), password));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * Der KI-Dienst für den Core (ADR 0011) mit dem API-Key aus Tresor oder Sitzung. Ohne Dimension bleibt die
     * semantische Suche aus, alles andere startet.
     */
    static AiServiceSettings aiService(AppSettings.AiSettings ai, String apiKey, String version) {
        if (ai.baseUrl() == null) {
            return AiServiceSettings.NONE;
        }
        AiServiceSettings service;
        try {
            service = new AiServiceSettings(URI.create(ai.baseUrl()), apiKey, ai.chatModel(), ai.embeddingModel(),
                    ai.embeddingDimensions(), ai.timeout(), null, Set.copyOf(ai.externalProcessingSources()));
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "KI-Dienst nicht nutzbar: {0}", e.getMessage());
            return AiServiceSettings.NONE;
        }
        if (ai.rerankModel() != null) {
            // Der Cross-Encoder ordnet nur die Trefferliste aus dem Cache neu (ADR 0013); ohne ihn bleibt sie.
            service = service.withRerankModel(ai.rerankModel());
        }
        if (service.embeddingModel() != null && service.embeddingDimensions() == null) {
            // Die Dimension steht in keinem Modellkatalog; der Reiter „KI“ misst sie mit Harvester#aiEmbeddingDimension.
            LOG.log(System.Logger.Level.WARNING, "Dimension des Embedding-Modells fehlt; die semantische Suche bleibt "
                    + "aus");
        }
        return service;
    }

    public AppSettings settings() {
        return settings;
    }

    public Workspace workspace() {
        return workspace;
    }

    /** Was der Core an Quellen beanstandet hat, die deshalb fehlen; je Eintrag Position, ID und Grund. */
    public List<String> rejectedSources() {
        return rejectedSources;
    }

    /** Quellen, die auf Passwort oder Token für diese Sitzung warten. */
    public Set<String> waitingForSecret() {
        return waitingForSecret;
    }

    @Override
    public void close() {
        harvester.close();
    }

    /**
     * Geheimnisse der Quellen im Speicher: in dieser Sitzung eingegeben oder beim Start aus dem Tresor geladen. Diese
     * Klasse schreibt sie nie irgendwohin.
     */
    public static final class SessionSecrets {

        private record Value(String login, String secret) {
        }

        private final Map<String, Value> secrets = Collections.synchronizedMap(new HashMap<>());
        private volatile String aiKey;
        private volatile char[] proxyPassword;

        /**
         * Kennzeichen der Zugangsdaten von Quellen und Proxy, nur im Speicher und nie ausgegeben: Ändert sich eines,
         * muss die Laufzeit neu starten, weil der Core sie beim Bauen übernimmt. Der KI-Key gehört nicht dazu, er
         * wechselt zur Laufzeit.
         */
        int sourceFingerprint() {
            int hash;
            synchronized (secrets) {
                hash = secrets.hashCode();
            }
            char[] proxy = proxyPassword;
            return 31 * hash + (proxy == null ? 0 : java.util.Arrays.hashCode(proxy));
        }

        /**
         * Wofür ein Wert gilt: Anmeldeart, (bei Basic) Benutzer und Platzhalter. Ändert sich eins davon in der Datei,
         * passt ein alter Wert nicht mehr (ein Token taugt nicht als Passwort, ein neuer Platzhalter meint einen
         * neuen Wert) und bleibt unbenutzt.
         */
        public static String login(SourceSettingsConfig.AuthType type, String username, String variable) {
            String kind = type == SourceSettingsConfig.AuthType.BASIC
                    ? "basic:" + (username == null ? "" : username.strip())
                    : String.valueOf(type).toLowerCase(java.util.Locale.ROOT);
            return kind + "|" + (variable == null ? "" : variable.strip());
        }

        public static String login(AppSettings.SourceEntry entry) {
            SourceSettingsConfig.Auth auth = entry.config().auth();
            return login(auth.type(), auth.username(), entry.secretVariable());
        }

        /** @param login {@link #login} der Anmeldung, für die der Wert gilt */
        public void put(String sourceId, String login, char[] secret) {
            secrets.put(sourceId, new Value(login, new String(secret)));
            java.util.Arrays.fill(secret, '\0');
        }

        public boolean has(String sourceId) {
            return secrets.containsKey(sourceId);
        }

        /** Ob ein Wert für genau diese Anmeldung der Quelle da ist. */
        public boolean has(AppSettings.SourceEntry entry) {
            return entry.config().auth().type() != SourceSettingsConfig.AuthType.NONE
                    && has(entry.id(), login(entry));
        }

        /** Ob ein Wert für die Anmeldung {@code login} ({@link #login}) da ist. */
        public boolean has(String sourceId, String login) {
            Value value = secrets.get(sourceId);
            return value != null && value.login().equals(login);
        }

        public void remove(String sourceId) {
            secrets.remove(sourceId);
        }

        SourceSettingsConfig apply(SourceSettingsConfig config) {
            String secret = secrets.get(config.id()).secret();
            SourceSettingsConfig.Auth auth = config.auth();
            SourceSettingsConfig.Auth filled = switch (auth.type()) {
                case NONE -> auth;
                case BASIC -> new SourceSettingsConfig.Auth(auth.type(), auth.username(), secret, null);
                case PAT -> new SourceSettingsConfig.Auth(auth.type(), null, null, secret);
            };
            return new SourceSettingsConfig(config.id(), config.type(), config.title(), config.baseUrl(), filled,
                    config.options(), config.clientCertificate(), config.trustBundle());
        }

        /** API-Key des KI-Dienstes für diese Sitzung oder {@code null}; das übergebene Feld wird geleert. */
        public void aiKey(char[] key) {
            aiKey = key == null || key.length == 0 ? null : new String(key);
            if (key != null) {
                java.util.Arrays.fill(key, '\0');
            }
        }

        String aiKey() {
            return aiKey;
        }

        /** Passwort für die Anmeldung am Proxy für diese Sitzung oder {@code null}; das übergebene Feld wird geleert. */
        public void proxyPassword(char[] password) {
            char[] old = proxyPassword;
            proxyPassword = password == null || password.length == 0 ? null : password.clone();
            if (old != null) {
                java.util.Arrays.fill(old, '\0');
            }
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
        }

        /** Eine Kopie des Proxy-Passworts oder {@code null}; der Aufrufer leert sie nach Gebrauch. */
        public char[] proxyPassword() {
            char[] current = proxyPassword;
            return current == null ? null : current.clone();
        }

        @Override
        public String toString() {
            return "SessionSecrets[" + secrets.size() + " Quelle(n), Werte verborgen]";
        }
    }
}
