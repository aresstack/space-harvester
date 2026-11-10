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

            @Override
            public void pause(com.aresstack.spaceharvester.application.jobs.PauseReason reason) {
                context.pause(reason);
            }
        }, sourceId, spaceKey);
    }

    /**
     * Wie {@link #provision(JobContext, String, String, Predicate)}, aber was zählt, ergibt sich erst aus der Inventur:
     * {@code counting} bekommt alle Kopfdaten der Inventur und liefert die Zählregel, etwa nur den Teilbaum einer
     * Startseite. So passen „Seiten (Inventur)“ und „Exportiert“ zum Umfang des Exports, nicht zum ganzen Space.
     * Standard: die Regel für eine leere Inventur, ohne Blick auf die echte.
     */
    default Provisioned provisionScoped(JobContext context, String sourceId, String spaceKey,
                                        java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> counting) {
        return provision(context, sourceId, spaceKey, counting.apply(List.of()));
    }

    /**
     * Wie {@link #provisionScoped(JobContext, String, String, java.util.function.Function)}; Inhalte lädt das Einlesen
     * nur für die Seiten, die {@code loading} aus der Inventur bestimmt, etwa den Teilbaum eines Exports. Der Rest des
     * Space kommt nur mit Kopfdaten in den Katalog. Standard: wie ohne {@code loading}.
     *
     * @param loading aus den Kopfdaten der Inventur die Seiten mit Inhalt, oder {@code null} für alle
     */
    default Provisioned provisionScoped(JobContext context, String sourceId, String spaceKey,
                                        java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> counting,
                                        java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> loading) {
        return provisionScoped(context, sourceId, spaceKey, counting);
    }

    /**
     * Wie {@link #provisionScoped(JobContext, String, String, java.util.function.Function, java.util.function.Function)};
     * mit {@code reloadAll} („Neu erzwingen“, ADR 0025) lädt das Einlesen auch unveränderte Seiten neu, in eine neue
     * Generation, die den bisherigen Stand erst nach dem Lauf ersetzt. Standard: wie ohne {@code reloadAll}.
     */
    default Provisioned provisionScoped(JobContext context, String sourceId, String spaceKey,
                                        java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> counting,
                                        java.util.function.Function<List<PageHeader>, Predicate<PageHeader>> loading,
                                        boolean reloadAll) {
        return provisionScoped(context, sourceId, spaceKey, counting, loading);
    }

    /**
     * Seiten der bestätigten Generation ohne aktuellen Inhalt im Cache: nur katalogisiert, nie geladen oder veraltet.
     * Ein Export, der eine davon braucht, liest neu ein, statt die Generation zu übernehmen. Standard: keine.
     */
    default java.util.Set<String> unloaded(String sourceId, String spaceKey) {
        return java.util.Set.of();
    }

    /**
     * „Nur Fehler neu versuchen“ (ADR 0025): lädt genau diese Seiten nach, ohne Inventur, auf der bestätigten
     * Generation des Space, und bestätigt danach die nächste Generation. Jede Seite wird einzeln nachgefragt; was
     * {@code allowed} nach den frischen Kopfdaten nicht zulässt (nicht lesbar, außerhalb des Umfangs), fällt still weg,
     * ebenso Seiten, die die Quelle nicht mehr führt. Standard: nicht möglich.
     *
     * @return {@link Retried#UNAVAILABLE}, wenn es keine bestätigte Generation gibt oder die Umsetzung es nicht kann;
     *         dann liest der Aufrufer wie gewohnt ein
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException wenn die Quelle nicht konfiguriert
     *                                                                                 ist
     */
    default Retried retry(JobContext context, String sourceId, String spaceKey, java.util.Set<String> pageIds,
                          Predicate<PageHeader> allowed) {
        return Retried.UNAVAILABLE;
    }

    /**
     * Ergebnis von {@link #retry}.
     *
     * @param done        ob nachgeladen wurde
     * @param failedPages frische Kopfdaten der zulässigen Seiten, deren Abruf wieder scheiterte
     * @param unchecked   Seiten, deren Kopfdaten die Quelle nicht lieferte (Verbindung, Drosselung, Fehler); sie
     *                    bleiben, wie sie waren, und gelten weiter als gescheitert
     */
    record Retried(boolean done, List<PageHeader> failedPages, java.util.Set<String> unchecked) {

        public static final Retried UNAVAILABLE = new Retried(false, List.of(), java.util.Set.of());

        public Retried {
            failedPages = List.copyOf(failedPages);
            unchecked = java.util.Set.copyOf(unchecked);
        }
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
