package com.aresstack.spaceharvester.nlp.malt;

import java.util.List;

/**
 * Die Felder des Modell-Manifests ({@code model-manifest.json}, Schema {@code maltparser-model-manifest/v1} aus #99),
 * die der Verbraucher prüft. Weitere Felder (Treebank, Metriken, Lizenz) bleiben im Manifest und werden hier nicht
 * gebraucht.
 *
 * @param schemaVersion     {@code maltparser-model-manifest/v1}
 * @param modelId           {@code maltparser-de-gsd}
 * @param modelVersion      Modellversion
 * @param maltParserVersion MaltParser-Version beim Training
 * @param configName        Konfigurationsname
 * @param mcoFile           Dateiname der {@code .mco}
 * @param mcoSha256         SHA-256 der {@code .mco}
 * @param inputFormat       {@code conllu}
 * @param inputColumns      vom Modell genutzte Eingabespalten ({@code FORM}, {@code UPOS})
 * @param labels            DEPREL-Labels aus dem Training
 */
public record ModelManifest(String schemaVersion, String modelId, String modelVersion, String maltParserVersion,
                            String configName, String mcoFile, String mcoSha256, String inputFormat,
                            List<String> inputColumns, List<String> labels) {

    public static final String SCHEMA = "maltparser-model-manifest/v1";

    public ModelManifest {
        inputColumns = inputColumns == null ? List.of() : List.copyOf(inputColumns);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
