package com.aresstack.spaceharvester.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.webhooks} (ADR 0014): je Quelle das Geheimnis, mit dem Confluence ihre
 * Webhook-Aufrufe signiert ({@code space-harvester.webhooks.secrets.<Quelle>}). Ohne Geheimnis nimmt der Server für
 * die Quelle keine Aufrufe an, auch wenn {@code sync.events} gilt. Das Geheimnis steht nicht in
 * {@code GET /api/v1/configuration}, nicht im Log und nie in {@link #toString()}.
 *
 * @param secrets Geheimnis je Quellen-ID
 */
@ConfigurationProperties("space-harvester.webhooks")
public record WebhookProperties(Map<String, String> secrets) {

    public WebhookProperties {
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    /** Eine frische Kopie des Geheimnisses oder {@code null}. */
    char[] secret(String sourceId) {
        String secret = secrets.get(sourceId);
        return secret == null || secret.isBlank() ? null : secret.toCharArray();
    }

    @Override
    public String toString() {
        return "WebhookProperties[secrets=" + secrets.keySet() + "]";
    }
}
