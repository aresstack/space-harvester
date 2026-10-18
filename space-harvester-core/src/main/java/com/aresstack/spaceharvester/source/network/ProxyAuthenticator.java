package com.aresstack.spaceharvester.source.network;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anmeldung am Proxy für Verbindungen über {@code HttpURLConnection} (#189): den synchronen Confluence-Transport der
 * Kopie {@code enterprise-ai-sources} und den KI-Dienst. Wie {@code ProxyAuthenticator} im Enterprise AI Client
 * prozessweit, weil das JDK die Anmeldung für den HTTPS-Tunnel ({@code CONNECT}) nur beim
 * {@link Authenticator#setDefault Standard-Authenticator} erfragt. Antwortet nur dem Proxy
 * ({@link RequestorType#PROXY}); Anmeldungen bei Servern bleiben den Adaptern. Der WebClient-Transport braucht ihn
 * nicht, er meldet sich je Verbindung selbst an.
 *
 * <p>Seit JDK 8u111 ist BASIC für den Tunnel abgeschaltet ({@code jdk.http.auth.tunneling.disabledSchemes});
 * {@link #install} gibt es frei, sofern die Property nicht gesetzt ist. Das JDK liest sie nur einmal: Ein Programm,
 * das schon vorher Verbindungen aufbaut, ruft {@link #allowBasicTunneling()} beim Start.
 */
public final class ProxyAuthenticator extends Authenticator {

    static final String TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    private static final Logger LOG = LoggerFactory.getLogger(ProxyAuthenticator.class);

    private final ProxyCredentials credentials;

    private ProxyAuthenticator(ProxyCredentials credentials) {
        this.credentials = credentials;
    }

    /**
     * Setzt die Anmeldung als Standard-Authenticator, wenn eine eingerichtet ist und kein fremder schon gesetzt ist.
     *
     * @return der gesetzte Authenticator oder {@code null}, wenn nichts gesetzt wurde
     */
    public static synchronized ProxyAuthenticator install(ProxyCredentials credentials) {
        if (credentials == null || !credentials.configured()) {
            return null;
        }
        Authenticator current = Authenticator.getDefault();
        if (current != null && !(current instanceof ProxyAuthenticator)) {
            LOG.warn("Proxy-Anmeldung für HttpURLConnection nicht eingerichtet: das Programm hat schon einen eigenen "
                    + "Authenticator");
            return null;
        }
        allowBasicTunneling();
        ProxyAuthenticator authenticator = new ProxyAuthenticator(credentials);
        Authenticator.setDefault(authenticator);
        LOG.info("Proxy-Anmeldung BASIC eingerichtet");
        return authenticator;
    }

    /** Gibt BASIC für den HTTPS-Tunnel frei, sofern die Property nicht gesetzt ist. */
    public static void allowBasicTunneling() {
        if (System.getProperty(TUNNELING_DISABLED_SCHEMES) == null) {
            System.setProperty(TUNNELING_DISABLED_SCHEMES, "");
        }
    }

    /** Nimmt diese Anmeldung zurück, sofern sie noch der Standard ist. */
    public void uninstall() {
        synchronized (ProxyAuthenticator.class) {
            if (Authenticator.getDefault() == this) {
                Authenticator.setDefault(null);
            }
        }
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() != RequestorType.PROXY) {
            return null;
        }
        return credentials.get().orElse(null);
    }

    @Override
    public String toString() {
        return "ProxyAuthenticator[" + credentials + "]";
    }
}
