package com.aresstack.spaceharvester.nlp.malt;

import java.util.Objects;

/**
 * Welches Modell ein Verbraucher erwartet; jede Abweichung im Verzeichnis ist ein Fehler.
 *
 * @param modelId      {@code maltparser-de-gsd}
 * @param modelVersion Modellversion ({@code 1.0.0})
 * @param configName   MaltParser-Konfigurationsname, zugleich Dateiname ohne {@code .mco}
 * @param mcoSha256    SHA-256 der {@code .mco}-Datei
 */
public record ExpectedModel(String modelId, String modelVersion, String configName, String mcoSha256) {

    public ExpectedModel {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(configName, "configName");
        Objects.requireNonNull(mcoSha256, "mcoSha256");
    }
}
