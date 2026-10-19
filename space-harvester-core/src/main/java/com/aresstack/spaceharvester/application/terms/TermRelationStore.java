package com.aresstack.spaceharvester.application.terms;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port der Beziehungsablage (#120). Beziehungen sind Nutzerdaten, kein Cache: Die Ablage liegt außerhalb des
 * Cache-Verzeichnisses und übersteht dessen Neuaufbau. Jede Änderung ist atomar und steht im Änderungsprotokoll.
 */
public interface TermRelationStore {

    /** Alle Beziehungen, auch abgelehnte. */
    List<TermRelation> all();

    Optional<TermRelation> find(String id);

    /**
     * Schreibt Beziehungen und entfernt andere in einem Schritt; danach oder gar nicht.
     *
     * @param put     neue oder geänderte Beziehungen, ersetzt nach {@link TermRelation#id()}
     * @param removed Kennungen zu entfernender Beziehungen
     * @param audit   Protokolleinträge dieser Änderung
     */
    void write(Collection<TermRelation> put, Collection<String> removed, List<AuditEntry> audit);

    /** Protokoll einer Beziehung, älteste zuerst. */
    List<AuditEntry> history(String id);

    /**
     * Ein Eintrag im Änderungsprotokoll: wer, wann, was.
     *
     * @param relationId Beziehung
     * @param action     {@code create}, {@code confirm}, {@code reject}, {@code restore}, {@code remove},
     *                   {@code propose} oder {@code withdraw}
     * @param actor      {@code AccessContext#jobOwner()} oder Kennung des Laufs
     * @param at         Zeitpunkt
     * @param status     Prüfstatus danach oder {@code null}, wenn entfernt
     * @param revision   Revision danach
     */
    record AuditEntry(String relationId, String action, String actor, Instant at, ReviewStatus status,
                      long revision) {
    }
}
