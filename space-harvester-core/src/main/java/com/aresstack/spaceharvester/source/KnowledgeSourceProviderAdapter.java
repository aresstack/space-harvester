package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;

/**
 * Ein Quellentyp aus einem {@link KnowledgeSourceProvider} des Enterprise AI Client, für Quellen ohne Seitenliste je
 * Partition (#187): Prüfen, Öffnen und Umfang wie dort ({@code KnowledgeSourceManagement.open}): Einstellungen prüfen
 * ({@link KnowledgeSourceProvider#validate}), die Quellenadresse aus Typ und Endpoint bilden
 * ({@link KnowledgeSourceProvider#locator}, {@code KnowledgeSourceType.addressFor}), öffnen und den Umfang der
 * Indexierung ({@link KnowledgeSourceProvider#scope}) mitgeben. Der Core liest die Quelle dann über {@code discover}
 * und {@code load} als eine Partition je Quelle.
 *
 * <p>Die Einstellungen stehen unter {@code options} mit den Schlüsseln des Upstream-Typs, der Titel unter
 * {@code title}; {@code base-url} gibt es auf diesem Weg nicht, die Adresse ist ein Feld des Typs.
 *
 * <p>Ein Typ mit Netz ({@link #connected}) baut seinen Provider je Quelle mit dem Netzweg des Katalogs, wie die
 * Composition Root des Enterprise AI Client ({@code AdapterAssembly.sourceProviders}): Proxy-Route je Ziel
 * ({@link SourceAdapter.Context#routes()}, eine nicht bestimmbare Route lässt die Anfrage scheitern, nie still
 * DIRECT), Server-Vertrauen aus {@code trust-bundle}, Client-Zertifikat aus {@code client-certificate} (mTLS) und
 * Zugangsdaten über den {@link SecretProvider} des Katalogs ({@code auth.type basic}, sonst vom Anbieter des
 * Programms, #188). Ohne {@link #connected} kennt der Typ weder Anmeldung noch Zertifikate.
 */
