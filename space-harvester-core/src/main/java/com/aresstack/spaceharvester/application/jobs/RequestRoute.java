package com.aresstack.spaceharvester.application.jobs;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Route und Transport eines gescheiterten Abrufs, als unterdrückte Ausnahme am Fehler selbst: so bleibt der Typ des
 * Fehlers erhalten, und {@link FailureDiagnosis} kann die Route zeigen, die genau dieser Abruf genommen hat
 * ({@code PROXY host:port}, {@code DIRECT}, {@code UNAVAILABLE …}), statt nur die Route der Quelle.
 */
public final class RequestRoute extends RuntimeException {

    private static final int MAX_DEPTH = 12;

    private final String route;
    private final String transport;

    /**
     * @param route     Beschreibung der Route, etwa {@code HttpRoute.describe()}
     * @param transport Transport des Abrufs, etwa {@code WebClient}
     */
    public RequestRoute(String route, String transport) {
        super("Route " + route + " über " + transport, null, false, false);
        this.route = route;
        this.transport = transport;
    }

    public String route() {
        return route;
    }

    public String transport() {
        return transport;
    }

    /** {@code route · transport}, wie es im Ereignis steht. */
    public String describe() {
        return route + " · " + transport;
    }

    /**
     * Hängt die Route an {@code failure}, sofern der Fehler noch keine trägt. Gemeinsame Fehlerinstanzen ohne
     * Stacktrace (Netty nutzt solche) bleiben unberührt, damit keine Route an fremden Abrufen hängen bleibt.
     */
    public static <T extends Throwable> T attach(T failure, String route, String transport) {
        if (failure != null && failure.getStackTrace().length > 0 && find(failure).isEmpty()) {
            failure.addSuppressed(new RequestRoute(route, transport));
        }
        return failure;
    }

    /** Die erste Route in der Kette von {@code failure} samt unterdrückter Ausnahmen. */
    public static Optional<RequestRoute> find(Throwable failure) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth++ < MAX_DEPTH && seen.put(cause, true) == null;
             cause = cause.getCause()) {
            if (cause instanceof RequestRoute route) {
                return Optional.of(route);
            }
            for (Throwable suppressed : cause.getSuppressed()) {
                if (suppressed instanceof RequestRoute route) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }
}
