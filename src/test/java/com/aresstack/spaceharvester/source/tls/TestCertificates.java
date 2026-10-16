package com.aresstack.spaceharvester.source.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Erzeugt für Tests frische Schlüssel und selbstsignierte Zertifikate mit dem {@code keytool} des Test-JDK in einem
 * temporären Verzeichnis; im Repository liegt kein privater Schlüssel.
 */
public final class TestCertificates {

    public static final String STORE_PASSWORD = "changeit";
    public static final String CLIENT_PASSWORD = "client-geheim";
    public static final String CLIENT_ALIAS = "confluence-client";

    /** Serverschlüssel für 127.0.0.1 und {@code secure.proxied.test} (Proxy-Tests, siehe test-hosts). */
    public final Path server;
    /** Vom Server akzeptiertes Client-Zertifikat. */
    public final Path client;
    /** Ein Client-Zertifikat, das der Server nicht kennt. */
    public final Path stranger;
    /** Abgelaufenes Client-Zertifikat. */
    public final Path expired;
    /** Nur das Client-Zertifikat, ohne privaten Schlüssel. */
    public final Path certificateOnly;
    /** Truststore des Servers: kennt nur {@link #client}. */
    public final Path serverTrust;
    /** Truststore des Harvesters: kennt das Serverzertifikat. */
    public final Path harvesterTrust;

    public TestCertificates(Path dir) throws IOException, InterruptedException {
        server = dir.resolve("server.p12");
        client = dir.resolve("client.p12");
        stranger = dir.resolve("stranger.p12");
        expired = dir.resolve("expired.p12");
        certificateOnly = dir.resolve("certificate-only.p12");
        serverTrust = dir.resolve("server-trust.p12");
        harvesterTrust = dir.resolve("harvester-trust.p12");

        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1,dns:secure.proxied.test", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", server.toString(), "-storepass", STORE_PASSWORD);
        keyPair(client, "CN=space-harvester", "-validity", "2");
        keyPair(stranger, "CN=stranger", "-validity", "2");
        keyPair(expired, "CN=expired", "-startdate", "-10d", "-validity", "1");

        Path serverCert = dir.resolve("server.crt");
        Path clientCert = dir.resolve("client.crt");
        keytool("-exportcert", "-alias", "server", "-keystore", server.toString(), "-storepass", STORE_PASSWORD,
                "-rfc", "-file", serverCert.toString());
        keytool("-exportcert", "-alias", CLIENT_ALIAS, "-keystore", client.toString(), "-storepass", CLIENT_PASSWORD,
                "-rfc", "-file", clientCert.toString());
        importCert(serverTrust, "client", clientCert, STORE_PASSWORD);
        importCert(harvesterTrust, "server", serverCert, STORE_PASSWORD);
        importCert(certificateOnly, CLIENT_ALIAS, clientCert, CLIENT_PASSWORD);
    }

    /** TLS-Kontext des Testservers: Serverschlüssel und {@link #serverTrust}. */
    public SSLContext serverContext() throws IOException, GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load(server, STORE_PASSWORD), STORE_PASSWORD.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load(serverTrust, STORE_PASSWORD));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    /** Vertrauen des Harvesters in den Testserver. */
    public X509TrustManager harvesterTrustManager() throws IOException, GeneralSecurityException {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load(harvesterTrust, STORE_PASSWORD));
        return (X509TrustManager) trust.getTrustManagers()[0];
    }

    private static KeyStore load(Path file, String password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    private static void keyPair(Path store, String dname, String... validity)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", CLIENT_ALIAS, "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", dname, "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", CLIENT_PASSWORD));
        args.addAll(List.of(validity));
        keytool(args.toArray(String[]::new));
    }

    private static void importCert(Path store, String alias, Path cert, String password)
            throws IOException, InterruptedException {
        keytool("-importcert", "-noprompt", "-alias", alias, "-file", cert.toString(), "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", password);
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool " + args[0] + " fehlgeschlagen: " + output);
        }
    }
}
