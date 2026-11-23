package com.aresstack.spaceharvester.application.deadlines;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Eine versionierte Fristenregel des Katalogs (ADR 0035, Abschnitt 7): Rechtsgrundlage mit belegtem Wortlaut und Quelle,
 * Gültigkeit, Voraussetzungen und Art. Eine Ausnahme nennt die Regel, die sie verdrängt; ob sie im Einzelfall greift,
 * entscheidet allein der {@link DeadlineRuleEvaluator}.
 *
 * @param id          Kennung im Katalog
 * @param version     Version der Regel
 * @param legalBasis  Rechtsgrundlage, z. B. {@code Nr. 6 Satz 1 VO/VW-RiB}
 * @param wording     belegter Wortlaut der Fundstelle
 * @param sources     Quellen (Adresse und Abrufdatum)
 * @param validFrom   gültig ab (Datum der belegten Fassung); leer, wenn nicht belegt: dann ist die Wirksamkeit zum
 *                    maßgeblichen Zeitpunkt offen
 * @param book        Kontenart, für die sie gilt ({@link DeadlineFieldKeys#BOOK_ADVANCE} oder
 *                    {@link DeadlineFieldKeys#BOOK_CUSTODY})
 * @param kind        Grundsatz oder Ausnahme
 * @param displaces   verdrängte Regel (nur bei einer Ausnahme)
 * @param categories  Kontenkategorien, an denen die Ausnahme erkennbar ist (leer: keine)
 * @param individual  ob die Ausnahme eine nachgewiesene Einzelgenehmigung braucht (z. B. Einwilligung des BMF)
 * @param formula     wie die Frist aus dem Entstehungsjahr folgt
 */
public record DeadlineRule(String id, String version, String legalBasis, String wording, List<String> sources,
                           Optional<LocalDate> validFrom, String book, Kind kind, Optional<String> displaces,
                           Set<String> categories, boolean individual, Formula formula) {

    /** Grundsatz oder Ausnahme. */
    public enum Kind {
        STANDARD,
        EXCEPTION
    }

    /** Wie die Frist folgt; nie aus dem Erlassdatum. */
    public enum Formula {
        /** Drei Monate nach Ende des Entstehungshaushaltsjahres. */
        THREE_MONTHS_AFTER_ORIGIN_YEAR("spätestens drei Monate nach Ende des Haushaltsjahres der Entstehung"),
        /** Ende des zweiten auf die Entstehung folgenden Haushaltsjahres. */
        END_OF_SECOND_FOLLOWING_YEAR("spätestens zum Ende des zweiten auf die Entstehung folgenden Haushaltsjahres"),
        /** Drei Monate nach Ende des Monats der Buchung als Einzahlung (unanbringliche Auszahlungen). */
        THREE_MONTHS_AFTER_BOOKING_MONTH("spätestens drei Monate nach Ende des Monats der Buchung als Einzahlung"),
        /** Von den Fristen ausgenommen. */
        EXEMPT("von den Fristen ausgenommen"),
        /** Wie in der nachgewiesenen Einzelgenehmigung bestimmt. */
        AS_GRANTED("wie in der Einzelgenehmigung bestimmt");

        private final String text;

        Formula(String text) {
            this.text = text;
        }

        /** Die abstrakte Frist in Worten. */
        public String text() {
            return text;
        }

        /**
         * Das konkrete Datum aus dem belegten Entstehungsjahr (Haushaltsjahr = Kalenderjahr); leer, wenn die Formel
         * kein Datum aus dem Jahr ergibt.
         */
        public Optional<LocalDate> concrete(int originYear) {
            return switch (this) {
                case THREE_MONTHS_AFTER_ORIGIN_YEAR -> Optional.of(LocalDate.of(originYear + 1, 3, 31));
                case END_OF_SECOND_FOLLOWING_YEAR -> Optional.of(LocalDate.of(originYear + 2, 12, 31));
                default -> Optional.empty();
            };
        }
    }

    public DeadlineRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(legalBasis, "legalBasis");
        Objects.requireNonNull(wording, "wording");
        sources = List.copyOf(sources);
        validFrom = validFrom == null ? Optional.empty() : validFrom;
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(kind, "kind");
        displaces = displaces == null ? Optional.empty() : displaces;
        categories = Set.copyOf(categories);
        Objects.requireNonNull(formula, "formula");
        if (wording.isBlank() || sources.isEmpty()) {
            throw new IllegalArgumentException("Regel " + id + " ohne belegten Wortlaut oder Quelle");
        }
        if ((kind == Kind.EXCEPTION) != displaces.isPresent()) {
            throw new IllegalArgumentException("Nur eine Ausnahme verdrängt eine Regel: " + id);
        }
    }

    /** Verweis für ein Prüfergebnis. */
    public DeadlineAssessment.RuleRef ref() {
        return new DeadlineAssessment.RuleRef(id, version, legalBasis);
    }
}
