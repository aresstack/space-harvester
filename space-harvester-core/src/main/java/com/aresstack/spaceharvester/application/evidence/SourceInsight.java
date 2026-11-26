package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Objects;

/**
 * Erkenntnisebene einer Aussage mit Quellstellenbeleg (ADR 0041, Punkt 7). Die Prüfung durch einen Menschen ist die
 * vorhandene aus W3 ({@link ParsedStatements.Review}, Ablage in {@code StandingDecision}), keine zweite.
 *
 * <ul>
 *   <li>Verworfen bleibt verworfen ({@link InsightLevel#REJECTED}).</li>
 *   <li>Bestätigt ist eine Aussage nur, wenn der Beleg technisch {@link SourceLocationCheck.Outcome#CURRENT aktuell}
 *       ist. Ist er möglicherweise veraltet oder nicht prüfbar, fällt sie auf {@link InsightLevel#HYPOTHESIS} zurück
 *       und trägt {@link #confirmationOutdated()}: Die frühere Bestätigung wird nie ungeprüft auf eine neue Revision
 *       übertragen.</li>
 *   <li>Ohne Prüfung ist sie eine Hypothese.</li>
 * </ul>
 *
 * Die Aussage belegt immer den Ist-Zustand ({@link Bearing#IST}), auch bestätigt.
 *
 * @param level                Erkenntnisebene
 * @param confirmationOutdated ob eine Bestätigung vorliegt, die für einen früheren Stand gegeben wurde
 * @param check                Ergebnis der technischen Prüfung
 */
public record SourceInsight(InsightLevel level, boolean confirmationOutdated, SourceLocationCheck.Result check) {

    public SourceInsight {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(check, "check");
        if (level == InsightLevel.PARSER_FACT) {
            throw new IllegalArgumentException("Eine Aussage ist nie selbst ein Parserfakt");
        }
        if (level == InsightLevel.CONFIRMED && !check.current()) {
            throw new IllegalArgumentException("Bestätigt nur mit aktuellem Beleg");
        }
        if (confirmationOutdated && level != InsightLevel.HYPOTHESIS) {
            throw new IllegalArgumentException("Eine veraltete Bestätigung lässt nur die Hypothese übrig");
        }
    }

    /** Ebene aus der Prüfung durch einen Menschen und der technischen Prüfung. */
    public static SourceInsight of(ParsedStatements.Review review, SourceLocationCheck.Result check) {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(check, "check");
        return switch (review) {
            case REJECTED -> new SourceInsight(InsightLevel.REJECTED, false, check);
            case CONFIRMED -> check.current() ? new SourceInsight(InsightLevel.CONFIRMED, false, check)
                    : new SourceInsight(InsightLevel.HYPOTHESIS, true, check);
            case UNREVIEWED -> new SourceInsight(InsightLevel.HYPOTHESIS, false, check);
        };
    }

    /** Immer Ist: Auch eine bestätigte Aussage über Programmcode ist keine geltende Soll-Regel. */
    public Bearing bearing() {
        return Bearing.IST;
    }
}
