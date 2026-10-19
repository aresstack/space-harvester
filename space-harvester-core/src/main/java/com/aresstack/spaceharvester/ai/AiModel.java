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
 */
public record AiModel(String id, String displayName, Set<String> categories) {

    /** Kategorie der Embedding-Modelle. */
    public static final String EMBEDDING = "embedding";
    /** Kategorie der Chat-Modelle. */
    public static final String CHAT = "chat";
    /** Kategorie der Modelle mit Bildeingabe, wie {@code ModelCategory.VISION} im Enterprise AI Client (ADR 0021). */
    public static final String VISION = "vision";

    public AiModel {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        categories = categories == null ? Set.of() : Set.copyOf(categories);
    }
}
