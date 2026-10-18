package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import java.time.Duration;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Liest bei einem {@link EnvironmentChangeEvent} mit Schlüsseln unter {@code space-harvester.} die Konfiguration neu
 * aus dem Environment und übergibt sie dem {@link ConfigurationManager}. Das Log nennt Ergebnis, Version und die
 * Schlüssel der Probleme, nie Werte.
 *
 * <p>Wer über {@code POST /api/v1/configuration} etwas geändert hat, verliert diese Änderung beim nächsten Refresh:
 * Das Environment ist dann wieder die Quelle.
 */
class EnvironmentConfigurationRefresh implements ApplicationListener<EnvironmentChangeEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentConfigurationRefresh.class);

    private final ConfigurationManager manager;
    private final Environment environment;

    EnvironmentConfigurationRefresh(ConfigurationManager manager, Environment environment) {
        this.manager = manager;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (event.getKeys().stream().noneMatch(key -> key.startsWith(HarvesterConfiguration.PREFIX))) {
            return;
        }
        HarvesterConfiguration next;
        try {
            next = read();
        } catch (RuntimeException e) {
            // Die Meldung des Binders kann Werte enthalten; nur die Art des Fehlers.
            LOG.warn("Geänderte Konfiguration nicht lesbar ({}), Version {} bleibt wirksam", e.getClass().getName(),
                    manager.current().version());
            return;
        }
        ConfigurationManager.Activation activation = manager.apply(next);
        switch (activation.outcome()) {
            case ACTIVATED -> LOG.info("Konfiguration aus dem Environment aktiviert: Version {}",
                    activation.snapshot().version());
            case UNCHANGED -> LOG.debug("Konfiguration im Environment unverändert, Version {}",
                    activation.snapshot().version());
            case REJECTED -> LOG.warn("Konfiguration aus dem Environment abgelehnt ({}), Version {} bleibt wirksam",
                    activation.problems().stream().map(ConfigurationProblem::key).distinct()
                            .collect(Collectors.joining(", ")), activation.snapshot().version());
        }
        if (!activation.restartRequired().isEmpty()) {
            LOG.warn("Erst nach einem Neustart wirksam: {}", String.join(", ", activation.restartRequired()));
        }
    }

    /** Dieselben Records wie beim Start, frisch aus dem Environment gebunden. */
    HarvesterConfiguration read() {
        Binder binder = Binder.get(environment);
        return ConfigurationManagerConfiguration.initial(
                binder.bindOrCreate("space-harvester.ai", Bindable.of(AiProperties.class)),
                binder.bindOrCreate("space-harvester.search", Bindable.of(SearchProperties.class)),
                binder.bindOrCreate("space-harvester.sync", Bindable.of(SyncProperties.class)),
                binder.bindOrCreate("space-harvester.cache", Bindable.of(CacheProperties.class)),
                binder.bindOrCreate("space-harvester.network", Bindable.of(SpaceHarvesterProperties.Network.class)),
                binder.bind("space-harvester.cache.max-age", Duration.class).orElse(Duration.ZERO),
                binder.bind("space-harvester.cache.version-check", Boolean.class).orElse(true));
    }
}
