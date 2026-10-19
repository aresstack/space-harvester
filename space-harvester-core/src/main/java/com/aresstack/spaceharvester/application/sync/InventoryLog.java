package com.aresstack.spaceharvester.application.sync;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bezugspunkt der regelmäßigen Bestandsinventur je Space (ADR 0014 Punkt 1): der Start der letzten geplanten Inventur
 * oder, solange keine lief, der Zeitpunkt, ab dem sie eingeplant ist. Die nächste Inventur ist einen Abstand danach
 * fällig, auch über Neustarts und Konfigurationswechsel hinweg; sonst liefe bei {@code P7D} und täglichem Start nie eine.
 */
public interface InventoryLog {

    /** Der Bezugspunkt des Space oder leer, wenn für ihn noch keiner vermerkt ist. */
    Optional<Instant> since(String sourceId, String spaceKey);

    /** Vermerkt einen neuen Bezugspunkt, etwa den Start einer Inventur. */
    void mark(String sourceId, String spaceKey, Instant at);

    /** Nur im Speicher: übersteht keinen Neustart, etwa ohne Cache-Verzeichnis. */
    static InventoryLog inMemory() {
        Map<String, Instant> marks = new ConcurrentHashMap<>();
        return new InventoryLog() {
            @Override
            public Optional<Instant> since(String sourceId, String spaceKey) {
                return Optional.ofNullable(marks.get(sourceId + "/" + spaceKey));
            }

            @Override
            public void mark(String sourceId, String spaceKey, Instant at) {
                marks.put(sourceId + "/" + spaceKey, Objects.requireNonNull(at, "at"));
            }
        };
    }
}
