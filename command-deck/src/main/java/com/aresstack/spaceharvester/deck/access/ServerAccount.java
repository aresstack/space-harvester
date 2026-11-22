package com.aresstack.spaceharvester.deck.access;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Ein Host-Zugang aus dem Passwortspeicher (KeePass-Eintrag), gemeinsam für Terminal, FTP und NDV: dieselbe
 * Maschine, dieselbe Kennung, dasselbe Passwort (Angelo, 22.11.2026). Enthält kein Passwort; das liest erst
 * {@link ServerAccounts#credentials(String)} kurz vor dem Gebrauch.
 *
 * @param entry    Titel des Eintrags, eindeutig im Speicher
 * @param location Adresse aus dem Eintrag (URL-Feld), etwa {@code 10.1.2.3} oder {@code tn3270://host:23}
 * @param user     Benutzer aus dem Eintrag, leer wenn keiner
 */
public record ServerAccount(String entry, String location, String user) {

    public ServerAccount {
        Objects.requireNonNull(entry, "entry");
        location = location == null ? "" : location.trim();
        user = user == null ? "" : user.trim();
    }

    /** Der Rechnername oder die IP aus der Adresse, ohne Schema, Port und Pfad; leer, wenn keine eingetragen ist. */
    public String host() {
        return parse(location).host;
    }

    /** Ein Port aus der Adresse, wenn einer dabeisteht ({@code host:992}). */
    public OptionalInt port() {
        int port = parse(location).port;
        return port > 0 ? OptionalInt.of(port) : OptionalInt.empty();
    }

    /** Ob der Eintrag eine Adresse hat, mit der sich verbinden lässt. */
    public boolean reachable() {
        return !host().isEmpty();
    }

    @Override
    public String toString() {
        return host().isEmpty() ? entry : entry + " (" + host() + ")";
    }

    private record Address(String host, int port) {
    }

    private static Address parse(String location) {
        if (location.isEmpty()) {
            return new Address("", -1);
        }
        String text = location.contains("://") ? location : "tn3270://" + location;
        try {
            URI uri = URI.create(text.replace(" ", ""));
            String host = uri.getHost();
            if (host != null) {
                return new Address(host.toLowerCase(Locale.ROOT).startsWith("[")
                        ? host.substring(1, host.length() - 1) : host, uri.getPort());
            }
        } catch (IllegalArgumentException ignored) {
            // Unübliche Schreibweise: unten von Hand zerlegen.
        }
        String rest = location.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int colon = rest.lastIndexOf(':');
        if (colon > 0 && rest.indexOf(':') == colon) {
            try {
                return new Address(rest.substring(0, colon), Integer.parseInt(rest.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return new Address(rest.substring(0, colon), -1);
            }
        }
        return new Address(rest, -1);
    }
}
