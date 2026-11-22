package com.aresstack.spaceharvester.deck.terminal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Zugangsdaten für die automatische Anmeldung einer Sitzung (wie in MainframeMate): Benutzer, Passwort und ein
 * optionales Startkommando. Das Passwort lebt nur im Speicher und wird nach dem Eintippen gelöscht
 * ({@link #close()}); {@link #toString()} zeigt es nie.
 */
public final class TerminalLogin implements AutoCloseable {

    private final String user;
    private final char[] password;
    private final String command;

    public TerminalLogin(String user, char[] password, String command) {
        this.user = Objects.requireNonNull(user, "user").trim();
        this.password = password == null ? new char[0] : password.clone();
        this.command = command == null ? "" : command.trim();
        if (this.user.isEmpty()) {
            throw new IllegalArgumentException("Benutzer fehlt");
        }
        if (this.password.length == 0) {
            throw new IllegalArgumentException("Passwort fehlt");
        }
    }

    public String user() {
        return user;
    }

    /** Eine Kopie des Passworts; der Aufrufer löscht sie nach Gebrauch. */
    public char[] copyPassword() {
        return password.clone();
    }

    /** Nach der Anmeldung einzugeben, leer für keins. */
    public String command() {
        return command;
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "TerminalLogin[" + user + ", Passwort verborgen" + (command.isEmpty() ? "" : ", Kommando") + "]";
    }
}
