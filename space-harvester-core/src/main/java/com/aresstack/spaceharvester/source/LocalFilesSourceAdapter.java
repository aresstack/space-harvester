package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.LocalFilesSourceProvider;
import com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.sources.SourceField;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quellentyp {@code files}: ein lokales Verzeichnis über {@code LocalFilesSourceProvider} aus
 * {@code enterprise-ai-sources} (#18). Eine Partition je Quelle (Schlüssel = Quellen-ID), darin alle lesbaren
 * Dokumente nach relativem Pfad; die Seiten-ID ist der relative Pfad als Base64url, der Inhalt die Datei Byte für
 * Byte. Ohne Netz, Anmeldung und Zertifikat.
 *
 * <p>Erkennung und Extraktion wie im Enterprise AI Client (#183): {@link DocumentExtraction#detector()} (Endung, sonst
 * Apache Tika am Inhalt) und {@link DocumentExtraction#registry()} (Markdown, Klartext, sonst Tika für PDF, Office,
 * OpenDocument, RTF). Den Lesetext für Index und Anreicherungen bildet {@link LocalFilesTextProjector} aus dem
 * abgelegten Original, mit dessen Größen- und Zeitgrenzen.
 *
 * <p>Einstellungen unter {@code options}: {@code directory} (Pflicht) und die übrigen Felder des Upstream-Typs, etwa
 * {@code include}, {@code exclude}, {@code maxFileMegabytes}. Links aus dem Verzeichnis hinaus folgt die Quelle nur in
 * freigegebene Ordner ({@link LocalFileGrants}, wie {@code LocalFileAccessPolicy} im Enterprise AI Client).
 */
public final class LocalFilesSourceAdapter implements SourceAdapter {

    public static final String TYPE = LocalFilesSourceProvider.TYPE_ID;

    /** Standard für {@code options.maxFileMegabytes}: dieselben 20 MiB wie bei Confluence-Antworten (ADR 0002). */
    static final int DEFAULT_MAX_FILE_MEGABYTES = 20;
    /** Obergrenze im Dienst: ein Original wird ganz in den Speicher gelesen. */
    static final int MAX_FILE_MEGABYTES = 64;

    private final LocalFilesSourceProvider provider;

    /** Nur das Verzeichnis der Quelle ist freigegeben. */
    public LocalFilesSourceAdapter() {
        this(LocalFileGrants::none);
    }

    /**
     * Mit den freigegebenen Ordnern der wirksamen Konfiguration ({@code local-files.*}, ADR 0015), bei jedem Zugriff
     * gelesen: Ein Wechsel wirkt ohne Neustart.
     */
    public static LocalFilesSourceAdapter following(ConfigurationManager manager) {
        Objects.requireNonNull(manager, "manager");
        return new LocalFilesSourceAdapter(() -> manager.current().configuration().localFiles().grants());
    }

    /**
     * Mit weiteren freigegebenen Ordnern wie im Enterprise AI Client ({@code FileAccessConfig}); gelesen bei jedem
     * Dateizugriff, Änderungen gelten ohne Neustart.
     */
    public LocalFilesSourceAdapter(Supplier<LocalFileGrants> grants) {
        Objects.requireNonNull(grants, "grants");
        this.provider = new LocalFilesSourceProvider(DocumentExtraction.detector(), DocumentExtraction.registry(),
                () -> {
                    LocalFileGrants granted = grants.get();
                    // Nur Verzeichnisse zählen, beim Zugriff statt beim Speichern der Konfiguration: Eine Datei als
                    // Wurzel gäbe über den Präfixvergleich sonst genau sich selbst frei.
                    return granted == null ? LocalFileAccessPolicy.none()
                            : LocalFileAccessPolicy.of(granted.allowAll(),
                                    granted.roots().stream().filter(Files::isDirectory).toList());
                });
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "Ordner";
    }

    @Override
    public String addressOption() {
        return "directory";
    }

    /** Die Felder des Upstream-Typs ohne {@code title}, der außerhalb von {@code options} steht. */
    @Override
    public List<SourceField> fields() {
        return SourceFields.of(provider.type(), Set.of("title"));
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
        String megabytes = config.options().get("maxFileMegabytes");
        if (megabytes != null && megabytes.trim().matches("\\d{1,9}")
                && Integer.parseInt(megabytes.trim()) > MAX_FILE_MEGABYTES) {
            problems.add("options.maxFileMegabytes darf höchstens " + MAX_FILE_MEGABYTES + " sein");
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
        values.putIfAbsent("maxFileMegabytes", Integer.toString(DEFAULT_MAX_FILE_MEGABYTES));
        if (config.title() != null) {
            values.put("title", config.title());
        }
        return SourceSettings.of(values);
    }
}
