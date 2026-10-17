package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Das Rezept eines Modells, gelesen aus {@code model/maltparser-de-gsd.properties}: Modellversion, gepinnte
 * Treebank-Dateien mit SHA-256, MaltParser-Einstellungen, Featuremodell und Qualitätsgrenzen. Jede Änderung daran ist
 * eine bewusste Modelländerung (README.md, Neutraining).
 */
public record ModelRecipe(
        Path directory,
        String modelId,
        String modelVersion,
        String configName,
        String maltParserVersion,
        Treebank treebank,
        Training training,
        License license,
        Map<String, Double> minimumMetrics) {

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern HEAP = Pattern.compile("\\d+[mg]");
    private static final Pattern INPUT_COLUMN = Pattern.compile("InputColumn\\(\\s*([A-Z]+)\\s*,");
    /** Spaltennamen des MaltParser-Datenformats conllu zu den Namen des Eingabevertrags. */
    private static final Map<String, String> MALT_COLUMNS = Map.of("FORM", "FORM", "UPOSTAG", "UPOS",
            "LEMMA", "LEMMA", "XPOSTAG", "XPOS", "FEATS", "FEATS");

    /** Release-Tag, z. B. {@code maltparser-de-gsd-v1.0.0}. */
    public String releaseTag() {
        return modelId + "-v" + modelVersion;
    }

    public String mcoFileName() {
        return configName + ".mco";
    }

    public Path featureModel() {
        return directory.resolve(training.featureModel());
    }

    public record Treebank(String name, String repository, String release, String commit, List<TreebankFile> files) {

        public TreebankFile file(String role) {
            return files.stream().filter(file -> file.role().equals(role)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Treebank-Datei " + role + " fehlt im Rezept"));
        }

        public String downloadUrl(TreebankFile file) {
            return repository.replace("https://github.com/", "https://raw.githubusercontent.com/")
                    + "/" + commit + "/" + file.name();
        }
    }

    /** Eine gepinnte Datei; {@code role} ist train, dev, test, license oder readme. */
    public record TreebankFile(String role, String name, String sha256) {
    }

    public record Training(String algorithm, String learner, String featureModel, String heap, int timeoutMinutes) {
    }

    /**
     * Vorgesehene Modelllizenz und Stand der Freigabe ({@code pending} bis zur dokumentierten Lizenzprüfung in
     * docs/NLP_MODEL_LICENSE.md, dann {@code approved}). Nur ein freigegebenes Modell darf öffentlich erscheinen.
     */
    public record License(String model, String approval) {

        public boolean approved() {
            return "approved".equals(approval);
        }
    }

    public static ModelRecipe load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ModelRecipe recipe = parse(file.toAbsolutePath().getParent(), properties);
        recipe.checkFeatureModel();
        return recipe;
    }

    static ModelRecipe parse(Path directory, Properties properties) {
        List<TreebankFile> files = new ArrayList<>();
        for (String role : List.of("train", "dev", "test", "license", "readme")) {
            files.add(new TreebankFile(role,
                    required(properties, "treebank.file." + role, null),
                    required(properties, "treebank.file." + role + ".sha256", SHA256)));
        }
        Treebank treebank = new Treebank(
                required(properties, "treebank.name", null),
                required(properties, "treebank.repository", Pattern.compile("https://github\\.com/[\\w.-]+/[\\w.-]+")),
                required(properties, "treebank.release", null),
                required(properties, "treebank.commit", COMMIT),
                List.copyOf(files));
        Training training = new Training(
                required(properties, "training.algorithm", NAME),
                required(properties, "training.learner", Pattern.compile("liblinear|libsvm")),
                required(properties, "training.featureModel", Pattern.compile("[\\w.-]+\\.xml")),
                required(properties, "training.heap", HEAP),
                Integer.parseInt(required(properties, "training.timeoutMinutes", Pattern.compile("[1-9]\\d*"))));
        Map<String, Double> minimum = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("quality.minimum.")).sorted()
                .forEach(key -> minimum.put(key.substring("quality.minimum.".length()),
                        Double.parseDouble(properties.getProperty(key).trim())));
        return new ModelRecipe(directory,
                required(properties, "model.id", NAME),
                required(properties, "model.version", VERSION),
                required(properties, "model.configName", NAME),
                required(properties, "maltparser.version", VERSION),
                treebank, training,
                new License(required(properties, "license.model", null),
                        required(properties, "license.approval", Pattern.compile("pending|approved"))),
                Map.copyOf(minimum));
    }

    /** Das Featuremodell darf nur Spalten aus {@link InputContract#COLUMNS} lesen. */
    void checkFeatureModel() {
        String xml;
        try {
            xml = Files.readString(featureModel(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String column : inputColumns(xml)) {
            if (!InputContract.COLUMNS.contains(column)) {
                throw new IllegalStateException("Featuremodell " + training.featureModel() + " liest " + column
                        + "; erlaubt sind nur " + InputContract.COLUMNS + " (Eingabevertrag mit #98)");
            }
        }
    }

    static List<String> inputColumns(String featureModelXml) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = INPUT_COLUMN.matcher(featureModelXml);
        while (matcher.find()) {
            String column = MALT_COLUMNS.getOrDefault(matcher.group(1), matcher.group(1));
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static String required(Properties properties, String key, Pattern pattern) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rezept: " + key + " fehlt");
        }
        value = value.trim();
        if (pattern != null && !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Rezept: " + key + " = " + value + " ist ungültig");
        }
        return value;
    }
}
