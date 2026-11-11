package com.aresstack.spaceharvester.application.signin;

import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Kurze, bereinigte Ursache eines gescheiterten Sendens des Einmalcodes oder Anforderns eines neuen Codes (#571), statt
 * pauschal „Quelle nicht erreichbar“. Format: {@code <Art> – Ziel <Host>}, etwa
 * {@code Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example} oder
 * {@code TLS-Fehler (SSLHandshakeException: Received fatal alert: bad_certificate) – Ziel wiki.example}.
 *
 * <p>Die Art kommt aus den Ausnahmetypen der Ursachenkette und aus HTTP-Statuscodes in deren Meldungen: Proxy-407,
 * abgelehnter Proxy-Tunnel, nicht verfügbare Proxy-Route, TLS, Zeitüberschreitung, unbekannter Host, abgelehnte
 * Verbindung, HTTP-Status der Quelle, sonst „Verbindungsfehler“ mit dem Typ der innersten Ursache. Freitext der Meldungen
 * erscheint nur bei TLS und nur nach {@link #sanitize}; der Host nur ohne Pfad, Query und Port. Der Code, Cookies und
 * Kopfzeilen kommen nie vor: sie stehen in keiner Meldung des Transports, und {@link SignInRequests#submit} entfernt
 * den Code zusätzlich, falls er doch auftaucht.
 */
public final class SignInFailures {

    /** Höchstlänge einer übernommenen Meldung. */
    static final int MAX_DETAIL = 120;
    /** Proxy verlangt Anmeldung; das JDK meldet die 407 je nach Weg unterschiedlich. */
    static final String PROXY_AUTHENTICATION = "Proxy verlangt Anmeldung (HTTP 407)";

    /**
     * Anfang der Meldung, wenn Confluence einen Einmalcode bei Anmeldung mit Personal Access Token mit einem Serverfehler
     * beantwortet (#571): Ein Token hat keine Web-Sitzung, an die die Zwei-Faktor-Anmeldung den Code binden könnte.
     */
    static final String TOKEN_LOGIN_REJECTED = "Confluence nimmt den Einmalcode bei Anmeldung mit Personal Access Token "
            + "nicht an";

    /**
     * Anfang der Meldung, wenn Confluence die Anmeldung mit Benutzer und Passwort für die Web-Sitzung einer Quelle mit
     * Token ablehnt (HTTP 401, #571): falsches Passwort, falscher Benutzer oder eine gesperrte Anmeldung nach
     * Fehlversuchen.
     */
    static final String LOGIN_REJECTED = "Confluence lehnt Benutzername oder Passwort ab";

    /**
     * Anfang der Meldung, wenn der Proxy beim Senden des Einmalcodes auch im zweiten Versuch keinen Tunnel zu Confluence
     * aufbaute (HTTP 5xx auf {@code CONNECT}, #571): Der Code hat Confluence nicht erreicht.
     */
    static final String CODE_NOT_DELIVERED = "Der Proxy hat keine Verbindung zu Confluence aufgebaut";

    /** {@code Unable to tunnel through proxy. Proxy returns "HTTP/1.1 502 Bad Gateway"} des JDK. */
    private static final Pattern TUNNEL = Pattern.compile("tunnel through proxy.*?HTTP/\\d(?:\\.\\d)? (\\d{3})",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PROXY_407 = Pattern.compile("\\b407\\b");
    /** „Confluence antwortet mit HTTP 403“ des Adapters. */
    private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP (\\d{3})\\b");
    private static final Pattern ROUTE_UNAVAILABLE = Pattern.compile("proxy route for .* unavailable|no route decision",
            Pattern.CASE_INSENSITIVE);

    /** Query einer Adresse, bis zum nächsten Leerraum oder Anführungszeichen. */
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s\"')]*");
    /** {@code name=wert} oder {@code name: wert} mit einem Namen, der nach Geheimnis klingt. */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(cookie|set-cookie|authorization|proxy-authorization|token|atl_token|jsessionid|session|password|"
                    + "passwort|secret|otp|code|pin)\\b(\\s*[=:]\\s*)[^\\s,;&\"')]+");
    /** Wert eines Anmeldeschemas ({@code Basic …}, {@code Bearer …}). */
    private static final Pattern SCHEME_VALUE = Pattern.compile(
            "(?i)\\b(basic|bearer|negotiate|ntlm)(\\s+)[^\\s,;\"')]+");
    /** Lange Zeichenfolgen ohne Leerraum (Token, Base64, Hex). */
    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9+/=_\\-]{32,}");

    private SignInFailures() {
    }

    /**
     * Die Ursache als kurzer Text.
     *
     * @param failure die Ausnahme beim Senden (etwa die {@code SourceAccessException} mit Ursachenkette)
     * @param host    Ziel, etwa {@link com.aresstack.spaceharvester.application.sources.SignInChallenge#service()};
     *                Pfad, Query und Port werden entfernt, leer oder {@code null} lässt das Ziel weg
     */
    public static String describe(Throwable failure, String host) {
        String kind = kind(failure);
        String target = host(host);
        return target.isEmpty() ? kind : kind + " – Ziel " + target;
    }

    /**
     * Klare Meldung statt „Quelle antwortet mit HTTP 500“, wenn Confluence den Einmalcode einer Quelle mit Personal Access
     * Token nicht annimmt (#571); {@link #describe} gibt sie unverändert als Art aus.
     *
     * @param status HTTP-Status der Antwort auf das Senden
     */
    public static String tokenLoginRejected(int status) {
        return TOKEN_LOGIN_REJECTED + " (HTTP " + status + "). Bilder und Anhänge hinter der Zwei-Faktor-Anmeldung "
                + "brauchen bei dieser Quelle die Anmeldung mit Benutzer und Passwort";
    }

    /**
     * Klare Meldung statt „Quelle antwortet mit HTTP 401“, wenn Confluence Benutzer und Passwort für die Web-Sitzung
     * ablehnt (#571); {@link #describe} gibt sie unverändert als Art aus.
     */
    public static String loginRejected(int status) {
        return LOGIN_REJECTED + " (HTTP " + status + "). Bitte Benutzername und Passwort prüfen (nicht den Access-Token); "
                + "nach mehreren Fehlversuchen kann Confluence erst eine Anmeldung im Browser verlangen";
    }

    /**
     * Klare Meldung, wenn der Proxy den Tunnel beim Senden des Codes zweimal mit einem Serverfehler ablehnte (#571);
     * {@link #describe} gibt sie unverändert als Art aus.
     */
    public static String codeNotDelivered(int status) {
        return CODE_NOT_DELIVERED + " (HTTP " + status + ", auch im zweiten Versuch). Der Code hat Confluence nicht "
                + "erreicht und ist noch nicht verbraucht; bitte gleich noch einmal Bestätigen";
    }

    /**
     * HTTP-Status, mit dem der Proxy den {@code CONNECT}-Tunnel mit einem Serverfehler (5xx) ablehnte, sonst -1. Dann
     * kam die Anfrage nie beim Ziel an.
     */
    public static int refusedTunnel(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current : chain(failure)) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        Matcher tunnel = TUNNEL.matcher(messages);
        return tunnel.find() && tunnel.group(1).startsWith("5") ? Integer.parseInt(tunnel.group(1)) : -1;
    }

    /** Ob die Ursachenkette eine Antwort mit diesem HTTP-Status meldet („… HTTP 401 …“). */
    public static boolean answeredWith(Throwable failure, int status) {
        for (Throwable current : chain(failure)) {
            Matcher found = current.getMessage() == null ? null : HTTP_STATUS.matcher(current.getMessage());
            while (found != null && found.find()) {
                if (Integer.parseInt(found.group(1)) == status) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Typ der innersten Ursache (einfacher Klassenname) fürs Protokoll; ohne Meldung. */
    public static String rootType(Throwable failure) {
        return failure == null ? "" : root(failure).getClass().getSimpleName();
    }

    /**
     * Bereinigt eine Meldung: ohne Query-Teile von Adressen, ohne Werte nach geheimnisartigen Namen (Cookie,
     * Authorization, Token, Passwort, Code …), ohne lange Token-artige Zeichenfolgen und Zeilenumbrüche, gekürzt auf
     * {@value #MAX_DETAIL} Zeichen.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\r', ' ').replace('\n', ' ').trim();
        clean = QUERY.matcher(clean).replaceAll("?…");
        clean = SECRET_PAIR.matcher(clean).replaceAll("$1$2***");
        clean = SCHEME_VALUE.matcher(clean).replaceAll("$1$2***");
        clean = LONG_RUN.matcher(clean).replaceAll("…");
        if (clean.length() > MAX_DETAIL) {
            clean = clean.substring(0, MAX_DETAIL - 1) + "…";
        }
        return clean;
    }

    /**
     * Ersetzt jedes Vorkommen von {@code secret} in {@code text} durch {@code ***}, ohne aus dem Geheimnis einen
     * {@code String} zu bauen; kürzer als drei Zeichen oder {@code null} bleibt der Text unverändert.
     */
    public static String without(String text, char[] secret) {
        if (text == null || secret == null || secret.length < 3 || text.length() < secret.length) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (matches(text, i, secret)) {
                out.append("***");
                i += secret.length;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    private static boolean matches(String text, int from, char[] secret) {
        if (from + secret.length > text.length()) {
            return false;
        }
        for (int k = 0; k < secret.length; k++) {
            if (text.charAt(from + k) != secret[k]) {
                return false;
            }
        }
        return true;
    }

    static String kind(Throwable failure) {
        if (failure == null) {
            return "Unbekannter Fehler";
        }
        StringBuilder messages = new StringBuilder();
        for (Throwable current : chain(failure)) {
            if (current instanceof HttpRetryException retry && retry.responseCode() == 407) {
                return PROXY_AUTHENTICATION;
            }
            if (current instanceof SSLException) {
                String detail = sanitize(current.getMessage());
                return "TLS-Fehler (" + current.getClass().getSimpleName() + (detail.isEmpty() ? "" : ": " + detail)
                        + ")";
            }
            if (current instanceof SocketTimeoutException
                    || current.getClass().getSimpleName().endsWith("TimeoutException")) {
                return "Zeitüberschreitung (" + current.getClass().getSimpleName() + ")";
            }
            if (current instanceof UnknownHostException) {
                return "Host nicht auflösbar (UnknownHostException)";
            }
            if (current instanceof NoRouteToHostException) {
                return "Keine Route zum Host (NoRouteToHostException)";
            }
            if (current instanceof ConnectException) {
                return "Verbindung abgelehnt (ConnectException)";
            }
            if (current.getMessage() != null && (current.getMessage().startsWith(TOKEN_LOGIN_REJECTED)
                    || current.getMessage().startsWith(LOGIN_REJECTED)
                    || current.getMessage().startsWith(CODE_NOT_DELIVERED))) {
                return current.getMessage();
            }
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        String text = messages.toString();
        Matcher tunnel = TUNNEL.matcher(text);
        if (tunnel.find()) {
            return "407".equals(tunnel.group(1)) ? PROXY_AUTHENTICATION
                    : "Proxy-Tunnel abgelehnt (HTTP " + tunnel.group(1) + ")";
        }
        if (ROUTE_UNAVAILABLE.matcher(text).find()) {
            return "Proxy-Route nicht verfügbar";
        }
        Matcher status = HTTP_STATUS.matcher(text);
        if (status.find()) {
            return "407".equals(status.group(1)) ? PROXY_AUTHENTICATION
                    : "Quelle antwortet mit HTTP " + status.group(1);
        }
        if (PROXY_407.matcher(text).find() && text.toLowerCase(Locale.ROOT).contains("proxy")) {
            return PROXY_AUTHENTICATION;
        }
        return "Verbindungsfehler (" + root(failure).getClass().getSimpleName() + ")";
    }

    private static String host(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        for (char stop : new char[] {'/', '?', '#', ' '}) {
            int index = value.indexOf(stop);
            if (index >= 0) {
                value = value.substring(0, index);
            }
        }
        if (!value.startsWith("[")) {
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(0, colon);
            }
        }
        return value.length() > 253 ? value.substring(0, 253) : value;
    }

    private static Iterable<Throwable> chain(Throwable failure) {
        java.util.List<Throwable> list = new java.util.ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = failure; current != null && seen.put(current, Boolean.TRUE) == null;
             current = current.getCause()) {
            list.add(current);
        }
        return list;
    }

    private static Throwable root(Throwable failure) {
        Throwable root = failure;
        for (Throwable current : chain(failure)) {
            root = current;
        }
        return root;
    }
}
