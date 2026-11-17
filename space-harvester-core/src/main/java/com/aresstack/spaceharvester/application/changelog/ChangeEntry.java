package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
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
 * @param editor         wer die gespeicherte Fassung zuletzt bearbeitet hat, soweit die Quelle es mit dem Inhalt
 *                       liefert (ADR 0031), sonst {@code null}. Bei mehreren Versionen zwischen zwei Abrufen ist nur der
 *                       letzte Bearbeiter bekannt. Personenbezug: nur in der Zeile, mit ihren Rechten, nie in Logs,
 *                       Auftragsmeldungen oder im Suchtext des Protokolls; geht mit der Zeile
 * @param labels         die Labels der Seite nach der Änderung, soweit die Quelle sie liefert, sonst leer
 */
public record ChangeEntry(String sourceId, String spaceKey, String pageId, Instant at, ChangeKind kind,
                          ChangeTrigger trigger, String oldVersion, String newVersion, String oldTitle,
                          String newTitle, String oldParentId, String newParentId, String oldSpaceKey,
                          String newSpaceKey, Long generation, boolean contentPending, String editor,
                          List<String> labels) {

    /** Ohne Bearbeiter und Labels. */
    public ChangeEntry(String sourceId, String spaceKey, String pageId, Instant at, ChangeKind kind,
                       ChangeTrigger trigger, String oldVersion, String newVersion, String oldTitle, String newTitle,
                       String oldParentId, String newParentId, String oldSpaceKey, String newSpaceKey,
                       Long generation, boolean contentPending) {
        this(sourceId, spaceKey, pageId, at, kind, trigger, oldVersion, newVersion, oldTitle, newTitle, oldParentId,
                newParentId, oldSpaceKey, newSpaceKey, generation, contentPending, null, List.of());
    }

    public ChangeEntry {
        labels = labels == null ? List.of() : List.copyOf(labels);
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(trigger, "trigger");
    }
}
