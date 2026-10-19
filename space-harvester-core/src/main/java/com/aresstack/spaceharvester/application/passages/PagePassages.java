package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die semantischen Absätze einer Seite für die Leseansicht (Reiter „Absätze“, #354) und die REST-Schnittstelle,
 * gelesen aus dem gespeicherten Ergebnis von {@value PassageProcessor#ID} für genau den angezeigten Text, wie
 * {@code PageAcronyms} und {@code PageTerms}. Berechnet wird hier nichts. Passagen gibt es, solange das Ergebnis aus
 * genau diesem Text stammt: bei {@link State#CURRENT} und bei {@link State#STALE} nur wegen einer anderen
 * Prozessorversion (anderes Modell, andere Zerlegung oder Rückfall nach Struktur, der wiederholt wird); dann gelten die
 * Stellen noch, die Grenzen sind aber nicht die aktuellen.
 *
 * @param state    ob und warum Absätze vorliegen
 * @param result   das gespeicherte Ergebnis oder {@code null}
 * @param mode     wie die Grenzen entstanden, ohne Passagen {@code null}
 * @param reason   bei {@link PassagePayload.Mode#STRUCTURE} warum ({@code no-model}, {@code source-not-approved},
 *                 {@code model-unavailable})
 * @param model    bei {@link PassagePayload.Mode#SEMANTIC} das Embedding-Modell
 * @param passages in Lesereihenfolge
 * @param warnings Hinweise des Prozessors, etwa gekürzte Listen
 */
public record PagePassages(State state, Enrichment result, PassagePayload.Mode mode, String reason, String model,
                           String segmentation, List<Passage> passages, List<String> warnings) {

    /** Länge der Themenzeile. */
    static final int TITLE_CHARS = 80;

    public PagePassages {
        Objects.requireNonNull(state, "state");
        passages = List.copyOf(passages);
        warnings = List.copyOf(warnings);
    }

    /** Zustand der Analyse gegenüber dem angezeigten Text. */
    public enum State {
        /** Aus genau diesem Text mit der aktuellen Prozessorversion. */
        CURRENT,
        /** Es gibt ein Ergebnis, aber für einen anderen Text oder eine andere Prozessorversion. */
        STALE,
        /** Der letzte Lauf ist fehlgeschlagen. */
        FAILED,
        /** Noch kein Ergebnis oder kein lesbarer Text. */
        MISSING
    }

    /**
     * Ein Absatz.
     *
     * @param index         Nummer in der Seite, ab 0
     * @param start         erstes Zeichen im Text (UTF-16)
     * @param end           Ende, exklusiv
     * @param title         Themenzeile: der erste Satz, höchstens {@value #TITLE_CHARS} Zeichen (wie der Titel eines
     *                      Themas im Client)
     * @param text          Wortlaut aus dem angezeigten Text
     * @param section       Überschriftenkette oder {@code null} vor der ersten Überschrift
     * @param firstSentence Nummer des ersten Satzes in der Seite, ab 0
     * @param sentences     Zahl der Sätze
     * @param kind          Art des Blocks
     * @param boundary      warum der Absatz hier beginnt
     * @param similarity    bei {@code topic} die gemessene Ähnlichkeit der Satzfenster, sonst {@code null}
     */
    public record Passage(int index, int start, int end, String title, String text, String section,
                          int firstSentence, int sentences, PassageSegmentation.Kind kind,
                          PassageSegmentation.Boundary boundary, Double similarity) {
    }

    /**
     * Liest die Absätze einer Seite.
     *
     * @param key   die angezeigte Seite; Ergebnisse anderer Seiten werden nie verwendet
     * @param views die Anreicherungen dieser Seite ({@link Enrichments#view})
     * @param text  der angezeigte Text aus derselben Payload oder {@code null}
     */
    public static PagePassages read(PageKey key, List<Enrichments.View> views, PageText text) {
        Objects.requireNonNull(key, "key");
        Enrichments.View view = views.stream()
                .filter(candidate -> PassageProcessor.ID.equals(candidate.enrichment().processorId())
                        && key.equals(candidate.enrichment().key()))
                .findFirst().orElse(null);
        if (view == null) {
            return empty(State.MISSING, null);
        }
        Enrichment result = view.enrichment();
        if (text == null) {
            return empty(State.MISSING, result);
        }
        if (result.status() == Enrichment.Status.FAILED) {
            return empty(State.FAILED, result);
        }
        if (!result.inputSha256().equals(text.inputSha256())) {
            return empty(State.STALE, result);
        }
        PassagePayload.Parsed parsed;
        try {
            parsed = PassagePayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return empty(State.FAILED, result);
        }
        String content = text.text();
        List<Passage> passages = new ArrayList<>(parsed.passages().size());
        for (PassageSegmentation.Passage passage : parsed.passages()) {
            // Eine Stelle außerhalb des Textes wäre ein Fehler des Ergebnisses; sie wird nie gezeigt.
            if (passage.end() > content.length()) {
                continue;
            }
            String wording = content.substring(passage.start(), passage.end());
            passages.add(new Passage(passages.size(), passage.start(), passage.end(), title(wording), wording,
                    passage.section(), passage.firstSentence(), passage.sentences(), passage.kind(),
                    passage.boundary(), passage.similarity()));
        }
        State state = view.freshness() == Enrichment.Freshness.CURRENT ? State.CURRENT : State.STALE;
        return new PagePassages(state, result, parsed.mode(), parsed.reason(), parsed.model(), parsed.segmentation(),
                passages, result.warnings());
    }

    private static PagePassages empty(State state, Enrichment result) {
        return new PagePassages(state, result, null, null, null, null, List.of(),
                result == null ? List.of() : result.warnings());
    }

    /** Der erste Satz bis zum ersten Punkt, gekürzt (Client {@code TopicClusterer.suggestedTitle}). */
    static String title(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        int end = flat.indexOf('.');
        String head = end > 0 ? flat.substring(0, end) : flat;
        return head.length() <= TITLE_CHARS ? head : head.substring(0, TITLE_CHARS);
    }
}
