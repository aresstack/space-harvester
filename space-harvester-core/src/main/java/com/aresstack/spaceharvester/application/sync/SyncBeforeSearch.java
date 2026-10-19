package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holt vor einer Suche die Änderungen ihrer Quellen nach, wo deren Regel es verlangt ({@code sync.before-search},
 * ADR 0015), mit der Betriebsart der Quelle wie das Nachholen beim Start. Die Regel kommt bei jeder Suche aus der
 * wirksamen Konfiguration, wirkt also ohne Neustart.
 *
 * <p>Die Suche wartet nicht: Sie antwortet aus dem Stand, den sie vorfindet, und die nachgeholten Änderungen stehen
 * der nächsten Suche zur Verfügung. Je Quelle startet höchstens alle {@link #MIN_GAP} ein Nachholen, damit eine Folge
 * von Suchen die Quelle nicht mit Abgleichen überzieht; ein noch laufender Abgleich desselben Space wird nicht doppelt
 * gestartet.
 */
public final class SyncBeforeSearch {

    /** Mindestabstand zweier Nachholen derselben Quelle, wie der kürzeste Abstand im Zeitplan. */
    public static final Duration MIN_GAP = Reconciliation.MIN_INTERVAL;

    private static final System.Logger LOG = System.getLogger(SyncBeforeSearch.class.getName());

    private final SpaceSyncJobs syncs;
    private final Supplier<HarvesterConfiguration> configuration;
    private final String owner;
    private final Clock clock;
    private final Map<String, Instant> lastStarted = new ConcurrentHashMap<>();

    /**
     * @param configuration die jeweils wirksame Konfiguration
     * @param owner         Besitzer der Abgleichsaufträge, wie beim Nachholen beim Start
     */
    public SyncBeforeSearch(SpaceSyncJobs syncs, Supplier<HarvesterConfiguration> configuration, String owner,
                            Clock clock) {
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.owner = owner;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Startet das Nachholen für die Quellen aus {@code sourceIds}, deren Regel es verlangt, in einem eigenen Thread;
     * kehrt sofort zurück und wirft nicht.
     */
    public void searching(Set<String> sourceIds) {
        try {
            if (sourceIds.isEmpty() || !syncs.available()) {
                return;
            }
            HarvesterConfiguration current = configuration.get();
            Instant now = clock.instant();
            Set<String> due = new HashSet<>();
            for (String sourceId : sourceIds) {
                if (current.syncFor(sourceId).beforeSearch() && claim(sourceId, now)) {
                    due.add(sourceId);
                }
            }
            if (due.isEmpty()) {
                return;
            }
            // Auch das Einreihen über alle bestätigten Spaces läuft nicht im Such-Thread: Die Suche beginnt sofort.
            // Ein eigener virtueller Thread je Nachholen genügt, es kommt je Quelle höchstens alle MIN_GAP vor.
            Thread.ofVirtual().name("sync-before-search").start(() -> catchUp(current, due));
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private void catchUp(HarvesterConfiguration current, Set<String> due) {
        try {
            syncs.catchUpAll(owner, sourceId -> due.contains(sourceId) ? current.syncFor(sourceId).mode() : null);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private static void failed(RuntimeException e) {
        // Die Suche läuft ohne Nachholen weiter; keine Quellen- oder Suchangaben im Protokoll.
        LOG.log(System.Logger.Level.WARNING, "Nachholen vor der Suche nicht gestartet ({0})", e.getClass().getName());
    }

    /** Ob die Quelle jetzt dran ist; merkt sich den Zeitpunkt, damit parallele Suchen nicht doppelt nachholen. */
    private boolean claim(String sourceId, Instant now) {
        boolean[] claimed = {false};
        lastStarted.compute(sourceId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(MIN_GAP))) {
                return last;
            }
            claimed[0] = true;
            return now;
        });
        return claimed[0];
    }
}
