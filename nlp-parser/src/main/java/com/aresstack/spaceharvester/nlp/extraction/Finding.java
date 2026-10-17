package com.aresstack.spaceharvester.nlp.extraction;

/** Hinweis an einer Aussage: etwas, das die Regeln erkannt, aber bewusst nicht aufgelöst oder unterstellt haben. */
public enum Finding {
    /** Passiv: das Subjekt ist betroffen, nicht handelnd. */
    PASSIVE,
    /** Teilsatz ({@code weil …}, Relativsatz, Infinitivgruppe): nachrangig, nicht in der Kernsicht. */
    SUBORDINATE_CLAUSE,
    /** Koordiniertes Prädikat ohne eigenes Subjekt; das Subjekt des ersten Prädikats ist übernommen, nicht belegt. */
    SUBJECT_FROM_COORDINATION,
    /** Kein Subjekt gefunden. */
    NO_SUBJECT,
    /** Kein Akkusativobjekt; die Aussage hat keinen Kern Subjekt → Prädikat → Objekt. */
    NO_ACCUSATIVE_OBJECT,
    /** Mehrere koordinierte Objekte; jedes steht als eigener Beteiligter da. */
    COORDINATED_OBJECTS,
    /** Ein Beteiligter ist ein Pronomen mit offenem Bezug. */
    UNRESOLVED_PRONOUN,
    /** Kasus widerspricht der Parserkante; die Kante bleibt, die Zuordnung gilt als unsicher. */
    CASE_CONFLICT,
    /** Ein Beteiligter stammt aus dem Kasus-Rückfall, nicht aus einer Parserkante. */
    HEURISTIC_PARTICIPANT,
    /** Ein Dativargument ohne bestätigten Dativ (Kasus unbekannt oder mehrdeutig). */
    UNCONFIRMED_DATIVE
}
