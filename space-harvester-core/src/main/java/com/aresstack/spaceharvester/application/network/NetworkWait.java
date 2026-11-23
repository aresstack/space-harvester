package com.aresstack.spaceharvester.application.network;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Warten auf das Netz in einem laufenden Auftrag, der seine Elemente der Reihe nach über das Netz holt (etwa „Anhänge
 * vorladen“): Ein Netzfehler ({@link #failed}) verbraucht keinen Versuch und zählt nicht als gescheitert; der Auftrag
 * steht als „Wartet auf Netzverbindung“ ({@link JobContext#awaitingNetwork}), der Grund steht einmal in der Diagnose,
 * und er wartet ohne Abfragen auf {@link NetworkState#permit()}. Danach holt der Aufrufer dasselbe Element erneut;
 * Antwortet die Gegenseite ({@link #answered}), ist das Netz zurück. Eine Instanz je Lauf, nicht threadsicher.
 */
public final class NetworkWait {

    /** Code der Meldung, dass der Auftrag auf das Netz wartet. */
    public static final String OFFLINE = "NETWORK_OFFLINE";
    /** Code der Meldung, dass das Netz zurück ist. */
    public static final String ONLINE = "NETWORK_BACK";

    private final NetworkState network;
    private final JobContext context;
    private final String component;
    private final Clock clock;
    private Instant waitingSince;
    /** Offline erteilte Erlaubnis für den nächsten Abruf, noch nicht verbraucht. */
    private boolean holding;

    public NetworkWait(NetworkState network, JobContext context, String component) {
        this(network, context, component, Clock.systemUTC());
    }

    NetworkWait(NetworkState network, JobContext context, String component, Clock clock) {
        this.network = Objects.requireNonNull(network, "network");
        this.context = Objects.requireNonNull(context, "context");
        this.component = Objects.requireNonNull(component, "component");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Vor einem Abruf: wartet, solange das Netz weg ist und ein anderer prüft; kehrt sofort zurück, wenn es da ist.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobCancelledException wenn währenddessen Abbruch oder
     *                                                                              Anhalten angefordert wird
     */
    public void beforeFetch() {
        if (holding || network.tryPermit()) {
            return;
        }
        waitForPermit(null);
    }

    /**
     * Meldet einen gescheiterten Abruf. War es das Netz, wartet der Auftrag, bis er erneut abrufen darf.
     *
     * @return ob es das Netz war; dann holt der Aufrufer dasselbe Element erneut, statt es zu zählen
     * @throws com.aresstack.spaceharvester.application.jobs.JobCancelledException wenn währenddessen Abbruch oder
     *                                                                              Anhalten angefordert wird
     */
    public boolean failed(Throwable failure) {
        holding = false;
        if (!network.failed(failure)) {
            answered();
            return false;
        }
        waitForPermit(failure);
        return true;
    }

    /**
     * Ein Abruf scheiterte am Netz, und der Netzzustand weiß es schon (etwa weil ein anderer Dienst den Fehler gemeldet
     * hat): wartet wie {@link #failed} auf die nächste Erlaubnis, ohne den Fehler erneut zu melden.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobCancelledException wenn währenddessen Abbruch oder
     *                                                                              Anhalten angefordert wird
     */
    public void offline() {
        holding = false;
        beforeFetch();
    }

    /** Die Gegenseite hat geantwortet (geladen oder mit einem Fehler der Sache): Das Netz ist da. */
    public void answered() {
        holding = false;
        network.succeeded();
        if (waitingSince != null) {
            long seconds = Duration.between(waitingSince, clock.instant()).getSeconds();
            waitingSince = null;
            context.awaitingNetwork(false);
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.NETWORK, component, ONLINE,
                    "Netzverbindung zurück nach " + seconds + " s; der Auftrag macht hier weiter", null, null, null));
        }
    }

    private void waitForPermit(Throwable failure) {
        if (waitingSince == null) {
            waitingSince = clock.instant();
            context.awaitingNetwork(true);
            NetworkState.Status status = network.status();
            String reason = status.reason() != null ? status.reason()
                    : failure == null ? "Netz nicht erreichbar" : failure.getClass().getSimpleName();
            context.record(new JobEvent.Draft(JobEvent.Severity.WARNING, JobEvent.Category.NETWORK, component, OFFLINE,
                    "Keine Netzverbindung (" + reason + "): Der Auftrag wartet, bis sie zurück ist, und macht dann "
                    + "von selbst hier weiter; nichts zählt als gescheitert", null, null, failure));
        }
        CompletableFuture<Void> permit = network.permit();
        try (AutoCloseable stop = context.whenStopRequested(() -> permit.cancel(false))) {
            permit.join();
            // Erteilt: Der nächste Abruf (dasselbe Element erneut) braucht keine zweite Erlaubnis.
            holding = true;
        } catch (CancellationException | CompletionException e) {
            // Abbruch, Anhalten oder Ende des Dienstes: der Prüfpunkt unten entscheidet.
        } catch (Exception e) {
            // Abmelden scheiterte; ohne Folgen.
        }
        context.checkpoint();
    }
}
