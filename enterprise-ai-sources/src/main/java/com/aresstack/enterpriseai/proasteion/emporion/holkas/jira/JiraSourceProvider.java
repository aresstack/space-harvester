package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.AddressableKnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Quelltyp „Jira“ ({@code source.<n>.type=jira}, Ressourcenschema {@code jira}): Felder, Prüfung und Aufbau einer
 * {@link JiraKnowledgeSource} samt Transport, wie {@code ConfluenceSourceProvider}. Im Dialog: {@code title},
 * {@code baseUrl}, {@code credentialRef}, das Client-Zertifikat ({@code clientCertificate.alias}; ohne Angabe gilt
 * {@code MM_CertAlias} aus dem KeePass-Eintrag, {@code clientCertificate.use=false} schaltet es ab) und unter
 * „Erweitert“ die JQL-Zusatzbedingung {@code jql}. Wie bei Confluence gibt es keine Startpunkte: indexiert werden
 * alle Vorgänge der Projekte, die der Benutzer unter Wissensquellen angehakt hat
 * ({@link SourceScope#excludedPartitions()}).
 * Nur in der Datei: {@code startPoints}, {@code maxDepth}, {@code maxResources}, {@code pageSize}, {@code maxComments},
 * {@code maxResponseBytes}, {@code maxRetries}, {@code retryBackoffMillis}, {@code maxRetryBackoffMillis},
 * {@code allowInsecureHttp}, {@code connectTimeoutMillis}, {@code readTimeoutMillis} und
 * {@code clientCertificate.keyStoreFile}/{@code .keyStorePasswordRef}.
 */
public final class JiraSourceProvider implements KnowledgeSourceProvider, AddressableKnowledgeSourceProvider {

    public static final String TYPE_ID = "jira";

    static final String CERTIFICATE_ALIAS = "clientCertificate.alias";
    static final String CERTIFICATE_USE = "clientCertificate.use";
    static final String JQL = "jql";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, JiraIds.SCHEME, "Jira")
            .description("Vorgänge aller Projekte von Jira Data Center über die REST-API (nur lesend); welche "
                    + "durchsucht werden, hakt man unter Wissensquellen an")
            .idPrefix("jira")
            .summaryKey("baseUrl")
            .titleKey("title")
            .partitionsLabel("Projekte")
            .field(SourceSettingField.text("title", "Titel",
                    "Name unter Wissensquellen, z. B. wenn es Jira mehrfach gibt").withDefault("Jira"))
            .field(SourceSettingField.text("baseUrl", "Basis-URL", "Basis-URL von Jira, ohne /rest").required()
                    .fromVault(SourceSettingField.VaultValue.LOCATION))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzername und Passwort oder nur einem Personal Access Token "
                            + "(Benutzername leer); leer = anonym"))
            .field(SourceSettingField.of(CERTIFICATE_ALIAS, SourceSettingField.Kind.CLIENT_CERTIFICATE,
                    "Zertifikat", "Windows-Zertifikat für mTLS-Authentifizierung auswählen"))
            .field(SourceSettingField.text(JQL, "JQL-Einschränkung",
                    "Zusätzliche JQL-Bedingung für Suche und Indexierung, z. B. resolution = Unresolved").advanced())
            .build();

    private static final Logger LOG = Logger.getLogger(JiraSourceProvider.class.getName());

    private static final int MAX_TIMEOUT = 600000;

    /** Ohne {@code maxResources} in der Datei kein Vorgangslimit; Obergrenze des Readers. */
    private static final int UNLIMITED = 1000000;

    /** Baut den TLS-Kontext für ein Client-Zertifikat (mTLS); die Composition Root kennt Tresor und Vertrauen. */
    public interface ClientCertificateSockets {
        /**
         * @param alias        Alias des Zertifikats
         * @param keyStoreFile PKCS12-Datei oder {@code null} für den Windows-Zertifikatspeicher
         * @param passwordRef  KeePass-Verweis auf das Passwort der Datei oder {@code null}
         */
        SSLSocketFactory socketFactory(String alias, Path keyStoreFile, SecretRef passwordRef);
    }

    private final SecretProvider secrets;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final ClientCertificateSockets certificates;

    /**
     * @param secrets          Security-Port für Benutzer/Passwort je Anfrage
     * @param routes           Proxy-Route je Ziel; {@code null} = JVM-Standard
     * @param sslSocketFactory Vertrauensquellen ohne Client-Zertifikat; {@code null} = JVM-Standard
     * @param userAgent        User-Agent; {@code null} = Standard des Transports
     * @param certificates     TLS-Kontext mit Client-Zertifikat
     */
    public JiraSourceProvider(SecretProvider secrets, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                              String userAgent, ClientCertificateSockets certificates) {
        if (secrets == null || certificates == null) {
            throw new IllegalArgumentException("secrets and certificates must not be null");
        }
        this.secrets = secrets;
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
        this.certificates = certificates;
    }

    /** Der Typ ohne Laufzeitumgebung (Beschreibung, Felder). */
    public static KnowledgeSourceType sourceType() {
        return TYPE;
    }

    @Override
    public KnowledgeSourceType type() {
        return TYPE;
    }

    @Override
    public Pattern addressPattern() {
        return JiraKnowledgeSource.addressPattern0();
    }

    @Override
    public List<String> validate(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        config(reader);
        timeouts(reader);
        certificate(reader);
        reader.bool(CERTIFICATE_USE, true);
        scope(reader, settings);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return scope(new SourceSettingsReader(TYPE, settings), settings);
    }

    /**
     * Wie {@link SourceSettingsReader#scope}, aber die Startpunkte zerlegt {@link JiraIds#splitStartPoints}: JQL
     * enthält Kommas in Klammern und Anführungszeichen, die der allgemeine Leser als Trenner nähme.
     */
    private static SourceScope scope(SourceSettingsReader reader, SourceSettings settings) {
        List<String> startPoints = JiraIds.splitStartPoints(settings.get("startPoints"));
        SourceScope limits = new SourceSettingsReader(TYPE, settings.with("startPoints", ""))
                .scope(JiraIds.ALL_PROJECTS, 1, UNLIMITED);
        reader.integer("maxDepth", 1, 0, 100);
        reader.integer("maxResources", UNLIMITED, 1, UNLIMITED);
        if (startPoints.isEmpty()) {
            return limits;
        }
        for (String point : startPoints) {
            if (JiraIds.startPoint("", point) == null && !point.startsWith(JiraIds.SCHEME + ":")) {
                reader.problem("startPoints", "unbekannte Form: Projekt-Schlüssel, project:KEY, Vorgangs-Schlüssel oder jql:…");
            }
        }
        return SourceScope.builder().startPoints(startPoints).maxDepth(limits.maxDepth())
                .maxResources(limits.maxResources()).build();
    }

    /** Kanonische Basis-URL ohne Schema ({@code jira.intern/jira}); zwei Kontextpfade sind zwei Quellen. */
    @Override
    public String locator(SourceSettings settings) {
        return SourceLocators.url(settings.get("baseUrl"));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        JiraConfig config = config(reader);
        int[] timeouts = timeouts(reader);
        Certificate certificate = certificate(reader);
        if (reader.hasProblems() || config == null) {
            throw new IllegalArgumentException("Jira-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        UrlConnectionJiraTransport.Builder transport = UrlConnectionJiraTransport.builder()
                .routes(routes)
                .connectTimeoutMillis(timeouts[0])
                .readTimeoutMillis(timeouts[1])
                // Abgerissene Antworten so oft ab dem letzten Byte fortsetzen, wie die Quelle Wiederholungen erlaubt.
                .resumes(config.maxRetries());
        if (userAgent != null) {
            transport.userAgent(userAgent);
        }
        final VaultEntryOptions options = new VaultEntryOptions(secrets, config.credentialRef());
        boolean useCertificate = reader.bool(CERTIFICATE_USE, true);
        if (!useCertificate) {
            LOG.info("[Jira] " + sourceId + ": Kein Zertifikat konfiguriert — ohne mTLS");
            if (sslSocketFactory != null) {
                transport.sslSocketFactory(sslSocketFactory);
            }
        } else if (certificate != null) {
            LOG.info("[Jira] " + sourceId + ": clientCertificate.alias=" + certificate.alias
                    + (certificate.keyStoreFile == null ? " (Windows-MY)" : " (" + certificate.keyStoreFile + ")"));
            transport.sslSocketFactory(certificates.socketFactory(certificate.alias, certificate.keyStoreFile,
                    certificate.passwordRef));
        } else if (config.credentialRef() != null) {
            // Wie MainframeMate: Das Client-Zertifikat steht als MM_CertAlias im KeePass-Eintrag (Windows-MY).
            final SourceAddress id = sourceId;
            transport.sslSocketFactory(new LazySslSocketFactory(() -> {
                String alias;
                try {
                    alias = options.certificateAlias();
                } catch (SecretUnavailableException e) {
                    throw new IOException("KeePass-Eintrag für das Client-Zertifikat nicht lesbar: "
                            + e.getMessage(), e);
                }
                if (alias == null) {
                    LOG.info("[Jira] " + id + ": Kein Zertifikat konfiguriert (weder in der Quelle noch als "
                            + VaultEntryOptions.CERT_ALIAS + " im KeePass-Eintrag) — ohne mTLS");
                    return sslSocketFactory;
                }
                LOG.info("[Jira] " + id + ": Client-Zertifikat '" + alias + "' aus dem KeePass-Eintrag (Windows-MY)");
                return certificates.socketFactory(alias, null, null);
            }));
        } else {
            LOG.info("[Jira] " + sourceId + ": Kein Zertifikat konfiguriert — ohne mTLS");
            if (sslSocketFactory != null) {
                transport.sslSocketFactory(sslSocketFactory);
            }
        }
        return new JiraKnowledgeSource(sourceId, config, transport.build(), secrets, options);
    }

    private static JiraConfig config(SourceSettingsReader r) {
        URI baseUrl = r.httpUri("baseUrl", true);
        SecretRef credentialRef = r.secretRef("credentialRef");
        int pageSize = r.integer("pageSize", 50, 1, 1000);
        int maxComments = r.integer("maxComments", 200, 0, 10000);
        int maxResponseBytes = r.integer("maxResponseBytes", 20 * 1024 * 1024, 1, Integer.MAX_VALUE);
        boolean allowInsecureHttp = r.bool("allowInsecureHttp", false);
        int maxRetries = r.integer("maxRetries", 0, 0, 10);
        int retryBackoffMillis = r.integer("retryBackoffMillis", 1000, 0, 60000);
        int maxRetryBackoffMillis = r.integer("maxRetryBackoffMillis", 30000, 0, 300000);
        String jql = r.text(JQL, "");
        if (baseUrl == null) {
            return null;
        }
        try {
            return JiraConfig.builder(baseUrl)
                    .credentialRef(credentialRef)
                    .pageSize(pageSize)
                    .maxComments(maxComments)
                    .maxResponseBytes(maxResponseBytes)
                    .allowInsecureHttp(allowInsecureHttp)
                    .maxRetries(maxRetries)
                    .retryBackoffMillis(retryBackoffMillis)
                    .maxRetryBackoffMillis(maxRetryBackoffMillis)
                    .jqlFilter(jql)
                    .build();
        } catch (IllegalArgumentException e) {
            r.problem("baseUrl", "Jira-Einstellung ungültig (mit KeePass-Eintrag nur https, JQL ohne ORDER BY)");
            return null;
        }
    }

    private static int[] timeouts(SourceSettingsReader r) {
        return new int[] {r.integer("connectTimeoutMillis", 15000, 1, MAX_TIMEOUT),
                r.integer("readTimeoutMillis", 60000, 1, MAX_TIMEOUT)};
    }

    private static Certificate certificate(SourceSettingsReader r) {
        String alias = r.text(CERTIFICATE_ALIAS, null);
        Path keyStore = r.path("clientCertificate.keyStoreFile", null);
        SecretRef passwordRef = r.secretRef("clientCertificate.keyStorePasswordRef");
        if (alias == null) {
            if (keyStore != null || passwordRef != null) {
                r.problem(CERTIFICATE_ALIAS, "fehlt, obwohl ein Client-Zertifikat konfiguriert ist");
            }
            return null;
        }
        if (keyStore == null && passwordRef != null) {
            r.problem("clientCertificate.keyStorePasswordRef",
                    "nur zusammen mit keyStoreFile sinnvoll (Windows-MY hat kein Passwort)");
        }
        return new Certificate(alias, keyStore, passwordRef);
    }

    private static final class Certificate {
        final String alias;
        final Path keyStoreFile;
        final SecretRef passwordRef;

        Certificate(String alias, Path keyStoreFile, SecretRef passwordRef) {
            this.alias = alias;
            this.keyStoreFile = keyStoreFile;
            this.passwordRef = passwordRef;
        }
    }
}
