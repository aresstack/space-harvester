package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port der Glossarablage. Glossareinträge sind Nutzerdaten, kein Cache: Die Ablage liegt außerhalb des
 * Cache-Verzeichnisses und übersteht dessen Neuaufbau, Neustarts und Modellwechsel. Jede Änderung ist atomar und
 * steht im Änderungsprotokoll.
 */
public interface GlossaryStore {

    /** Alle Einträge, auch abgelehnte. */
    List<GlossaryEntry> all();

    Optional<GlossaryEntry> find(String id);

    /**
     * Schreibt Einträge und entfernt andere in einem Schritt; danach oder gar nicht.
     *
     * @param put     neue oder geänderte Einträge, ersetzt nach {@link GlossaryEntry#id()}
     * @param removed Kennungen zu entfernender Einträge
     * @param audit   Protokolleinträge dieser Änderung
     */
    void write(Collection<GlossaryEntry> put, Collection<String> removed, List<AuditEntry> audit);

    /** Protokoll eines Eintrags, älteste zuerst. */
    List<AuditEntry> history(String id);

    /**
     * Ein Eintrag im Änderungsprotokoll.
     *
     * @param entryId  Glossareintrag
     * @param action   {@code create}, {@code edit}, {@code confirm}, {@code reject}, {@code restore},
     *                 {@code remove}, {@code propose}, {@code withdraw}, {@code stale} oder {@code purge}
     * @param actor    {@code AccessContext#jobOwner()} oder Kennung des Laufs
     * @param at       Zeitpunkt
     * @param status   Prüfstatus danach oder {@code null}, wenn entfernt
     * @param revision Revision danach
     */
    record AuditEntry(String entryId, String action, String actor, Instant at, ReviewStatus status, long revision) {
    }
}
