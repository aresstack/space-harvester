package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.UrlConnectionConfluenceTransport;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.application.signin.SignInFailures;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nachstellung von #571: der Einmalcode geht als Formular-POST über denselben synchronen Transport
 * ({@link UrlConnectionConfluenceTransport}, gebaut wie im {@code ConfluenceSourceProvider} mit einem
 * {@link HttpRoutePort}) wie die Bild-GETs. Ein Proxy verlangt BASIC; die Anmeldung kommt wie im Programm vom
 * prozessweiten {@link ProxyAuthenticator}. Je Test ein eigener Proxy, damit der Anmelde-Cache des JDK (je
 * Proxy-Adresse) keinen Versuch vorab anmeldet.
 *
 * <p>Befund: Über HTTPS ({@code CONNECT}-Tunnel, auch mit Client-Zertifikat) gelingen GET und POST; das JDK meldet
 * den Tunnel vor dem Senden des Formulars an. Über einen HTTP-Proxy ohne Tunnel gelingt der GET, der erste POST aber
 * scheitert intern mit {@link java.net.HttpRetryException}, weil der Transport den Inhalt mit fester Länge streamt und
 * das JDK nach der 407 nicht wiederholen kann; nach außen kommt die 407 des Proxys als Antwort. Der Fehler liegt in der unveränderten Kopie {@code enterprise-ai-sources};
 * die Korrektur steht als Patch für den Enterprise AI Client bereit. Hat das Programm vorher über denselben Proxy
 * schon eine Anfrage angemeldet (etwa den Bild-GET), meldet das JDK den POST vorab an und er gelingt.
 */
class ConfluencePostThroughProxyTest {

    private static final String USER = "proxy-nutzer";
    private static final String PASSWORD = "geheim-0815";
    private static final byte[] FORM = "otp=123456&atl_token=t".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static HttpServer http;
    private static HttpsServer https;
    private static HttpsServer mtls;

    private TestProxy proxy;
    private ProxyAuthenticator authenticator;

    @BeforeAll
    static void startServers() throws Exception {
        certificates = new TestCertificates(dir);
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        http.createContext("/", ConfluencePostThroughProxyTest::answer);
        http.start();
        https = httpsServer(false);
        mtls = httpsServer(true);
    }

