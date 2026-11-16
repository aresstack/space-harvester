package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.Objects;

/**
 * Der Unterschied einer geänderten Seite im Bericht eines Updates (ADR 0030, Abschnitt 4): je ersetztem Inhalt ein
 * Schritt, vom alten bis zum neuen Stand des Updates. Was fehlt, steht als Zustand da; geschätzt wird nichts.
 *
 * @param status ob und warum ein Unterschied vorliegt
 * @param steps  die Schritte in zeitlicher Reihenfolge; leer außer bei {@link Status#AVAILABLE} und
 *               {@link Status#CONTENT_PENDING}
 */
public record ContentDiff(Status status, List<ContentChange> steps) {

    public enum Status {
        /** Die Schritte reichen bis zur neuen Version des Updates. */
        AVAILABLE,
        /** Der Inhalt der neuen Version ist noch nicht lokal; vorhandene Schritte enden davor. */
        CONTENT_PENDING,
        /** Der bisherige Inhalt war nicht im Cache, als der neue kam; es gibt keinen Unterschied. */
        PREVIOUS_MISSING,
        /** Der Aufrufer darf den Inhalt der Seite nicht lesen. */
        NOT_PERMITTED
    }

    public ContentDiff {
        Objects.requireNonNull(status, "status");
        steps = List.copyOf(steps);
    }
}
