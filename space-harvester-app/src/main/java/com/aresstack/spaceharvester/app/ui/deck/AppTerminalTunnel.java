package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.terminal.HttpConnectTunnel;
import com.aresstack.spaceharvester.deck.terminal.TerminalTunnel;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * „Proxy verwenden“ des Terminals: fragt die Proxy-Regel der App (auch PAC) für das Ziel wie für eine
 * HTTPS-Adresse und baut den Tunnel mit {@code CONNECT}. Sagt die Regel „direkt“ (etwa für eine Ausnahme), geht die
 * Verbindung direkt; ohne gültige Regel scheitert sie mit Grund, nie still direkt. Das Proxy-Kennwort kommt aus
 * Tresor oder Sitzung, bleibt nur für den Aufbau im Speicher und wird danach überschrieben.
 */
public final class AppTerminalTunnel implements TerminalTunnel {

    private final AppContext context;

    public AppTerminalTunnel(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Socket open(String host, int port, int timeoutMillis) throws IOException {
        var settings = context.settings();
        if (settings == null || settings.proxy() == null) {
            return TerminalTunnel.NONE.open(host, port, timeoutMillis);
        }
        ProxySettings proxy = AppRuntime.proxy(settings.proxy(), context.secrets());
        HttpRoute route;
        try {
            route = ProxyRoutes.from(proxy).routeFor(URI.create("https://" + HttpConnectTunnel.authority(host) + ":"
                    + port + "/"));
        } catch (RuntimeException e) {
            throw new IOException("Proxy-Regel nicht auswertbar: " + e.getMessage(), e);
        }
        if (route.isUnavailable()) {
            throw new IOException("Kein Netzweg zu " + host + ": " + route.describe());
        }
        if (!route.isProxy()) {
            Socket direct = new Socket(Proxy.NO_PROXY);
            try {
                direct.connect(new InetSocketAddress(host, port), timeoutMillis);
                return direct;
            } catch (IOException e) {
                direct.close();
                throw e;
            }
        }
        ProxySettings.Authentication auth = proxy.authentication();
        char[] password = auth == null ? null : auth.password();
        try {
            return HttpConnectTunnel.open(route.proxyHost(), route.proxyPort(), auth == null ? null : auth.username(),
                    password, host, port, timeoutMillis);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
