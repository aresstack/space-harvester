package com.aresstack.spaceharvester.app.settings;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Eingaben für den KI-Dienst ({@code space-harvester.ai}, ADR 0011): Basisadresse eines OpenAI-kompatiblen Dienstes,
 * gewählte Modelle und die Wissensquellen, deren Inhalte an den Dienst gehen dürfen. Der API-Key gehört nicht dazu;
 * er liegt im Tresor und erreicht den Core nur zur Laufzeit.
 *
 * @param baseUrl                   z. B. {@code https://api.openai.com/v1}; leer heißt kein KI-Dienst
 * @param chatModel                 Modell für Text (etwa für spätere Suchbegriffe) oder leer
 * @param embeddingModel            Modell für Embeddings der semantischen Suche oder leer
 * @param embeddingDimensions       Vektordimension als Text oder leer (dann aus der ersten Antwort)
 * @param timeout                   Zeitgrenze je Anfrage; {@code null} für den Standard des Core, bleibt erhalten
 * @param externalProcessingSources Quellen-IDs, deren Inhalte extern verarbeitet werden dürfen; leer heißt keine
 * @param rerankModel               Cross-Encoder des Dienstes ({@code /rerank}) oder leer: Reihenfolge bleibt
 * @param chunkTokens               Obergrenze je Abschnitt für Embeddings als Text oder leer (Core: 350)
 * @param chunkOverlapSentences     überlappende Sätze zwischen Abschnitten als Text oder leer (Core: 1)
 * @param visionModel               Modell, das Bilder beschreibt ({@code vision-model}, #362), oder leer: keine
 *                                  Bildbeschreibung
 */
public record AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                      Duration timeout, List<String> externalProcessingSources, String rerankModel,
                      String chunkTokens, String chunkOverlapSentences, String visionModel) {

    /** Kleinste Obergrenze je Abschnitt, wie im Core ({@code KnowledgeChunkingPolicy}). */
    public static final int MIN_CHUNK_TOKENS = 8;
    public static final AiDraft EMPTY = new AiDraft(null, null, null, null, null, List.of());

    public AiDraft {
        baseUrl = trim(baseUrl);
        chatModel = trim(chatModel);
        embeddingModel = trim(embeddingModel);
        embeddingDimensions = trim(embeddingDimensions);
        rerankModel = trim(rerankModel);
        chunkTokens = trim(chunkTokens);
        chunkOverlapSentences = trim(chunkOverlapSentences);
        visionModel = trim(visionModel);
        externalProcessingSources = externalProcessingSources == null ? List.of() : externalProcessingSources
                .stream().map(AiDraft::trim).filter(id -> id != null).distinct().toList();
    }

    /** Ohne Vision-Modell. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, null);
    }

    /** Ohne Rerank-Modell und mit der Zerlegung des Core. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, null, null,
                null);
    }

    public static AiDraft of(AppSettings.AiSettings settings) {
        return new AiDraft(settings.baseUrl(), settings.chatModel(), settings.embeddingModel(),
                settings.embeddingDimensions() == null ? null : String.valueOf(settings.embeddingDimensions()),
                settings.timeout(), settings.externalProcessingSources(), settings.rerankModel(),
                settings.chunkTokens() == null ? null : String.valueOf(settings.chunkTokens()),
                settings.chunkOverlapSentences() == null ? null : String.valueOf(settings.chunkOverlapSentences()),
                settings.visionModel());
    }

    /**
     * Dieselben Eingaben mit anderen Modellen (Reiter „Modelle“); Adresse, Freigaben und Vision-Modell bleiben.
     */
    public AiDraft withModels(String chatModel, String embeddingModel, String embeddingDimensions,
                              String rerankModel, String chunkTokens, String chunkOverlapSentences) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel);
    }

    /** Dieselben Eingaben mit anderem Vision-Modell. */
    public AiDraft withVisionModel(String visionModel) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel);
    }

    /** Dieselben Modelle mit anderem Dienst und anderen Freigaben (Reiter „KI-Dienst“). */
    public AiDraft withService(String baseUrl, List<String> externalProcessingSources) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel);
    }

    /** Ob nichts eingetragen ist; dann wird der Block entfernt. */
    public boolean empty() {
        return baseUrl == null && chatModel == null && embeddingModel == null && embeddingDimensions == null
                && timeout == null && externalProcessingSources.isEmpty() && rerankModel == null
                && chunkTokens == null && chunkOverlapSentences == null && visionModel == null;
    }

    /** Was nicht passt; leer, wenn die Eingaben so in die Datei können. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (baseUrl == null) {
            // Freigaben je Quelle dürfen ohne Dienst stehen: Sie wirken, sobald einer eingetragen ist.
            if (chatModel != null || embeddingModel != null || embeddingDimensions != null || rerankModel != null
                    || visionModel != null || chunkTokens != null || chunkOverlapSentences != null) {
                problems.add("Die Basisadresse des KI-Dienstes fehlt.");
            }
        } else if (looksLikeKey(baseUrl)) {
            problems.add("Die Basisadresse sieht wie ein API-Key aus; der gehört in das Feld API-Key, die "
                    + "Basisadresse beginnt mit http:// oder https://.");
        } else if (!baseUrl.matches("(?i)https?://.+") || !parsable(baseUrl)) {
            problems.add("Die Basisadresse beginnt mit http:// oder https:// und ist eine gültige Adresse.");
        }
        if (embeddingDimensions != null && dimensions() == null) {
            problems.add("Die Dimension ist eine positive ganze Zahl oder bleibt leer.");
        }
        if (chunkTokens != null && tokens() == null) {
            problems.add("Die Abschnittsgröße ist eine ganze Zahl ab " + MIN_CHUNK_TOKENS + " oder bleibt leer.");
        }
        if (chunkOverlapSentences != null && overlap() == null) {
            problems.add("Die Überlappung ist eine ganze Zahl ab 0 oder bleibt leer.");
        }
        return problems;
    }

    /**
     * Ob ein Wert eher ein Schlüssel als eine Adresse ist: ohne {@code ://}, ohne Leerzeichen und entweder mit
     * typischem Präfix ({@code sk-}, {@code Bearer }) oder lang und ohne Punkt.
     */
    static boolean looksLikeKey(String value) {
        if (value.contains("://")) {
            return false;
        }
        String v = value.strip();
        return v.regionMatches(true, 0, "sk-", 0, 3) || v.regionMatches(true, 0, "bearer ", 0, 7)
                || (v.length() >= 24 && !v.contains(".") && v.matches("[A-Za-z0-9_\\-]+"));
    }

    /** Die Dimension als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer dimensions() {
        if (embeddingDimensions == null || !embeddingDimensions.matches("\\d{1,6}")) {
            return null;
        }
        int value = Integer.parseInt(embeddingDimensions);
        return value > 0 ? value : null;
    }

    /** Die Obergrenze je Abschnitt als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer tokens() {
        Integer value = number(chunkTokens);
        return value != null && value >= MIN_CHUNK_TOKENS ? value : null;
    }

    /** Die überlappenden Sätze als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer overlap() {
        return number(chunkOverlapSentences);
    }

    private static Integer number(String text) {
        return text == null || !text.matches("\\d{1,6}") ? null : Integer.parseInt(text);
    }

    /** Der Block {@code ai:} mit Einrückung {@code indent}; nur Schlüssel mit Wert. */
    public String yaml(String indent) {
        StringBuilder yaml = new StringBuilder(indent).append("ai:\n");
        String inner = indent + "  ";
        append(yaml, inner, "base-url", baseUrl);
        append(yaml, inner, "chat-model", chatModel);
        append(yaml, inner, "embedding-model", embeddingModel);
        if (dimensions() != null) {
            yaml.append(inner).append("embedding-dimensions: ").append(dimensions()).append('\n');
        }
        append(yaml, inner, "rerank-model", rerankModel);
        append(yaml, inner, "vision-model", visionModel);
        if (tokens() != null) {
            yaml.append(inner).append("embedding-chunk-tokens: ").append(tokens()).append('\n');
        }
        if (overlap() != null) {
            yaml.append(inner).append("embedding-chunk-overlap-sentences: ").append(overlap()).append('\n');
        }
        if (timeout != null) {
            yaml.append(inner).append("timeout: ").append(timeout.toMillis()).append("ms\n");
        }
        if (!externalProcessingSources.isEmpty()) {
            yaml.append(inner).append("external-processing-sources:\n");
            externalProcessingSources.forEach(id -> yaml.append(inner).append("  - ").append(quote(id)).append('\n'));
        }
        return yaml.toString();
    }

    private static void append(StringBuilder yaml, String indent, String key, String value) {
        if (value != null) {
            yaml.append(indent).append(key).append(": ").append(quote(value)).append('\n');
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean parsable(String uri) {
        try {
            return new URI(uri).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
