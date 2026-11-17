package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.util.Optional;

/**
 * Themenvorschläge eines Updates (ADR 0030, Nachtrag Abschnitt 4) mit Rechten und Ablage, über der reinen Funktion
 * {@link TopicProposer}. Läuft nur im Auftrag „Zusammenfassen“ mit dessen Aufrufer, nie beim Öffnen einer Ansicht.
 */
public interface ChangeTopicsQuery {

    /**
     * @param caller   der Aufrufer des Auftrags; es zählt nur, was er sehen darf
     * @param sourceId Quelle
     * @param updateId Update oder {@code ChangeUpdate#OPEN}
     * @param spaceKey Ebene: leer für die ganze Quelle, sonst nur dieser Space
     * @param job      Fortschritt, Prüfpunkt, Anhalten
     */
    TopicSet topics(AccessContext caller, String sourceId, String updateId, Optional<String> spaceKey,
                    JobContext job);
}
