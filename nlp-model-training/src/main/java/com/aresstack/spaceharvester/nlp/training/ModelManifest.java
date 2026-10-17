package com.aresstack.spaceharvester.nlp.training;

import java.util.List;
import java.util.Map;

/**
 * Maschinenlesbare Beschreibung eines Modells, als {@code model-manifest.json} im Modell-Asset und einzeln als
 * {@code <tag>.manifest.json}. Verbraucher (#98) prüfen damit {@link #mcoSha256} und {@link InputSchema#columns};
 * unbekannte Felder ignorieren sie. Neue Felder sind erlaubt, Umdeutungen brauchen eine neue {@link #SCHEMA}.
 */
public record ModelManifest(
        String schemaVersion,
        String modelId,
        String modelVersion,
        String releaseTag,
        String configName,
        String mcoFile,
        String mcoSha256,
        String maltParserVersion,
        Training training,
        Treebank treebank,
        InputSchema inputSchema,
        List<String> labels,
        Map<String, Double> metrics,
        String evaluationFile,
        License license) {

    public static final String SCHEMA = "maltparser-model-manifest/v1";

    public record Training(String algorithm, String learner, String featureModel, String featureModelSha256,
                           String recipeSha256, String commit, String javaVersion, String gradleVersion,
                           String trainedAt, long durationSeconds, String heap, Long peakRssMiB,
                           int sentences, int words) {
    }

    public record Treebank(String name, String repository, String release, String commit,
                           List<ModelRecipe.TreebankFile> files) {
    }

    public record InputSchema(String format, List<String> columns, String tokenization, String uposTagset,
                              String multiwordTokens, String emptyNodes, String note) {

        static InputSchema current() {
            return new InputSchema(InputContract.FORMAT, InputContract.COLUMNS, InputContract.TOKENIZATION,
                    "UD v2 (17 UPOS)", "removed", "removed",
                    "Ein Satz je Block, Wort-IDs ab 1; nur FORM und UPOS werden gelesen, übrige Spalten dürfen _ sein. "
                            + "Verschmelzungen (am, zum, vom …) vor dem Parsen in syntaktische Wörter auflösen.");
        }
    }

    /**
     * Lizenzangaben. {@code model} ist die vorgesehene Modelllizenz; verbindlich wird sie erst mit der Freigabe in
     * docs/NLP_MODEL_LICENSE.md, bis dahin ist {@code releaseApproval} {@code pending}.
     */
    public record License(String model, String treebankAnnotations, String treebankTexts, String parser,
                          String trainingCode, String releaseApproval, List<String> files) {
    }
}
