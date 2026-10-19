package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.List;
import java.util.function.Function;

/**
 * Quelltyp „SharePoint“ ({@code source.<n>.type=sharepoint}, Ressourcenschema {@code sharepoint}):
 * Dokumentbibliotheken einer Site über die REST-API. Schlüssel: {@code siteUrl} (Adresse der Site, auch aus dem
 * KeePass-Eintrag), {@code credentialRef} (KeePass-Eintrag; leer = Windows-Anmeldung des JDK),
 * {@code startPoints} (Standard {@code .} = alle Dokumentbibliotheken), {@code maxDepth} (Standard 10),
 * {@code maxResources} (Standard 2000), {@code maxFileMegabytes} (Standard 50); nur in der Datei:
 * {@code connectTimeoutMillis}, {@code readTimeoutMillis}, {@code userAgent}.
 */
public final class SharePointSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "sharepoint";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, SharePointKnowledgeSource.SCHEME,
                    "SharePoint")
            .description("Dokumente aus den Bibliotheken einer SharePoint-Site (REST-API); PDF, Office und Text über Apache Tika")
            .idPrefix("sharepoint")
            .fullIndexing(true)
            .summaryKey("siteUrl")
            .field(SourceSettingField.text("siteUrl", "Site-URL",
                    "Adresse der SharePoint-Site, z. B. …/sites/team").required()
                    .fromVault(SourceSettingField.VaultValue.LOCATION))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzername (DOMÄNE\\name) und Passwort; leer = Windows-Anmeldung"))
            .field(SourceSettingField.text("startPoints", "Bibliotheken oder Ordner",
                    "Kommagetrennt relativ zur Site, z. B. Dokumente/Handbuch (. = alle Bibliotheken)").withDefault(".").advanced())
            .field(SourceSettingField.of("maxDepth", SourceSettingField.Kind.NUMBER, "Tiefe",
                    "Wie viele Ordnerebenen durchsucht werden").withDefault("10").advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Dateien (optional)", "Obergrenze je Lauf; leer = 2000").advanced())
            .field(SourceSettingField.of("maxFileMegabytes", SourceSettingField.Kind.NUMBER,
                    "Größte Datei in MB (optional)", "Größere Dateien werden übersprungen; leer = 50").advanced())
            .build();

    private static final int MAX_TIMEOUT = 600000;

    private final Function<SecretRef, SharePointCredentialsProvider> credentials;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String defaultUserAgent;
    private final ContentDetector detector;
    private final ExtractionRegistry extraction;

    /**
     * @param credentials      Anmeldedaten zum KeePass-Verweis (Brücke der Composition Root)
     * @param routes           Proxy-Route je Ziel; {@code null} = JVM-Standard
     * @param sslSocketFactory Vertrauensquellen für HTTPS; {@code null} = JVM-Standard
     * @param defaultUserAgent User-Agent, wenn die Quelle keinen eigenen hat; {@code null} = JDK-Standard
     */
    public SharePointSourceProvider(Function<SecretRef, SharePointCredentialsProvider> credentials, HttpRoutePort routes,
                                    SSLSocketFactory sslSocketFactory, String defaultUserAgent,
                                    ContentDetector detector, ExtractionRegistry extraction) {
        if (credentials == null || detector == null || extraction == null) {
            throw new IllegalArgumentException("credentials, detector and extraction must not be null");
        }
        this.credentials = credentials;
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.defaultUserAgent = defaultUserAgent;
        this.detector = detector;
        this.extraction = extraction;
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
    public List<String> validate(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        reader.httpUri("siteUrl", true);
        reader.secretRef("credentialRef");
        maxFileBytes(reader);
        timeouts(reader);
        scope(reader);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return scope(new SourceSettingsReader(TYPE, settings));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        URI siteUrl = reader.httpUri("siteUrl", true);
        SecretRef credentialRef = reader.secretRef("credentialRef");
        long maxFileBytes = maxFileBytes(reader);
        int[] timeouts = timeouts(reader);
        String userAgent = reader.text("userAgent", defaultUserAgent);
        if (reader.hasProblems() || siteUrl == null) {
            throw new IllegalArgumentException("SharePoint-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        SharePointCredentialsProvider provider = credentialRef == null ? SharePointCredentialsProvider.none()
                : credentials.apply(credentialRef);
        return new SharePointKnowledgeSource(sourceId, siteUrl, provider,
                new UrlConnectionSharePointTransport(routes, sslSocketFactory, userAgent, timeouts[0], timeouts[1]),
                detector, extraction, maxFileBytes);
    }

    /** Die Site ohne Schema, z. B. {@code sp.intern/sites/team}; Teil jeder Ressourcen-ID. */
    @Override
    public String locator(SourceSettings settings) {
        return siteLocator(settings.get("siteUrl"));
    }

    static String siteLocator(String siteUrl) {
        return SourceLocators.url(siteUrl);
    }

    private static long maxFileBytes(SourceSettingsReader r) {
        return r.integer("maxFileMegabytes", 50, 1, 2000) * 1024L * 1024L;
    }

    private static int[] timeouts(SourceSettingsReader r) {
        return new int[] {r.integer("connectTimeoutMillis", 15000, 1, MAX_TIMEOUT),
                r.integer("readTimeoutMillis", 60000, 1, MAX_TIMEOUT)};
    }

    private static SourceScope scope(SourceSettingsReader r) {
        return r.scope(SharePointKnowledgeSource.ALL_LIBRARIES, 10, 2000);
    }
}
