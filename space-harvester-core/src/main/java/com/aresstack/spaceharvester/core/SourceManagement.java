package com.aresstack.spaceharvester.core;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.CertificateExport;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.application.sources.SourceRemovalPendingException;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceCertificates;
import com.aresstack.spaceharvester.source.SourceDiagnostics;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Quellen zur Laufzeit verwalten (#188), wie {@code KnowledgeSourceManagement} und {@code SourceConnectionCheck} im
 * Enterprise AI Client: prüfen, Verbindung testen, hinzufügen, ändern, abschalten, entfernen. Eine Quelle ist wie dort
 * durch Typ und Ziel bestimmt: dieselbe Basisadresse unter einer zweiten ID lehnt {@link #validate} ab. Die
 * Einstellungen dauerhaft ablegen (Konfigurationsdatei, Tresor) bleibt Sache des einbettenden Programms; diese
 * Verwaltung ändert nur den laufenden Stand.
 *
 * <p>Abgeleitete Daten gehören zur Quelle: Wird sie entfernt oder zeigt sie nach einer Änderung auf ein anderes Ziel
 * (anderer Typ oder andere Basisadresse), werden ihre offenen Aufträge abgebrochen und Seiten-Cache samt Suchindex
 * und Abgleichständen, Anreicherungen (KI-Text, Embeddings, Lese-Ansichten), Assets, Begriffsbeziehungen, geprüfte
 * Fakten und Aussagen entfernt ({@link SourceDataStores}). Exporte bleiben, mit den Bildern, auf die sie verweisen; sie
 * entfernt ihr Besitzer selbst. {@link #purge} löscht dasselbe, ohne die Quelle zu entfernen.
 *
 * <p>Rechte: Die Verwaltung prüft keine {@code AccessPolicy}; sie gehört dem Betreiber des einbettenden Programms.
 */
public final class SourceManagement {

    /** So lange warten Entfernen und Ändern auf laufende Aufträge der Quelle. */
    static final Duration JOB_STOP_TIMEOUT = com.aresstack.spaceharvester.application.jobs.Jobs.STOP_TIMEOUT;

    private static final System.Logger LOG = System.getLogger(SourceManagement.class.getName());

    private final ConfiguredSourceCatalog catalog;
    private final Jobs jobs;
    private final SourceDataPurge purge;
    private final SourceDiagnostics diagnostics;
    private final SourceCertificates certificates;

    SourceManagement(ConfiguredSourceCatalog catalog, Jobs jobs, SourceDataPurge purge) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.purge = Objects.requireNonNull(purge, "purge");
        this.diagnostics = new SourceDiagnostics(catalog);
        this.certificates = new SourceCertificates(catalog);
        purge.onSettled(this::withdrawn);
    }

    /** Die Einstellungen aller Quellen in ihrer Reihenfolge; ihr {@code toString} zeigt keine Geheimnisse. */
    public List<SourceSettingsConfig> sources() {
        return catalog.configs();
    }

    /**
     * Probleme der Einstellungen mit Feldnamen, nie Werten; leer, wenn sie sich übernehmen lassen. Öffnet nichts.
     *
     * @param replacing {@code true} beim Ändern einer vorhandenen Quelle derselben ID, sonst ist die ID vergeben
     */
    public List<String> validate(SourceSettingsConfig draft, boolean replacing) {
        return catalog.problems(draft, replacing);
    }

    /**
     * Wie {@link #validate(SourceSettingsConfig, boolean)}, dazu gegen Quellen, die das Programm eingerichtet, aber
     * nicht geöffnet hat (wartet auf ihr Geheimnis, beim Start abgelehnt): Ihre IDs und Ziele sind ebenso belegt.
     */
    public List<String> validate(SourceSettingsConfig draft, boolean replacing,
                                 Collection<SourceSettingsConfig> alsoConfigured) {
        return catalog.problems(draft, replacing, alsoConfigured);
    }

    /**
     * „Verbindung testen“: öffnet die Einstellungen nur für diesen Test und listet die sichtbaren Partitionen (Spaces)
     * live, ohne etwas abzulegen. Die Meldung nennt die Art des Fehlers, nie Werte der Einstellungen.
     */
    public List<String> libraries(SourceSettingsConfig draft, boolean replacing) {
        return catalog.probe(draft, replacing, source -> source.libraries());
    }

    public ConnectionCheck test(SourceSettingsConfig draft, boolean replacing) {
        try {
            List<Partition> partitions = catalog.probe(draft, replacing, source -> source.partitions());
            return ConnectionCheck.success(partitions.size());
        } catch (InvalidSourceConfigurationException e) {
            return ConnectionCheck.failure("Einstellungen: " + String.join("; ", e.problems()));
        } catch (SourceAccessException e) {
            return ConnectionCheck.failure(SourceDiagnostics.message(e.reason()));
        } catch (RuntimeException e) {
            return ConnectionCheck.failure("Unerwarteter Fehler (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Verbindungsdiagnose in Stufen (#189): Proxy-Route, Namensauflösung, TCP samt Proxy-Tunnel, TLS, Anmeldung, API;
     * siehe {@link SourceDiagnostics}. Öffnet die Einstellungen nur für den Test und legt nichts ab.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen der Einstellungen
     */
    public SourceDiagnosis diagnose(SourceSettingsConfig draft, boolean replacing) {
        return diagnostics.diagnose(draft, replacing);
    }

    /** {@link #diagnose(SourceSettingsConfig, boolean)} für eine vorhandene Quelle; leer ohne diese ID. */
    public Optional<SourceDiagnosis> diagnose(String sourceId) {
        return diagnostics.diagnose(sourceId);
    }

    /**
     * Zertifikatsexport einer Quelle (Strang ZX): Serverkette samt fehlender CA-Zertifikate aus dem Vertrauen dieses
     * Rechners und öffentlicher Teil des Client-Zertifikats als ZIP mit Anleitung. Blockiert (Netz).
     *
     * @param directory Zielordner; den Ausgabeordner liefert der Aufrufer
     * @param cancelled vor dem Schreiben gefragt; {@code true} bricht ohne Datei ab
     * @return leer ohne Quelle dieser ID
     * @throws IllegalArgumentException wenn die Quelle keine https-Adresse hat
     * @throws java.util.concurrent.CancellationException wenn abgebrochen wurde
     */
    public Optional<CertificateExport> exportCertificates(String sourceId, java.nio.file.Path directory,
                                                          java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        return certificates.export(sourceId, directory, cancelled);
    }

    /** Wie {@link #exportCertificates}, aber nur die QR-Codes der vertrauten Zertifikate als Bilder (Strang ZX). */
    public Optional<CertificateExport> exportCertificateCodes(String sourceId, java.nio.file.Path directory,
                                                              java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        return certificates.exportQrCodes(sourceId, directory, cancelled);
    }

    /** Die vertrauten Zertifikate der Quelle als QR-Codes zur Anzeige; schreibt nichts. Blockiert (Netz). */
    public Optional<List<com.aresstack.spaceharvester.application.sources.CertificateCode>> certificateCodes(
            String sourceId, java.util.function.BooleanSupplier cancelled) {
        return certificates.codes(sourceId, cancelled);
    }

    /**
     * Fügt eine Quelle hinzu.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen; dann ändert sich nichts
     */
    public synchronized void add(SourceSettingsConfig config) {
        purge.withdrawing(config.id()).ifPresent(job -> {
            throw new SourceRemovalPendingException(config.id(), job.id());
        });
        catalog.add(config);
        LOG.log(System.Logger.Level.INFO, "Quelle {0} hinzugefügt", config.id());
    }

    /**
     * Ändert die Einstellungen einer Quelle. Zeigt sie danach auf ein anderes Ziel, werden ihre bisherigen Daten
     * entfernt; sonst bleiben Cache und Assets (etwa bei neuem Token oder Proxy).
     *
     * @return ob abgeleitete Daten entfernt wurden
     * @throws InvalidSourceConfigurationException mit den Problemen; dann bleibt die bisherige Quelle unverändert
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException ohne Quelle dieser ID
     */
    public synchronized boolean change(SourceSettingsConfig config) {
        SourceSettingsConfig previous = catalog.replace(config);
        boolean moved = !Objects.equals(previous.type(), config.type())
                || !Objects.equals(previous.baseUrl(), config.baseUrl());
        LOG.log(System.Logger.Level.INFO, "Quelle {0} geändert{1}", config.id(), moved ? ", neues Ziel" : "");
        if (moved) {
            // Fundstelle 22: als Auftrag „Daten bereinigen“, sichtbar und fortsetzbar. Er hält die Quelle: Ein gleich
            // danach gestarteter Abgleich wartet hinter ihm (Jobs#sourceHold), statt von ihm beendet zu werden.
            startWithdrawal(config.id(), SourceDataPurge.MOVED);
        }
        return moved;
    }

    /**
     * Ändert nur den Anzeigenamen einer Quelle; die Quelle bleibt geöffnet, Daten, Aufträge und Zugangsdaten bleiben
     * ({@link ConfiguredSourceCatalog#rename}). Anders als {@link #change} öffnet das die Quelle nicht neu.
     *
     * @return ob sich der Anzeigename geändert hat
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException ohne Quelle dieser ID
     */
    public synchronized boolean rename(String sourceId, String title) {
        boolean renamed = catalog.rename(sourceId, title);
        if (renamed) {
            LOG.log(System.Logger.Level.INFO, "Quelle {0} umbenannt", sourceId);
        }
        return renamed;
    }

    /** Ob die Quelle eingeschaltet ist; {@code false} auch für unbekannte. */
    public boolean enabled(String sourceId) {
        return catalog.enabled(sourceId);
    }

    /**
     * Schaltet eine Quelle ab oder wieder ein (wie {@code setEnabled} im Enterprise AI Client). Abgeschaltet behält sie
     * Einstellungen, Cache, Anreicherungen und Assets, nimmt aber an Suche, Lesen, Aufträgen und Export nicht teil;
     * ihre offenen Aufträge werden abgebrochen. Eingeschaltet ist alles wieder da, ohne neuen Abgleich.
     *
     * @return ob sich etwas geändert hat
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException ohne Quelle dieser ID
     */
    public synchronized boolean setEnabled(String sourceId, boolean enabled) {
        Boolean held = heldInactive.computeIfPresent(sourceId, (id, before) -> enabled);
        if (held != null) {
            // Bis ihre alten Daten gelöscht sind, bleibt die Quelle aus; der Schalter des Benutzers gilt danach.
            return false;
        }
        boolean changed = catalog.setEnabled(sourceId, enabled);
        if (changed && !enabled) {
            stopJobs(sourceId);
        }
        if (changed) {
            LOG.log(System.Logger.Level.INFO, "Quelle {0} {1}", sourceId, enabled ? "eingeschaltet" : "abgeschaltet");
        }
        return changed;
    }

    /**
     * Entfernt eine Quelle samt ihrer abgeleiteten Daten.
     *
     * @return {@code false}, wenn es keine Quelle mit dieser ID gab
     */
    public synchronized boolean remove(String sourceId) {
        Optional<SourceSettingsConfig> removed = catalog.remove(sourceId);
        if (removed.isEmpty()) {
            return false;
        }
        // Fundstelle 22: Die Daten löscht ein Auftrag im Core; er läuft weiter, wenn die App neu lädt, und setzt nach
        // einem Neustart fort. Bis dahin lässt sich dieselbe ID nicht neu anlegen ({@link #removalPending}).
        startWithdrawal(sourceId, SourceDataPurge.REMOVED);
        LOG.log(System.Logger.Level.INFO, "Quelle {0} entfernt; ihre Daten werden gelöscht", sourceId);
        return true;
    }

    /**
     * Startet den Auftrag, der die Daten löscht. Nimmt ihn {@link Jobs} nicht an, etwa weil die Warteschlange voll ist,
     * wird wie früher sofort im aufrufenden Thread gelöscht: Die Quelle ist dann schon aus dem Katalog, ihre Daten
     * dürfen nicht ohne Auftrag liegen bleiben.
     *
     * @return der Auftrag, leer, wenn schon gelöscht wurde
     */
    private Optional<JobStatus> startWithdrawal(String sourceId, String reason) {
        try {
            return Optional.of(purge.startWithdrawal(sourceId, Harvester.OWNER, reason));
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "Auftrag zum Löschen der Daten von Quelle {0} nicht angenommen ({1});"
                    + " es wird sofort gelöscht", sourceId, e.getClass().getName());
            SourceDataPurge.Outcome outcome = purge.purge(sourceId);
            LOG.log(System.Logger.Level.INFO, "Daten der Quelle {0} entfernt ({1} von {2} Ablagen)", sourceId,
                    outcome.cleared().size(), outcome.cleared().size() + outcome.failed().size());
            return Optional.empty();
        }
    }

    /**
     * „Daten bereinigen“: löscht als Auftrag alle lokal gespeicherten Daten dieser einen Quelle, wie beim
     * Entfernen, behält aber die Quelle mit Einstellungen und Schalter; auch abgeschaltet. Siehe {@link SourceDataPurge}.
     *
     * @param owner auslösender Client
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException ohne Quelle dieser ID
     */
    public JobStatus purge(String sourceId, String owner) {
        JobStatus started = purge.start(sourceId, owner);
        LOG.log(System.Logger.Level.INFO, "Daten der Quelle {0} werden bereinigt", sourceId);
        return started;
    }

    /**
     * Der Auftrag, der die Daten einer entfernten Quelle dieser ID noch löscht; solange lässt sich die ID nicht neu
     * anlegen (Fundstelle 22).
     */
    public Optional<JobStatus> removalPending(String sourceId) {
        return purge.withdrawing(sourceId);
    }

    /**
     * Der Auftrag, der die Daten der Quelle gerade löscht (entfernt oder umgestellt), solange das Löschen offen ist,
     * sonst {@code null}; aus dem Speicher, darf auf dem EDT laufen. Solange startet kein anderer Auftrag der Quelle,
     * „Daten bereinigen“ ist gesperrt, und der Auftrag lässt sich nicht löschen.
     */
    public String withdrawalOpen(String sourceId) {
        return purge.openWithdrawal(sourceId);
    }

    /**
     * Die Quellen, deren Daten nach dem Entfernen noch gelöscht werden, je mit dem Auftrag ({@link #removalPending});
     * aus dem Speicher, darf auf dem EDT laufen.
     */
    public java.util.Map<String, JobStatus> removalsPending() {
        return purge.withdrawals();
    }

    /**
     * Beim Start: setzt unterbrochenes oder gescheitertes Löschen entfernter oder umgestellter Quellen im selben Eintrag
     * fort. Ist eine entfernte Quelle inzwischen wieder eingerichtet (etwa per YAML), bleibt sie abgeschaltet, bis ihre
     * alten Daten gelöscht sind; danach ist sie wieder eingeschaltet und leer (Abnahme zu Fundstelle 22).
     */
    public synchronized List<JobStatus> resumeWithdrawals() {
        // Erst halten, dann fortsetzen: Sonst könnte das Löschen enden, bevor die Quelle gehalten ist.
        purge.withdrawals().keySet().forEach(this::holdWhileWithdrawing);
        return purge.resumePending();
    }

    /**
     * Quellen, die bis zum Ende ihres Löschens abgeschaltet sind ({@link #resumeWithdrawals}), je mit dem Schalter des
     * Benutzers: Den führt {@link #setEnabled} solange getrennt; danach gilt er wieder.
     */
    private final java.util.Map<String, Boolean> heldInactive = new java.util.concurrent.ConcurrentHashMap<>();

    private void holdWhileWithdrawing(String sourceId) {
        if (catalog.config(sourceId).isPresent() && !heldInactive.containsKey(sourceId)) {
            heldInactive.put(sourceId, catalog.enabled(sourceId));
            catalog.setEnabled(sourceId, false);
            LOG.log(System.Logger.Level.INFO, "Quelle {0} bleibt abgeschaltet, bis ihre alten Daten gelöscht sind",
                    sourceId);
        }
    }

    private synchronized void withdrawn(String sourceId) {
        Boolean wanted = heldInactive.remove(sourceId);
        if (wanted != null && wanted) {
            try {
                catalog.setEnabled(sourceId, true);
                LOG.log(System.Logger.Level.INFO, "Quelle {0} nach dem Löschen ihrer alten Daten wieder eingeschaltet",
                        sourceId);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Quelle {0} nicht wieder eingeschaltet ({1})", sourceId,
                        e.getClass().getName());
            }
        }
    }

    /** Die Ablagen, die {@link #purge} und das Entfernen leeren, in ihrer Reihenfolge. */
    public List<String> purgedStores() {
        return purge.stores();
    }

    private void stopJobs(String sourceId) {
        try {
            if (!jobs.cancelSource(sourceId, JOB_STOP_TIMEOUT)) {
                LOG.log(System.Logger.Level.WARNING, "Aufträge der Quelle {0} laufen nach {1} s noch",
                        sourceId, JOB_STOP_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Ergebnis von {@link #test}.
     *
     * @param partitions sichtbare Partitionen (Spaces), {@code 0} bei einem Fehler
     * @param message    für Benutzer; ohne Werte der Einstellungen
     */
    public record ConnectionCheck(boolean successful, int partitions, String message) {

        static ConnectionCheck success(int partitions) {
            return new ConnectionCheck(true, partitions, partitions == 1 ? "Verbunden, 1 Space sichtbar"
                    : "Verbunden, " + partitions + " Spaces sichtbar");
        }

        static ConnectionCheck failure(String message) {
            return new ConnectionCheck(false, 0, message);
        }
    }
}
