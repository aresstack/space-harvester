package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.embedding.onnx.E5Defaults;
import com.aresstack.spaceharvester.embedding.onnx.OnnxEmbeddingSettings;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut aus den gelesenen Einstellungen und den nur in dieser Sitzung eingegebenen Geheimnissen den Harvester und die
 * fachlichen Aktionen. Quellen, deren Passwort oder Token noch fehlt, bleiben bis zur Eingabe außen vor.
 */
public final class AppRuntime implements AutoCloseable {

    private final AppSettings settings;
    private final Harvester harvester;
    private final Workspace workspace;
    private final Set<String> waitingForSecret;

    private AppRuntime(AppSettings settings, Harvester harvester, Workspace workspace, Set<String> waitingForSecret) {
        this.settings = settings;
        this.harvester = harvester;
        this.workspace = workspace;
        this.waitingForSecret = waitingForSecret;
    }

    /**
     * @param sessionSecrets Passwort bzw. Token je Quellen-ID, nur im Speicher
     * @param version        App-Version für den User-Agent
     */
    public static AppRuntime start(AppSettings settings, SessionSecrets sessionSecrets, String version) {
        List<SourceSettingsConfig> sources = new ArrayList<>();
        Set<String> waiting = new LinkedHashSet<>();
        for (AppSettings.SourceEntry entry : settings.sources()) {
            if (!entry.secretMissing()) {
                sources.add(entry.config());
            } else if (sessionSecrets.has(entry.id())) {
                sources.add(sessionSecrets.apply(entry.config()));
            } else {
                waiting.add(entry.id());
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        sources.forEach(source -> ids.add(source.id()));
        Harvester.Builder builder = Harvester.builder()
                .sources(sources)
                .dataDirectory(settings.storage().dataDirectory())
                .exportDirectory(settings.storage().exportDirectory())
                .assetDirectory(settings.storage().assetDirectory())
                .maxAssetBytes(settings.storage().maxAssetBytes())
                .userAgent("space-harvester-app/" + version)
                .proxy(settings.proxy())
                .trustBundles(TrustStores.of(settings.trustStores()))
                .asyncTransport(settings.asyncTransport())
                .jobLimits(settings.jobLimits())
                .searchTimeout(settings.storage().searchTimeout())
                .maxTreeNodes(settings.storage().maxTreeNodes())
                .pdfFont(settings.storage().pdfFont());
        // Der Aufrufer des Harvesters ist der Bediener; nur er ist freigegeben, nur für konfigurierte Quellen.
        AccessPolicy policy = new LocalOperatorPolicy(AccessContext.technicalClient(Harvester.OWNER), ids);
        builder.accessPolicy(policy);
        AppSettings.EmbeddingSettings embedding = settings.embedding();
        if (embedding.enabled()) {
            builder.embedding(new OnnxEmbeddingSettings(embedding.modelFile(), embedding.tokenizerFile(),
                    embedding.modelSha256(), embedding.tokenizerSha256(), embedding.modelId(), embedding.dimensions(),
                    embedding.maxTokens(), embedding.batchSize(), embedding.threads(), embedding.maxConcurrent(),
                    embedding.acquireTimeout(), embedding.retryAfter(), E5Defaults.PASSAGE_PREFIX,
                    E5Defaults.QUERY_PREFIX, E5Defaults.PAD_TOKEN_ID), embedding.maxChunkChars());
        }
        Harvester harvester = builder.build();
        ActivityLog activities = new ActivityLog(settings.storage().dataDirectory().resolve("app")
                .resolve("activities.txt"));
        return new AppRuntime(settings, harvester, new Workspace(harvester, new AccessGuard(policy), activities),
                Collections.unmodifiableSet(waiting));
    }

    public AppSettings settings() {
        return settings;
    }

    public Workspace workspace() {
        return workspace;
    }

    /** Quellen, die auf Passwort oder Token für diese Sitzung warten. */
    public Set<String> waitingForSecret() {
        return waitingForSecret;
    }

    @Override
    public void close() {
        harvester.close();
    }

    /** Geheimnisse, die der Bediener in dieser Sitzung eingegeben hat; sie werden nie gespeichert. */
    public static final class SessionSecrets {

        private final Map<String, String> secrets = Collections.synchronizedMap(new HashMap<>());

        public void put(String sourceId, char[] secret) {
            secrets.put(sourceId, new String(secret));
            java.util.Arrays.fill(secret, '\0');
        }

        public boolean has(String sourceId) {
            return secrets.containsKey(sourceId);
        }

        public void remove(String sourceId) {
            secrets.remove(sourceId);
        }

        SourceSettingsConfig apply(SourceSettingsConfig config) {
            String secret = secrets.get(config.id());
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
