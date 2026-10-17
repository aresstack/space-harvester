package com.aresstack.spaceharvester.nlp.extraction;

/**
 * Eine Zeile der Kernsicht: Subjekt → Prädikat → Akkusativobjekt einer Aussage. Empfänger, Zeit, Umstände und der
 * Originalsatz stehen in der Aussage ({@link ObjectCentricAnalysis#statement(String)}) und werden bei Bedarf
 * nachgeladen.
 *
 * @param statement      ID der Aussage
 * @param subject        ID der Subjekt-Erwähnung
 * @param subjectText    Subjekt im Wortlaut
 * @param predicateText  Prädikat im Wortlaut
 * @param predicateLemma Grundform des Prädikats oder {@code null}
 * @param object         ID der Objekt-Erwähnung
 * @param objectText     Objekt im Wortlaut
 * @param unresolved     Subjekt oder Objekt ist ein Pronomen mit offenem Bezug
 */
public record CoreRelation(String statement, String subject, String subjectText, String predicateText,
                           String predicateLemma, String object, String objectText, boolean unresolved) {
}
