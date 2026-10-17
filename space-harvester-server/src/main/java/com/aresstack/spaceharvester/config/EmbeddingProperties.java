package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.embedding.onnx.E5Defaults;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration unter {@code space-harvester.embedding} (#89, ADR 0004).
 *
 * @param provider      {@code none} (Standard, keine Embeddings) oder {@code onnx} (lokales Modell im Prozess)
 * @param maxChunkChars höchstens so viele Zeichen je eingebettetem Stück; 1500 bleibt bei E5 unter 512 Token
 * @param onnx          Einstellungen des lokalen Modells
 */
@ConfigurationProperties("space-harvester.embedding")
public record EmbeddingProperties(@DefaultValue("none") String provider, @DefaultValue("1500") int maxChunkChars,
                                  @DefaultValue Onnx onnx) {

    /**
     * Das lokale Modell; ohne Angaben gilt die gepinnte Fassung von {@code intfloat/multilingual-e5-small}
     * ({@link E5Defaults}). Modelldateien stellt der Betrieb bereit, geladen wird nichts.
     *
     * @param modelDir        Verzeichnis mit der heruntergeladenen Revision des Modell-Repositorys
     * @param modelFile       ONNX-Datei; Standard {@code <modelDir>/onnx/model.onnx}
     * @param tokenizerFile   Tokenizer; Standard {@code <modelDir>/tokenizer.json}
     * @param modelSha256     Prüfsumme der ONNX-Datei
     * @param tokenizerSha256 Prüfsumme des Tokenizers
     * @param modelId         Kennung samt Revision, Teil jeder Prozessorversion
     * @param dimensions      Länge der Vektoren
     * @param maxTokens       Token je Text, längere werden abgeschnitten
     * @param batchSize       Texte je Modellaufruf
     * @param threads         Rechenthreads je Modellaufruf
     * @param maxConcurrent   gleichzeitige Modellaufrufe
     * @param acquireTimeout  Wartezeit auf einen freien Modellaufruf
     * @param retryAfter      Wartezeit bis zum nächsten Ladeversuch nach einem Fehler
     */
    public record Onnx(Path modelDir, Path modelFile, Path tokenizerFile,
                       @DefaultValue(E5Defaults.MODEL_SHA256) String modelSha256,
                       @DefaultValue(E5Defaults.TOKENIZER_SHA256) String tokenizerSha256,
                       @DefaultValue(E5Defaults.MODEL_ID) String modelId,
                       @DefaultValue("" + E5Defaults.DIMENSIONS) int dimensions,
                       @DefaultValue("" + E5Defaults.MAX_TOKENS) int maxTokens,
                       @DefaultValue("16") int batchSize,
                       @DefaultValue("2") int threads,
                       @DefaultValue("1") int maxConcurrent,
                       @DefaultValue("2m") Duration acquireTimeout,
                       @DefaultValue("5m") Duration retryAfter) {

        Path resolvedModelFile() {
            return resolve(modelFile, E5Defaults.MODEL_FILE);
        }

        Path resolvedTokenizerFile() {
            return resolve(tokenizerFile, E5Defaults.TOKENIZER_FILE);
        }

        private Path resolve(Path explicit, String inModelDir) {
            if (explicit != null) {
                return explicit;
            }
            if (modelDir == null) {
                throw new IllegalArgumentException("space-harvester.embedding.onnx.model-dir fehlt");
            }
            return modelDir.resolve(inModelDir);
        }
    }
}
