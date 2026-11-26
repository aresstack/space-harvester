package com.aresstack.spaceharvester.ai;

import java.util.Objects;
import java.util.Set;

/**
 * Ein Modell aus {@code GET {base-url}/models}, für die Auswahl im Reiter „KI“.
 *
 * @param id          Kennung, wie sie in {@code chat-model} bzw. {@code embedding-model} gehört
 * @param displayName Anzeigename des Dienstes, sonst die Kennung
 * @param categories  Einordnung des Dienstes, z. B. {@code chat}, {@code embedding}, {@code rerank},
 *                    {@code vision}; leer, wenn er
 *                    keine meldet
 * @param contextTokens Kontextlänge des Modells in Tokens, wie der Dienst sie meldet ({@code context_length}); 0, wenn
 *                      er keine nennt
 */
public record AiModel(String id, String displayName, Set<String> categories, int contextTokens) {

    /** Kategorie der Embedding-Modelle. */
    public static final String EMBEDDING = "embedding";
    /** Kategorie der Chat-Modelle. */
    public static final String CHAT = "chat";
    /** Kategorie der Modelle mit Bildeingabe, wie {@code ModelCategory.VISION} im Enterprise AI Client (ADR 0021). */
    public static final String VISION = "vision";
    /** Kategorie der Sprachausgabe, wie {@code ModelCategory.TTS} im Enterprise AI Client (Podcast). */
    public static final String TTS = "tts";

    public AiModel {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        contextTokens = Math.max(0, contextTokens);
    }

    /** Ohne gemeldete Kontextlänge. */
    public AiModel(String id, String displayName, Set<String> categories) {
        this(id, displayName, categories, 0);
    }
}
