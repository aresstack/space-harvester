package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import com.aresstack.enterpriseai.source.api.files.FilePatterns;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;
import com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Quelltyp „Lokale Dateien“ ({@code source.<n>.type=files}, Ressourcenschema {@code file}): ein Verzeichnis,
 * rekursiv. Schlüssel wie bisher: {@code directory}, {@code startPoints} (Standard {@code .}), {@code maxDepth}
 * (Standard 20), {@code maxResources} (Standard 5000), {@code maxFileMegabytes} (Standard 50), {@code include} und
 * {@code exclude} (Glob-Muster, #120). Das Verzeichnis einer Quelle gilt als freigegeben; Links hinaus führen nur in
 * weitere freigegebene Ordner der {@link LocalFileAccessPolicy}.
 */
public final class LocalFilesSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "files";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, "file", "Lokale Dateien")
            .description("Ein Verzeichnis, rekursiv; PDF, Office, Text, Markdown und HTML über Apache Tika")
            .idPrefix("dateien")
            .fullIndexing(true)
            .summaryKey("directory")
            .field(SourceSettingField.of("directory", SourceSettingField.Kind.DIRECTORY, "Verzeichnis",
                    "Ordner mit den Dokumenten, z. B. C:\\Daten\\Handbuch").required())
            .field(SourceSettingField.text("startPoints", "Startpunkte",
                    "Kommagetrennte Unterordner (. = alles)").withDefault(".").advanced())
            .field(SourceSettingField.of("maxDepth", SourceSettingField.Kind.NUMBER, "Tiefe",
                    "Wie viele Verzeichnisebenen durchsucht werden").withDefault("20").advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Dateien (optional)", "Obergrenze je Lauf; leer = 5000").advanced())
            .field(SourceSettingField.of("maxFileMegabytes", SourceSettingField.Kind.NUMBER,
                    "Größte Datei in MB (optional)", "Größere Dateien werden übersprungen; leer = 50").advanced())
            .field(SourceSettingField.text("include", "Nur diese Dateien (optional)",
                    "Glob-Muster, kommagetrennt, z. B. *.pdf, *.docx, *.md; leer = alle Dokumenttypen").advanced())
            .field(SourceSettingField.text("exclude", "Ausschließen (optional)",
                    "Glob-Muster, kommagetrennt, z. B. .git/**, node_modules/**, build/**, *.tmp").advanced())
            .build();

    private final ContentDetector detector;
    private final ExtractionRegistry extraction;
    private final Supplier<LocalFileAccessPolicy> access;

    public LocalFilesSourceProvider(ContentDetector detector, ExtractionRegistry extraction) {
        this(detector, extraction, LocalFileAccessPolicy::none);
    }

    /** @param access the user's folder grants, read on every file access (changes apply without restart) */
    public LocalFilesSourceProvider(ContentDetector detector, ExtractionRegistry extraction,
                                    Supplier<LocalFileAccessPolicy> access) {
        if (detector == null || extraction == null) {
            throw new IllegalArgumentException("detector and extraction must not be null");
        }
        this.detector = detector;
        this.extraction = extraction;
        this.access = access == null ? LocalFileAccessPolicy::none : access;
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
        directory(reader);
        maxFileBytes(reader);
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
        Path directory = directory(reader);
        long maxFileBytes = maxFileBytes(reader);
        if (reader.hasProblems() || directory == null) {
            throw new IllegalArgumentException("Dateiquelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        return new LocalFilesKnowledgeSource(sourceId, directory, detector, extraction, maxFileBytes, access,
                FilePatterns.split(reader.text("include", null)), FilePatterns.split(reader.text("exclude", null)));
    }

    /** Das Verzeichnis absolut mit {@code /} ({@code file:/C:/Daten/Doku/sub/a.txt}). */
    @Override
    public String locator(SourceSettings settings) {
        Path directory = directory(new SourceSettingsReader(TYPE, settings));
        return directory == null ? "" : LocalFilesKnowledgeSource.locatorOf(directory);
    }

    private static Path directory(SourceSettingsReader r) {
        Path directory = r.path("directory", null);
        if (directory == null && r.text("directory", null) == null) {
            r.problem("directory", "fehlt (Pflichtangabe: Verzeichnis mit den Dokumenten)");
        }
        return directory;
    }

    private static long maxFileBytes(SourceSettingsReader r) {
        return r.integer("maxFileMegabytes", 50, 1, 2000) * 1024L * 1024L;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        return r.scope(".", 20, 5000);
    }
}
