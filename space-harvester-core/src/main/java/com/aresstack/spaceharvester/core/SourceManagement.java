package com.aresstack.spaceharvester.core;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Quellen zur Laufzeit verwalten (#188), wie {@code KnowledgeSourceManagement} und {@code SourceConnectionCheck} im
 * Enterprise AI Client: prüfen, Verbindung testen, hinzufügen, ändern, abschalten, entfernen. Eine Quelle ist wie dort
 * durch Typ und Ziel bestimmt: dieselbe Basisadresse unter einer zweiten ID lehnt {@link #validate} ab. Die
 * Einstellungen dauerhaft ablegen (Konfigurationsdatei, Tresor) bleibt Sache des einbettenden Programms; diese
 * Verwaltung ändert nur den laufenden Stand.
 *
 * <p>Abgeleitete Daten gehören zur Quelle: Wird sie entfernt oder zeigt sie nach einer Änderung auf ein anderes Ziel
 * (anderer Typ oder andere Basisadresse), werden ihre offenen Aufträge abgebrochen und Seiten-Cache samt Suchindex
 * und Abgleichständen, Anreicherungen (KI-Text, Embeddings, Lese-Ansichten) und Assets entfernt. Exporte bleiben,
 * mit den Bildern, auf die sie verweisen; sie entfernt ihr Besitzer selbst.
 *
 * <p>Rechte: Die Verwaltung prüft keine {@code AccessPolicy}; sie gehört dem Betreiber des einbettenden Programms.
 */
public final class SourceManagement {

    /** So lange warten Entfernen und Ändern auf laufende Aufträge der Quelle. */
    static final Duration JOB_STOP_TIMEOUT = Duration.ofSeconds(30);

    private static final System.Logger LOG = System.getLogger(SourceManagement.class.getName());

    private final ConfiguredSourceCatalog catalog;
    private final Jobs jobs;
    private final LucenePageCache cache;
    private final LuceneEnrichmentStore enrichments;
    private final FileSystemAssetStore assets;
    private final ExportArtifactWriter exports;
    private final Clock clock;

    SourceManagement(ConfiguredSourceCatalog catalog, Jobs jobs, LucenePageCache cache,
                     LuceneEnrichmentStore enrichments, FileSystemAssetStore assets, ExportArtifactWriter exports,
                     Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * „Verbindung testen“: öffnet die Einstellungen nur für diesen Test und listet die sichtbaren Partitionen (Spaces)
     * live, ohne etwas abzulegen. Die Meldung nennt die Art des Fehlers, nie Werte der Einstellungen.
     */
    public ConnectionCheck test(SourceSettingsConfig draft, boolean replacing) {
        try {
            List<Partition> partitions = catalog.probe(draft, replacing, source -> source.partitions());
            return ConnectionCheck.success(partitions.size());
        } catch (InvalidSourceConfigurationException e) {
            return ConnectionCheck.failure("Einstellungen: " + String.join("; ", e.problems()));
        } catch (SourceAccessException e) {
            return ConnectionCheck.failure(reason(e.reason()));
        } catch (RuntimeException e) {
            return ConnectionCheck.failure("Unerwarteter Fehler (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Fügt eine Quelle hinzu.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen; dann ändert sich nichts
     */
    public synchronized void add(SourceSettingsConfig config) {
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
            withdraw(config.id());
        }
        return moved;
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
        withdraw(sourceId);
        LOG.log(System.Logger.Level.INFO, "Quelle {0} entfernt", sourceId);
        return true;
    }

    private void withdraw(String sourceId) {
        Instant start = clock.instant();
        stopJobs(sourceId);
        cache.removeSource(sourceId);
        enrichments.removeSource(sourceId);
        int blobs = assets.removeSource(sourceId, exportImages()::contains, start);
        LOG.log(System.Logger.Level.INFO, "Daten der Quelle {0} entfernt ({1} Asset-Inhalte)", sourceId, blobs);
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

    /** Inhalte, auf die bestehende Exporte verweisen (Bilder und gezeichnete Diagramme). */
    private Set<String> exportImages() {
        Set<String> referenced = new HashSet<>();
        for (String exportId : exports.published()) {
            try {
                exports.assets(exportId).ifPresent(manifest -> {
                    for (ExportAssetManifest.Entry entry : manifest.assets()) {
                        if (entry.sha256() != null) {
                            referenced.add(entry.sha256());
                        }
                        if (entry.rendition() != null && entry.rendition().drawn()) {
                            referenced.add(entry.rendition().sha256());
                        }
                    }
                });
            } catch (RuntimeException e) {
                // Ein unlesbares Manifest darf das Entfernen nicht verhindern; seine Bilder fehlen dann ggf. später.
                LOG.log(System.Logger.Level.WARNING, "Bildmanifest eines Exports nicht lesbar ({0})",
                        e.getClass().getName());
            }
        }
        return referenced;
    }

    private static String reason(SourceAccessException.Reason reason) {
        return switch (reason) {
            case ACCESS_DENIED -> "Anmeldung abgelehnt: Zugangsdaten fehlen, sind falsch oder reichen nicht aus";
            case NOT_FOUND -> "Ziel nicht gefunden: Adresse prüfen";
            case UNAVAILABLE -> "Nicht erreichbar: Netzweg, Proxy oder Zertifikat prüfen";
            case RATE_LIMITED -> "Die Quelle drosselt gerade; später erneut versuchen";
            case UNSUPPORTED -> "Die Quelle kann keine Partitionen auflisten";
            case INVALID_RESPONSE -> "Antwort der Quelle nicht verwertbar: Adresse und Quellentyp prüfen";
        };
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
