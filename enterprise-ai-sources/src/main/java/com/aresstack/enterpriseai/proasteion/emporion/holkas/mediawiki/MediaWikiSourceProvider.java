package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
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
 * Quelltyp „MediaWiki“ ({@code source.<n>.type=mediawiki}, Ressourcenschema {@code wiki}): Felder, Prüfung und
 * Aufbau einer {@link MediaWikiKnowledgeSource}. Schlüssel wie bisher: {@code apiUrl}, {@code credentialRef},
 * {@code startPoints}, {@code maxDepth}, {@code maxResources}, {@code requiresLogin},
 * {@code displayName}; nur in der Datei: {@code connectTimeoutMillis}, {@code readTimeoutMillis},
 * {@code userAgent}, {@code linkNamespaces}.
 */
public final class MediaWikiSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "mediawiki";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, "wiki", "MediaWiki")
            .description("Seiten einer MediaWiki-Site über die api.php, ab Startseiten entlang der Wiki-Links")
            .idPrefix("wiki")
            .summaryKey("apiUrl")
            .field(SourceSettingField.text("apiUrl", "API-URL",
                    "Die api.php der MediaWiki-Installation, z. B. …/w/api.php").required()
                    .fromVault(SourceSettingField.VaultValue.LOCATION))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag", "Eintrag mit Benutzername, Passwort und URL des Wikis; leer = anonym"))
            .field(SourceSettingField.text("startPoints", "Startpunkte", "Kommagetrennte Seitentitel").required())
            .field(SourceSettingField.of("maxDepth", SourceSettingField.Kind.NUMBER, "Tiefe",
                    "Wie viele Linkebenen ab den Startpunkten verfolgt werden (0 = nur Startpunkte)").withDefault("1").advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Seiten (optional)", "Obergrenze je Lauf; leer = Standard").advanced())
            .field(SourceSettingField.of("requiresLogin", SourceSettingField.Kind.FLAG, "Anmeldung erforderlich",
                    "Vor dem Lesen anmelden (Standard: ja, wenn ein KeePass-Eintrag gesetzt ist)").advanced())
            .field(SourceSettingField.text("displayName", "Anzeigename (optional)", "Name der Quelle in Quellenangaben").advanced())
            .build();

    private static final int MAX_TIMEOUT = 600000;

    private final Function<SecretRef, MediaWikiCredentialsProvider> credentials;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String defaultUserAgent;

    /**
     * @param credentials      Zugangsdaten zum KeePass-Verweis (Brücke der Composition Root)
     * @param routes           Proxy-Route je Ziel; {@code null} = JVM-Standard
     * @param sslSocketFactory Vertrauensquellen für HTTPS; {@code null} = JVM-Standard
     * @param defaultUserAgent User-Agent, wenn die Quelle keinen eigenen hat; {@code null} = Standard des Adapters
     */
    public MediaWikiSourceProvider(Function<SecretRef, MediaWikiCredentialsProvider> credentials,
                                   HttpRoutePort routes, SSLSocketFactory sslSocketFactory, String defaultUserAgent) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        this.credentials = credentials;
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.defaultUserAgent = defaultUserAgent;
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
        site(reader);
        reader.scope(null, 1, SourceScope.DEFAULT_MAX_RESOURCES);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return new SourceSettingsReader(TYPE, settings).scope(null, 1, SourceScope.DEFAULT_MAX_RESOURCES);
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        MediaWikiSiteConfig site = site(reader);
        SecretRef credentialRef = reader.secretRef("credentialRef");
        if (reader.hasProblems() || site == null) {
            throw new IllegalArgumentException("MediaWiki-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        MediaWikiCredentialsProvider provider = credentialRef == null ? MediaWikiCredentialsProvider.anonymous()
                : credentials.apply(credentialRef);
        return new MediaWikiKnowledgeSource(sourceId, site, provider, routes, sslSocketFactory);
    }

    /**
     * Kanonischer Endpoint der Wiki-Installation ohne Schema und ohne {@code api.php}, z. B.
     * {@code wiki.intern/w}; Teil jeder Ressourcen-ID ({@code wiki:wiki.intern/w/Hauptseite}).
     */
    @Override
    public String locator(SourceSettings settings) {
        return siteLocator(settings.get("apiUrl"));
    }

    static String siteLocator(String apiUrl) {
        String locator = SourceLocators.url(apiUrl);
        return locator.endsWith("/api.php") ? locator.substring(0, locator.length() - "/api.php".length()) : locator;
    }

    /** Die Site-Konfiguration oder {@code null} (dann sind Probleme vermerkt). */
    private MediaWikiSiteConfig site(SourceSettingsReader r) {
        URI apiUrl = r.httpUri("apiUrl", true);
        SecretRef credentialRef = r.secretRef("credentialRef");
        String displayName = r.text("displayName", null);
        boolean requiresLogin = r.bool("requiresLogin", credentialRef != null);
        int connect = r.integer("connectTimeoutMillis", 15000, 1, MAX_TIMEOUT);
        int read = r.integer("readTimeoutMillis", 30000, 1, MAX_TIMEOUT);
        String userAgent = r.text("userAgent", defaultUserAgent);
        List<String> namespaces = r.list("linkNamespaces");
        if (apiUrl == null) {
            return null;
        }
        try {
            MediaWikiSiteConfig.Builder site = MediaWikiSiteConfig.builder(siteLocator(apiUrl.toString()),
                            apiUrl.toString())
                    .requiresLogin(requiresLogin)
                    .connectTimeoutMillis(connect)
                    .readTimeoutMillis(read);
            if (displayName != null) {
                site.displayName(displayName);
            }
            if (userAgent != null) {
                site.userAgent(userAgent);
            }
            if (!namespaces.isEmpty()) {
                int[] ids = new int[namespaces.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = Integer.parseInt(namespaces.get(i));
                }
                site.linkNamespaces(ids);
            }
            return site.build();
        } catch (NumberFormatException e) {
            r.problem("linkNamespaces", "Namensräume müssen ganze Zahlen sein");
            return null;
        } catch (IllegalArgumentException e) {
            r.problem("apiUrl", "MediaWiki-Einstellung ungültig (Adresse)");
            return null;
        }
    }
}
