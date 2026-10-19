package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ein Quellentyp aus einem {@link KnowledgeSourceProvider} des Enterprise AI Client, für Quellen ohne Seitenliste je
 * Partition (#187): Prüfen, Öffnen und Umfang wie dort ({@code KnowledgeSourceManagement.open}): Einstellungen prüfen
 * ({@link KnowledgeSourceProvider#validate}), die Quellenadresse aus Typ und Endpoint bilden
 * ({@link KnowledgeSourceProvider#locator}, {@code KnowledgeSourceType.addressFor}), öffnen und den Umfang der
 * Indexierung ({@link KnowledgeSourceProvider#scope}) mitgeben. Der Core liest die Quelle dann über {@code discover}
 * und {@code load} als eine Partition je Quelle.
 *
 * <p>Die Einstellungen stehen unter {@code options} mit den Schlüsseln des Upstream-Typs, der Titel unter
 * {@code title}. Anmeldung, Zertifikate und {@code base-url} kennt dieser Weg noch nicht (#188).
 */
public final class KnowledgeSourceProviderAdapter implements SourceAdapter {

    private final KnowledgeSourceProvider provider;

    public KnowledgeSourceProviderAdapter(KnowledgeSourceProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public String type() {
        return provider.type().id();
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        List<String> problems = new ArrayList<>();
        if (config.baseUrl() != null) {
            problems.add("base-url passt nicht zu type " + type() + "; die Adresse steht unter options");
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            problems.add("auth wird für type " + type() + " noch nicht unterstützt");
        }
        if (config.clientCertificate().enabled()) {
            problems.add("client-certificate passt nicht zu type " + type());
        }
        if (config.trustBundle() != null) {
            problems.add("trust-bundle passt nicht zu type " + type());
        }
        if (config.options().containsKey(titleKey())) {
            problems.add("options." + titleKey() + " ist nicht erlaubt; dafür gibt es title");
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        SourceSettings settings = settings(config);
        provider.validate(settings).forEach(problem -> problems.add(withKey(problem)));
        if (problems.isEmpty() && address(settings).isEmpty()) {
            problems.add("options: die Einstellungen nennen keine Adresse der Quelle");
        }
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        SourceSettings settings = settings(config);
        List<String> problems = provider.validate(settings);
        String address = address(settings);
        if (!problems.isEmpty() || address.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems.isEmpty()
                    ? List.of("options: die Einstellungen nennen keine Adresse der Quelle")
                    : problems.stream().map(this::withKey).toList());
        }
        try {
            return OpenedSource.discovering(provider.open(SourceAddress.of(address), settings),
                    provider.scope(settings));
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of(e.getMessage()));
        }
    }

    /** Die kanonische Quellenadresse wie im Enterprise AI Client; leer ohne Endpoint. */
    private String address(SourceSettings settings) {
        return provider.type().addressFor(provider.locator(settings));
    }

    /** Upstream meldet mit Anzeigenamen ({@code Adresse: fehlt}); hier zählt der Schlüssel unter options. */
    private String withKey(String problem) {
        for (SourceSettingField field : provider.type().fields()) {
            if (problem.startsWith(field.label() + ":")) {
                return "options." + field.key() + problem.substring(field.label().length());
            }
        }
        return "options: " + problem;
    }

    /** Feld des Titels im Upstream-Typ, sonst {@code title} wie bei lokalen Dateien. */
    private String titleKey() {
        return provider.type().titleKey().isEmpty() ? "title" : provider.type().titleKey();
    }

    private SourceSettings settings(SourceSettingsConfig config) {
        Map<String, String> values = new LinkedHashMap<>(config.options());
        if (config.title() != null) {
            values.put(titleKey(), config.title());
        }
        return SourceSettings.of(values);
    }
}
