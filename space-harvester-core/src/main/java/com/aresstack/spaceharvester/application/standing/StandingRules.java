package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Die Regeln des Stands (ADR 0032, FL6), ohne Zustand und ohne Ablage.
 *
 * <p>Aussage, in dieser Reihenfolge:
 * <ol>
 *     <li>Entscheidung eines Menschen zur Aussage.</li>
 *     <li>Vorstufe (KI): Stammen die Merkmale aus dem vereinfachten Satz ({@code Derivation#featuresFrom()} =
 *     Vorstufe) und sagt der Zeitbezug nichts Eigenes (GILT oder unbekannt), entscheidet das Kennzeichen: Planung →
 *     geplant, verworfen → überholt, Tatsache und Bedingung → gilt.</li>
 *     <li>Zeitbezug (FL2): gilt → {@link Standing#GILT}, geplant → {@link Standing#GEPLANT}, früher und verworfen →
 *     {@link Standing#UEBERHOLT} (Grund getrennt).</li>
 *     <li>Ohne Zeitbezug bei einer Aussage der Vorstufe: das Kennzeichen; sonst {@link Standing#UNKLAR}.</li>
 *     <li>Danach die Seite: Sie zieht nur Aussagen mit, deren Stand aus dem Zeitbezug GILT kommt, denn GILT ist in FL2
 *     auch der Wert, wenn der Satz nichts sagt. Sagt der Satz selbst geplant, früher oder verworfen, gewinnt der Satz.
 *     Einzige Ausnahme: Geplantes auf einer überholten Seite wird unklar.</li>
 * </ol>
 * Verneinung und Verbindlichkeit ändern den Stand nie; sie bleiben Merkmale der Aussage („gilt: muss nicht …“).
 */
public final class StandingRules {

    private StandingRules() {
    }

    /**
     * Was die Seite für ihre Aussagen vorgibt; {@code null}, wenn sie nichts vorgibt.
     *
     * @param standing Stand, auf den sie Aussagen mit Zeitbezug GILT zieht
     * @param reason   Grund dafür
     * @param mark     Label oder Titel, das gegriffen hat, oder {@code null} bei einer Entscheidung zur Seite
     */
    public record PageCap(Standing standing, StandingReason reason, PageMark mark) {

        public PageCap {
            Objects.requireNonNull(standing, "standing");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Ob eine Aussage in der Sicht zählt und mit welcher Stufe, aus der einen Zählstelle
     * {@link com.aresstack.spaceharvester.application.knowledge.CountedStatements}. Leer: gehört nicht in die Sicht
     * (ungültig oder von einem Menschen abgelehnt). {@link ParsedStatements.Quality#PLAUSIBLE} zählt für den Stand der
     * Seite, {@link ParsedStatements.Quality#UNCERTAIN} erscheint als unsicher und zählt nicht. FL4 hängt die wirksame
     * Stufe aus dem Prüfschritt in der Zählstelle ein.
     */
    public static Optional<ParsedStatements.Quality> effectiveQuality(ParsedStatements.Statement statement) {
        // Die eine Zählstelle (FL5/FL4); ungültige filtert sie heute selbst, der Stand verlässt sich nicht darauf.
        return com.aresstack.spaceharvester.application.knowledge.CountedStatements.level(statement)
                .filter(level -> level != ParsedStatements.Quality.INVALID);
    }

    /** Art der Aussage. */
    public static StatementKind kind(ParsedStatements.Statement statement) {
        return statement.state() ? StatementKind.STATE : StatementKind.ACTION;
    }

    /**
     * Vorgabe der Seite aus Entscheidung und Hinweisen. Überholt geht vor Entwurf.
     *
     * @param decision Entscheidung zur Seite oder {@code null}
     */
    public static PageCap cap(StandingDecision decision, Collection<PageMark> marks) {
        if (decision != null) {
            return switch (decision.standing()) {
                case GILT -> null;
                case UEBERHOLT -> new PageCap(Standing.UEBERHOLT, StandingReason.PAGE_OUTDATED, null);
                case GEPLANT, UNKLAR -> new PageCap(decision.standing(), StandingReason.PAGE_DECISION, null);
            };
        }
        Optional<PageMark> outdated = marks.stream().filter(mark -> mark.kind() == PageMark.Kind.OUTDATED)
                .findFirst();
        if (outdated.isPresent()) {
            return new PageCap(Standing.UEBERHOLT, StandingReason.PAGE_OUTDATED, outdated.get());
        }
        return marks.stream().filter(mark -> mark.kind() == PageMark.Kind.DRAFT).findFirst()
                .map(mark -> new PageCap(Standing.UNKLAR, StandingReason.PAGE_DRAFT, mark)).orElse(null);
    }

    /**
     * Stand einer Aussage.
     *
     * @param decision Entscheidung zu genau dieser Aussage oder {@code null}
     * @param cap      Vorgabe der Seite oder {@code null}
     */
    public static StatementStanding statement(ParsedStatements.Statement statement, StandingDecision decision,
                                              PageCap cap) {
        Objects.requireNonNull(statement, "statement");
        if (decision != null) {
            return new StatementStanding(decision.standing(), StandingBasis.DECISION, StandingReason.DECISION);
        }
        StatementStanding own = own(statement);
        if (cap == null) {
            return own;
        }
        if (silentlyCurrent(own.reason()) && cap.standing() != Standing.GILT) {
            return new StatementStanding(cap.standing(), StandingBasis.PAGE, cap.reason(), cap.mark());
        }
        if (cap.standing() == Standing.UEBERHOLT && own.standing() == Standing.GEPLANT) {
            return new StatementStanding(Standing.UNKLAR, StandingBasis.PAGE, StandingReason.PAGE_OUTDATED_PLAN,
                    cap.mark());
        }
        return own;
    }

    /**
     * Ob der Stand „gilt“ nur der stille Vorgabewert ist: Zeitbezug GILT oder Kennzeichen Tatsache bzw. Bedingung der
     * Vorstufe. Beide sagen nichts über die Zeit; nur solche Aussagen zieht die Seite mit.
     */
    static boolean silentlyCurrent(StandingReason reason) {
        return reason == StandingReason.TEMPORALITY_GILT || reason == StandingReason.STAGE_FACT
                || reason == StandingReason.STAGE_CONDITION;
    }

    /** Stand der Aussage aus ihren eigenen Merkmalen und Kennzeichen, ohne Mensch und Seite. */
    static StatementStanding own(ParsedStatements.Statement statement) {
        ParsedStatements.Temporality temporality = statement.features().temporality();
        ParsedStatements.Derivation derivation = statement.derivation();
        boolean stage = derivation.origin() == ParsedStatements.Origin.VORSTUFE
                && derivation.stageFlag() != ParsedStatements.StageFlag.NONE;
        boolean silent = temporality == ParsedStatements.Temporality.GILT
                || temporality == ParsedStatements.Temporality.UNKNOWN;
        if (stage && (temporality == ParsedStatements.Temporality.UNKNOWN
                || silent && derivation.featuresFrom() == ParsedStatements.Origin.VORSTUFE)) {
            return switch (derivation.stageFlag()) {
                case FACT -> new StatementStanding(Standing.GILT, StandingBasis.STAGE_FLAG, StandingReason.STAGE_FACT);
                case CONDITION -> new StatementStanding(Standing.GILT, StandingBasis.STAGE_FLAG,
                        StandingReason.STAGE_CONDITION);
                case PLANNING -> new StatementStanding(Standing.GEPLANT, StandingBasis.STAGE_FLAG,
                        StandingReason.STAGE_PLANNING);
                case DISCARDED -> new StatementStanding(Standing.UEBERHOLT, StandingBasis.STAGE_FLAG,
                        StandingReason.STAGE_DISCARDED);
                case NONE -> throw new IllegalStateException();
            };
        }
        return switch (temporality) {
            case GILT -> new StatementStanding(Standing.GILT, StandingBasis.FEATURES, StandingReason.TEMPORALITY_GILT);
            case GEPLANT -> new StatementStanding(Standing.GEPLANT, StandingBasis.FEATURES,
                    StandingReason.TEMPORALITY_GEPLANT);
            case FRUEHER -> new StatementStanding(Standing.UEBERHOLT, StandingBasis.FEATURES,
                    StandingReason.TEMPORALITY_FRUEHER);
            case VERWORFEN -> new StatementStanding(Standing.UEBERHOLT, StandingBasis.FEATURES,
                    StandingReason.TEMPORALITY_VERWORFEN);
            case UNKNOWN -> new StatementStanding(Standing.UNKLAR, StandingBasis.NONE, StandingReason.NO_FEATURES);
        };
    }

    /**
     * Etikett einer Seite aus den Zahlen ihrer sicheren Aussagen: der Stand mit den meisten; bei Gleichstand an der
     * Spitze oder ohne sichere Aussage {@link Standing#UNKLAR}. Die Zahlen sind das Ergebnis, das Etikett steht nur
     * daneben.
     */
    public static Standing majority(Map<Standing, Integer> certainCounts) {
        int best = 0;
        Standing winner = null;
        boolean tie = false;
        for (Standing standing : Standing.values()) {
            int count = certainCounts.getOrDefault(standing, 0);
            if (count > best) {
                best = count;
                winner = standing;
                tie = false;
            } else if (count == best && count > 0) {
                tie = true;
            }
        }
        return winner == null || tie ? Standing.UNKLAR : winner;
    }
}
