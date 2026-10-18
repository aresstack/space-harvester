package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.sync.SpaceSync;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.sync}: geplanter Hintergrundabgleich (#16).
 *
 * @param interval Abstand zwischen zwei geplanten Abgleichen; ohne Angabe gibt es keinen Hintergrundabgleich. Eine
 *                 Kadenz, kein zugesagter Aktualitätsgrad: ein Lauf, der noch läuft, wird nicht doppelt gestartet.
 * @param spaces   Spaces für den Hintergrundabgleich, je Eintrag {@code <sourceId>/<spaceKey>}
 * @param withheldRetention wie lange ein Abgleich die Rohdaten einer gesperrten Seite behält (ADR 0014, „nicht
 *                          gefunden“ ohne bestätigte Löschung); Standard 30 Tage
 */
@ConfigurationProperties("space-harvester.sync")
public record SyncProperties(Duration interval, List<String> spaces, Duration withheldRetention) {

    public SyncProperties {
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        withheldRetention = withheldRetention == null ? SpaceSync.DEFAULT_WITHHELD_RETENTION : withheldRetention;
        if (withheldRetention.isNegative()) {
            throw new IllegalArgumentException("space-harvester.sync.withheld-retention darf nicht negativ sein");
        }
        if (interval != null && (interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("space-harvester.sync.interval muss positiv sein");
        }
        for (String space : spaces) {
            int slash = space.indexOf('/');
            if (slash < 1 || slash == space.length() - 1 || space.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException(
                        "space-harvester.sync.spaces erwartet <sourceId>/<spaceKey>, nicht '" + space + "'");
            }
        }
    }
}
