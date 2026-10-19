package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import java.util.Arrays;

/**
 * Kurzlebige Anmeldedaten für die BetaView-Anmeldung. Der Adapter verwendet sie nur während der Anmeldung und
 * ruft danach {@link #clear()} auf; sie werden weder gespeichert noch geloggt.
 */
public final class BetaViewCredentials {

    private final String username;
    private final char[] password;

    public BetaViewCredentials(String username, char[] password) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
        this.username = username;
        this.password = password.clone();
    }

    public String username() {
        return username;
    }

    char[] password() {
        return password;
    }

    /** Überschreibt das Passwort im Speicher. */
    public void clear() {
        Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "BetaViewCredentials{username=" + username + ", password=***}";
    }
}
