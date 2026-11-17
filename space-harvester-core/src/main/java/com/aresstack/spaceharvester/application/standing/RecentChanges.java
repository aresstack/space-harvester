package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Port: wann der Inhalt einer Seite zuletzt neu kam oder sich änderte, aus dem Änderungsprotokoll (ADR 0030), nur
 * gelesen und nur Zeilen, die der Aufrufer sehen darf.
 */
@FunctionalInterface
public interface RecentChanges {

    /** Ohne Änderungsprotokoll. */
    RecentChanges NONE = (caller, from, cancelled) -> Map.of();

    /** Jüngste Inhaltsänderung je Seite seit {@code from} (einschließlich). */
    Map<PageKey, Instant> since(AccessContext caller, Instant from, BooleanSupplier cancelled);
}
