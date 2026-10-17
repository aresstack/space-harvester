package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.confluence.ClientCertificateException;
import com.aresstack.spaceharvester.source.confluence.ConfluenceSources;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.X509TrustManager;

/**
 * Quellentyp {@code confluence}: Confluence Data Center über den Adapter aus {@code enterprise-ai-sources}, mit
 * Anmeldung (none, basic, pat), Client-Zertifikat (#33), Proxy-Route (#34), asynchronen Snapshots (#47) und
 * delegierten Aufrufen mit Nutzertoken (#24).
 */
public final class ConfluenceSourceAdapter implements SourceAdapter {

    public static final String TYPE = "confluence";

    /** Standard für {@code options.maxRetries}, wenn die Quelle nichts angibt. */
    static final String DEFAULT_MAX_RETRIES = "2";

    /**
     * {@code options.foreignFileRedirects} ist im Dienst immer {@code false}: Weiterleitungen beim Datei-Download auf
     * einen anderen Host werden nie verfolgt (Upstream-Standard {@code true} gilt für den Desktop-Client). Der Dienst
     * läuft im internen Netz; eine Quelle könnte ihn sonst auf beliebige interne Ziele schicken (SSRF, #90).
     */
    static final String FOREIGN_FILE_REDIRECTS = "false";

    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[^}]*}");

    /** Schlüssel, die der Adapter selbst setzt und die nicht über {@code options} kommen dürfen. */
    private static final Set<String> RESERVED_OPTIONS = Set.of("baseUrl", "title", "credentialRef",
            "clientCertificate.alias", "clientCertificate.use", "clientCertificate.keyStoreFile",
            "clientCertificate.keyStorePasswordRef");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        List<String> problems = new ArrayList<>();
        if (config.baseUrl() == null) {
            problems.add("base-url fehlt");
        }
        problems.addAll(checkAuth(config.auth()));
        problems.addAll(checkCertificate(config.clientCertificate()));
        Map<String, String> credentials = new TreeMap<>(Map.of(
                "auth.username", nullToEmpty(config.auth().username()),
                "auth.password", nullToEmpty(config.auth().password()),
                "auth.token", nullToEmpty(config.auth().token())));
        if (config.clientCertificate().enabled()) {
            // Ein abgeschaltetes Zertifikat darf seine Einstellungen samt Platzhalter behalten; es wird nicht benutzt.
            credentials.put("client-certificate.password", nullToEmpty(config.clientCertificate().password()));
        }
        problems.addAll(checkResolved(credentials));
        for (String key : config.options().keySet()) {
            if (RESERVED_OPTIONS.contains(key)) {
                problems.add("options." + key + " ist nicht erlaubt; dafür gibt es eigene Felder");
            }
        }
        String foreign = config.options().get("foreignFileRedirects");
        if (foreign != null && !FOREIGN_FILE_REDIRECTS.equalsIgnoreCase(foreign.trim())) {
            problems.add("options.foreignFileRedirects ist im Dienst nicht einschaltbar; Weiterleitungen auf fremde Hosts"
                    + " werden nie verfolgt");
        }
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, SourceAdapter.Context context) {
        ConfiguredSecrets secrets = context.secrets();
        switch (config.auth().type()) {
            case BASIC -> secrets.put(config.id(), config.auth().username(), config.auth().password());
            case PAT -> secrets.put(config.id(), null, config.auth().token());
            case NONE -> { }
        }
        ClientCertificate certificate = config.clientCertificate();
        if (certificate.enabled() && certificate.type() == CertificateStoreType.PKCS12) {
            secrets.put(ConfiguredSecrets.certificateRefFor(config.id()), null, certificate.password());
        }
        X509TrustManager trust;
        try {
            trust = config.trustBundle() == null ? null : context.trustBundles().trustManager(config.trustBundle());
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of("trust-bundle: " + e.getMessage()));
        }
        String thumbprint = certificate.enabled() ? certificate.thumbprint() : null;
        KnowledgeSourceProvider provider = ConfluenceSources.provider(secrets, context.routes(), context.userAgent(),
                trust, thumbprint, context.async());
        SourceSettings settings = settings(config);
        List<String> invalid = provider.validate(settings);
        if (!invalid.isEmpty()) {
            throw new InvalidSourceConfigurationException(invalid);
        }
        try {
            // Mit mTLS lädt open() das Zertifikat sofort: Fehler verhindern den Start, statt die Quelle
            // stillschweigend ohne Client-Zertifikat zu betreiben.
            var port = provider.open(SourceAddress.of(config.id()), settings);
            boolean pkcs12 = certificate.enabled() && certificate.type() == CertificateStoreType.PKCS12;
            DelegatedConfluenceHttp delegated = ConfluenceSources.delegatedHttp(config.baseUrl(), secrets,
                    context.routes(), context.userAgent(), trust, thumbprint,
                    certificate.enabled() ? certificate.alias().trim() : null, pkcs12 ? certificate.path() : null,
                    pkcs12 ? ConfiguredSecrets.certificateRefFor(config.id()) : null);
            return new OpenedSource(port, delegated);
        } catch (ClientCertificateException e) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: " + e.getMessage()));
        }
    }

    private static List<String> checkAuth(SourceSettingsConfig.Auth auth) {
        boolean username = present(auth.username());
        boolean password = present(auth.password());
        boolean token = present(auth.token());
        List<String> problems = new ArrayList<>();
        if (auth.type() == null) {
            problems.add("auth.type fehlt (none, basic oder pat)");
            return problems;
        }
        switch (auth.type()) {
            case NONE -> {
                if (username || password || token) {
                    problems.add("auth.type ist none, aber Zugangsdaten sind gesetzt; auth.type basic oder pat angeben");
                }
            }
            case BASIC -> {
                if (!username || !password) {
                    problems.add("auth.type basic braucht auth.username und auth.password");
                }
                if (token) {
                    problems.add("auth.token passt nicht zu auth.type basic");
                }
            }
            case PAT -> {
                if (!token) {
                    problems.add("auth.type pat braucht auth.token");
                }
                if (username || password) {
                    problems.add("auth.username/auth.password passen nicht zu auth.type pat");
                }
            }
        }
        return problems;
    }

    private static List<String> checkCertificate(ClientCertificate certificate) {
        List<String> problems = new ArrayList<>();
        boolean path = certificate.path() != null;
        boolean password = present(certificate.password());
        if (!certificate.enabled()) {
            // Abgeschaltet gilt Standard-TLS; die übrigen Angaben dürfen zum Wiedereinschalten stehen bleiben.
            return problems;
        }
        if (certificate.type() == null) {
            problems.add("client-certificate.type fehlt (pkcs12 oder windows-my)");
        }
        if (!present(certificate.alias())) {
            problems.add("client-certificate.alias fehlt");
        }
        if (certificate.type() == CertificateStoreType.PKCS12) {
            if (!path) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.path");
            }
            if (!password) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.password");
            }
        }
        if (certificate.type() == CertificateStoreType.WINDOWS_MY && (path || password)) {
            problems.add("client-certificate.path/client-certificate.password passen nicht zu client-certificate.type "
                    + "windows-my");
        }
        if (present(certificate.thumbprint()) && !ConfluenceSources.validThumbprint(certificate.thumbprint())) {
            problems.add("client-certificate.thumbprint ist kein SHA-1- (40) oder SHA-256-Fingerabdruck (64 "
                    + "Hex-Zeichen)");
        }
        return problems;
    }

    /**
     * Spring lässt Platzhalter ohne Wert stehen; ein {@code ${...}} darf nie als Zugangsdatum zur Quelle gehen (#22).
     * Die Meldung nennt nur den Namen des Platzhalters.
     */
    private static List<String> checkResolved(Map<String, String> secrets) {
        List<String> problems = new ArrayList<>();
        new TreeMap<>(secrets).forEach((field, value) -> {
            Matcher placeholder = UNRESOLVED.matcher(value);
            if (placeholder.find()) {
                problems.add(field + ": Platzhalter " + placeholder.group() + " hat keinen Wert (Umgebungsvariable, "
                        + "lokale YAML oder Secret im Azure Key Vault mit Profil azure-keyvault prüfen)");
            }
        });
        return problems;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    static SourceSettings settings(SourceSettingsConfig config) {
        Map<String, String> values = new LinkedHashMap<>(config.options());
        // Der Dienst läuft unbeaufsichtigt: kurze Netzfehler, 5xx und 429 werden standardmäßig zweimal wiederholt
        // (Upstream-Standard 0 gilt für den Desktop-Client). 401, 403, 404 und zu große Antworten nie.
        values.putIfAbsent("maxRetries", DEFAULT_MAX_RETRIES);
        // Ein Anhang, der auf einen fremden Host (CDN, Objektspeicher) umleitet, wird nie dort geladen.
        values.put("foreignFileRedirects", FOREIGN_FILE_REDIRECTS);
        values.put("baseUrl", config.baseUrl().toString());
        if (config.title() != null) {
            values.put("title", config.title());
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            values.put("credentialRef", ConfiguredSecrets.refFor(config.id()).id());
        }
        ClientCertificate certificate = config.clientCertificate();
        // Ohne eingeschaltetes Zertifikat ausdrücklich ohne mTLS, damit der Adapter keinen Alias im Tresor sucht.
        values.put("clientCertificate.use", Boolean.toString(certificate.enabled()));
        if (certificate.enabled()) {
            values.put("clientCertificate.alias", certificate.alias().trim());
            if (certificate.type() == CertificateStoreType.PKCS12) {
                values.put("clientCertificate.keyStoreFile", certificate.path().toString());
                values.put("clientCertificate.keyStorePasswordRef",
                        ConfiguredSecrets.certificateRefFor(config.id()).id());
            }
        }
        return SourceSettings.of(values);
    }

}
