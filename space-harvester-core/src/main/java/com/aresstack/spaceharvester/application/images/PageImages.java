package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;

/**
 * Die Bilder einer Seite mit ihren Beschreibungen für die Leseansicht (Reiter „Bilder“, #362), gelesen aus dem
 * gespeicherten Ergebnis des Prozessors {@code images} für genau den angezeigten Text, wie {@code PageTerms} für
 * {@code terms}. Beschrieben wird hier nichts; das tut {@link PageImageAnalysis#describe} auf Abruf.
 *
 * @param state    ob und warum Beschreibungen vorliegen
 * @param reason   bei {@link State#MISSING} der Grund: {@value #NOT_ANALYSED}, {@value #NO_TEXT},
 *                 {@value #VISION_MODEL_MISSING} oder {@value #SOURCE_NOT_RELEASED}; bei {@link State#STALE}
 *                 {@value #ATTACHMENT_CHANGED}, wenn ein Bild seit dem Beschreiben ersetzt wurde; sonst {@code null}
 * @param result   das gespeicherte Ergebnis oder {@code null}
 * @param images   in Reihenfolge der Seite; bei {@link State#STALE} die des alten Ergebnisses (Beschreibungen gelten
 *                 dann nicht mehr sicher), bei {@link State#MISSING} und {@link State#FAILED} leer
 * @param warnings Hinweise, etwa gekürzte Listen
 */
public record PageImages(State state, String reason, Enrichment result, List<ImagePayload.Entry> images,
                         List<String> warnings) {

    /** Noch nicht beschrieben. */
    public static final String NOT_ANALYSED = "not-analysed";
    /** Kein lesbarer Text: die Payload ist nicht projizierbar. */
    public static final String NO_TEXT = "no-text";
    /** Kein Vision-Modell eingestellt ({@code ai.vision-model}). */
    public static final String VISION_MODEL_MISSING = "vision-model-missing";
    /** Die Quelle ist nicht für den KI-Dienst freigegeben ({@code ai.external-processing-sources}). */
    public static final String SOURCE_NOT_RELEASED = "source-not-released";
    /** Ein Bildanhang hat seit dem Beschreiben eine neue Version (laut zuletzt geladener Lese-Ansicht). */
    public static final String ATTACHMENT_CHANGED = "attachment-changed";

    public PageImages {
        Objects.requireNonNull(state, "state");
        images = List.copyOf(images);
        warnings = List.copyOf(warnings);
    }

    /** Zustand der Analyse gegenüber dem angezeigten Text und dem eingestellten Modell. */
    public enum State {
        /** Aus genau diesem Text mit dem eingestellten Modell. */
        CURRENT,
        /**
         * Es gibt ein Ergebnis, aber für einen anderen Text, ein anderes Modell oder Bilder einer anderen Fassung der
         * Seite ({@link PageImageAnalysis#PAGE_CHANGED}); neu beschreiben.
         */
        STALE,
        /** Der letzte Lauf ist fehlgeschlagen. */
        FAILED,
        /** Noch kein Ergebnis; {@link #reason()} nennt den Grund. */
        MISSING
    }

    /** Kein Ergebnis aus dem angegebenen Grund. */
    public static PageImages missing(String reason) {
        return new PageImages(State.MISSING, reason, null, List.of(), List.of());
    }

    /**
     * Liest die Bilder einer Seite.
     *
     * @param key   die angezeigte Seite; Ergebnisse anderer Seiten werden nie verwendet
     * @param views die Anreicherungen dieser Seite ({@link Enrichments#view})
     * @param text  der angezeigte Text aus derselben Payload oder {@code null}
     */
    public static PageImages read(PageKey key, List<Enrichments.View> views, PageText text) {
        Objects.requireNonNull(key, "key");
        Enrichments.View view = views.stream()
                .filter(candidate -> ImagePayload.PROCESSOR.equals(candidate.enrichment().processorId())
                        && key.equals(candidate.enrichment().key()))
                .findFirst().orElse(null);
        if (view == null) {
            return missing(text == null ? NO_TEXT : NOT_ANALYSED);
        }
        Enrichment result = view.enrichment();
        if (text == null) {
            return new PageImages(State.MISSING, NO_TEXT, result, List.of(), result.warnings());
        }
        if (result.status() == Enrichment.Status.FAILED) {
            return new PageImages(State.FAILED, null, result, List.of(), result.warnings());
        }
        List<ImagePayload.Entry> entries;
        try {
            entries = ImagePayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return new PageImages(State.FAILED, null, result, List.of(), result.warnings());
        }
        boolean current = view.freshness() == Enrichment.Freshness.CURRENT
                && result.inputSha256().equals(text.inputSha256())
                && !result.warnings().contains(PageImageAnalysis.PAGE_CHANGED);
        return new PageImages(current ? State.CURRENT : State.STALE, null, result, entries, result.warnings());
    }

    /** Zahl der beschriebenen Bilder. */
    public int describedCount() {
        return (int) images.stream().filter(image -> image.status() == ImageStatus.DESCRIBED).count();
    }
}
