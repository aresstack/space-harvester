package com.aresstack.spaceharvester.deck.access;

import java.util.Arrays;
import java.util.Objects;

/** Benutzer und Passwort eines {@link ServerAccount}, nur kurz im Speicher; {@link #close()} löscht das Passwort. */
public final class ServerCredentials implements AutoCloseable {

    private final ServerAccount account;
    private final char[] password;

    public ServerCredentials(ServerAccount account, char[] password) {
        this.account = Objects.requireNonNull(account, "account");
        this.password = password == null ? new char[0] : password.clone();
    }

    public ServerAccount account() {
        return account;
    }

    public String user() {
        return account.user();
    }

    /** Eine Kopie; der Aufrufer löscht sie. */
    public char[] copyPassword() {
        return password.clone();
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "ServerCredentials[" + account.entry() + ", Passwort verborgen]";
    }
}
