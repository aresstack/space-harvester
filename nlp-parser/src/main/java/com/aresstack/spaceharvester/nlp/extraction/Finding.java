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
    PREDICATIVE_PARTICLE,
    /**
     * Die Grundform des Prädikats ist aus der Wortform erzeugt, weil OpenNLP keine eigene lieferte (ADR 0032, FL2):
     * „abruft“ → „abrufen“. Merkmal {@code LemmaFrom=Form} am Token; reiner Hinweis, ändert die Stufe nicht.
     */
    LEMMA_FROM_FORM,
    /**
     * Ein Beteiligter ist ein Teilsatz (Erwähnung der Art {@link Mention.Kind#CLAUSE}): „Wer … stellt, erhält …“,
     * „Es wird geprüft, ob …“. Er verweist auf die Aussage des Teilsatzes. Aus {@code csubj} oder nachgestelltem
     * {@code ccomp} ist das Parserevidenz; ein vorangestellter dass-/ob-Satz ({@code advcl}) oder eine Infinitivgruppe
     * im Vorfeld ist abgeleitet ({@link Basis#HEURISTIC}, unsicher). wenn-/falls-/sofern-Sätze sind nie Beteiligte.
     */
    CLAUSE_PARTICIPANT,
    /**
     * Gereihte Verben mit einem Objekt am Ende („erfasst, prüft und genehmigt den Mittelabruf“): das Objekt ist von
     * hinten übernommen, unsicher (es gibt kein Valenzlexikon: „erscheint und unterschreibt den Antrag“).
     */
    OBJECT_FROM_COORDINATION,
    /** Relativpronomen auf das Nomen aufgelöst, an dem der Relativsatz hängt („die Mittel, die … abruft“). */
    RELATIVE_PRONOUN_RESOLVED,
    /**
     * Personalpronomen als Objekt eines gereihten Verbs auf das Objekt des Bezugsverbs aufgelöst („prüft den Antrag
     * und leitet ihn weiter“); nur bei genau einem Kandidaten, unsicher.
     */
    PRONOUN_FROM_COORDINATION,
    /**
     * Eine zu-Infinitivgruppe hat kein Subjekt: ein Subjekt laut Parser ist bei fehlendem Objekt das Objekt
     * (unsicher), sonst verworfen („Die Unterlagen einzureichen, obliegt …“).
     */
    SUBJECT_OF_INFINITIVE,
    /**
     * Im Passiv ohne von-Gruppe ist eine durch-Gruppe als Handelnder übernommen, abgeleitet und unsicher („wird durch
     * die Bewilligungsstelle erlassen“); im Aktiv bleibt sie Umstand oder Mittel.
     */
    AGENT_FROM_DURCH,
    /** „ist … zu + Infinitiv“: die von-Gruppe ist als Handelnder übernommen, abgeleitet und unsicher. */
    AGENT_FROM_VON,
    /**
     * Zustandsaussage mit Eigenschaft (ADR 0032): Kopula ({@code sein}, {@code werden}, {@code bleiben}) mit
     * prädikativem Adjektiv („Der Antrag ist unvollständig“). Das Prädikat ist das Adjektiv, kein Verb; der Träger hat
     * die Rolle {@link Role#BEARER}. Zählt nie als Verb (Verbliste, Valenz).
     */
    STATE_PROPERTY,
    /**
     * Zustandsaussage mit Rollennomen (ADR 0032): Kopula mit prädikativem Nomen („Der Sachbearbeiter ist
     * Ansprechpartner“, „Der Zuschuss ist eine Förderung“). Das Prädikat ist das Nomen, kein Verb.
     */
    STATE_ROLE_NOUN,
    /**
     * Zustandswechsel: die Kopula ist {@code werden} („Der Bescheid wird bestandskräftig“). Der Zustand gilt noch nicht,
     * er tritt ein; reiner Hinweis, ändert die Stufe nicht.
     */
    STATE_CHANGE
}
