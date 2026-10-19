package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

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
 * Quelltyp „BetaView“ ({@code source.<n>.type=betaview}, Ressourcenschema {@code betaview}): Dokumente der
 * Ergebnisliste eines BetaView-Favoriten. Schlüssel: {@code baseUrl} (Adresse der Weboberfläche, auch aus dem
 * KeePass-Eintrag), {@code credentialRef} (KeePass-Eintrag, Pflicht), {@code favoriteId}, {@code daysBack}
 * (Standard 60), {@code extension}, {@code form}, {@code report}, {@code jobName} (Muster, Standard {@code *}),
 * {@code maxResources} (Standard 200), {@code maxFileMegabytes} (Standard 50); nur in der Datei: {@code locale}
 * (Standard {@code de}), {@code connectTimeoutMillis}, {@code readTimeoutMillis}, {@code userAgent}.
 */
public final class BetaViewSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "betaview";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, BetaViewKnowledgeSource.SCHEME,
                    "BetaView")
            .description("Listen und Reports aus dem BetaView-Dokumentarchiv: die Ergebnisliste eines Favoriten")
            .idPrefix("betaview")
            .fullIndexing(true)
            .summaryKey("baseUrl")
            .field(SourceSettingField.text("baseUrl", "Adresse",
                    "Weboberfläche von BetaView, z. B. …/betaview/").required()
                    .fromVault(SourceSettingField.VaultValue.LOCATION))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzername und Passwort für BetaView").required())
            .field(SourceSettingField.text("favoriteId", "Favoriten-ID",
                    "ID des Favoriten, dessen Ergebnisliste gelesen wird (favoriteID in der Adresse)").required())
            .field(SourceSettingField.of("daysBack", SourceSettingField.Kind.NUMBER, "Zeitraum in Tagen",
                    "Dokumente der letzten n Tage").withDefault("60"))
            .field(SourceSettingField.text("extension", "Extension", "Muster, z. B. A* (* = alle)").withDefault("*").advanced())
            .field(SourceSettingField.text("form", "Form", "Muster (* = alle)").withDefault("*").advanced())
            .field(SourceSettingField.text("report", "Report", "Muster (* = alle)").withDefault("*").advanced())
            .field(SourceSettingField.text("jobName", "Jobname", "Muster (* = alle)").withDefault("*").advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Dokumente (optional)", "Obergrenze je Lauf; leer = 200").advanced())
            .field(SourceSettingField.of("maxFileMegabytes", SourceSettingField.Kind.NUMBER,
                    "Größtes Dokument in MB (optional)", "Größere Downloads werden abgebrochen; leer = 50").advanced())
            .build();

    private static final int MAX_TIMEOUT = 600000;

    private final Function<SecretRef, BetaViewCredentialsProvider> credentials;
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
    public BetaViewSourceProvider(Function<SecretRef, BetaViewCredentialsProvider> credentials, HttpRoutePort routes,
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
        read(reader);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return scope(new SourceSettingsReader(TYPE, settings));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        Parts parts = read(reader);
        if (reader.hasProblems() || parts == null) {
            throw new IllegalArgumentException("BetaView-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        return new BetaViewKnowledgeSource(sourceId, parts.base, parts.selection, credentials.apply(parts.credentialRef),
                new UrlConnectionBetaViewTransport(routes, sslSocketFactory, reader.text("userAgent", defaultUserAgent),
                        parts.connectTimeout, parts.readTimeout),
                detector, extraction, parts.maxFileBytes);
    }

    /** Die Weboberfläche ohne Schema, z. B. {@code bv.intern/betaview}; Teil jeder Ressourcen-ID. */
    @Override
    public String locator(SourceSettings settings) {
        return baseLocator(settings.get("baseUrl"));
    }

    static String baseLocator(String baseUrl) {
        return SourceLocators.url(baseUrl);
    }

    /** Die Basis mit {@code /} am Ende, damit Aktionen relativ dazu aufgelöst werden. */
    static URI withTrailingSlash(URI uri) {
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    private static final class Parts {
        URI base;
        SecretRef credentialRef;
        BetaViewSelection selection;
        long maxFileBytes;
        int connectTimeout;
        int readTimeout;
    }

    private static Parts read(SourceSettingsReader r) {
        Parts parts = new Parts();
        URI base = r.httpUri("baseUrl", true);
        parts.credentialRef = r.secretRef("credentialRef");
        if (parts.credentialRef == null && r.text("credentialRef", null) == null) {
            r.problem("credentialRef", "fehlt (Pflichtangabe: KeePass-Eintrag mit Benutzername und Passwort)");
        }
        String favoriteId = r.required("favoriteId");
        if (favoriteId != null && !favoriteId.matches("[A-Za-z0-9]+")) {
            r.problem("favoriteId", "nur Buchstaben und Ziffern");
        }
        int daysBack = r.integer("daysBack", 60, 1, 3650);
        parts.maxFileBytes = r.integer("maxFileMegabytes", 50, 1, 2000) * 1024L * 1024L;
        parts.connectTimeout = r.integer("connectTimeoutMillis", 15000, 1, MAX_TIMEOUT);
        parts.readTimeout = r.integer("readTimeoutMillis", 120000, 1, MAX_TIMEOUT);
        scope(r);
        if (base == null || favoriteId == null || r.hasProblems()) {
            return null;
        }
        parts.base = withTrailingSlash(base);
        parts.selection = new BetaViewSelection(favoriteId, r.text("locale", "de"), daysBack, r.text("extension", "*"),
                r.text("form", "*"), r.text("report", "*"), r.text("jobName", "*"));
        return parts;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        return r.scope(".", 0, 200);
    }
}
