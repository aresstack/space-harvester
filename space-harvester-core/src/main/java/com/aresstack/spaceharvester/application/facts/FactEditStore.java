package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Ablage der manuellen und geprüften Fakten (#182), außerhalb des Caches: Ein Neuaufbau des Caches oder der
 * Anreicherungen berührt sie nicht. Mit Protokoll jeder Änderung. Rechte prüft der Port nicht; das tut
 * {@link FactEdits}.
 */
public interface FactEditStore {

    /** Alle Einträge. */
    List<FactEdit> all();

    /** Die Einträge einer Seite, nach Kennung. */
    List<FactEdit> forPage(PageKey page);

    Optional<FactEdit> find(String factId);

    /** Ersetzt {@code put}, entfernt {@code removed} und hängt {@code audit} an, alles oder nichts. */
    void write(Collection<FactEdit> put, Collection<String> removed, List<AuditEntry> audit);

    /** Das Protokoll eines Fakts, älteste Änderung zuerst. */
    List<AuditEntry> history(String factId);

    /**
     * Eine Zeile im Protokoll.
     *
     * @param page     die Seite des Fakts
     * @param action   {@code add}, {@code review} oder {@code remove}
     * @param actor    {@code AccessContext#jobOwner()}
     * @param review   Prüfstatus danach
     * @param revision Revision danach; beim Entfernen die zuletzt gültige
     */
    record AuditEntry(String factId, PageKey page, String action, String actor, Instant at, FactReview review,
                      long revision) {
    }
}
