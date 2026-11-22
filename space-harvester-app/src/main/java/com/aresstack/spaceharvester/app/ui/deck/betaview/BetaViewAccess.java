package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.betaview.http.HttpClientFactory;
import java.util.List;
import java.util.Objects;

/**
 * Was der BetaView-Bereich von der App braucht: den Netzweg (Proxy und TLS wie die Quellen) und die vorhandene
 * KeePass-Kopplung für Benutzer und Kennwort. Eigene Naht, damit Tests ohne KeePass und Netz auskommen.
 */
public interface BetaViewAccess {

    /** Der Netzweg zum BetaView-Server. */
    HttpClientFactory http();

    /** Ob die App mit KeePass gekoppelt ist (ohne KeePass zu fragen, also auch auf dem EDT). */
    boolean keePassPaired();

    /** Titel der KeePass-Einträge; koppelt bei Bedarf mit Dialog. Blockiert, also nur im Hintergrund. */
    List<String> keePassTitles() throws Exception;

    /** Benutzer, Kennwort und Adresse eines KeePass-Eintrags. Blockiert, also nur im Hintergrund. */
    Login keePassLogin(String title) throws Exception;

    /**
     * Ein gelesener KeePass-Eintrag.
     *
     * @param user     Benutzer
     * @param password Kennwort; der Empfänger überschreibt es nach Gebrauch
     * @param location Adresse (URL-Feld des Eintrags), leer ohne
     */
    record Login(String user, char[] password, String location) {

        public Login {
            user = user == null ? "" : user.trim();
            password = password == null ? new char[0] : password;
            location = location == null ? "" : location.trim();
        }
    }

    /** Proxy und TLS aus den Einstellungen der App, Zugangsdaten aus ihrer KeePass-Kopplung. */
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

            @Override
            public boolean keePassPaired() {
                return context.keePass().paired();
            }

            @Override
            public List<String> keePassTitles() throws Exception {
                return context.keePass().titles();
            }

            @Override
            public Login keePassLogin(String title) throws Exception {
                try (var entry = context.keePass().read(title)) {
                    return new Login(entry.userName(), entry.copyPassword(), entry.location());
                }
            }
        };
    }

    /** Ohne Proxy und ohne KeePass (Tests, Bildschirmfotos). */
    static BetaViewAccess direct() {
        return new BetaViewAccess() {
            @Override
            public HttpClientFactory http() {
                return HttpClientFactory.direct();
            }

            @Override
            public boolean keePassPaired() {
                return false;
            }

            @Override
            public List<String> keePassTitles() throws Exception {
                throw new IllegalStateException("KeePass ist hier nicht angebunden");
            }

            @Override
            public Login keePassLogin(String title) throws Exception {
                throw new IllegalStateException("KeePass ist hier nicht angebunden");
            }
        };
    }
}
