package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.deck.ndv.pal.PalNdvConnector;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Objects;

/**
 * Der Weg zum NDV-Server, wenn eine Verbindung „Proxy der App verwenden“ gewählt hat (Angelo 23.11.: wählbar je
 * Verbindung, Vorgabe direkt wie MainframeMate). Dieselbe Regel wie für die Quellen: fester Proxy oder PAC aus
 * Einstellungen › Netzwerk; ohne gültige Regel (PAC gescheitert) nie stillschweigend direkt. Die Anmeldung am Proxy
 * fragt das JDK beim Standard-Authenticator der App ab.
 */
public final class NdvNetwork {

    private NdvNetwork() {
    }

    /** Der Weg nach den aktuellen Einstellungen der App, bei jedem Verbinden neu gelesen. */
    public static PalNdvConnector.Route of(AppContext context) {
        Objects.requireNonNull(context, "context");
        return profile -> {
            var settings = context.settings();
            return route(settings == null ? ProxySettings.disabled()
                    : com.aresstack.spaceharvester.app.runtime.AppRuntime.proxy(settings.proxy(), context.secrets()),
                    profile);
        };
    }

    /** Der Weg zu {@code profile} nach {@code proxy}: {@link Proxy#NO_PROXY}, wenn die Regel direkt sagt. */
    static Proxy route(ProxySettings proxy, NdvProfile profile) throws IOException {
        String host = profile.host().contains(":") ? "[" + profile.host() + "]" : profile.host();
        HttpRoute route = ProxyRoutes.from(proxy == null ? ProxySettings.disabled() : proxy)
                .routeFor(URI.create("http://" + host + ":" + profile.port() + "/"));
        if (route.isUnavailable()) {
            throw new IOException("Kein Weg über den Proxy zu " + profile.label() + ": " + route.describe());
        }
        if (route.isProxy()) {
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort()));
        }
        return Proxy.NO_PROXY;
    }
}
