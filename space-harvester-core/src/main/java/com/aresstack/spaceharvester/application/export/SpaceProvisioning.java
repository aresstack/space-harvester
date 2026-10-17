package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.util.Set;

/**
 * Stellt den Bestand eines Space im laufenden Exportauftrag bereit: liest ihn von der Quelle in den Cache ein und
 * bestätigt eine Generation, auf demselben Weg wie ein Abgleich (Inventur, Inhaltsabruf, vorgemerkte Generation). Der
 * Export ruft das auf seinem eigenen Ausführungsplatz auf; es entsteht kein zweiter Auftrag, auf den er warten müsste.
 *
 * <p>Abbruch und Laufzeitgrenze greifen über {@link JobContext#checkpoint()}; ein abgebrochenes Einlesen verwirft alles
 * Vorgemerkte, die bisherige Generation bleibt gültig.
 */
public interface SpaceProvisioning {

    /** Ob ein Einlesen möglich ist (Cache-Speicher und Inhaltsabruf angebunden). */
    boolean available();

    /**
     * Liest den Space ein.
     *
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException wenn die Quelle nicht konfiguriert
     *                                                                                 ist
     * @throws com.aresstack.spaceharvester.application.sources.SourceAccessException  wenn die Quelle nicht antwortet
     */
    Provisioned provision(JobContext context, String sourceId, String spaceKey);

    /**
     * Ergebnis des Einlesens.
     *
     * @param confirmed     eine Generation ist bestätigt (eigene oder die eines gleichzeitigen Abgleichs)
     * @param superseded    ein gleichzeitiger Abgleich hat zuerst bestätigt; sein Stand ist der aktuelle
     * @param failedPageIds Seiten, deren Abruf scheiterte: fehlen im Cache oder bleiben auf altem Stand; der Export
     *                      nennt davon nur, was der Auftraggeber lesen darf
     */
    record Provisioned(boolean confirmed, boolean superseded, Set<String> failedPageIds) {

        public static final Provisioned NOT_CONFIRMED = new Provisioned(false, false, Set.of());

        public Provisioned {
            failedPageIds = Set.copyOf(failedPageIds);
        }
    }

    /** Ohne Cache-Speicher oder Inhaltsabruf: Exporte nur aus vorhandenem Bestand. */
    static SpaceProvisioning unavailable() {
        return new SpaceProvisioning() {

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Provisioned provision(JobContext context, String sourceId, String spaceKey) {
                throw new IllegalStateException("Kein Einlesen angebunden");
            }
        };
    }
}
