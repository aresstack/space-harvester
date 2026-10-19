package com.aresstack.spaceharvester.application.passages;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis von {@link PassageQueries#resolve}: der gesuchte Absatz mit seinen Nachbarn oder, wenn es ihn nicht mehr
 * gibt, alle aktuellen Absätze der Seite und der Grund.
 *
 * @param requested die aufgelöste ID
 * @param match     ob und warum der Absatz gefunden wurde
 * @param page      die aktuellen Absätze der Seite (Zustand, Modus, Modell)
 * @param passages  bei {@link Match#FOUND} der Absatz mit Nachbarn, sonst alle aktuellen Absätze
 */
public record PassageLookup(PassageId requested, Match match, PagePassages page, List<PagePassages.Passage> passages) {

    public PassageLookup {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(page, "page");
        passages = List.copyOf(passages);
    }

    /** Sucht {@code requested} unter den aktuellen Absätzen, mit {@code neighbours} Nachbarn je Seite. */
    static PassageLookup of(PassageId requested, PagePassages current, int neighbours) {
        List<PagePassages.Passage> passages = current.passages();
        for (int i = 0; i < passages.size(); i++) {
            if (passages.get(i).id().equals(requested.value())) {
                return new PassageLookup(requested, Match.FOUND, current,
                        passages.subList(Math.max(0, i - neighbours), Math.min(passages.size(), i + neighbours + 1)));
            }
        }
        if (passages.isEmpty()) {
            return new PassageLookup(requested, Match.UNAVAILABLE, current, passages);
        }
        // Wie im Client: eine andere Capture heißt anderer Text, sonst haben Zerlegung oder Modell gewechselt.
        boolean olderText = !PassageId.parse(passages.get(0).id()).capture().equals(requested.capture());
        return new PassageLookup(requested, olderText ? Match.OLDER_VERSION : Match.CHANGED, current, passages);
    }

    /** Wie der Client die Fälle unterscheidet ({@code KnowledgeResourceReader}). */
    public enum Match {
        /** Der Absatz gehört zum aktuellen Ergebnis; er kommt mit seinen Nachbarn. */
        FOUND,
        /** Der Absatz stammt aus einer älteren Fassung der Seite (andere Capture); sein Text ist weg. */
        OLDER_VERSION,
        /** Gleicher Text, aber Zerlegung oder Embedding-Modell haben gewechselt; den Absatz gibt es nicht mehr. */
        CHANGED,
        /** Für die Seite liegen gerade keine Absätze vor (noch nicht berechnet, fehlgeschlagen, kein Text). */
        UNAVAILABLE
    }
}
