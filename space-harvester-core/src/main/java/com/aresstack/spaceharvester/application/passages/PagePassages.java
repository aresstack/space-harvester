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
     * @param id            stabile Adresse ({@link PassageId}): Seite, Fassung des Textes, Position und die Versionen
     *                      von Zerlegung und Modell, mit denen das Ergebnis entstand
     * @param index         Nummer in der Seite (Stelle im gespeicherten Ergebnis), ab 0
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
    public record Passage(String id, int index, int start, int end, String title, String text, String section,
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
        // Die Capture deckt Text und Gliederung ab (inputSha256): Die Grenzen hängen auch an den Abschnitten, eine
        // umgestufte Überschrift bei gleichem Text gibt neue Grenzen und damit neue IDs.
        String capture = PassageId.captureOf(result.inputSha256());
        String segmentation = PassageId.versionTag(parsed.segmentation());
        String model = PassageId.versionTag(parsed.model() == null ? PassageId.NO_MODEL : parsed.model());
        List<Passage> passages = new ArrayList<>(parsed.passages().size());
        for (int index = 0; index < parsed.passages().size(); index++) {
            PassageSegmentation.Passage passage = parsed.passages().get(index);
            // Eine Stelle außerhalb des Textes wäre ein Fehler des Ergebnisses; sie wird nie gezeigt. Gezählt wird nach
            // der Stelle im Ergebnis, wie in idAt: Dieselbe ID zeigt auf jedem Weg auf denselben Absatz.
            if (passage.end() > content.length()) {
                continue;
            }
            String wording = content.substring(passage.start(), passage.end());
            passages.add(new Passage(PassageId.of(key.sourceId(), key.pageId(), capture, index, segmentation, model)
                    .value(), index, passage.start(), passage.end(), title(wording), wording,
                    passage.section(), passage.firstSentence(), passage.sentences(), passage.kind(),
                    passage.boundary(), passage.similarity()));
        }
        State state = view.freshness() == Enrichment.Freshness.CURRENT ? State.CURRENT : State.STALE;
        return new PagePassages(state, result, parsed.mode(), parsed.reason(), parsed.model(), parsed.segmentation(),
                passages, result.warnings());
    }

    /**
     * Die Passage-ID des Absatzes, der den Bereich {@code [start, end)} ganz enthält, allein aus dem schon geladenen
     * Ergebnis {@code result} (für Treffer der semantischen Suche, wie {@code passage:} je Treffer im Client): ohne
     * Text, die Capture ist der {@code inputSha256} des Ergebnisses wie in {@link #read}. {@code null}, wenn
     * {@code result} kein erfolgreiches Ergebnis von {@value PassageProcessor#ID} für genau diese Seite und dieselbe
     * Eingabe ({@code inputSha256}) ist, Absätze sich überlappen (kaputtes Ergebnis, {@link #overlapping}) oder kein
     * Absatz den Bereich enthält. Gezählt wird wie in {@link #read}: nach der Stelle im Ergebnis.
     *
     * @param inputSha256 Eingabe-Hash der Textprojektion, aus der der Bereich stammt
     */
    public static String idAt(PageKey key, Enrichment result, String inputSha256, int start, int end) {
        if (result == null || !PassageProcessor.ID.equals(result.processorId()) || !key.equals(result.key())
                || result.status() != Enrichment.Status.OK || inputSha256 == null
                || !inputSha256.equals(result.inputSha256())) {
            return null;
        }
        PassagePayload.Parsed parsed;
        try {
            parsed = PassagePayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<PassageSegmentation.Passage> passages = parsed.passages();
        if (overlapping(passages)) {
            return null;
        }
        for (int index = 0; index < passages.size(); index++) {
            PassageSegmentation.Passage passage = passages.get(index);
            if (passage.start() <= start && end <= passage.end()) {
                try {
                    return PassageId.of(key.sourceId(), key.pageId(), PassageId.captureOf(result.inputSha256()), index,
                            PassageId.versionTag(parsed.segmentation()),
                            PassageId.versionTag(parsed.model() == null ? PassageId.NO_MODEL : parsed.model()))
                            .value();
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Ob sich Absätze eines Ergebnisses überlappen. Ohne Text ist ein kaputtes Ergebnis nur daran zu erkennen; Treffer
     * bekommen dann keine ID, die auf einen Absatz zeigen könnte, den die Leseansicht nie zeigt.
     */
    static boolean overlapping(List<PassageSegmentation.Passage> passages) {
        for (int index = 1; index < passages.size(); index++) {
            if (passages.get(index).start() < passages.get(index - 1).end()) {
                return true;
            }
        }
        return false;
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
