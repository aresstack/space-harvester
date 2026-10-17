package com.aresstack.spaceharvester.embedding.onnx;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Einstellungen des lokalen ONNX-Modells.
 *
 * @param modelFile       ONNX-Datei, z. B. {@code onnx/model.onnx} des Modells
 * @param tokenizerFile   {@code tokenizer.json} desselben Modells
 * @param modelSha256     erwartete SHA-256 der ONNX-Datei (hex); eine andere Datei wird nicht geladen
 * @param tokenizerSha256 erwartete SHA-256 des Tokenizers (hex)
 * @param modelId         Kennung samt Revision; ändert sie sich, veralten alle Vektoren
 * @param dimensions      Länge der Vektoren; die Ausgabe des Modells muss sie haben
 * @param maxTokens       höchstens so viele Token je Text, längere werden am Ende abgeschnitten (E5: 512)
 * @param batchSize       Texte je Modellaufruf
 * @param threads         Rechenthreads je Modellaufruf
 * @param maxConcurrent   gleichzeitige Modellaufrufe; begrenzt zusammen mit {@code batchSize} den Speicher
 * @param acquireTimeout  so lange wartet ein Aufruf auf einen freien Platz, sonst gilt das Modell als überlastet
 * @param retryAfter      nach einem gescheiterten Laden wird frühestens so spät erneut geladen
 * @param passagePrefix   Präfix für Dokumenttexte (E5: {@code "passage: "})
 * @param queryPrefix     Präfix für Suchanfragen (E5: {@code "query: "})
 * @param padTokenId      Token-ID zum Auffüllen kürzerer Texte eines Stapels (XLM-R: 1)
 */
public record OnnxEmbeddingSettings(Path modelFile, Path tokenizerFile, String modelSha256, String tokenizerSha256,
                                    String modelId, int dimensions, int maxTokens, int batchSize, int threads,
                                    int maxConcurrent, Duration acquireTimeout, Duration retryAfter,
                                    String passagePrefix, String queryPrefix, long padTokenId) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public OnnxEmbeddingSettings {
        Objects.requireNonNull(modelFile, "modelFile");
        Objects.requireNonNull(tokenizerFile, "tokenizerFile");
        modelSha256 = sha256(modelSha256, "modelSha256");
        tokenizerSha256 = sha256(tokenizerSha256, "tokenizerSha256");
        Objects.requireNonNull(modelId, "modelId");
        requireRange("dimensions", dimensions, 1, 4096);
        requireRange("maxTokens", maxTokens, 4, 8192);
        requireRange("batchSize", batchSize, 1, 256);
        requireRange("threads", threads, 1, 64);
        requireRange("maxConcurrent", maxConcurrent, 1, 64);
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout darf nicht negativ sein");
        }
        if (retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter darf nicht negativ sein");
        }
        passagePrefix = passagePrefix == null ? "" : passagePrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
    }

    private static String sha256(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " muss eine SHA-256 in Hex sein");
        }
        return normalized;
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " muss zwischen " + min + " und " + max + " liegen");
        }
    }
}
