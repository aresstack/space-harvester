package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import java.net.URI;

/**
 * Erfährt, dass eine Verbindung über eine gelieferte Proxy-Route nicht aufgebaut werden konnte (Proxy nicht
 * erreichbar). {@link ProxyRoutes} verwirft dann die gemerkte Route und lässt das PAC-Skript beim nächsten Mal neu
 * laden, weil die Proxy-Regeln sich geändert haben können (wie {@code ProxySelector.connectFailed}).
 */
public interface RouteFailureListener {

    /**
     * @param target Ziel der gescheiterten Verbindung
     * @param route  die Route, über die verbunden werden sollte
     */
    void routeFailed(URI target, HttpRoute route);

    /** Meldet {@code routeFailed}, wenn {@code routes} zuhört und die Route über einen Proxy ging; sonst nichts. */
    static void notify(Object routes, URI target, HttpRoute route) {
        if (routes instanceof RouteFailureListener listener && route != null && route.isProxy() && target != null) {
            listener.routeFailed(target, route);
        }
    }
}
