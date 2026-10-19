package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Der externe KI-Dienst unter {@code space-harvester.ai} (ADR 0011), dieselben Namen wie in der Desktop-App
 * ({@link AiServiceSettings}). Den API-Key liefert der Betrieb aus einem Secret-Speicher (Umgebung, Config-Server,
 * Key Vault), nie im Klartext in einer eingecheckten Datei.
 *
 * @param embeddingChunkChars höchstens so viele Zeichen je eingebettetem Stück; 0 (Standard) ohne Zeichengrenze wie
 *                            im Enterprise AI Client, sonst mindestens 100
 * @param embeddingChunkTokens Budget je Stück in Tokens (#181, wie {@code knowledge.chunk.maxTokens}), mindestens 8
 * @param embeddingChunkOverlapSentences so viele Schlusssätze wiederholt das nächste Stück (wie
 *                                       {@code knowledge.chunk.overlapSentences}); 0 ohne Überlappung
 * @param rerankModel         Rerank-Modell des Dienstes ({@code POST /rerank}, #181) oder leer
 * @param visionModel         Vision-Modell für Bildbeschreibungen (ADR 0021) oder leer
 */
@ConfigurationProperties("space-harvester.ai")
public record AiProperties(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                           Integer embeddingDimensions, @DefaultValue("30s") Duration timeout, String trustBundle,
                           @DefaultValue Set<String> externalProcessingSources,
                           @DefaultValue("0") int embeddingChunkChars, String rerankModel,
                           @DefaultValue("350") int embeddingChunkTokens,
                           @DefaultValue("1") int embeddingChunkOverlapSentences, String visionModel) {

    AiServiceSettings toSettings() {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout,
                trustBundle, externalProcessingSources, rerankModel, visionModel);
    }

    /** Ohne API-Key. */
    @Override
    public String toString() {
        return toSettings().toString();
    }
}
