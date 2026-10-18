package com.aresstack.spaceharvester.source.network;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Macht aus einer gescheiterten Verbindung eine technische Ursache und einen Hinweis, was zu tun ist (#189), wie
 * {@code ConnectionDiagnosis} im Enterprise AI Client; die Hinweise nennen die Schlüssel dieses Projekts
 * ({@code space-harvester.network.*}, {@code trust-bundle}).
 *
 * <p>{@link #detail} folgt der Ursachenkette und reiht die Meldungen mit dem einfachen Klassennamen aneinander, ohne
 * Wiederholungen, auf {@value #MAX_DETAIL_LENGTH} Zeichen begrenzt; {@link #mask} ersetzt alles, was wie ein
 * Bearer-Token oder eine Basic-Anmeldung aussieht. {@link #hint} ordnet die Kette einer bekannten Ursache zu
 * (Proxy-Anmeldung, Proxy-Route, Zertifikat, TLS, Namensauflösung, Zeitüberschreitung, Verbindung abgelehnt) oder
 * liefert {@code null}.
 */
public final class ConnectionDiagnosis {

    static final int MAX_DETAIL_LENGTH = 600;
    static final String MASKED = "***";

    private static final Pattern CREDENTIALS = Pattern.compile("(?i)\\b(bearer|basic)(?:\\s|%20|\\+)+[^\\s\"',;&]+");
    private static final Pattern QUALIFIED_EXCEPTION =
            Pattern.compile("\\b(?:[a-z][a-z0-9_]*\\.)+([A-Z][A-Za-z0-9_$]*)");
    private static final String SEPARATOR = " | ";

    private ConnectionDiagnosis() {
    }

    /** Ursachenkette als eine Zeile, ohne Geheimnisse; leer ohne Ausnahme. */
    public static String detail(Throwable error) {
        List<String> parts = new ArrayList<>();
        String previous = "";
        for (Throwable t : chain(error)) {
            String message = t.getMessage();
            String name = t.getClass().getSimpleName();
            String part;
            if (message == null || message.isBlank()) {
                part = name;
            } else {
                message = QUALIFIED_EXCEPTION.matcher(message.strip()).replaceAll("$1");
                if (!previous.isEmpty() && previous.contains(message)) {
                    continue; // das JDK wiederholt die Meldung der Ursache in der umschließenden Ausnahme
                }
                part = message.startsWith(name) ? message : name + ": " + message;
            }
            if (!previous.contains(part)) {
                parts.add(part);
            }
            previous = part;
        }
        String joined = mask(String.join(SEPARATOR, parts).replace('\r', ' ').replace('\n', ' ').strip());
        return joined.length() <= MAX_DETAIL_LENGTH ? joined : joined.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    /** Was Betrieb oder Benutzer tun können, nach erkannter Ursache; {@code null}, wenn nichts Bekanntes zutrifft. */
    public static String hint(Throwable error) {
        List<Throwable> chain = chain(error);
        String text = allMessages(chain).toLowerCase(Locale.ROOT);
        if (text.contains("407") || text.contains("proxy authentication")) {
            return "Der Proxy verlangt eine Anmeldung: space-harvester.network.proxy.auth (BASIC, Benutzername und "
                    + "Passwort) setzen; eine Windows-integrierte Anmeldung (NTLM/Kerberos) wird nicht unterstützt.";
        }
        if (text.contains("proxy route for") || text.contains("pac-failed") || text.contains("route-unavailable")) {
            return "Die Proxy-Route ließ sich nicht bestimmen; es wurde keine Verbindung versucht. Die "
                    + "Proxy-Diagnose zeigt jeden Schritt (PAC-Adresse, Skript, Ergebnis); mode, pac-url oder "
                    + "pac-discovery-script unter space-harvester.network.proxy anpassen.";
        }
        if (text.contains("unable to tunnel through proxy") || text.contains("proxy returns")
                || text.contains("proxyconnectexception")) {
            return "Der Proxy hat die Verbindung zum Ziel abgelehnt: space-harvester.network.proxy (mode, "
                    + "non-proxy-hosts) prüfen; die Proxy-Diagnose zeigt die Route.";
        }
        // Nur Zertifikatsursachen; eine SSLHandshakeException kann auch Protokollversion oder Cipher-Suite betreffen.
        if (has(chain, CertificateException.class) || text.contains("pkix") || text.contains("certification path")
                || text.contains("certificate")) {
            return "Java vertraut dem Zertifikat des Servers nicht, häufig wegen eines Firmen-Proxys mit eigenem "
                    + "Zertifikat. Abhilfe: unter Windows space-harvester.network.tls.use-windows-root und "
                    + "use-windows-ca-stores einschalten, die CA als PEM-Datei unter tls.ca-certificates-file "
                    + "eintragen oder der Quelle ein trust-bundle geben.";
        }
        if (has(chain, SSLException.class) || text.contains("handshake_failure") || text.contains("protocol_version")
                || text.contains("no cipher suites")) {
            return "Die TLS-Verhandlung mit dem Server ist gescheitert (Protokollversion, Cipher-Suite oder ein "
                    + "verlangtes Client-Zertifikat): client-certificate der Quelle prüfen.";
        }
        if (has(chain, UnknownHostException.class)) {
            return "Der Hostname lässt sich nicht auflösen. Hinter einem Firmen-Proxy muss die Proxy-Route den Proxy "
                    + "nennen (mode pac-url-powershell wertet das PAC-Skript des Unternehmens aus, manual-proxy setzt "
                    + "einen festen Proxy); sonst Schreibweise der Basis-URL und Netzverbindung prüfen.";
        }
        if (has(chain, SocketTimeoutException.class) || text.contains("timeout") || text.contains("timed out")) {
            return "Zeitüberschreitung: Server oder Proxy antworten nicht. Netzverbindung und Proxy-Route prüfen.";
        }
        if (has(chain, ConnectException.class) || has(chain, NoRouteToHostException.class)) {
            return "Die Verbindung wird abgelehnt oder blockiert (Firewall, falscher Port, Proxy nötig): Basis-URL "
                    + "und space-harvester.network.proxy prüfen.";
        }
        return null;
    }

    /** Ersetzt alles, was wie ein Bearer-Token oder eine Basic-Anmeldung aussieht, auch URL-kodiert. */
    public static String mask(String text) {
        return text == null ? "" : CREDENTIALS.matcher(text).replaceAll("$1 " + MASKED);
    }

    private static boolean has(List<Throwable> chain, Class<? extends Throwable> type) {
        return chain.stream().anyMatch(type::isInstance);
    }

    private static String allMessages(List<Throwable> chain) {
        StringBuilder text = new StringBuilder();
        for (Throwable t : chain) {
            text.append(t.getClass().getName()).append(' ');
            if (t.getMessage() != null) {
                text.append(t.getMessage()).append(' ');
            }
        }
        return text.toString();
    }

    private static List<Throwable> chain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = error;
        while (current != null && chain.size() < 12 && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
