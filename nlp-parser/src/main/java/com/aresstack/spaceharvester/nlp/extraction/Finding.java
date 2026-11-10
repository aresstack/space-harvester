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
    UNCONFIRMED_DATIVE,
    /**
     * Eine Zeitangabe mit Präposition hing laut Parser am Dativempfänger ({@code dem Vorgesetzten am Montag}) und
     * ist als Zeitkontext der Aussage übernommen; der nominale Bezug bleibt daneben erhalten.
     */
    TIME_FROM_NOMINAL,
    /** Ein Reflexivpronomen ({@code sich}) am Prädikat; es ist kein eigener Beteiligter. */
    REFLEXIVE,
    /**
     * Ein Hilfsverb, das der Parser über eine Teilsatzgrenze an dieses Prädikat gehängt hat, zählt nicht
     * ({@code stellt … bereit, die … ausgewertet werden}).
     */
    AUXILIARY_OUTSIDE_CLAUSE,
    /** Kein Subjekt übernommen: zwischen den gereihten Prädikaten beginnt ein Teilsatz mit eigenem Subjekt. */
    COORDINATION_ACROSS_CLAUSE,
    /**
     * Impliziter Handelnder aus der Kennung eines Listeneintrags ohne Subjektkante („Datenkatalog: organisiert und
     * stellt … bereit“); immer {@link Basis#HEURISTIC} und unsicher. Hängt der Parser die Kennung selbst als Subjekt
     * an, ist das Parserevidenz und kein solcher Fall.
     */
    SUBJECT_FROM_LABEL,
    /** Die Wiederholung der Kennung im Vorfeld („Register: Register erweitern …“) ist dasselbe Subjekt, kein Objekt. */
    LABEL_REPEATED,
    /**
     * Verbzusatz, den der Parser als prädikatives Adjektiv anhängt („stellt … sicher“), ist ins Prädikat übernommen;
     * ein daran hängendes Subjekt gilt als unsicheres Objekt.
     */
    PREDICATIVE_PARTICLE
}
