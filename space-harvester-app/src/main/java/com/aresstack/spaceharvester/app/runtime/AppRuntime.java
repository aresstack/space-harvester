package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.embedding.onnx.E5Defaults;
import com.aresstack.spaceharvester.embedding.onnx.OnnxEmbeddingSettings;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.io.IOException;
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

    private final AppSettings settings;
    private final Harvester harvester;
    private final Workspace workspace;
    private static final Pattern SOURCE_POSITION = Pattern.compile("sources\\[(\\d+)]");

    private final Set<String> waitingForSecret;
    private final List<String> rejectedSources;

    private AppRuntime(AppSettings settings, Harvester harvester, Workspace workspace, Set<String> waitingForSecret,
                       List<String> rejectedSources) {
        this.settings = settings;
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
            if (sessionSecrets.has(entry.config())) {
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
        while (harvester == null) {
            Set<String> ids = new LinkedHashSet<>();
            sources.forEach(source -> ids.add(source.id()));
            // Der Aufrufer des Harvesters ist der Bediener; nur er ist freigegeben, nur für konfigurierte Quellen.
            policy = new LocalOperatorPolicy(AccessContext.technicalClient(Harvester.OWNER), ids);
            try {
                harvester = builder(settings, settings.storage(), sources, policy, version, true).build();
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
        ActivityLog activities = new ActivityLog(settings.storage().dataDirectory().resolve("app")
                .resolve("activities.txt"));
        return new AppRuntime(settings, harvester, new Workspace(harvester, new AccessGuard(policy), activities),
                Collections.unmodifiableSet(waiting), List.copyOf(rejected));
    }

    /**
     * Prüft eine noch nicht gespeicherte Quelle: startet einen eigenen Harvester nur mit ihr in einem leeren
     * Arbeitsordner (der Datenordner der laufenden App bleibt gesperrt und unberührt) und lädt die Spaces live.
     *
     * @param settings       die Konfiguration mit der neuen Quelle, z. B. aus {@code ConfigFileEditor.draft}
     * @param sessionSecrets Passwort bzw. Token aus dem Assistenten; geht einer Property oder Variable vor
     * @param scratch        leerer Arbeitsordner; wird danach gelöscht
     * @return die sichtbaren Spaces
     */
    public static List<Partition> probe(AppSettings settings, String sourceId, SessionSecrets sessionSecrets,
                                        String version, Path scratch) {
        AppSettings.SourceEntry entry = settings.sources().stream().filter(s -> s.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Die Quelle " + sourceId + " ist nicht eingerichtet."));
        SourceSettingsConfig source;
        if (sessionSecrets.has(entry.config())) {
            source = sessionSecrets.apply(entry.config());
        } else if (!entry.secretMissing()) {
            source = entry.config();
        } else {
            throw new IllegalStateException("Für den Test fehlt " + (entry.config().auth().type()
                    == SourceSettingsConfig.AuthType.PAT ? "das Token" : "das Passwort") + " (Platzhalter "
                    + entry.secretVariable() + "). Bitte im Schritt Anmeldung eingeben.");
        }
        AppSettings.Storage storage = new AppSettings.Storage(scratch.resolve("data"), scratch.resolve("exports"),
                scratch.resolve("assets"), settings.storage().maxAssetBytes(), settings.storage().searchTimeout(),
                settings.storage().maxTreeNodes(), null);
        AccessPolicy policy = new LocalOperatorPolicy(AccessContext.technicalClient(Harvester.OWNER), Set.of(sourceId));
        try (Harvester harvester = builder(settings, storage, List.of(source), policy, version, false).build()) {
            Workspace workspace = new Workspace(harvester, new AccessGuard(policy),
                    new ActivityLog(scratch.resolve("activities.txt")));
            return workspace.spaces(sourceId);
        } finally {
            deleteQuietly(scratch);
        }
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
                                             boolean withEmbedding) {
        Harvester.Builder builder = Harvester.builder()
                .sources(sources)
                .dataDirectory(storage.dataDirectory())
                .exportDirectory(storage.exportDirectory())
                .assetDirectory(storage.assetDirectory())
                .maxAssetBytes(storage.maxAssetBytes())
                .userAgent("space-harvester-app/" + version)
                .proxy(settings.proxy())
                .trustBundles(TrustStores.of(settings.trustStores()))
                .asyncTransport(settings.asyncTransport())
                .jobLimits(settings.jobLimits())
                .searchTimeout(storage.searchTimeout())
                .maxTreeNodes(storage.maxTreeNodes())
                .pdfFont(storage.pdfFont());
        builder.accessPolicy(policy);
        AppSettings.EmbeddingSettings embedding = settings.embedding();
        if (withEmbedding && embedding.enabled()) {
            builder.embedding(new OnnxEmbeddingSettings(embedding.modelFile(), embedding.tokenizerFile(),
                    embedding.modelSha256(), embedding.tokenizerSha256(), embedding.modelId(), embedding.dimensions(),
                    embedding.maxTokens(), embedding.batchSize(), embedding.threads(), embedding.maxConcurrent(),
                    embedding.acquireTimeout(), embedding.retryAfter(), E5Defaults.PASSAGE_PREFIX,
                    E5Defaults.QUERY_PREFIX, E5Defaults.PAD_TOKEN_ID), embedding.maxChunkChars());
        }
        return builder;
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

        /**
         * Wofür ein Wert gilt: Anmeldeart und (bei Basic) Benutzer. Ändert sich das in der Datei, passt ein alter
         * Wert nicht mehr (ein Token taugt nicht als Passwort) und bleibt unbenutzt.
         */
        public static String login(SourceSettingsConfig.AuthType type, String username) {
            return type == SourceSettingsConfig.AuthType.BASIC ? "basic:" + (username == null ? "" : username.strip())
                    : String.valueOf(type).toLowerCase(java.util.Locale.ROOT);
        }

        public static String login(SourceSettingsConfig.Auth auth) {
            return login(auth.type(), auth.username());
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
        public boolean has(SourceSettingsConfig config) {
            return config.auth().type() != SourceSettingsConfig.AuthType.NONE
                    && has(config.id(), login(config.auth()));
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

        @Override
        public String toString() {
            return "SessionSecrets[" + secrets.size() + " Quelle(n), Werte verborgen]";
        }
    }
}
