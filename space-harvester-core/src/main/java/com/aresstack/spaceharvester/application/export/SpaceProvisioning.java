package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import java.util.List;
import java.util.function.Predicate;

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
     * Wie {@link #provision(JobContext, String, String)}, mit Fortschritt: {@code discovered} zählt die Seiten der
     * Inventur, {@code processed} die abgeglichenen, jeweils nur solche, für die {@code counted} gilt. So verraten die
     * Zähler keine Seiten, die der Auftraggeber nicht lesen darf. Eine Umsetzung ohne eigene Fassung dieser Methode
     * kann nicht filtern; sie erhält einen Auftrag ohne Zähler.
     */
    default Provisioned provision(JobContext context, String sourceId, String spaceKey,
                                  Predicate<PageHeader> counted) {
        return provision(new JobContext() {
            @Override
            public String jobId() {
                return context.jobId();
            }

            @Override
            public String owner() {
                return context.owner();
            }

            @Override
            public void checkpoint() {
                context.checkpoint();
            }

            @Override
            public boolean cancelRequested() {
                return context.cancelRequested();
            }

            @Override
            public void discovered(long pages) {
            }

            @Override
            public void processed(long pages) {
            }

            @Override
            public void written(long pages) {
            }

            @Override
            public void failed(long pages) {
            }

            @Override
            public void phase(String component, String phase) {
                context.phase(component, phase);
            }
        }, sourceId, spaceKey);
    }

    /**
     * Ergebnis des Einlesens.
     *
     * @param confirmed     eine Generation ist bestätigt (eigene oder die eines gleichzeitigen Abgleichs)
     * @param superseded    ein gleichzeitiger Abgleich hat zuerst bestätigt; sein Stand ist der aktuelle
     * @param failedPages Kopfdaten aus der Inventur der Seiten, deren Abruf scheiterte: fehlen im Cache oder bleiben
     *                    auf altem Stand; der Export nennt und zählt davon nur, was der Auftraggeber lesen darf
     */
    record Provisioned(boolean confirmed, boolean superseded, List<PageHeader> failedPages) {

        public static final Provisioned NOT_CONFIRMED = new Provisioned(false, false, List.of());

        public Provisioned {
            failedPages = List.copyOf(failedPages);
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
