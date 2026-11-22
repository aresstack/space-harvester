package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.betaview.http.HttpClientFactory;
import java.util.Objects;

/**
 * Der Netzweg des BetaView-Bereichs: Proxy und TLS wie die Quellen. Benutzer und Kennwort kommen nicht von hier,
 * sondern aus den gemeinsamen Host-Zugängen des Command Deck ({@link
 * com.aresstack.spaceharvester.deck.access.ServerAccounts}, dieselbe KeePass-Kopplung wie Terminal, FTP und NDV).
 */
public interface BetaViewAccess {

    /** Der Netzweg zum BetaView-Server. */
    HttpClientFactory http();

    /** Proxy und TLS aus den Einstellungen der App. */
    static BetaViewAccess of(AppContext context) {
        Objects.requireNonNull(context, "context");
        return new BetaViewAccess() {
            @Override
            public HttpClientFactory http() {
                return () -> {
                    var settings = context.settings();
                    if (settings == null) {
                        return java.net.http.HttpClient.newBuilder();
                    }
                    return com.aresstack.spaceharvester.app.nlp.HttpModelDownload.routed(
                            com.aresstack.spaceharvester.app.runtime.AppRuntime.proxy(settings.proxy(),
                                    context.secrets()), settings.tls());
                };
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
