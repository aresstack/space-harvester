package com.aresstack.spaceharvester.deck.files.sharepoint;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Port: SharePoint-Zugangsdaten, die nach der ersten erfolgreichen Anmeldung gespeichert werden (wie MainframeMate
 * „Zugangsdaten speichern“). Der Anmeldedialog erscheint nur, wenn nichts gespeichert ist oder das Gespeicherte nicht
 * mehr passt. Die App speichert verschlüsselt für das Windows-Konto; ohne das gelten die Daten bis zum Schließen.
 * Beide Aufrufe können blockieren (nie auf dem EDT).
 */
public interface SavedLogins {

    /** Benutzer und Kennwort; der Aufrufer überschreibt {@code password} nach Gebrauch. */
    record Login(String user, char[] password) {
    }

    /** Das Gespeicherte für diesen Rechner (WebDAV-Host wie im UNC-Pfad). */
    Optional<Login> load(String host) throws Exception;

    /** Speichert für diesen Rechner; {@code password} bleibt unverändert, der Aufrufer überschreibt es. */
    void save(String host, String user, char[] password) throws Exception;

    /** Nur im Speicher, bis die App schließt (Tests und Rechner ohne Tresor). */
    static SavedLogins inMemory() {
        return new SavedLogins() {
            private final Map<String, Login> logins = new ConcurrentHashMap<>();

            @Override
            public Optional<Login> load(String host) {
                Login login = logins.get(host.toLowerCase(Locale.ROOT));
                return login == null ? Optional.empty()
                        : Optional.of(new Login(login.user(), login.password().clone()));
            }

            @Override
            public void save(String host, String user, char[] password) {
                Login old = logins.put(host.toLowerCase(Locale.ROOT), new Login(user, password.clone()));
                if (old != null) {
                    Arrays.fill(old.password(), '\0');
                }
            }
        };
    }
}
