package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Connect a BSCW Classic folder to the existing source and indexing pipeline. */
public final class BscwSourceAdapter implements SourceAdapter {

    public static final String TYPE = "bscw";

    private static final int DEFAULT_DEPTH = 20;
    private static final int MAX_DEPTH = 50;
    private static final int DEFAULT_FILE_MEGABYTES = 64;
    private static final int MAX_FILE_MEGABYTES = 64;
    /** Vorgabe und Höchstwert für {@code options.chunkMegabytes} (Download großer Originale auf die Platte). */
    private static final int DEFAULT_CHUNK_MEGABYTES = BscwWebDavKnowledgeSource.DEFAULT_CHUNK_BYTES / (1024 * 1024);

    private final com.aresstack.spaceharvester.application.assets.DocumentText documents;
    private final RetrySettings retry;

    /** Mit der gemeinsamen Tika-Extraktion (Vorgaben von {@code assets.extraction.*}). */
    public BscwSourceAdapter() {
        this(TikaDocumentText.shared());
    }

    /** Mit der Tika-Extraktion des Dienstes, also mit den eingestellten {@code assets.extraction.*} (F39-10). */
    public BscwSourceAdapter(com.aresstack.spaceharvester.application.assets.DocumentText documents) {
        this(documents, RetrySettings.DEFAULT);
    }