public final class KnowledgeSourceProviderAdapter implements SourceAdapter {

    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[^}]*}");

    /**
     * Was ein Provider mit Netz je Quelle bekommt.
     *
     * @param secrets   Zugangsdaten der Quellen; die Quelle selbst steht unter dem Verweis im Anmeldefeld
     * @param routes    Proxy-Route je Ziel-URL
     * @param tls       Vertrauen und Client-Zertifikat der Quelle; {@code null} = JVM-Standard
     * @param userAgent User-Agent für HTTP-Aufrufe
     */
    public record Connection(SecretProvider secrets, HttpRoutePort routes, SSLSocketFactory tls, String userAgent) {
    }

    /** Baut den Upstream-Provider für den Netzweg einer Quelle; ohne Ein- und Ausgabe. */
    @FunctionalInterface
    public interface ConnectedProvider {

        KnowledgeSourceProvider provider(Connection connection);
    }

    /** Fest gebauter Provider ohne Netz; {@code null} bei {@link #connected}. */
    private final KnowledgeSourceProvider fixed;
    private final ConnectedProvider factory;
    /** Provider ohne Netz und ohne Zugangsdaten, nur für Typ, Prüfung, Umfang und Adresse. */
    private final KnowledgeSourceProvider unconnected;
    /** Feld des Upstream-Typs für den Verweis auf die Zugangsdaten; {@code null} ohne Anmeldung. */
    private final String credentialKey;
    /** Feld, in das {@code title} geht; {@code null} = das des Upstream-Typs. */
    private final String titleField;
    /** Ob das Protokoll TLS kennt; ohne sind {@code client-certificate} und {@code trust-bundle} Fehler. */
    private final boolean tls;
    /** Ob ein eigener Verweis unter {@code options.<credentialKey>} zum Anbieter des Programms durchgeht (#188). */
    private final boolean credentialRefPassThrough;
    private final boolean needsCredentials;
    /** Anzeigename; {@code null} = der des Upstream-Typs. */
    private final String displayName;

    public KnowledgeSourceProviderAdapter(KnowledgeSourceProvider provider) {
        this.fixed = Objects.requireNonNull(provider, "provider");
        this.factory = null;
        this.unconnected = provider;
        this.credentialKey = null;
        this.titleField = null;
        this.tls = false;
        this.credentialRefPassThrough = false;
        this.needsCredentials = false;
        this.displayName = null;
    }

    private KnowledgeSourceProviderAdapter(ConnectedProvider factory, String credentialKey, String titleField) {
        this(factory, Objects.requireNonNull(factory.provider(new Connection(KnowledgeSourceProviderAdapter::none,
                HttpRoutePort.direct(), null, null)), "provider"), credentialKey, titleField, true, false, false, null);
    }

    private KnowledgeSourceProviderAdapter(ConnectedProvider factory, KnowledgeSourceProvider unconnected,
                                           String credentialKey, String titleField, boolean tls,
                                           boolean credentialRefPassThrough, boolean needsCredentials,
                                           String displayName) {
        this.fixed = null;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.unconnected = unconnected;
        this.credentialKey = credentialKey;
        this.titleField = titleField;
        this.tls = tls;
        this.credentialRefPassThrough = credentialRefPassThrough;
        this.needsCredentials = needsCredentials;
        this.displayName = displayName;
    }

    /**
     * Ein Typ mit Netz (#187), etwa MediaWiki: je Quelle ein eigener Provider mit Route, TLS und Zugangsdaten.
     *
     * @param credentialKey Feld des Upstream-Typs, in das der Verweis {@code source:<id>} auf Benutzer und Passwort
     *                      geht ({@code auth.type basic}); {@code null}, wenn der Typ keine Anmeldung kennt
     * @param titleField    Feld des Upstream-Typs für {@code title}; {@code null} = das des Typs
     */
    public static KnowledgeSourceProviderAdapter connected(ConnectedProvider factory, String credentialKey,
                                                           String titleField) {
        return new KnowledgeSourceProviderAdapter(factory, credentialKey, titleField);
    }

    /**
     * Für Protokolle ohne TLS wie FTP oder NDV: {@code client-certificate} und {@code trust-bundle} werden abgelehnt
     * statt still übergangen.
     */
    public KnowledgeSourceProviderAdapter withoutTls() {
        requireConnected();
        return new KnowledgeSourceProviderAdapter(factory, unconnected, credentialKey, titleField, false,
                credentialRefPassThrough, needsCredentials, displayName);
    }

    /**
     * Lässt einen eigenen Verweis unter {@code options.<credentialKey>} zu, etwa {@code keepass:jes}, den der Anbieter
     * des einbettenden Programms auflöst (#188), wie bisher bei den Kopien aus dem Enterprise AI Client. Zusammen mit
     * {@code auth.type basic} ist er ein Fehler, weil dann {@code source:<id>} gilt.
     */
    public KnowledgeSourceProviderAdapter withCredentialRefPassThrough() {
        requireConnected();
        if (credentialKey == null) {
            throw new IllegalStateException("ohne Anmeldefeld gibt es keinen Verweis");
        }
        return new KnowledgeSourceProviderAdapter(factory, unconnected, credentialKey, titleField, tls, true,
                needsCredentials, displayName);
    }

    /** Der Typ verlangt Zugangsdaten ({@link SourceAdapter#needsCredentials()}), etwa JES oder NDV. */
    public KnowledgeSourceProviderAdapter requiringCredentials() {
        requireConnected();
        return new KnowledgeSourceProviderAdapter(factory, unconnected, credentialKey, titleField, tls,
                credentialRefPassThrough, true, displayName);
    }

    /** Eigener Anzeigename für Auswahllisten statt dem des Upstream-Typs. */
    public KnowledgeSourceProviderAdapter named(String displayName) {
        requireConnected();
        return new KnowledgeSourceProviderAdapter(factory, unconnected, credentialKey, titleField, tls,
                credentialRefPassThrough, needsCredentials, Objects.requireNonNull(displayName, "displayName"));
    }

    private void requireConnected() {
        if (factory == null) {
            throw new IllegalStateException("nur für Typen mit Netz (connected)");
        }
    }

    @Override
    public String type() {
        return unconnected.type().id();
    }

    /** Anzeigename des Upstream-Typs, etwa {@code MediaWiki}. */
    @Override
    public String displayName() {
        return displayName != null ? displayName : unconnected.type().displayName();
    }

    @Override
    public boolean needsCredentials() {
        return needsCredentials;
    }

    /** Das Adressfeld des Upstream-Typs ({@code summaryKey}). */
    @Override
    public String addressOption() {
        return unconnected.type().summaryKey();
    }

    /** Die Felder des Upstream-Typs ohne das Titelfeld, denn {@code title} steht außerhalb von {@code options}. */
    @Override
    public List<SourceField> fields() {
        return SourceFields.of(unconnected.type(), Set.of(titleKey()));
    }

    /** {@code none}, mit Anmeldefeld auch {@code basic}. */
    @Override
    public List<SourceType.AuthMode> authModes() {
        return credentialKey == null ? List.of(SourceType.AuthMode.NONE)
                : List.of(SourceType.AuthMode.NONE, SourceType.AuthMode.BASIC);
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        return check(config, false);
    }

    /** Mit {@code externalSecrets} dürfen Passwort und Zertifikatspasswort fehlen; sie kommen vom Anbieter (#188). */
    @Override
    public List<String> check(SourceSettingsConfig config, boolean externalSecrets) {
        List<String> problems = new ArrayList<>();
        if (config.baseUrl() != null) {
            problems.add("base-url passt nicht zu type " + type() + "; die Adresse steht unter options");
        }
        problems.addAll(checkAuth(config.auth(), externalSecrets));
        if (!tls) {
            if (config.clientCertificate().enabled()) {
                problems.add("client-certificate passt nicht zu type " + type());
            }
            if (config.trustBundle() != null) {
                problems.add("trust-bundle passt nicht zu type " + type());
            }
        } else {
            problems.addAll(SourceTls.problems(config.clientCertificate(), externalSecrets));
        }
        if (factory != null) {
            Map<String, String> secrets = new LinkedHashMap<>();
            secrets.put("auth.password", config.auth().password());
            if (tls && config.clientCertificate().enabled()) {
                secrets.put("client-certificate.password", config.clientCertificate().password());
            }
            problems.addAll(unresolved(secrets));
        }
        if (config.options().containsKey(titleKey())) {
            problems.add("options." + titleKey() + " ist nicht erlaubt; dafür gibt es title");
        }
        if (credentialKey != null && config.options().containsKey(credentialKey)) {
            if (!credentialRefPassThrough) {
                problems.add("options." + credentialKey + " ist nicht erlaubt; dafür gibt es auth");
            } else if (config.auth().type() == SourceSettingsConfig.AuthType.BASIC) {
                problems.add("options." + credentialKey + " passt nicht zu auth.type basic; es gilt auth");
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        SourceSettings settings = settings(config);
        unconnected.validate(settings).forEach(problem -> problems.add(withKey(problem)));
        if (problems.isEmpty() && address(settings).isEmpty()) {
            problems.add("options: die Einstellungen nennen keine Adresse der Quelle");
        }
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        SourceSettings settings = settings(config);
        List<String> problems = unconnected.validate(settings);
        String address = address(settings);
        if (!problems.isEmpty() || address.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems.isEmpty()
                    ? List.of("options: die Einstellungen nennen keine Adresse der Quelle")
                    : problems.stream().map(this::withKey).toList());
        }
        KnowledgeSourceProvider provider = fixed != null ? fixed : connect(config, context);
        try {
            return OpenedSource.discovering(provider.open(SourceAddress.of(address), settings),
                    provider.scope(settings));
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of(e.getMessage()));
        }
    }

    /** Der Provider der Quelle mit Route, TLS und Zugangsdaten des Katalogs. */
    private KnowledgeSourceProvider connect(SourceSettingsConfig config, Context context) {
        SourceSettingsConfig.Auth auth = config.auth();
        // Ohne Passwort in der Konfiguration fragt die Quelle beim Login den Anbieter des Programms (#188).
        if (auth.type() == SourceSettingsConfig.AuthType.BASIC && present(auth.password())) {
            context.secrets().put(config.id(), auth.username(), auth.password());
        }
        return factory.provider(new Connection(context.secrets(), context.routes(),
                tls ? SourceTls.socketFactory(config, context) : null, context.userAgent()));
    }

    /** Ohne Anmeldefeld nur {@code none}; sonst {@code none} oder {@code basic} (Benutzername und Passwort). */
    private List<String> checkAuth(SourceSettingsConfig.Auth auth, boolean externalSecrets) {
        List<String> problems = new ArrayList<>();
        if (auth.type() == null) {
            problems.add("auth.type fehlt (none" + (credentialKey == null ? ")" : " oder basic)"));
            return problems;
        }
        boolean username = present(auth.username());
        boolean password = present(auth.password());
        boolean token = present(auth.token());
        switch (auth.type()) {
            case NONE -> {
                if (username || password || token) {
                    problems.add("auth.type ist none, aber Zugangsdaten sind gesetzt");
                }
            }
            case BASIC -> {
                if (credentialKey == null) {
                    problems.add("auth wird für type " + type() + " noch nicht unterstützt");
                } else {
                    if (externalSecrets ? password && !username : !username || !password) {
                        problems.add("auth.type basic braucht auth.username und auth.password");
                    }
                    if (token) {
                        problems.add("auth.token passt nicht zu auth.type basic");
                    }
                }
            }
            case PAT -> problems.add(credentialKey == null ? "auth wird für type " + type() + " noch nicht unterstützt"
                    : "auth.type pat passt nicht zu type " + type() + " (none oder basic)");
        }
        return problems;
    }

    /**
     * Spring lässt Platzhalter ohne Wert stehen; ein {@code ${...}} darf nie als Zugangsdatum zur Quelle gehen (#22).
     * Die Meldung nennt nur den Namen des Platzhalters.
     */
    private static List<String> unresolved(Map<String, String> secrets) {
        List<String> problems = new ArrayList<>();
        secrets.forEach((field, value) -> {
            Matcher placeholder = UNRESOLVED.matcher(value == null ? "" : value);
            if (placeholder.find()) {
                problems.add(field + ": Platzhalter " + placeholder.group() + " hat keinen Wert");
            }
        });
        return problems;
    }

    /** Die kanonische Quellenadresse wie im Enterprise AI Client; leer ohne Endpoint. */
    private String address(SourceSettings settings) {
        return unconnected.type().addressFor(unconnected.locator(settings));
    }

    /** Upstream meldet mit Anzeigenamen ({@code Adresse: fehlt}); hier zählt der Schlüssel unter options. */
    private String withKey(String problem) {
        for (SourceSettingField field : unconnected.type().fields()) {
            if (problem.startsWith(field.label() + ":")) {
                return "options." + field.key() + problem.substring(field.label().length());
            }
        }
        return "options: " + problem;
    }

    /** Feld des Titels: das angegebene, sonst das des Upstream-Typs, sonst {@code title} wie bei lokalen Dateien. */
    private String titleKey() {
        if (titleField != null) {
            return titleField;
        }
        return unconnected.type().titleKey().isEmpty() ? "title" : unconnected.type().titleKey();
    }

    private SourceSettings settings(SourceSettingsConfig config) {
        Map<String, String> values = new LinkedHashMap<>(config.options());
        if (config.title() != null) {
            values.put(titleKey(), config.title());
        }
        if (credentialKey != null && config.auth().type() == SourceSettingsConfig.AuthType.BASIC) {
            values.put(credentialKey, ConfiguredSecrets.refFor(config.id()).id());
        }
        return SourceSettings.of(values);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static SecretMaterial none(SecretRef ref) throws SecretUnavailableException {
        throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                "nur zum Prüfen gebaut");
    }
}
