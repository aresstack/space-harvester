package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
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

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "BSCW Classic (WebDAV)";
    }

    @Override
    public String addressOption() {
        return "rootUrl";
    }

    @Override
    public List<SourceField> fields() {
        return List.of(
                new SourceField("rootUrl", "WebDAV-Ordner-URL",
                        "URL eines echten BSCW-Ordners verwenden; der persönliche Arbeitsbereich ist kein Ordner.",
                        SourceField.Kind.TEXT, true, false, null),
                new SourceField("maxDepth", "Maximale Ordnertiefe",
                        "Anzahl der Ebenen, in denen Unterordner durchsucht werden.",
                        SourceField.Kind.NUMBER, false, true, Integer.toString(DEFAULT_DEPTH)),
                new SourceField("maxFileMegabytes", "Maximale Dateigroesse (MiB)",
                        "Einzelne Downloads auf die zulässige Dateigröße begrenzen.",
                        SourceField.Kind.NUMBER, false, true, Integer.toString(DEFAULT_FILE_MEGABYTES)));
    }

    @Override
    public boolean needsCredentials() {
        return false;
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
        if (config.baseUrl() != null) {
            problems.add("base-url passt nicht zu BSCW; verwende options.rootUrl");
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.BASIC) {
            problems.add("BSCW braucht auth.type basic");
        } else if (!externalSecrets && (config.auth().username() == null
                || config.auth().username().isBlank() || config.auth().password() == null
                || config.auth().password().isEmpty())) {
            problems.add("auth.username und auth.password sind erforderlich");
        }
        if (config.clientCertificate().enabled()) {
            problems.add("client-certificate wird von BSCW WebDAV nicht unterstuetzt");
        }
        if (config.trustBundle() != null) {
            problems.add("trust-bundle wird von BSCW WebDAV nicht unterstuetzt");
        }
        if (config.options().containsKey("credentialRef")) {
            problems.add("options.credentialRef ist nicht erlaubt; verwende auth");
        }
        if (config.options().containsKey("title")) {
            problems.add("options.title ist nicht erlaubt; verwende title");
        }
        for (String option : config.options().keySet()) {
            if (!List.of("rootUrl", "maxDepth", "maxFileMegabytes").contains(option)) {
                problems.add("Unbekannte BSCW-Option: " + option);
            }
        }
        String url = config.options().get("rootUrl");
        if (url == null || url.isBlank()) {
            problems.add("options.rootUrl fehlt");
        } else {
            try {
                validateRoot(URI.create(url.trim()));
            } catch (IllegalArgumentException e) {
                problems.add("options.rootUrl muss eine HTTPS-URL auf einen BSCW-Ordner sein");
            }
        }
        validateNumber(config, problems, "maxDepth", DEFAULT_DEPTH, MAX_DEPTH);
        validateNumber(config, problems, "maxFileMegabytes", DEFAULT_FILE_MEGABYTES, MAX_FILE_MEGABYTES);
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
        URI root = URI.create(config.options().get("rootUrl").trim());
        int depth = number(config, "maxDepth", DEFAULT_DEPTH);
        int maxBytes = number(config, "maxFileMegabytes", DEFAULT_FILE_MEGABYTES) * 1024 * 1024;
        BscwWebDavTransport transport = new BscwWebDavTransport(root, config.id(), context.secrets(),
                context.routes(), context.proxyCredentials(), context.userAgent(), maxBytes,
                context.trustBundles().defaultTrust());
        BscwWebDavKnowledgeSource port = new BscwWebDavKnowledgeSource(SourceAddress.of(config.id()), root,
                transport, depth, TikaDocumentText.shared());
        return new OpenedSource(port, null);
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
