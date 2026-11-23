package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.util.Objects;

/**
 * Der Netzweg des BetaView-Bereichs: TLS wie die Quellen, als Vorgabe auch der Proxy der App (so läuft BetaView
 * beim Anwender). Unter Einstellungen › BetaView › Verbindung lässt sich der Proxy abschalten; dann geht BetaView
 * direkt zum Server wie MainframeMate ({@code BetaViewHttpClient} öffnet {@code url.openConnection()} ohne Proxy). Benutzer und Kennwort kommen nicht von hier,
 * sondern aus den gemeinsamen Host-Zugängen des Command Deck ({@link
 * com.aresstack.spaceharvester.deck.access.ServerAccounts}, dieselbe KeePass-Kopplung wie Terminal, FTP und NDV).
 */
public interface BetaViewAccess {

    /** Schlüssel im App-Zustand: true = Proxy der App verwenden. Vorgabe true. */
    String USE_PROXY = "deck.betaview.use-proxy";

    /** Ob BetaView den Proxy der App verwendet. */
    static boolean useProxy() {
        return com.aresstack.spaceharvester.app.ui.AppState.shared().getBoolean(USE_PROXY, true);
    }

    /** Merkt, ob BetaView den Proxy der App verwendet; gilt ab der nächsten Verbindung. */
    static void useProxy(boolean use) {
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(USE_PROXY, use);
    }

    /** Der Netzweg zum BetaView-Server. */
    HttpClientFactory http();

    /** TLS aus den Einstellungen der App; den Proxy der App nur, wenn {@link #useProxy()} gilt. */
    static BetaViewAccess of(AppContext context) {
        Objects.requireNonNull(context, "context");
        return new BetaViewAccess() {
            @Override
            public HttpClientFactory http() {
                return com.aresstack.spaceharvester.app.ui.deck.DeckNetwork.http(context,
                        BetaViewAccess::useProxy);
            }
        };
    }

    /** Ohne Proxy (Tests, Bildschirmfotos). */
    static BetaViewAccess direct() {
        return new BetaViewAccess() {
            @Override
            public HttpClientFactory http() {
                return HttpClientFactory.direct();
            }
        };
    }
}
