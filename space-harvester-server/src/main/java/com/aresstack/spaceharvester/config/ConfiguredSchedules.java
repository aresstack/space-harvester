package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Der Zeitplan aus {@code sync.spaces} (ADR 0014/0015), wie im Core-Harvester: je Eintrag mit Abstand und Betriebsart
 * seiner Quelle, ohne Abstand keiner. Im Server legt nur die Konfiguration Abgleiche an; ein Wechsel ersetzt sie.
 */
final class ConfiguredSchedules {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredSchedules.class);

    /** Ein eingeplanter Abgleich. */
    record Planned(String sourceId, String spaceKey, Duration interval, SyncMode mode) {
    }

    private final Reconciliation reconciliation;
    private final List<Planned> active = new ArrayList<>();

    ConfiguredSchedules(Reconciliation reconciliation) {
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    }

    /**
     * Die Abgleiche der Konfiguration.
     *
     * @param strict ob Probleme die Konfiguration ablehnen; sonst werden die Einträge übersprungen
     * @throws ConfigurationRejectedException bei unbekannter Quelle oder zu kurzem Abstand, wenn {@code strict}
     */
    static List<Planned> plan(HarvesterConfiguration configuration, SourceCatalog catalog, boolean strict) {
        return plan(configuration, catalog, strict, SyncSettings::interval, SyncSettings.INTERVAL);
    }

    /**
     * Die Bestandsinventuren der Konfiguration: je Eintrag aus {@code sync.spaces} mit {@code inventory-interval} und
     * Betriebsart seiner Quelle, ohne Abstand keine.
     *
     * @throws ConfigurationRejectedException wie {@link #plan(HarvesterConfiguration, SourceCatalog, boolean)}
     */
    static List<Planned> planInventories(HarvesterConfiguration configuration, SourceCatalog catalog,
                                         boolean strict) {
        return plan(configuration, catalog, strict, SyncSettings::inventoryInterval, SyncSettings.INVENTORY_INTERVAL);
    }

    private static List<Planned> plan(HarvesterConfiguration configuration, SourceCatalog catalog, boolean strict,
                                      Function<SyncSettings, Duration> intervalOf, String intervalKey) {
        List<Planned> planned = new ArrayList<>();
        List<ConfigurationProblem> problems = new ArrayList<>();
        for (String entry : configuration.sync().spaces()) {
            int slash = entry.indexOf('/');
            if (slash < 1 || slash == entry.length() - 1) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES,
                        "Jeder Eintrag hat die Form <Quelle>/<Space-Key>, nicht „" + entry + "“"));
                continue;
            }
            String sourceId = entry.substring(0, slash);
            SyncSettings rules = configuration.syncFor(sourceId);
            Duration interval = intervalOf.apply(rules);
            if (interval == null) {
                continue;
            }
            if (catalog.find(sourceId).isEmpty()) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES, "Unbekannte Quelle „" + sourceId + "“"));
                continue;
            }
            if (interval.compareTo(Reconciliation.MIN_INTERVAL) < 0) {
                problems.add(new ConfigurationProblem(intervalKey,
                        "Der Abstand muss mindestens eine Minute betragen"));
                continue;
            }
            planned.add(new Planned(sourceId, entry.substring(slash + 1), interval, rules.mode()));
        }
        if (!problems.isEmpty()) {
            if (strict) {
                throw new ConfigurationRejectedException(problems.stream().distinct().toList());
            }
            LOG.warn("{} Einträge des Zeitplans übersprungen", problems.size());
        }
        return List.copyOf(planned);
    }

    /** Ersetzt die bisher eingeplanten Abgleiche; laufende enden regulär. */
    synchronized void replace(List<Planned> planned) {
        for (Planned previous : active) {
            reconciliation.cancel(previous.sourceId(), previous.spaceKey());
        }
        active.clear();
        for (Planned schedule : planned) {
            reconciliation.schedule(schedule.sourceId(), schedule.spaceKey(), schedule.interval(), schedule.mode());
            active.add(schedule);
        }
    }
}
