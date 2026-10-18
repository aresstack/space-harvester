package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die wirksame Konfiguration für die Beans des Servers (ADR 0015), wie {@code Harvester.Builder.configuration} im
 * Core: Regeln je Quelle (Aktualität, Frist für gesperrte Seiten, Freigabe an den KI-Dienst) liest jeder Abruf und
 * jeder Lauf hier, also nach {@code POST /api/v1/configuration} sofort aus der neuen Konfiguration. Laufende Aufträge
 * enden mit ihren.
 */
public final class LiveConfiguration implements AutoCloseable {

    private final ConfigurationManager manager;
    private final List<ConfigurationManager.Registration> registrations = new CopyOnWriteArrayList<>();
    private volatile HarvesterConfiguration current;

    public LiveConfiguration(ConfigurationManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.current = manager.current().configuration();
        registrations.add(manager.register(change -> () -> current = change.next()));
    }

    /** Meldet eine Bean an, die beim Wechsel Instanzen austauscht; abgemeldet mit dem Kontext. */
    void listen(ConfigurationListener listener) {
        registrations.add(manager.register(listener));
    }

    public HarvesterConfiguration current() {
        return current;
    }

    /** Aktualität des Caches für die Quelle ({@code cache.*} oder {@code sync.sources.<Quelle>.*}). */
    public CacheFreshness freshnessFor(String sourceId) {
        return current.freshnessFor(sourceId);
    }

    /** Wie lange ein Abgleich die Rohdaten einer gesperrten Seite der Quelle behält. */
    public Duration withheldRetentionFor(String sourceId) {
        return current.syncFor(sourceId).withheldRetention();
    }

    /** Ob Inhalte der Quelle an den KI-Dienst gehen dürfen ({@code ai.external-processing-sources}). */
    public boolean mayProcessExternally(String sourceId) {
        return current.ai().mayProcessExternally(sourceId);
    }

    @Override
    public void close() {
        registrations.forEach(ConfigurationManager.Registration::close);
    }
}
