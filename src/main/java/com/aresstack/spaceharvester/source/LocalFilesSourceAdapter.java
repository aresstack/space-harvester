package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.SimpleContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.LocalFilesSourceProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quellentyp {@code files}: ein lokales Verzeichnis über {@code LocalFilesSourceProvider} aus
 * {@code enterprise-ai-sources} (#18). Eine Partition je Quelle (Schlüssel = Quellen-ID), darin alle lesbaren
 * Dokumente nach relativem Pfad; die Seiten-ID ist der relative Pfad als Base64url, der Inhalt die Datei Byte für
 * Byte. Ohne Netz, Anmeldung und Zertifikat; Text wird hier nicht extrahiert.
 *
 * <p>Einstellungen unter {@code options}: {@code directory} (Pflicht) und die übrigen Felder des Upstream-Typs, etwa
 * {@code include}, {@code exclude}, {@code maxFileMegabytes}.
 */
public final class LocalFilesSourceAdapter implements SourceAdapter {

    public static final String TYPE = LocalFilesSourceProvider.TYPE_ID;

    private final LocalFilesSourceProvider provider =
            new LocalFilesSourceProvider(new SimpleContentDetector(), new ExtractionRegistry());

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        List<String> problems = new ArrayList<>();
        if (config.baseUrl() != null) {
            problems.add("base-url passt nicht zu type files; das Verzeichnis steht unter options.directory");
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            problems.add("auth passt nicht zu type files (lokale Dateien haben keine Anmeldung)");
        }
        if (config.clientCertificate().enabled()) {
            problems.add("client-certificate passt nicht zu type files");
        }
        if (config.trustBundle() != null) {
            problems.add("trust-bundle passt nicht zu type files");
        }
        if (config.options().containsKey("title")) {
            problems.add("options.title ist nicht erlaubt; dafür gibt es title");
        }
        if (problems.isEmpty()) {
            provider.validate(settings(config)).forEach(problem -> problems.add(withKey(problem)));
        }
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        try {
            return new OpenedSource(provider.open(SourceAddress.of(config.id()), settings(config)), null);
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of(e.getMessage()));
        }
    }

    /** Upstream meldet mit Anzeigenamen ({@code Verzeichnis: fehlt}); hier zählt der Schlüssel unter options. */
    private String withKey(String problem) {
        for (SourceSettingField field : provider.type().fields()) {
            if (problem.startsWith(field.label() + ":")) {
                return "options." + field.key() + problem.substring(field.label().length());
            }
        }
        return "options: " + problem;
    }

    private static SourceSettings settings(SourceSettingsConfig config) {
        Map<String, String> values = new LinkedHashMap<>(config.options());
        if (config.title() != null) {
            values.put("title", config.title());
        }
        return SourceSettings.of(values);
    }
}