    private BscwSourceAdapter(com.aresstack.spaceharvester.application.assets.DocumentText documents,
                              RetrySettings retry) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
        this.retry = java.util.Objects.requireNonNull(retry, "retry");
    }

    /**
     * Derselbe Adapter mit den Wiederholungen aus {@code jobs.retry}: Ein abgerissenes Stück eines Downloads auf die
     * Platte wird so oft neu angefordert, die schon geladenen Stücke bleiben.
     */
    public BscwSourceAdapter withRetry(RetrySettings value) {
        return new BscwSourceAdapter(documents, value);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "BSCW Classic (WebDAV)";
    }

    @Override
    public URI connectionTarget(SourceSettingsConfig config) {
        return rootUri(config);
    }

    @Override
    public String connectionDescription(int partitions) {
        return "BSCW-Startordner über WebDAV erreichbar";
    }

    @Override
    public boolean usesBaseUrl() {
        return true;
    }

    @Override
    public String addressOption() {
        return null;
    }

    @Override
    public List<SourceField> fields() {
        return List.of(
                new SourceField("maxDepth", "Maximale Ordnertiefe",
                        "Anzahl der Ebenen, in denen Unterordner durchsucht werden.",
                        SourceField.Kind.NUMBER, false, true, Integer.toString(DEFAULT_DEPTH)),
                new SourceField("maxFileMegabytes", "Maximale Dateigroesse (MiB)",
                        "Einzelne Downloads auf die zulässige Dateigröße begrenzen.",
                        SourceField.Kind.NUMBER, false, true, Integer.toString(DEFAULT_FILE_MEGABYTES)));
    }

    @Override
    public boolean needsCredentials() {
        return true;
    }

    @Override
    public List<SourceType.AuthMode> authModes() {
        return List.of(SourceType.AuthMode.BASIC);
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        return check(config, false);
    }

    @Override
    public List<String> check(SourceSettingsConfig config, boolean externalSecrets) {
        List<String> problems = new ArrayList<>();
        // Accept legacy options.rootUrl as an input for already configured sources.
        if (config.baseUrl() != null && config.options().containsKey("rootUrl")) {
            problems.add("base-url und options.rootUrl dürfen nicht gleichzeitig gesetzt sein");
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.BASIC) {
            problems.add("BSCW braucht auth.type basic");
        } else if (!externalSecrets && (config.auth().username() == null
                || config.auth().username().isBlank() || config.auth().password() == null
                || config.auth().password().isEmpty())) {
            problems.add("auth.username und auth.password sind erforderlich");
        }
        if (config.clientCertificate().enabled()) {
            problems.add("client-certificate wird von BSCW WebDAV noch nicht unterstuetzt");
        }
        if (config.options().containsKey("credentialRef")) {
            problems.add("options.credentialRef ist nicht erlaubt; verwende auth");
        }
        if (config.options().containsKey("title")) {
            problems.add("options.title ist nicht erlaubt; verwende title");
        }
        for (String option : config.options().keySet()) {
            if (!List.of("rootUrl", "maxDepth", "maxFileMegabytes", "chunkMegabytes").contains(option)) {
                problems.add("Unbekannte BSCW-Option: " + option);
            }
        }
        URI root = rootUri(config);
        if (root == null) {
            problems.add("base-url fehlt (URL eines BSCW-Ordners)");
        } else {
            try {
                validateRoot(root);
            } catch (IllegalArgumentException e) {
                problems.add("base-url muss auf einen BSCW-Ordner verweisen");
            }
        }
        validateNumber(config, problems, "maxDepth", DEFAULT_DEPTH, MAX_DEPTH);
        validateNumber(config, problems, "maxFileMegabytes", DEFAULT_FILE_MEGABYTES, MAX_FILE_MEGABYTES);
        validateNumber(config, problems, "chunkMegabytes", DEFAULT_CHUNK_MEGABYTES, MAX_FILE_MEGABYTES);
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        List<String> problems = check(config, context.secrets().external());
        if (!problems.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems);
        }
        if (config.auth().password() != null && !config.auth().password().isEmpty()) {
            context.secrets().put(config.id(), config.auth().username(), config.auth().password());
        }
        URI root = rootUri(config);
        int depth = number(config, "maxDepth", DEFAULT_DEPTH);
        int maxBytes = number(config, "maxFileMegabytes", DEFAULT_FILE_MEGABYTES) * 1024 * 1024;
        BscwWebDavTransport transport = new BscwWebDavTransport(root, config.id(), context.secrets(),
                context.routes(), context.proxyCredentials(), context.userAgent(), maxBytes,
                context.trustBundles().defaultTrust(), context.timeouts().webDavRequest());
        // Ein Stück je Abruf passt immer in die Antwortgrenze des Transports (maxFileMegabytes).
        int chunk = Math.min(number(config, "chunkMegabytes", DEFAULT_CHUNK_MEGABYTES) * 1024 * 1024, maxBytes);
        BscwWebDavKnowledgeSource port = new BscwWebDavKnowledgeSource(SourceAddress.of(config.id()), root,
                transport, depth, documents, chunk, retry);
        return new OpenedSource(port, null);
    }

    private static URI rootUri(SourceSettingsConfig config) {
        if (config.baseUrl() != null) {
            return config.baseUrl();
        }
        String legacy = config.options().get("rootUrl");
        if (legacy == null || legacy.isBlank()) {
            return null;
        }
        try {
            return URI.create(legacy.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void validateRoot(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean localHttp = "http".equalsIgnoreCase(scheme) && host != null
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        if ((!localHttp && !"https".equalsIgnoreCase(scheme)) || host == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().matches("/sec/bscw\\.cgi/[0-9]+/?")) {
            throw new IllegalArgumentException("Unsupported BSCW folder URL");
        }
    }

    private static void validateNumber(SourceSettingsConfig config, List<String> problems, String field,
                                       int defaultValue, int maximum) {
        String raw = config.options().get(field);
        if (raw == null) {
            return;
        }
        try {
            int number = Integer.parseInt(raw);
            if (number < 1 || number > maximum) {
                problems.add("options." + field + " muss zwischen 1 und " + maximum + " liegen");
            }
        } catch (NumberFormatException e) {
            problems.add("options." + field + " muss eine ganze Zahl sein");
        }
    }

    private static int number(SourceSettingsConfig config, String field, int defaultValue) {
        String value = config.options().get(field);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
