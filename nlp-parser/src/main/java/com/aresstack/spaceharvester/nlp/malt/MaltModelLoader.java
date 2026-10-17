package com.aresstack.spaceharvester.nlp.malt;

import com.aresstack.spaceharvester.nlp.model.ModelUnavailableException;
import com.aresstack.spaceharvester.nlp.model.Sha256;
import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.maltparser.concurrent.ConcurrentMaltParserModel;
import org.maltparser.concurrent.ConcurrentMaltParserService;

/**
 * Lädt ein fertig trainiertes MaltParser-Modell aus einem Verzeichnis mit {@code model-manifest.json} und der
 * {@code .mco} (Inhalt der Release-ZIP aus #99). Geprüft wird, bevor MaltParser die Datei öffnet:
 * <ol>
 *   <li>Manifest-Schema, Modell-ID, Version und Konfigurationsname wie erwartet,</li>
 *   <li>MaltParser-Version des Trainings gleich der Laufzeit ({@value #MALTPARSER_VERSION}),</li>
 *   <li>Eingabeformat CoNLL-U und nur Spalten, die die Vorverarbeitung liefert ({@link #SUPPORTED_COLUMNS}),</li>
 *   <li>Dateiname = Konfigurationsname + {@code .mco} (MaltParser leitet den Namen daraus ab),</li>
 *   <li>SHA-256 der {@code .mco} gleich dem erwarteten Wert und dem Manifest.</li>
 * </ol>
 * Jede Abweichung ist eine {@link ModelUnavailableException}; es wird nichts geladen, ersetzt oder trainiert.
 */
public final class MaltModelLoader {

    public static final String MALTPARSER_VERSION = "1.9.2";
    public static final String MANIFEST = "model-manifest.json";
    /** Eingabespalten, die {@link com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor} füllt. */
    public static final Set<String> SUPPORTED_COLUMNS = Set.of("FORM", "UPOS");

    private MaltModelLoader() {
    }

    /** Lädt das fest hinterlegte deutsche Modell aus #99 aus {@code directory}. */
    public static MaltDependencyParser loadPinned(Path directory) {
        ExpectedModel expected = GermanParserModelPin.expected().orElseThrow(() -> new ModelUnavailableException(
                "Noch kein freigegebenes deutsches Parsermodell hinterlegt (german-parser-model.properties, #99)"));
        return load(directory, expected);
    }

    public static MaltDependencyParser load(Path directory, ExpectedModel expected) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(expected, "expected");
        ModelManifest manifest = readManifest(directory.resolve(MANIFEST));
        require(ModelManifest.SCHEMA.equals(manifest.schemaVersion()), "Manifest-Schema " + manifest.schemaVersion()
                + ", erwartet " + ModelManifest.SCHEMA);
        require(expected.modelId().equals(manifest.modelId()), "Modell " + manifest.modelId() + ", erwartet "
                + expected.modelId());
        require(expected.modelVersion().equals(manifest.modelVersion()), "Modellversion " + manifest.modelVersion()
                + ", erwartet " + expected.modelVersion());
        require(expected.configName().equals(manifest.configName()), "Konfigurationsname " + manifest.configName()
                + ", erwartet " + expected.configName());
        require(MALTPARSER_VERSION.equals(manifest.maltParserVersion()), "Modell trainiert mit MaltParser "
                + manifest.maltParserVersion() + ", Laufzeit ist " + MALTPARSER_VERSION);
        require("conllu".equals(manifest.inputFormat()), "Eingabeformat " + manifest.inputFormat()
                + ", erwartet conllu");
        require(!manifest.inputColumns().isEmpty() && SUPPORTED_COLUMNS.containsAll(manifest.inputColumns()),
                "Modell braucht die Eingabespalten " + manifest.inputColumns() + ", die Vorverarbeitung liefert nur "
                        + SUPPORTED_COLUMNS);
        String fileName = expected.configName() + ".mco";
        require(fileName.equals(manifest.mcoFile()), "Modelldatei " + manifest.mcoFile() + ", erwartet " + fileName);
        Path mco = directory.resolve(fileName);
        require(Files.isRegularFile(mco), "Modelldatei fehlt: " + mco);
        String actual = Sha256.of(mco);
        require(actual.equals(expected.mcoSha256()), "SHA-256 von " + mco + " ist " + actual + ", erwartet "
                + expected.mcoSha256());
        require(actual.equals(manifest.mcoSha256()), "SHA-256 von " + mco + " weicht vom Manifest ab ("
                + manifest.mcoSha256() + ")");
        long started = System.nanoTime();
        ConcurrentMaltParserModel model;
        try {
            model = ConcurrentMaltParserService.initializeParserModel(mco.toFile());
        } catch (Exception e) {
            throw new ModelUnavailableException("MaltParser kann " + mco + " nicht laden", e);
        }
        return new MaltDependencyParser(model, manifest, actual, (System.nanoTime() - started) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    static ModelManifest readManifest(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ModelUnavailableException("Modell-Manifest fehlt: " + file);
        }
        Map<String, Object> json;
        try {
            json = JSON.std.mapFrom(Files.readString(file));
        } catch (IOException e) {
            throw new ModelUnavailableException("Modell-Manifest nicht lesbar: " + file, e);
        }
        Map<String, Object> input = json.get("inputSchema") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return new ModelManifest(text(json.get("schemaVersion")), text(json.get("modelId")),
                text(json.get("modelVersion")), text(json.get("maltParserVersion")), text(json.get("configName")),
                text(json.get("mcoFile")), text(json.get("mcoSha256")), text(input.get("format")),
                strings(input.get("columns")), strings(json.get("labels")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ModelUnavailableException(message);
        }
    }
}
