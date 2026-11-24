package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.extraction.DocumentRef;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prüfergebnis je Sachverhalt nach Grundsatz und Ausnahme (ADR 0035, Abschnitt 6), getrennt von den Dokumentfakten.
 * Genau drei Zustände; nur {@code DeadlineRuleEvaluator} erzeugt sie (ArchUnit). Eine bestätigte Standardfrist gibt
 * es nur mit einem ausdrücklichen Abschluss der Ausnahmenprüfung durch den Benutzer, eine abweichende Frist nur mit
 * nachgewiesener Ausnahme.
 */
public sealed interface DeadlineAssessment
        permits DeadlineAssessment.StandardProvisional, DeadlineAssessment.StandardConfirmed,
        DeadlineAssessment.DeviationConfirmed {

    /** Dokument samt Version. */
    DocumentRef document();

    /** Stabiler Sachverhalt im Dokument. */
    String matterId();

    /** Die Regel, deren Frist gilt (bei einer Abweichung die Ausnahme). */
    RuleRef rule();

    /** Die Frist. */
    Deadline deadline();

    /**
     * Eine Regel des Katalogs.
     *
     * @param id         Kennung im Katalog
     * @param version    Version der Regel
     * @param legalBasis Rechtsgrundlage, z. B. {@code § 60 Abs. 1 Satz 2 BHO}
     */
    record RuleRef(String id, String version, String legalBasis) {

        public RuleRef {
            Objects.requireNonNull(id, "id");
            version = version == null ? "" : version;
            legalBasis = legalBasis == null ? "" : legalBasis;
        }
    }

    /**
     * Eine Frist: immer abstrakt, konkret nur mit belegtem Entstehungsdatum.
     *
     * @param rule     die abstrakte Regel im Wortlaut, z. B. „Ende des zweiten auf die Entstehung folgenden
     *                 Haushaltsjahres“
     * @param concrete das berechnete Datum oder leer
     */
    record Deadline(String rule, Optional<LocalDate> concrete) {

        public Deadline {
            Objects.requireNonNull(rule, "rule");
            concrete = concrete == null ? Optional.empty() : concrete;
        }
    }

    /** Warum eine Standardfrist nur vorläufig ist. */
    enum ProvisionalReason {
        /** Kein Rechtswissen eingerichtet oder leer. */
        KNOWLEDGE_EMPTY,
        /** Ausnahmen nicht abschließend geprüft (kein Abschluss durch den Benutzer). */
        UNCHECKED_EXCEPTIONS,
        /** Kein belegtes Entstehungsdatum; nur die abstrakte Frist. */
        NO_ORIGIN_DATE
    }

    /** Wie weit ein Merkmal einer Ausnahme belegt ist. */
    enum Proof {
        PROVEN,
        NOT_PROVEN,
        OPEN
    }

    /**
     * Abschluss der Ausnahmenprüfung, nur durch ausdrückliche Bestätigung des Benutzers (O2). Er gilt nur für diesen
     * Stand: Ändert sich Dokumentversion, Katalog oder Wissensgeneration, ist er veraltet.
     */
    record ExceptionCheckClosure(String closedBy, Instant closedAt, String catalogVersion,
                                 String knowledgeGeneration, DocumentRef document, String matterId) {

        public ExceptionCheckClosure {
            Objects.requireNonNull(closedBy, "closedBy");
            Objects.requireNonNull(closedAt, "closedAt");
            Objects.requireNonNull(catalogVersion, "catalogVersion");
            Objects.requireNonNull(knowledgeGeneration, "knowledgeGeneration");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matterId, "matterId");
            if (closedBy.isBlank()) {
                throw new IllegalArgumentException("Abschluss ohne Benutzer");
            }
        }
    }

    /**
     * Beleg einer Ausnahme: alle drei Merkmale müssen nachgewiesen sein.
     *
     * @param exception     die Ausnahmeregel
     * @param matterRelation Sachverhaltsbezug
     * @param effectiveness Wirksamkeit zum maßgeblichen Zeitpunkt
     * @param legalEffect   Rechtswirkung (verdrängt sie die Standardfrist?)
     * @param evidence      Fundstellen (Dokument oder Rechtsquelle), wörtlich
     */
    record ExceptionEvidence(RuleRef exception, Proof matterRelation, Proof effectiveness, Proof legalEffect,
                             List<String> evidence) {

        public ExceptionEvidence {
            Objects.requireNonNull(exception, "exception");
            Objects.requireNonNull(matterRelation, "matterRelation");
            Objects.requireNonNull(effectiveness, "effectiveness");
            Objects.requireNonNull(legalEffect, "legalEffect");
            evidence = List.copyOf(evidence);
        }

        public boolean proven() {
            return matterRelation == Proof.PROVEN && effectiveness == Proof.PROVEN && legalEffect == Proof.PROVEN;
        }
    }

    /** Standardregel bekannt, Ausnahmen nicht vollständig geprüft. */
    record StandardProvisional(DocumentRef document, String matterId, RuleRef rule, Deadline deadline,
                               Set<ProvisionalReason> reasons, List<ExceptionEvidence> candidates)
            implements DeadlineAssessment {

        public StandardProvisional {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matterId, "matterId");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(deadline, "deadline");
            if (reasons == null || reasons.isEmpty()) {
                throw new IllegalArgumentException("Vorläufig ohne Grund");
            }
            reasons = Set.copyOf(EnumSet.copyOf(reasons));
            candidates = List.copyOf(candidates);
        }
    }

    /** Keine einschlägige Ausnahme nach abgeschlossener Prüfung. */
    record StandardConfirmed(DocumentRef document, String matterId, RuleRef rule, Deadline deadline,
                             ExceptionCheckClosure closure) implements DeadlineAssessment {

        public StandardConfirmed {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matterId, "matterId");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(closure, "closure");
            if (!closure.document().equals(document) || !closure.matterId().equals(matterId)) {
                throw new IllegalArgumentException("Abschluss gehört zu einem anderen Dokumentstand oder Sachverhalt");
            }
        }
    }

    /** Eine wirksame, einschlägige Ausnahme ist nachgewiesen; {@link #rule()} ist die Ausnahme. */
    record DeviationConfirmed(DocumentRef document, String matterId, RuleRef rule, Deadline deadline,
                              RuleRef displaced, ExceptionEvidence evidence) implements DeadlineAssessment {

        public DeviationConfirmed {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(matterId, "matterId");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(displaced, "displaced");
            Objects.requireNonNull(evidence, "evidence");
            if (!evidence.proven()) {
                throw new IllegalArgumentException("Abweichung ohne vollständig nachgewiesene Ausnahme");
            }
            if (!evidence.exception().equals(rule)) {
                throw new IllegalArgumentException("Beleg gehört zu einer anderen Regel");
            }
        }
    }
}
