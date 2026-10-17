package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
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
 * Quelltyp „Confluence“ ({@code source.<n>.type=confluence}, Ressourcenschema {@code confluence}): Felder,
 * Prüfung und Aufbau einer {@link ConfluenceKnowledgeSource} samt Transport. Im Dialog: {@code title},
 * {@code baseUrl}, {@code credentialRef} und das Client-Zertifikat ({@code clientCertificate.alias}, Auswahl
 * aus dem Windows-Zertifikatspeicher wie MainframeMate; ohne Angabe gilt {@code MM_CertAlias} aus dem
 * KeePass-Eintrag, {@code clientCertificate.use=false} schaltet es ab). Wie in MainframeMate gibt es keine
 * Startpunkte: indexiert werden alle Seiten der Spaces, die der Benutzer unter Wissensquellen angehakt hat
 * ({@link SourceScope#excludedPartitions()}).
 * Nur noch in der Datei: {@code startPoints}, {@code maxDepth}, {@code maxResources}, {@code searchSpaceKeys},
 * {@code includeAttachments}, {@code pageSize}, {@code maxAttachmentBytes},
 * {@code maxResponseBytes}, {@code maxRetries}, {@code retryBackoffMillis}, {@code maxRetryBackoffMillis},
 * {@code allowInsecureHttp}, {@code connectTimeoutMillis}, {@code readTimeoutMillis} und
 * {@code clientCertificate.keyStoreFile}/{@code .keyStorePasswordRef}.
 */
public final class ConfluenceSourceProvider implements KnowledgeSourceProvider, AddressableKnowledgeSourceProvider {

    public static final String TYPE_ID = "confluence";

    /** Alias des Client-Zertifikats; der Schalter „Zertifikat verwenden“ steht unter {@code clientCertificate.use}. */
    static final String CERTIFICATE_ALIAS = "clientCertificate.alias";
    static final String CERTIFICATE_USE = "clientCertificate.use";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, "confluence", "Confluence")
            .description("Alle Spaces von Confluence Data Center über die REST-API; welche durchsucht werden, "
                    + "hakt man unter Wissensquellen an")
            .idPrefix("confluence")
            .summaryKey("baseUrl")
            .titleKey("title")
            .partitionsLabel("Spaces")
            .field(SourceSettingField.text("title", "Titel",
                    "Name unter Wissensquellen, z. B. wenn es Confluence mehrfach gibt").withDefault("Confluence"))
            .field(SourceSettingField.text("baseUrl", "Basis-URL", "Basis-URL von Confluence, ohne /rest").required()
                    .fromVault(SourceSettingField.VaultValue.LOCATION))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzername, Passwort und URL von Confluence; leer = anonym"))
            .field(SourceSettingField.of(CERTIFICATE_ALIAS, SourceSettingField.Kind.CLIENT_CERTIFICATE,
                    "Zertifikat", "Windows-Zertifikat für mTLS-Authentifizierung auswählen"))
            .build();

    private static final Logger LOG = Logger.getLogger(ConfluenceSourceProvider.class.getName());

    private static final int MAX_TIMEOUT = 600000;

    /** Ohne {@code maxResources} in der Datei kein Seitenlimit (wie MainframeMate); Obergrenze des Readers. */
    private static final int UNLIMITED = 1000000;

    /** Startpunkt ohne Angabe: alle Seiten der Space-Schlüssel der Quelle, ohne sie aller Spaces. */
    static final String ALL_SPACES = "space:*";

    /** Baut den TLS-Kontext für ein Client-Zertifikat (mTLS); die Composition Root kennt Tresor und Vertrauen. */
    public interface ClientCertificateSockets {
        /**
         * @param alias        Alias des Zertifikats
         * @param keyStoreFile PKCS12-Datei oder {@code null} für den Windows-Zertifikatspeicher
         * @param passwordRef  KeePass-Verweis auf das Passwort der Datei oder {@code null}
         */
        SSLSocketFactory socketFactory(String alias, Path keyStoreFile, SecretRef passwordRef);
    }

    /**
     * Baut den asynchronen Transport einer Quelle ({@link #withAsyncTransports}). Die Composition Root gibt ihm
     * dieselbe Proxy-Route, dasselbe Vertrauen und dasselbe Client-Zertifikat wie dem synchronen Transport und
     * schließt ihn selbst, wenn sie ihn nicht mehr braucht.
     */
    public interface AsyncTransports {
        /**
         * @param sourceId             die Quelle
         * @param alias                Alias des Client-Zertifikats oder {@code null} ohne mTLS
         * @param keyStoreFile         PKCS12-Datei oder {@code null} für den Windows-Zertifikatspeicher
         * @param passwordRef          KeePass-Verweis auf das Passwort der Datei oder {@code null}
         * @param connectTimeoutMillis Verbindungs-Timeout der Quelle
         * @param readTimeoutMillis    Lese-Timeout der Quelle
         * @return der Transport oder {@code null}, dann bleibt die Quelle synchron
         */
        ConfluenceAsyncHttpTransport transport(SourceAddress sourceId, String alias, Path keyStoreFile,
                                               SecretRef passwordRef, int connectTimeoutMillis,
                                               int readTimeoutMillis);
    }

    private final SecretProvider secrets;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final ClientCertificateSockets certificates;
    private final java.nio.file.Path diagnosticsDirectory;
    private final AsyncTransports asyncTransports;
    private final java.util.concurrent.Executor mapping;

    /**
     * @param secrets          Security-Port für Benutzer/Passwort je Anfrage
     * @param routes           Proxy-Route je Ziel; {@code null} = JVM-Standard
     * @param sslSocketFactory Vertrauensquellen ohne Client-Zertifikat; {@code null} = JVM-Standard
     * @param userAgent        User-Agent; {@code null} = Standard des Transports
     * @param certificates     TLS-Kontext mit Client-Zertifikat
     */
    public ConfluenceSourceProvider(SecretProvider secrets, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                                    String userAgent, ClientCertificateSockets certificates) {
        this(secrets, routes, sslSocketFactory, userAgent, certificates, null);
    }

    /**
     * @param diagnosticsDirectory Verzeichnis für Diagnosedateien (HTML-Antworten statt Dateien) oder {@code null}
     */
    public ConfluenceSourceProvider(SecretProvider secrets, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                                    String userAgent, ClientCertificateSockets certificates,
                                    java.nio.file.Path diagnosticsDirectory) {
        this(secrets, routes, sslSocketFactory, userAgent, certificates, diagnosticsDirectory, null, null);
    }

    private ConfluenceSourceProvider(SecretProvider secrets, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                                     String userAgent, ClientCertificateSockets certificates,
                                     java.nio.file.Path diagnosticsDirectory, AsyncTransports asyncTransports,
                                     java.util.concurrent.Executor mapping) {
        if (secrets == null || certificates == null) {
            throw new IllegalArgumentException("secrets and certificates must not be null");
        }
        this.secrets = secrets;
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
        this.certificates = certificates;
        this.diagnosticsDirectory = diagnosticsDirectory;
        this.asyncTransports = asyncTransports;
        this.mapping = mapping;
    }

    /**
     * Derselbe Provider, dessen Quellen zusätzlich {@link com.aresstack.enterpriseai.source.api.AsyncStoredContentSource}
     * anbieten: Seiten-Snapshots über einen Transport aus {@code transports}, JSON-Abbildung auf {@code mapping}.
     * Ausgenommen sind Quellen, deren Client-Zertifikat erst der KeePass-Eintrag nennt ({@code MM_CertAlias}): Der
     * Alias steht erst beim ersten Verbindungsaufbau fest, sie bleiben synchron.
     *
     * @param transports baut den asynchronen Transport je Quelle
     * @param mapping    Executor für die JSON-Abbildung, nie ein I/O-Thread des Transports
     */
    public ConfluenceSourceProvider withAsyncTransports(AsyncTransports transports,
                                                        java.util.concurrent.Executor mapping) {
        if (transports == null || mapping == null) {
            throw new IllegalArgumentException("transports and mapping must not be null");
        }
        return new ConfluenceSourceProvider(secrets, routes, sslSocketFactory, userAgent, certificates,
                diagnosticsDirectory, transports, mapping);
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
        return ConfluenceLinks.addressPattern();
    }

    @Override
    public List<String> validate(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        config(reader);
        timeouts(reader);
        certificate(reader);
        reader.bool(CERTIFICATE_USE, true);
        reader.scope(ALL_SPACES, 1, UNLIMITED);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return new SourceSettingsReader(TYPE, settings).scope(ALL_SPACES, 1, UNLIMITED);
    }

    /** Kanonische Basis-URL ohne Schema ({@code cf.intern/confluence}); zwei Kontextpfade sind zwei Quellen. */
    @Override
    public String locator(SourceSettings settings) {
        return SourceLocators.url(settings.get("baseUrl"));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        ConfluenceConfig config = config(reader, diagnosticsDirectory);
        int[] timeouts = timeouts(reader);
        Certificate certificate = certificate(reader);
        if (reader.hasProblems() || config == null) {
            throw new IllegalArgumentException("Confluence-Quelle " + sourceId + " ist fehlerhaft: "
                    + reader.problems());
        }
        UrlConnectionConfluenceTransport.Builder transport = UrlConnectionConfluenceTransport.builder()
                .routes(routes)
                .connectTimeoutMillis(timeouts[0])
                .readTimeoutMillis(timeouts[1])
                .anonymousSslSocketFactory(sslSocketFactory); // fremde Hosts nie mit Client-Zertifikat
        if (userAgent != null) {
            transport.userAgent(userAgent);
        }
        final VaultEntryOptions options = new VaultEntryOptions(secrets, config.credentialRef());
        final boolean credentialRefPresent = config.credentialRef() != null;
        boolean useCertificate = reader.bool(CERTIFICATE_USE, true);
        // Zertifikat des asynchronen Transports, wie unten für den synchronen entschieden.
        Certificate asyncCertificate = null;
        boolean asyncPossible = true;
        if (!useCertificate) {
            // „Zertifikat verwenden“ abgewählt: wie MainframeMate ohne mTLS, auch wenn KeePass MM_CertAlias trägt.
            LOG.info("[Confluence] " + sourceId + ": Kein Zertifikat konfiguriert — ohne mTLS");
            if (sslSocketFactory != null) {
                transport.sslSocketFactory(sslSocketFactory);
            }
        } else if (certificate != null) {
            // Erst beim ersten Verbindungsaufbau geladen; ohne erreichbaren Tresor meldet die Quelle je Anfrage
            // UNAVAILABLE, bis das Zertifikat ladbar ist.
            LOG.info("[Confluence] " + sourceId + ": credentialRefPresent=" + credentialRefPresent
                    + " clientCertificate.alias=" + certificate.alias
                    + (certificate.keyStoreFile == null ? " (Windows-MY)" : " (" + certificate.keyStoreFile + ")"));
            transport.sslSocketFactory(certificates.socketFactory(certificate.alias, certificate.keyStoreFile,
                    certificate.passwordRef));
            asyncCertificate = certificate;
        } else if (config.credentialRef() != null) {
            asyncPossible = false;
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
                LOG.info("[Confluence] " + id + ": credentialRefPresent=true " + VaultEntryOptions.CERT_ALIAS + "="
                        + (alias == null ? "" : alias));
                if (alias == null) {
                    LOG.info("[Confluence] " + id + ": Kein Zertifikat konfiguriert (weder in der Quelle noch als "
                            + VaultEntryOptions.CERT_ALIAS + " im KeePass-Eintrag) — ohne mTLS");
                    return sslSocketFactory;
                }
                LOG.info("[Confluence] " + id + ": Client-Zertifikat '" + alias + "' aus dem KeePass-Eintrag "
                        + "(Windows-MY)");
                return certificates.socketFactory(alias, null, null);
            }));
        } else {
            LOG.info("[Confluence] " + sourceId + ": Kein Zertifikat konfiguriert — ohne mTLS");
            if (sslSocketFactory != null) {
                transport.sslSocketFactory(sslSocketFactory);
            }
        }
        ConfluenceAsyncHttpTransport async = null;
        if (asyncTransports != null && asyncPossible) {
            async = asyncCertificate == null
                    ? asyncTransports.transport(sourceId, null, null, null, timeouts[0], timeouts[1])
                    : asyncTransports.transport(sourceId, asyncCertificate.alias, asyncCertificate.keyStoreFile,
                            asyncCertificate.passwordRef, timeouts[0], timeouts[1]);
        } else if (asyncTransports != null) {
            LOG.info("[Confluence] " + sourceId + ": Zertifikat aus dem KeePass-Eintrag — Snapshots nur synchron");
        }
        if (async != null) {
            return new AsyncConfluenceKnowledgeSource(sourceId, config, transport.build(), async, mapping, secrets,
                    options);
        }
        return new ConfluenceKnowledgeSource(sourceId, config, transport.build(), secrets, options);
    }

    private static ConfluenceConfig config(SourceSettingsReader r) {
        return config(r, null);
    }

    private static ConfluenceConfig config(SourceSettingsReader r, java.nio.file.Path diagnosticsDirectory) {
        URI baseUrl = r.httpUri("baseUrl", true);
        SecretRef credentialRef = r.secretRef("credentialRef");
        int pageSize = r.integer("pageSize", 50, 1, 500);
        boolean attachments = r.bool("includeAttachments", false);
        int maxAttachmentBytes = r.integer("maxAttachmentBytes", 5 * 1024 * 1024, 1, Integer.MAX_VALUE);
        int maxResponseBytes = r.integer("maxResponseBytes", 20 * 1024 * 1024, 1, Integer.MAX_VALUE);
        boolean allowInsecureHttp = r.bool("allowInsecureHttp", false);
        int maxRetries = r.integer("maxRetries", 0, 0, 10);
        int retryBackoffMillis = r.integer("retryBackoffMillis", 1000, 0, 60000);
        int maxRetryBackoffMillis = r.integer("maxRetryBackoffMillis", 30000, 0, 300000);
        List<String> spaceKeys = r.list("searchSpaceKeys");
        if (baseUrl == null) {
            return null;
        }
        try {
            ConfluenceConfig.Builder builder = ConfluenceConfig.builder(baseUrl)
                    .credentialRef(credentialRef)
                    .pageSize(pageSize)
                    .includeAttachments(attachments)
                    .maxAttachmentBytes(maxAttachmentBytes)
                    .maxResponseBytes(maxResponseBytes)
                    .allowInsecureHttp(allowInsecureHttp)
                    .maxRetries(maxRetries)
                    .retryBackoffMillis(retryBackoffMillis)
                    .maxRetryBackoffMillis(maxRetryBackoffMillis)
                    .diagnosticsDirectory(diagnosticsDirectory);
            for (String spaceKey : spaceKeys) {
                builder.searchSpaceKey(spaceKey);
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            r.problem("baseUrl", "Confluence-Einstellung ungültig (mit KeePass-Eintrag nur https, Space-Schlüssel "
                    + "prüfen)");
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
