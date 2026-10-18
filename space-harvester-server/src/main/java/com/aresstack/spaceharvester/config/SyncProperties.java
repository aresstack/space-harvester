package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester.sync}: geplanter Hintergrundabgleich (#16).
 *
 * @param interval Abstand zwischen zwei geplanten Abgleichen (mindestens eine Minute); ohne Angabe gibt es keinen
 *                 Hintergrundabgleich. Eine Kadenz, kein zugesagter Aktualitätsgrad: ein Lauf, der noch läuft, wird
 *                 nicht doppelt gestartet.
 * @param spaces   Spaces für den Hintergrundabgleich, je Eintrag {@code <sourceId>/<spaceKey>}
 * @param withheldRetention wie lange ein Abgleich die Rohdaten einer gesperrten Seite behält (ADR 0014, „nicht
 *                          gefunden“ ohne bestätigte Löschung); Standard 30 Tage
 * @param loadUnknown       veraltet, durch {@code mode} ersetzt: {@code true} wie {@code full}, {@code false} wie
 *                          {@code sync-cache}
 * @param catchUpOnStart    ob der Dienst beim Start für jeden Space mit bestätigter Generation die verpassten
 *                          Änderungen nachholt (ADR 0014; Standard {@code false}, Änderungsquellen sind optional); eine
 *                          Änderungssuche und eine Papierkorbabfrage je Space, kein Vollabruf
 * @param mode              Betriebsart des Zeitplans und des Nachholens beim Start (ADR 0014): {@code catalog-only}
 *                          (Standard), {@code sync-cache} oder {@code full}
 */
@ConfigurationProperties("space-harvester.sync")
public record SyncProperties(Duration interval, List<String> spaces, Duration withheldRetention,
                             Boolean loadUnknown, Boolean catchUpOnStart, SyncMode mode) {

    public SyncProperties {
        if (mode != null && loadUnknown != null && loadUnknown != mode.loadsUnknown()) {
            throw new IllegalArgumentException(
                    "space-harvester.sync.mode und space-harvester.sync.load-unknown widersprechen sich");
        }
        mode = mode != null ? mode : loadUnknown == null ? SyncMode.CATALOG_ONLY : SyncMode.loadingUnknown(loadUnknown);
        loadUnknown = mode.loadsUnknown();
        catchUpOnStart = catchUpOnStart != null && catchUpOnStart;
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        withheldRetention = withheldRetention == null ? SpaceSync.DEFAULT_WITHHELD_RETENTION : withheldRetention;
        if (withheldRetention.isNegative()) {
            throw new IllegalArgumentException("space-harvester.sync.withheld-retention darf nicht negativ sein");
        }
        if (interval != null && interval.compareTo(Reconciliation.MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("space-harvester.sync.interval muss mindestens "
                    + Reconciliation.MIN_INTERVAL + " sein");
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
