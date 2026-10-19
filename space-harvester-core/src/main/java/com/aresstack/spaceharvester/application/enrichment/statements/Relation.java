package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Eine verankerte Aussage über eine Erwähnung oder zwischen Handlungen (#108): eine Eigenschaft mit Wortlaut aus dem
 * Text ({@link RelationKind#ATTRIBUTE}, {@link RelationKind#STATE}) oder die Folge zweier Handlungen nach einem
 * Signalwort ({@link RelationKind#BEFORE}). An ihren Satz und Kontext gebunden; nie eine allgemeine Wahrheit.
 *
 * @param key            Schlüssel innerhalb der Seite
 * @param sentence       Bereich des Satzes
 * @param kind           Art
 * @param subjectMention die Erwähnung einer Eigenschaft oder {@code null} bei einer Folge
 * @param subjectEvent   die frühere Handlung einer Folge oder {@code null}
 * @param objectEvent    die spätere Handlung einer Folge oder {@code null}
 * @param literal        die Wörter der Eigenschaft oder das Signalwort einer Folge
 * @param lemmaCandidate vorgeschlagene Grundform oder {@code null}
 * @param normalized     Suchwert
 * @param normalization  Verfahren des Suchwerts
 * @param context        Verneinung, Modalität, Bedingung
 * @param status         {@link ExtractionStatus}
 */
public record Relation(String key, TextSpan sentence, RelationKind kind, Mention subjectMention, Event subjectEvent,
                       Event objectEvent, GroundedText literal, String lemmaCandidate, String normalized,
                       String normalization, StatementContext context, ExtractionStatus status) {

    public Relation {
        context = context == null ? StatementContext.none() : context;
    }

    /** Eine Eigenschaft von {@code subject}, gegeben durch Wörter {@code literal} desselben Satzes. */
    public static Relation property(String key, TextSpan sentence, RelationKind kind, Mention subject,
                                    GroundedText literal, String lemmaCandidate, String normalized,
                                    String normalization, StatementContext context, ExtractionStatus status) {
        return new Relation(key, sentence, kind, subject, null, null, literal, lemmaCandidate, normalized,
                normalization, context, status);
    }

    /** {@code before} geschieht vor {@code after}, nach dem Signalwort {@code cue}. */
    public static Relation order(String key, TextSpan sentence, Event before, Event after, GroundedText cue,
                                 ExtractionStatus status) {
        return new Relation(key, sentence, RelationKind.BEFORE, null, before, after, cue, null,
                TermNormalizer.fromSurface(cue.text()), TermNormalizer.FROM_SURFACE, StatementContext.none(), status);
    }

    /** Dieselbe Relation mit anderem Status. */
    public Relation withStatus(ExtractionStatus value) {
        return new Relation(key, sentence, kind, subjectMention, subjectEvent, objectEvent, literal, lemmaCandidate,
                normalized, normalization, context, value);
    }

    @Override
    public String toString() {
        return "Relation[" + key + " " + kind + " " + (subjectMention != null ? subjectMention.key()
                : subjectEvent.key() + "<" + objectEvent.key()) + ", " + status + "]";
    }
}
