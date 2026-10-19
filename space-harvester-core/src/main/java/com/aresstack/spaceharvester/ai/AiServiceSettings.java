package com.aresstack.spaceharvester.ai;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Der KI-Dienst (ADR 0011): ein OpenAI-kompatibler Endpunkt ({@code /models}, {@code /embeddings}), wie im Enterprise AI
 * Client. Der Core liest keine Umgebungsvariablen und keine Dateien; Server (Spring-Properties) und Desktop-App
 * (Einstellungen, API-Key aus dem Tresor) übergeben die Werte, am einfachsten als {@link Properties} über
 * {@link #fromProperties}.
 *
 * @param baseUrl                   Basisadresse, z. B. {@code https://ki.example.org/v1}; ohne gibt es keinen Dienst
 * @param apiKey                    Bearer-Token oder {@code null}; erscheint nie in {@link #toString()}, Logs oder Fehlern
 * @param chatModel                 Chat-Modell für Suchvarianten (ADR 0017) oder {@code null}
 * @param embeddingModel            Embedding-Modell oder {@code null}; ohne gibt es keine semantische Suche
 * @param embeddingDimensions       Länge der Vektoren des Embedding-Modells; Pflicht mit {@code embeddingModel}, die
 *                                  Oberfläche misst sie einmal mit {@code Harvester.aiEmbeddingDimension}
 * @param timeout                   Lesefrist je Anfrage an den Dienst
 * @param trustBundle               Vertrauensquelle für den Dienst wie {@code trust-bundle} einer Quelle oder
 *                                  {@code null} für das Vertrauen der JVM
 * @param externalProcessingSources Quellen, deren Seiteninhalte an den Dienst gehen dürfen. Das Leserecht eines
 *                                  Benutzers genügt dafür nicht: ohne Eintrag verlässt kein Inhalt dieser Quelle das
 *                                  Haus, und ihre Seiten bekommen keine Embeddings. Suchanfragen selbst gehen immer an
 *                                  den Dienst, wenn die semantische Suche benutzt wird.
 * @param rerankModel               Rerank-Modell ({@code POST /rerank}, #181) oder {@code null}; mit ihm ordnet ein
 *                                  Cross-Encoder die Treffer der semantischen Suche um, aus freigegebenen Quellen
 * @param visionModel               Vision-Modell für Bildbeschreibungen ({@code POST /chat/completions} mit Bild,
 *                                  ADR 0021) oder {@code null}; wie {@code model.vision} im Enterprise AI Client. Ohne
 *                                  bleibt die Bildanalyse aus; Bilder gehen nur aus freigegebenen Quellen an den Dienst
 */
public record AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                                Integer embeddingDimensions, Duration timeout, String trustBundle,
                                Set<String> externalProcessingSources, String rerankModel, String visionModel) {

    /** Präfix der Properties im Server und in der Konfigurationsdatei der App. */
    public static final String PREFIX = "space-harvester.ai.";

    public static final String BASE_URL = "base-url";
    public static final String API_KEY = "api-key";
    public static final String CHAT_MODEL = "chat-model";
    public static final String EMBEDDING_MODEL = "embedding-model";
    public static final String EMBEDDING_DIMENSIONS = "embedding-dimensions";
    public static final String TIMEOUT = "timeout";
    public static final String TRUST_BUNDLE = "trust-bundle";
    public static final String EXTERNAL_PROCESSING_SOURCES = "external-processing-sources";
    public static final String RERANK_MODEL = "rerank-model";
    public static final String VISION_MODEL = "vision-model";

    /** Standard der Lesefrist. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Kein Dienst eingerichtet. */
    public static final AiServiceSettings NONE = new AiServiceSettings(null, null, null, null, null, null, null,
            Set.of(), null, null);

    public AiServiceSettings {
        if (baseUrl != null && !("https".equalsIgnoreCase(baseUrl.getScheme())
                || "http".equalsIgnoreCase(baseUrl.getScheme())) || baseUrl != null && baseUrl.getHost() == null) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " muss eine http(s)-Adresse sein");
        }
        if (baseUrl != null && baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " darf keine Zugangsdaten enthalten");
        }
        apiKey = blankToNull(apiKey);
        chatModel = blankToNull(chatModel);
        embeddingModel = blankToNull(embeddingModel);
        trustBundle = blankToNull(trustBundle);
        if (embeddingDimensions != null && (embeddingDimensions < 1 || embeddingDimensions > 4096)) {
            throw new IllegalArgumentException(PREFIX + EMBEDDING_DIMENSIONS + " muss zwischen 1 und 4096 liegen");
        }
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(PREFIX + TIMEOUT + " muss zwischen 1 ms und 10 min liegen");
        }
        externalProcessingSources = externalProcessingSources == null ? Set.of()
                : Set.copyOf(externalProcessingSources);
        rerankModel = blankToNull(rerankModel);
        visionModel = blankToNull(visionModel);
    }

    /** Ohne Vision-Modell, wie vor ADR 0021. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, null);
    }

    /** Ohne Rerank-Modell, wie vor #181. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, null, null);
    }

    /**
     * Liest die Einstellungen aus Properties mit oder ohne {@link #PREFIX}, z. B. {@code base-url} oder
     * {@code space-harvester.ai.base-url}. Fehlende Werte bleiben leer.
     *
     * @throws IllegalArgumentException bei einem ungültigen Wert, ohne den Wert zu nennen
     */
    public static AiServiceSettings fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String base = value(properties, BASE_URL);
        String dimensions = value(properties, EMBEDDING_DIMENSIONS);
        String timeout = value(properties, TIMEOUT);
        String sources = value(properties, EXTERNAL_PROCESSING_SOURCES);
        URI baseUrl;
        try {
            baseUrl = base == null ? null : URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " ist keine gültige Adresse");
        }
        Integer parsedDimensions;
        try {
            parsedDimensions = dimensions == null ? null : Integer.valueOf(dimensions);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + EMBEDDING_DIMENSIONS + " ist keine Zahl");
        }
        Duration parsedTimeout;
        try {
            parsedTimeout = timeout == null ? null : Duration.parse(timeout);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + TIMEOUT + " ist keine ISO-8601-Dauer wie PT30S");
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (sources != null) {
            Arrays.stream(sources.split(",")).map(String::strip).filter(id -> !id.isEmpty()).forEach(allowed::add);
        }
        return new AiServiceSettings(baseUrl, value(properties, API_KEY), value(properties, CHAT_MODEL),
                value(properties, EMBEDDING_MODEL), parsedDimensions, parsedTimeout, value(properties, TRUST_BUNDLE),
                allowed, value(properties, RERANK_MODEL), value(properties, VISION_MODEL));
    }

    /** Ob ein Dienst eingerichtet ist ({@link #baseUrl}). */
    public boolean configured() {
        return baseUrl != null;
    }

    /** Ob ein Chat-Modell Suchvarianten formulieren kann: Dienst und Modell (ADR 0017). */
    public boolean chat() {
        return baseUrl != null && chatModel != null;
    }

    /** Ob Embeddings berechnet werden können: Dienst, Modell und Dimension. */
    public boolean embeddings() {
        return baseUrl != null && embeddingModel != null && embeddingDimensions != null;
    }

    /** Ob Treffer über ein Rerank-Modell umgeordnet werden können: Dienst und Modell (#181). */
    public boolean reranking() {
        return baseUrl != null && rerankModel != null;
    }

    /** Ob Bilder beschrieben werden können: Dienst und Vision-Modell (ADR 0021). */
    public boolean vision() {
        return baseUrl != null && visionModel != null;
    }

    /** Ob Inhalte dieser Quelle an den Dienst gehen dürfen. */
    public boolean mayProcessExternally(String sourceId) {
        return externalProcessingSources.contains(sourceId);
    }

    /** Dieselben Einstellungen mit dem API-Key aus einem Tresor oder Secret-Speicher. */
    public AiServiceSettings withApiKey(String key) {
        return new AiServiceSettings(baseUrl, key, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel);
    }

    /** Dieselben Einstellungen mit anderem Embedding-Modell und anderer Dimension. */
    public AiServiceSettings withEmbedding(String model, Integer dimensions) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, model, dimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel);
    }

    /** Dieselben Einstellungen mit anderem Rerank-Modell (#181). */
    public AiServiceSettings withRerankModel(String model) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, model, visionModel);
    }

    /** Dieselben Einstellungen mit anderem Vision-Modell (ADR 0021). */
    public AiServiceSettings withVisionModel(String model) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, model);
    }

    /** Ohne API-Key, damit nichts davon in Logs oder Fehlermeldungen gelangt. */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add("baseUrl=" + (baseUrl == null ? null : baseUrl.getScheme() + "://" + baseUrl.getAuthority()
                + (baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath())));
        parts.add("apiKey=" + (apiKey == null ? "none" : "***"));
        parts.add("chatModel=" + chatModel);
        parts.add("embeddingModel=" + embeddingModel);
        parts.add("embeddingDimensions=" + embeddingDimensions);
        parts.add("timeout=" + timeout);
        parts.add("trustBundle=" + trustBundle);
        parts.add("externalProcessingSources=" + externalProcessingSources);
        parts.add("rerankModel=" + rerankModel);
        parts.add("visionModel=" + visionModel);
        return "AiServiceSettings[" + String.join(", ", parts) + "]";
    }

    private static String value(Properties properties, String name) {
        String value = properties.getProperty(PREFIX + name, properties.getProperty(name));
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