    private static HttpsServer httpsServer(boolean needClientAuth) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(certificates.serverContext()) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", ConfluencePostThroughProxyTest::answer);
        server.start();
        return server;
    }

    @AfterAll
    static void stopServers() {
        http.stop(0);
        https.stop(0);
        mtls.stop(0);
    }

    @BeforeEach
    void start() throws IOException {
        received.clear();
        proxy = new TestProxy().requireBasic(USER, PASSWORD);
        authenticator = ProxyAuthenticator.install(ProxyCredentials.of(
                new ProxySettings.Authentication(USER, PASSWORD.toCharArray()), null));
        assertThat(authenticator).as("Standard-Authenticator gesetzt").isNotNull();
    }

    @AfterEach
    void stop() throws IOException {
        authenticator.uninstall();
        proxy.close();
    }

    /** Antwortet mit Methode und Länge des empfangenen Inhalts. */
    private static void answer(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        String line = exchange.getRequestMethod() + " " + body.length;
        received.add(line);
        byte[] reply = line.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, reply.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply);
        }
    }

    private UrlConnectionConfluenceTransport transport(SSLSocketFactory tls) {
        HttpRoutePort routes = target -> HttpRoute.proxy("127.0.0.1", proxy.port(), "test");
        UrlConnectionConfluenceTransport.Builder builder = UrlConnectionConfluenceTransport.builder().routes(routes)
                .connectTimeoutMillis(5_000).readTimeoutMillis(5_000);
        if (tls != null) {
            builder.sslSocketFactory(tls).anonymousSslSocketFactory(tls);
        }
        return builder.build();
    }

    private static URI target(String scheme, int port) {
        return URI.create(scheme + "://" + ("https".equals(scheme) ? "secure" : "confluence") + ".proxied.test:" + port + "/plugins/otp/verify");
    }

    private static SSLSocketFactory trustOnly() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new javax.net.ssl.TrustManager[] {certificates.harvesterTrustManager()}, null);
        return context.getSocketFactory();
    }

    private static SSLSocketFactory withClientCertificate() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(certificates.client)) {
            store.load(in, TestCertificates.CLIENT_PASSWORD.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, TestCertificates.CLIENT_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), new javax.net.ssl.TrustManager[] {certificates.harvesterTrustManager()},
                null);
        return context.getSocketFactory();
    }

    private static final Map<String, String> HEADERS = Map.of("Accept", "text/html");

    @Test
    void httpsGetThroughTheTunnelSignsInAtTheProxy() throws Exception {
        ConfluenceHttpResponse response = transport(trustOnly()).get(target("https", https.getAddress().getPort()),
                HEADERS, 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("GET 0");
        assertThat(proxy.headerBlocks()).filteredOn(head -> head.startsWith("CONNECT ")).hasSize(2)
                .last().satisfies(head -> assertThat(head).containsIgnoringCase("Proxy-Authorization: Basic "));
    }

    @Test
    void httpsPostThroughTheTunnelSignsInBeforeTheFormIsSent() throws Exception {
        ConfluenceHttpResponse response = transport(trustOnly()).postForm(target("https",
                https.getAddress().getPort()), HEADERS, FORM.clone(), 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("POST " + FORM.length);
        assertThat(proxy.requestLines()).allSatisfy(line -> assertThat(line).startsWith("CONNECT "));
    }

    @Test
    void httpsPostWithClientCertificateThroughTheTunnel() throws Exception {
        ConfluenceHttpResponse response = transport(withClientCertificate()).postForm(target("https",
                mtls.getAddress().getPort()), HEADERS, FORM.clone(), 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("POST " + FORM.length);
    }

    @Test
    void httpGetThroughThePlainProxyRetriesAfter407() throws Exception {
        ConfluenceHttpResponse response = transport(null).get(target("http", http.getAddress().getPort()), HEADERS,
                4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("GET 0");
        assertThat(proxy.requestLines()).hasSize(2);
    }

    /**
     * Früher der bekannte Fehler der Kopie {@code enterprise-ai-sources}: {@code setFixedLengthStreamingMode}
     * verhinderte die Wiederholung nach der 407 des Proxys, das Formular erreichte Confluence nie. Seit Upstream-Patch
     * 10 (enterprise-ai-client#217) wiederholt das JDK den ersten POST mit Proxy-Anmeldung, und das Formular kommt
     * einmal vollständig an.
     */
    @Test
    void firstHttpPostThroughThePlainProxyRetriesAfter407() throws Exception {
        URI target = target("http", http.getAddress().getPort());

        ConfluenceHttpResponse response = transport(null).postForm(target, HEADERS, FORM.clone(), 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("POST " + FORM.length);
        assertThat(proxy.requestLines()).hasSize(2);
    }

    @Test
    void httpPostAfterAnAuthenticatedGetIsSignedInInAdvance() throws Exception {
        UrlConnectionConfluenceTransport transport = transport(null);
        URI target = target("http", http.getAddress().getPort());
        transport.get(target, HEADERS, 4096);

        ConfluenceHttpResponse response = transport.postForm(target, HEADERS, FORM.clone(), 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).containsExactly("GET 0", "POST " + FORM.length);
    }

    /**
     * Wie im Programm ohne die Test-Property aus {@code build.gradle}: in einer eigenen JVM ohne
     * {@code jdk.http.auth.tunneling.disabledSchemes}. Gibt {@link ProxyAuthenticator#install} BASIC frei, bevor die
     * erste {@code HttpURLConnection} entsteht (wie {@code SpaceHarvesterApp.main} über
     * {@link ProxyAuthenticator#allowBasicTunneling()}), gelingt der POST durch den Tunnel.
     */
    @Test
    void inAFreshJvmBasicIsAllowedForTheTunnelBeforeTheFirstConnection() throws Exception {
        assertThat(child("early")).isEqualTo("RESULT ok 200");
        assertThat(received).containsExactly("POST " + FORM.length);
    }

    /**
     * Gegenprobe: entsteht die erste {@code HttpURLConnection} vor der Freigabe, hat das JDK BASIC für den Tunnel
     * schon abgeschaltet und fragt den Authenticator nicht; der POST scheitert am {@code CONNECT}. So sähe es aus,
     * wenn ein Programm {@link ProxyAuthenticator#allowBasicTunneling()} nicht beim Start ruft.
     */
    @Test
    void inAFreshJvmBasicIsRefusedForTheTunnelWhenTheJdkReadTheSettingEarlier() throws Exception {
        String result = child("late");

        assertThat(result).isEqualTo("RESULT java.io.IOException: Unable to tunnel through proxy. Proxy returns "
                + "\"HTTP/1.1 407 Proxy Authentication Required\"");
        assertThat(received).isEmpty();
        assertThat(SignInFailures.describe(new IOException(result.substring("RESULT java.io.IOException: ".length())),
                "secure.proxied.test")).isEqualTo("Proxy verlangt Anmeldung (HTTP 407) – Ziel secure.proxied.test");
    }

    private String child(String mode) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                "-Djdk.net.hosts.file=" + System.getProperty("jdk.net.hosts.file"), Child.class.getName(), mode,
                String.valueOf(proxy.port()), String.valueOf(https.getAddress().getPort()),
                certificates.harvesterTrust.toString());
        // Keine geerbten JVM-Optionen der Umgebung, die die Tunnel-Property schon setzen.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return output.lines().filter(line -> line.startsWith("RESULT ")).findFirst().orElse(output);
    }

    /** Die eigene JVM für {@link #child}: POST durch den Tunnel mit dem prozessweiten {@link ProxyAuthenticator}. */
    static final class Child {

        public static void main(String[] args) throws Exception {
            String mode = args[0];
            int proxyPort = Integer.parseInt(args[1]);
            int httpsPort = Integer.parseInt(args[2]);
            if (System.getProperty(ProxyAuthenticator.TUNNELING_DISABLED_SCHEMES) != null) {
                System.out.println("RESULT property already set");
                return;
            }
            if ("late".equals(mode)) {
                // Lädt die HttpURLConnection des JDK, die die Property dabei einmalig liest.
                new java.net.URL("http://127.0.0.1:9/").openConnection();
            }
            ProxyAuthenticator.install(ProxyCredentials.of(
                    new ProxySettings.Authentication(USER, PASSWORD.toCharArray()), null));
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(args[3]))) {
                store.load(in, TestCertificates.STORE_PASSWORD.toCharArray());
            }
            javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            UrlConnectionConfluenceTransport transport = UrlConnectionConfluenceTransport.builder()
                    .routes(target -> HttpRoute.proxy("127.0.0.1", proxyPort, "test"))
                    .sslSocketFactory(context.getSocketFactory()).connectTimeoutMillis(5_000)
                    .readTimeoutMillis(5_000).build();
            try {
                ConfluenceHttpResponse response = transport.postForm(target("https", httpsPort), HEADERS,
                        FORM.clone(), 4096);
                System.out.println("RESULT ok " + response.status());
            } catch (IOException e) {
                System.out.println("RESULT " + e);
            }
        }
    }
}
