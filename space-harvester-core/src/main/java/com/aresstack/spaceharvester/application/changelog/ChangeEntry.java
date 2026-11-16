package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.Objects;

/**
 * Eine Zeile des Änderungsprotokolls (ADR 0030, Abschnitt 1): eine Art einer Änderung einer Seite. Ändert ein
 * Schreiblauf an einer Seite mehreres zugleich (etwa Titel und Version), entsteht je Art eine Zeile mit denselben
 * alten und neuen Werten. Kein Inhalt; Titel stammen aus der Quelle und erscheinen in keinem Log.
 *
 * @param spaceKey       der neue Space, bei {@link ChangeKind#REMOVED} und {@link ChangeKind#WITHHELD} der bisherige
 * @param generation     die bestätigte Generation des Space aus demselben Schreiblauf, sonst {@code null}
 * @param contentPending bei {@link ChangeKind#NEW} und {@link ChangeKind#CONTENT_CHANGED}: der Inhalt dieser Version
 *                       ist noch nicht lokal
 */
public record ChangeEntry(String sourceId, String spaceKey, String pageId, Instant at, ChangeKind kind,
                          ChangeTrigger trigger, String oldVersion, String newVersion, String oldTitle,
                          String newTitle, String oldParentId, String newParentId, String oldSpaceKey,
                          String newSpaceKey, Long generation, boolean contentPending) {

    public ChangeEntry {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(trigger, "trigger");
    }
}
