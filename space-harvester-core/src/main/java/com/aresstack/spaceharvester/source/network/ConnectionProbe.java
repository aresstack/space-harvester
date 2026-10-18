package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.spaceharvester.application.sources.ConnectionStep.Stage;
import com.aresstack.spaceharvester.application.sources.ConnectionStep.Status;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Stufentest des Netzwegs zu einem Ziel (#189), wie {@code ConnectionProbe} im Enterprise AI Client: Proxy-Route,
 * Namensauflösung des ersten Sprungs (Proxy oder Ziel), TCP-Verbindung, bei einem Proxy der {@code CONNECT}-Tunnel
 * samt Anmeldung am Proxy und bei HTTPS der TLS-Handshake mit derselben Vertrauensregel wie die Quelle. Ein
 * gescheiterter Schritt beendet den Test; die folgenden Schritte erscheinen als {@link Status#SKIPPED}. Anmeldung
 * und API prüft der Aufrufer danach über den echten Quellenadapter.
 *
 * <p>Meldungen nennen Host und Port, nie Zugangsdaten, Pfade oder Query der Ziel-URL.
 */
public final class ConnectionProbe {

    private static final Pattern STATUS_LINE = Pattern.compile("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3}).*");
    private static final Pattern COMMON_NAME = Pattern.compile("(?:^|,)\\s*CN=([^,]+)");
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final ProxyRoutes routes;
    private final ProxyCredentials credentials;
    private final Duration timeout;

    public ConnectionProbe(ProxyRoutes routes, ProxyCredentials credentials, Duration timeout) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.credentials = credentials == null ? ProxyCredentials.none() : credentials;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static ConnectionStep failed(Stage stage, Throwable error) {
        return ConnectionStep.failed(stage, ConnectionDiagnosis.detail(error), ConnectionDiagnosis.hint(error));
    }

    /**
     * Läuft blockierend die Netzstufen bis {@link Stage#TLS} durch.
     *
     * @param trust              Vertrauen für HTTPS; {@code null} für den JVM-Truststore
     * @param clientCertificate  ob die Quelle ein Client-Zertifikat schickt; dann ist ein abgelehnter Handshake nur
     *                           eine Warnung, weil der Test ohne Zertifikat verbindet
     * @param onStep             bekommt jeden Schritt sofort
     * @return {@code true}, wenn kein Schritt gescheitert ist
     */
    public boolean run(URI target, X509TrustManager trust, boolean clientCertificate,
                       Consumer<ConnectionStep> onStep) {
        Objects.requireNonNull(onStep, "onStep");
        String host = target.getHost();
        boolean https = "https".equalsIgnoreCase(target.getScheme());
        int port = target.getPort() >= 0 ? target.getPort() : https ? 443 : 80;
        String endpoint = host + ":" + port;

        HttpRoute route;
        try {
            route = routes.routeFor(target);
        } catch (RuntimeException e) {
            return stop(onStep, Stage.PROXY_ROUTE, failed(Stage.PROXY_ROUTE, e));
        }
        if (route.isUnavailable()) {
            return stop(onStep, Stage.PROXY_ROUTE, ConnectionStep.failed(Stage.PROXY_ROUTE, "Ziel " + endpoint + ": "
                    + route.describe() + "; es wurde keine Verbindung versucht",
                    ConnectionDiagnosis.hint(new IllegalStateException("route-unavailable"))));
        }
        onStep.accept(ConnectionStep.ok(Stage.PROXY_ROUTE, "Ziel " + endpoint + ": " + route.describe()));

        String hopHost = route.isProxy() ? route.proxyHost() : host;
        int hopPort = route.isProxy() ? route.proxyPort() : port;
        String hop = (route.isProxy() ? "Proxy " : "") + hopHost + ":" + hopPort;
        InetAddress address;
        try {
            InetAddress[] all = InetAddress.getAllByName(hopHost);
            address = all[0];
            onStep.accept(ConnectionStep.ok(Stage.DNS, hopHost + " → " + address.getHostAddress()
                    + (all.length > 1 ? " (+" + (all.length - 1) + ")" : "")
                    + (route.isProxy() ? "; das Ziel löst der Proxy auf" : "")));
        } catch (IOException e) {
            return stop(onStep, Stage.DNS, failed(Stage.DNS, e));
        }

        try (Socket socket = new Socket()) {
            int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
            try {
                socket.connect(new InetSocketAddress(address, hopPort), millis);
                socket.setSoTimeout(millis);
            } catch (IOException e) {
                return stop(onStep, Stage.TCP, failed(Stage.TCP, e));
            }
            if (route.isProxy() && https) {
                ConnectionStep tunnel = tunnel(socket, endpoint);
                if (tunnel.failed()) {
                    return stop(onStep, Stage.TCP, tunnel);
                }
                onStep.accept(tunnel);
            } else {
                onStep.accept(ConnectionStep.ok(Stage.TCP, hop + " verbunden"));
            }
            if (!https) {
                onStep.accept(ConnectionStep.info(Stage.TLS, "unverschlüsselt (http), keine TLS-Prüfung"));
                return true;
            }
            return handshake(socket, host, port, trust, clientCertificate, onStep);
        } catch (IOException e) {
            return stop(onStep, Stage.TCP, failed(Stage.TCP, e));
        }
    }

    /** {@code CONNECT} zum Ziel; mit Anmeldung am Proxy, wenn eingerichtet. */
    private ConnectionStep tunnel(Socket socket, String endpoint) throws IOException {
        StringBuilder request = new StringBuilder("CONNECT ").append(endpoint).append(" HTTP/1.1\r\nHost: ")
                .append(endpoint).append("\r\n");
        boolean authenticated = false;
        PasswordAuthentication login = credentials.get().orElse(null);
        if (login != null) {
            char[] password = login.getPassword();
            byte[] raw = null;
            try {
                raw = utf8((login.getUserName() + ":").toCharArray(), password);
                request.append("Proxy-Authorization: Basic ").append(Base64.getEncoder().encodeToString(raw))
                        .append("\r\n");
                authenticated = true;
            } finally {
                Arrays.fill(password, '\0');
                if (raw != null) {
                    Arrays.fill(raw, (byte) 0);
                }
            }
        }
        request.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        request.setLength(0);
        String head = readHead(socket.getInputStream());
        Matcher status = STATUS_LINE.matcher(head.lines().findFirst().orElse(""));
        if (!status.matches()) {
            return ConnectionStep.failed(Stage.TCP, "Tunnel zu " + endpoint + ": keine HTTP-Antwort des Proxys",
                    ConnectionDiagnosis.hint(new IOException("proxy returns no response")));
        }
        int code = Integer.parseInt(status.group(1));
        if (code == 407) {
            String message = "Proxy verlangt eine Anmeldung (HTTP 407"
                    + (authenticated ? ", die gesendete BASIC-Anmeldung wurde abgelehnt)" : ")")
                    + schemes(head);
            return ConnectionStep.failed(Stage.TCP, message, ConnectionDiagnosis.hint(new IOException("407")));
        }
        if (code / 100 != 2) {
            return ConnectionStep.failed(Stage.TCP, "Tunnel zu " + endpoint + ": Proxy antwortet HTTP " + code,
                    ConnectionDiagnosis.hint(new IOException("Unable to tunnel through proxy. Proxy returns " + code)));
        }
        return ConnectionStep.ok(Stage.TCP, "Tunnel über Proxy zu " + endpoint + " steht"
                + (authenticated ? " (Anmeldung am Proxy: BASIC)" : ""));
    }

    private boolean handshake(Socket socket, String host, int port, X509TrustManager trust,
                              boolean clientCertificate, Consumer<ConnectionStep> onStep) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust == null ? null : new TrustManager[] {trust}, null);
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, false)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                onStep.accept(ConnectionStep.ok(Stage.TLS, "Handshake mit " + host + " (" + tls.getSession().getProtocol()
                        + ", " + tls.getSession().getCipherSuite() + "); Serverzertifikat "
                        + certificate(tls.getSession().getPeerCertificates())));
                return true;
            }
        } catch (GeneralSecurityException e) {
            return stop(onStep, Stage.TLS, failed(Stage.TLS, e));
        } catch (IOException e) {
            ConnectionStep failed = failed(Stage.TLS, e);
            boolean trustProblem = ConnectionDiagnosis.hint(e) != null
                    && ConnectionDiagnosis.hint(e).startsWith("Java vertraut");
            if (clientCertificate && !trustProblem) {
                onStep.accept(new ConnectionStep(Stage.TLS, Status.WARNING, failed.message() + " (Test ohne Client-Zertifikat;"
                        + " ob der Server es annimmt, zeigt der Schritt Anmeldung)", failed.hint()));
                return true;
            }
            return stop(onStep, Stage.TLS, failed);
        }
    }

    private static boolean stop(Consumer<ConnectionStep> onStep, Stage failedStage, ConnectionStep failed) {
        onStep.accept(failed);
        for (Stage stage : Stage.values()) {
            if (stage.ordinal() > failedStage.ordinal() && stage.ordinal() <= Stage.TLS.ordinal()) {
                onStep.accept(ConnectionStep.skipped(stage, "nicht geprüft"));
            }
        }
        return false;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
            matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
            if (matched == 4 || head.toString(StandardCharsets.ISO_8859_1).endsWith("\n\n")) {
                break;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static String schemes(String head) {
        List<String> schemes = new ArrayList<>();
        head.lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith("proxy-authenticate:"))
                .map(line -> line.substring(line.indexOf(':') + 1).strip())
                .map(value -> value.split("\\s+", 2)[0])
                .forEach(schemes::add);
        return schemes.isEmpty() ? "" : "; Verfahren des Proxys: " + String.join(", ", schemes);
    }

    private static String certificate(Certificate[] chain) {
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return "unbekannt";
        }
        return "für " + commonName(leaf.getSubjectX500Principal().getName()) + ", ausgestellt von "
                + commonName(leaf.getIssuerX500Principal().getName()) + ", gültig bis " + leaf.getNotAfter()
                .toInstant();
    }

    private static String commonName(String dn) {
        Matcher matcher = COMMON_NAME.matcher(dn);
        return matcher.find() ? matcher.group(1) : dn;
    }

    private static byte[] utf8(char[] prefix, char[] password) {
        char[] all = new char[prefix.length + password.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(password, 0, all, prefix.length, password.length);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(all));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            Arrays.fill(encoded.array(), (byte) 0);
            return bytes;
        } finally {
            Arrays.fill(all, '\0');
        }
    }
}
