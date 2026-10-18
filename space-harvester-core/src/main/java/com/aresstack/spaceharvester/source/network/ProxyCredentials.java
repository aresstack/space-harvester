package com.aresstack.spaceharvester.source.network;

import com.aresstack.spaceharvester.source.SourceSecrets;
import java.net.PasswordAuthentication;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benutzer und Passwort für die Anmeldung am Proxy (#189), je Verbindungsaufbau neu gelesen: aus
 * {@link ProxySettings#authentication()} oder, wenn dort kein Passwort steht, vom Anbieter des Programms
 * ({@link SourceSecrets}, Verweis {@value ProxySettings#SECRET_REF}). Kein Feld hält das Passwort des Anbieters;
 * Protokoll und Meldungen nennen nie Benutzer oder Passwort.
 */
public final class ProxyCredentials {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyCredentials.class);
    private static final ProxyCredentials NONE = new ProxyCredentials(null, null);

    private final ProxySettings.Authentication authentication;
    private final SourceSecrets provider;

    private ProxyCredentials(ProxySettings.Authentication authentication, SourceSecrets provider) {
        this.authentication = authentication;
        this.provider = provider;
    }

    /** Ohne Anmeldung am Proxy. */
    public static ProxyCredentials none() {
        return NONE;
    }

    /**
     * @param authentication die Anmeldung aus den Einstellungen; {@code null} ohne
     * @param provider       Anbieter des Programms oder {@code null}
     */
    public static ProxyCredentials of(ProxySettings.Authentication authentication, SourceSecrets provider) {
        return authentication == null ? NONE : new ProxyCredentials(authentication, provider);
    }

    /** Ob überhaupt eine Anmeldung am Proxy eingerichtet ist. */
    public boolean configured() {
        return authentication != null;
    }

    /**
     * Die Anmeldung für einen Verbindungsaufbau, leer ohne Anmeldung oder wenn der Anbieter nichts liefert (der
     * Proxy antwortet dann mit 407). Der Aufrufer löscht das Passwort nach Gebrauch.
     */
    public Optional<PasswordAuthentication> get() {
        if (authentication == null) {
            return Optional.empty();
        }
        char[] password = authentication.password();
        String username = authentication.username();
        if (password == null) {
            if (provider == null) {
                LOG.warn("Proxy-Anmeldung ohne Passwort und ohne Anbieter für Zugangsdaten");
                return Optional.empty();
            }
            Optional<SourceSecrets.Credential> found;
            try {
                found = provider.resolve(ProxySettings.SECRET_REF);
            } catch (SourceSecrets.Unavailable e) {
                LOG.warn("Proxy-Anmeldung: Zugangsdaten nicht verfügbar ({})", e.getMessage());
                return Optional.empty();
            } catch (RuntimeException e) {
                LOG.warn("Proxy-Anmeldung: Zugangsdaten nicht lesbar ({})", e.getClass().getSimpleName());
                return Optional.empty();
            }
            if (found.isEmpty()) {
                LOG.warn("Proxy-Anmeldung: kein Eintrag {} beim Anbieter", ProxySettings.SECRET_REF);
                return Optional.empty();
            }
            char[] secret = found.get().secret();
            password = secret.clone();
            Arrays.fill(secret, '\0');
            if (username == null) {
                username = found.get().principal();
            }
        }
        if (username == null || username.isBlank()) {
            Arrays.fill(password, '\0');
            LOG.warn("Proxy-Anmeldung ohne Benutzername");
            return Optional.empty();
        }
        try {
            return Optional.of(new PasswordAuthentication(username, password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Override
    public String toString() {
        return configured() ? "ProxyCredentials[basic]" : "ProxyCredentials[none]";
    }
}
