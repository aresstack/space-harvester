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
 * @param validUntil  gültig bis einschließlich (belegt, z. B. Nr. 10 VO/VW-RiB: Fristausnahmen der Buchungsstellen
 *                    9070 0000 97 und 9071 0000 96 bis Ablauf 2024); leer: unbefristet. Nach dem Ende wirkt die Regel
 *                    nicht mehr, auch nicht für ältere Beträge; dann greift sie nur mit neuem Nachweis.
 * @param bookingPoints Buchungsstellen (zehnstellige Buchungskonten, beginnend mit 90; nur Ziffern), an denen die Regel
 *                    erkennbar ist (leer: keine)
 * @param sections    Abschnitte des Buchs, an denen die Regel erkennbar ist (leer: keine)
 * @param locator     Fundstelle in der Quelle, z. B. {@code VO/VW-RiB Nr. 10, S. 10}
 */
public record DeadlineRule(String id, String version, String legalBasis, String wording, List<String> sources,
                           Optional<LocalDate> validFrom, String book, Kind kind, Optional<String> displaces,
                           Set<String> categories, boolean individual, Formula formula,
                           Optional<LocalDate> validUntil, Set<String> bookingPoints, Set<String> sections,
                           String locator) {

    /** Grundsatz, Ausnahme oder Verfahrenspflicht. */
    public enum Kind {
        STANDARD,
        EXCEPTION,
        /** Pflicht ohne eigene Ausgleichsfrist (etwa „im gleichen Haushaltsjahr anzuordnen“); verdrängt nichts. */
        PROCEDURE
    }

    /** Regeltyp für die Anzeige, abgeleitet aus Art und Formel. */
    public enum RuleType {
        BASE_DEADLINE("Grundfrist"),
        SPECIAL_DEADLINE("Sonderfrist"),
        EXCEPTION("Ausnahme"),
        PROCEDURE("Verfahrenspflicht");

        private final String label;

        RuleType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
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
        AS_GRANTED("wie in der Einzelgenehmigung bestimmt"),
        /** Ablauf des zweiten auf die Buchung folgenden Haushaltsjahres (Nr. 9.2.2.3 VO/VW-RiB). */
        END_OF_SECOND_YEAR_AFTER_BOOKING("spätestens zum Ablauf des zweiten auf die Buchung folgenden Haushaltsjahres"),
        /** Keine eigene Frist; die Regel beschreibt eine Pflicht oder einen Zeitpunkt (Verfahrenspflicht). */
        NONE("keine eigene Ausgleichsfrist");

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

    /** Unbefristet, ohne Buchungsstellen, Abschnitte und Fundstelle. */
    public DeadlineRule(String id, String version, String legalBasis, String wording, List<String> sources,
                        Optional<LocalDate> validFrom, String book, Kind kind, Optional<String> displaces,
                        Set<String> categories, boolean individual, Formula formula) {
        this(id, version, legalBasis, wording, sources, validFrom, book, kind, displaces, categories, individual,
                formula, Optional.empty(), Set.of(), Set.of(), "");
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
        validUntil = validUntil == null ? Optional.empty() : validUntil;
        bookingPoints = Set.copyOf(bookingPoints);
        sections = Set.copyOf(sections);
        locator = locator == null ? "" : locator;
        if (wording.isBlank() || sources.isEmpty()) {
            throw new IllegalArgumentException("Regel " + id + " ohne belegten Wortlaut oder Quelle");
        }
        if ((kind == Kind.EXCEPTION) != displaces.isPresent()) {
            throw new IllegalArgumentException("Nur eine Ausnahme verdrängt eine Regel: " + id);
        }
    }

    /** Ob die Regel nur mit nachgewiesener Einzelgenehmigung des BMF greift. */
    public boolean approvalRequired() {
        return individual;
    }

    /** Regeltyp: Grundfrist, Sonderfrist (eigene Frist), Ausnahme (ausgenommen oder nach Genehmigung) oder Pflicht. */
    public RuleType type() {
        return switch (kind) {
            case STANDARD -> RuleType.BASE_DEADLINE;
            case PROCEDURE -> RuleType.PROCEDURE;
            case EXCEPTION -> formula == Formula.EXEMPT || formula == Formula.AS_GRANTED
                    ? RuleType.EXCEPTION : RuleType.SPECIAL_DEADLINE;
        };
    }

    /**
     * Ob die Regel an diesem Tag gilt: nach {@link #validUntil()} nicht mehr, vor {@link #validFrom()} noch nicht;
     * ohne belegtes Datum der Fassung offen.
     */
    public DeadlineAssessment.Proof validOn(LocalDate day) {
        if (validUntil.isPresent() && day.isAfter(validUntil.get())) {
            return DeadlineAssessment.Proof.NOT_PROVEN;
        }
        if (validFrom.isEmpty()) {
            return DeadlineAssessment.Proof.OPEN;
        }
        return day.isBefore(validFrom.get()) ? DeadlineAssessment.Proof.NOT_PROVEN : DeadlineAssessment.Proof.PROVEN;
    }

    /** Verweis für ein Prüfergebnis. */
    public DeadlineAssessment.RuleRef ref() {
        return new DeadlineAssessment.RuleRef(id, version, legalBasis);
    }
}
