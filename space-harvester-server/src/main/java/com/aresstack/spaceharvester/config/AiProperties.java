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
 * @param embeddingChunkChars höchstens so viele Zeichen je eingebettetem Stück
 */
@ConfigurationProperties("space-harvester.ai")
public record AiProperties(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                           Integer embeddingDimensions, @DefaultValue("30s") Duration timeout, String trustBundle,
                           @DefaultValue Set<String> externalProcessingSources,
                           @DefaultValue("1500") int embeddingChunkChars) {

    AiServiceSettings toSettings() {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout,
                trustBundle, externalProcessingSources);
    }

    /** Ohne API-Key. */
    @Override
    public String toString() {
        return toSettings().toString();
    }
}
