package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.TrustBundles;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustStoresTest {

    @Test
    void theWindowsBundleIsBuiltOnFirstUseAndKept() throws Exception {
        AtomicInteger built = new AtomicInteger();
        X509TrustManager system = new AcceptingExtended();
        TrustBundles bundles = TrustStores.of(Map.of(), () -> {
            built.incrementAndGet();
            return system;
        });
        X509TrustManager manager = bundles.trustManager(TrustStores.WINDOWS);
        X509TrustManager other = bundles.trustManager(TrustStores.WINDOWS);

        assertThat(built).hasValue(0);
        manager.checkServerTrusted(new X509Certificate[0], "RSA");
        other.checkServerTrusted(new X509Certificate[0], "RSA");
        assertThat(built).as("einmal für alle Quellen").hasValue(1);
    }

    @Test
    void aFailingBuildFailsOnlyThatCheck() {
        AtomicInteger built = new AtomicInteger();
        X509TrustManager manager = TrustStores.of(Map.of(), () -> {
            if (built.incrementAndGet() == 1) {
                throw new IllegalStateException("PowerShell nicht erreichbar");
            }
            return new Accepting();
        }).trustManager(TrustStores.WINDOWS);

        assertThatThrownBy(() -> manager.checkServerTrusted(new X509Certificate[0], "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("IllegalStateException");
        assertThat(manager.getAcceptedIssuers()).isEmpty();
    }

    @Test
    void outsideWindowsOnlyTheJavaTruststoreCounts() {
        X509TrustManager manager = TrustStores.system(false);

        assertThat(manager.getAcceptedIssuers()).isNotEmpty();
    }

    @Test
    void otherNamesStillNeedAConfiguredBundle() {
        assertThatThrownBy(() -> TrustStores.of(Map.of(), false).trustManager("firma"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SSL-Bundle 'firma' ist nicht konfiguriert");
    }

    /**
     * Über den asynchronen Weg (SSLEngine, wie WebClient) muss der Hostname geprüft werden, auch wenn darunter ein
     * schlichter X509TrustManager steckt wie der Verbund aus win-trust-java.
     */
    @Test
    void theAsyncPathChecksTheHostnameAboveAPlainTrustManager(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, "changeit".toCharArray());
        }
        X509Certificate certificate = (X509Certificate) store.getCertificate("server");

        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            // Nur Anker, keine eigene Hostnamenprüfung: so verhält sich der Verbund aus win-trust-java.
            X509TrustManager plain = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("nicht für Clients");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (!chain[0].equals(certificate)) {
                        throw new CertificateException("unbekannt");
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[] {certificate};
                }
            };
            X509TrustManager windows = TrustStores.of(Map.of(), () -> plain).trustManager(TrustStores.WINDOWS);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, new TrustManager[] {windows}, null);
            HttpClient http = HttpClient.newBuilder().sslContext(client).connectTimeout(Duration.ofSeconds(10))
                    .build();
            int port = server.getAddress().getPort();

            HttpResponse<Void> ok = http.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertThat(ok.statusCode()).isEqualTo(204);
            // Gleicher Server, gleiches Zertifikat, aber eine Adresse, für die es nicht ausgestellt ist.
            assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(
                    URI.create("https://127.0.0.1:" + port + "/")).build(), HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(SSLHandshakeException.class);
        } finally {
            server.stop(0);
        }
    }

    private static final class AcceptingExtended extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static final class Accepting implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
