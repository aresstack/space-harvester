package com.aresstack.enterpriseai.embedding.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Eingabeprüfung für {@link EmbeddingPort#embed}, damit alle Implementierungen gleich reagieren. */
public final class EmbeddingInputs {

    private EmbeddingInputs() {
    }

    /**
     * @return unveränderliche Kopie der Eingabetexte
     * @throws IllegalArgumentException bei {@code null}-Liste oder {@code null}-Element
     */
    public static List<String> requireValid(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("texts must not be null");
        }
        List<String> copy = new ArrayList<String>(texts.size());
        int i = 0;
        for (String text : texts) {
            if (text == null) {
                throw new IllegalArgumentException("text " + i + " must not be null");
            }
            copy.add(text);
            i++;
        }
        return Collections.unmodifiableList(copy);
    }
}
