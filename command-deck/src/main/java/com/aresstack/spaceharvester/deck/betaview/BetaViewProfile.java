package com.aresstack.spaceharvester.deck.betaview;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Wohin eine BetaView-Anmeldung geht: die Basisadresse des BetaView-Webservers und der Benutzer. Das Kennwort gehört
 * nicht hierher; es kommt beim Anmelden einmal herein ({@link BetaViewSessions#open}) und wird danach überschrieben.
 *
 * @param address Basisadresse, immer mit {@code /} am Ende, etwa {@code https://host/betaview/}
 * @param user    Benutzername
 *
 * <p>Eine Vorgabe für die Adresse gibt es bewusst nicht: Interne Adressen stehen nirgends im Programm, sondern im
 * URL-Feld des KeePass-Eintrags oder im Formular (Angelo, 24.11.2026).
 */
public record BetaViewProfile(URI address, String user) {

    public BetaViewProfile {
        if (address == null) {
            throw new IllegalArgumentException("Adresse fehlt");
        }
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Die Adresse muss mit http:// oder https:// beginnen");
        }
        if (address.getHost() == null || address.getHost().isBlank()) {
            throw new IllegalArgumentException("In der Adresse fehlt der Rechnername");
        }
        user = user == null ? "" : user.trim();
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Benutzer fehlt");
        }
    }

    /**
     * Baut aus einer eingetippten oder eingefügten Adresse die Basisadresse wie MainframeMate
     * ({@code OpenBetaViewMenuCommand.normaliseUrl}): ohne Schema gilt https, vom Pfad bleibt nur der erste Abschnitt
     * (der Kontextpfad, etwa {@code /betaview}), Standardports entfallen, am Ende steht immer {@code /}. Aus
     * {@code host/betaview/login.action} wird so {@code https://host/betaview/}.
     */
    public static URI normalise(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Adresse fehlt");
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI parsed;
        try {
            parsed = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Keine gültige Adresse: " + raw.trim());
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Keine gültige Adresse: " + raw.trim());
        }
        StringBuilder base = new StringBuilder(scheme).append("://").append(host);
        int port = parsed.getPort();
        if (port > 0 && !(port == 80 && scheme.equals("http")) && !(port == 443 && scheme.equals("https"))) {
            base.append(':').append(port);
        }
        String path = parsed.getPath() == null ? "" : parsed.getPath();
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        String context = slash >= 0 ? stripped.substring(0, slash) : stripped;
        if (!context.isEmpty()) {
            base.append('/').append(context);
        }
        base.append('/');
        return URI.create(base.toString());
    }

    /** Kurzname für Pillen und Statuszeile: {@code benutzer@host}. */
    public String label() {
        return user + "@" + address.getHost();
    }
}
