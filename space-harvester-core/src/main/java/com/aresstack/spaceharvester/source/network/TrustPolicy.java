package com.aresstack.spaceharvester.source.network;

import com.aresstack.wintrust.CertificateTrustConfiguration;
import com.aresstack.wintrust.SystemTrustSslSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Vertrauensregel aus {@link TrustSettings} (#189), wie {@code TrustPolicy} im Enterprise AI Client: Die Quellen
 * liefert {@code win-trust-java} (JVM-Truststore, {@code Windows-ROOT}, Windows Root+CA), dazu die CA-Datei. Anders
 * als dort werden alle Vertrauensanker in einen PKIX-Trust-Manager der Java-Laufzeit übernommen: Er prüft Kette,
 * Gültigkeit und über {@code SSLEngine} (WebClient) auch den Hostnamen, was ein Verbund schlichter Trust-Manager
 * dort nicht täte. Die Regel wird nie prozessweit gesetzt; die Quellen bekommen den Trust-Manager je Verbindung.
 *
 * <p>Der Aufbau kann unter Windows Sekunden dauern (PowerShell-Export); {@link #deferred} baut deshalb erst bei der
 * ersten Prüfung und nie beim Start. {@link #problems} prüft nur die CA-Datei.
 */
public final class TrustPolicy {

    static final String JVM_SOURCE = "JVM-Truststore";
    static final String WINDOWS_ROOT_SOURCE = "Windows-ROOT";
    static final String WINDOWS_CA_SOURCE = "Windows Root+CA";
    static final String FILE_SOURCE = "CA-Datei";

    private static final Logger LOG = LoggerFactory.getLogger(TrustPolicy.class);

    private final List<String> sources;
    private final List<String> notices;
    private final List<String> diagnostics;
    private final X509ExtendedTrustManager trustManager;

    private TrustPolicy(List<String> sources, List<String> notices, List<String> diagnostics,
                        X509ExtendedTrustManager trustManager) {
        this.sources = List.copyOf(sources);
        this.notices = List.copyOf(notices);
        this.diagnostics = List.copyOf(diagnostics);
        this.trustManager = trustManager;
    }

    /**
     * Baut die Regel jetzt (blockierend; unter Windows mit PowerShell-Export, sofern eingeschaltet).
     *
     * @throws IllegalArgumentException wenn die CA-Datei nicht nutzbar ist (die Meldung nennt nie ihren Inhalt) oder
     *                                  keine eingeschaltete Quelle Zertifikate liefert; der JVM-Truststore springt nur
     *                                  ein, wenn er selbst eingeschaltet ist
     */
    public static TrustPolicy from(TrustSettings settings) {
        return build(settings, windows(System.getProperty("os.name", "")));
    }

    /** Die billige Prüfung für Start und Dialog: nur die CA-Datei, keine Windows-Speicher. */
    public static List<String> problems(TrustSettings settings) {
        List<String> problems = new ArrayList<>();
        if (!settings.useJvmDefault() && !settings.useWindowsRoot() && !settings.useWindowsCaStores()
                && settings.caCertificatesFile() == null) {
            problems.add("tls: mindestens eine Vertrauensquelle einschalten");
        }
        if (settings.caCertificatesFile() != null) {
            try {
                caCertificates(settings.caCertificatesFile());
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /**
     * Ein Trust-Manager, der die Regel bei der ersten Prüfung baut und dann behält; scheitert der Aufbau, scheitert
     * nur diese Prüfung (mit Grund), die nächste versucht es erneut.
     */
    public static X509ExtendedTrustManager deferred(TrustSettings settings) {
        return new Deferred(settings);
    }

    /**
     * Das Vertrauen für {@link com.aresstack.spaceharvester.source.TrustBundles#withDefault}: {@code null}, wenn nur
     * der JVM-Truststore gilt (dann bleibt alles wie ohne Einstellung), sonst {@link #deferred}.
     */
    public static X509ExtendedTrustManager defaultTrust(TrustSettings settings) {
        return settings == null || settings.jvmOnly() ? null : deferred(settings);
    }

    static TrustPolicy build(TrustSettings settings, boolean windows) {
        List<String> sources = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<X509Certificate> anchors = new ArrayList<>();
        if ((settings.useWindowsRoot() || settings.useWindowsCaStores()) && !windows) {
            notices.add("Windows-Zertifikatspeicher stehen nur unter Windows zur Verfügung; sie bleiben hier aus.");
        }
        boolean library = settings.useJvmDefault() || windows
                && (settings.useWindowsRoot() || settings.useWindowsCaStores());
        if (library) {
            CertificateTrustConfiguration configuration = CertificateTrustConfiguration.builder()
                    .useJvmDefault(settings.useJvmDefault())
                    .useWindowsRoot(settings.useWindowsRoot() && windows)
                    .useWindowsCaStores(settings.useWindowsCaStores() && windows)
                    .build();
            SystemTrustSslSocketFactory.Result result = SystemTrustSslSocketFactory.build(configuration);
            diagnostics.addAll(result.getDiagnostics());
            if (result.isJvmDefaultTrusted()) {
                sources.add(JVM_SOURCE);
            }
            if (result.isWindowsRootTrusted()) {
                sources.add(WINDOWS_ROOT_SOURCE);
            }
            if (result.isWindowsCaStoresTrusted()) {
                sources.add(WINDOWS_CA_SOURCE + " (" + result.getWindowsRootAnchorCount() + " Root, "
                        + result.getWindowsIntermediateCount() + " Zwischenzertifikate)");
            }
            X509TrustManager manager = result.getTrustManager();
            if (result.isFallbackToJvmDefault() || manager == null) {
                // Der JVM-Truststore gilt nur, wenn er eingeschaltet ist; sonst bliebe eine bewusste Einschränkung
                // unwirksam.
                if (settings.useJvmDefault()) {
                    notices.add("Keine Windows-Quelle nutzbar; es gilt der JVM-Truststore.");
                    manager = jvmTrustManager();
                    if (!sources.contains(JVM_SOURCE)) {
                        sources.add(JVM_SOURCE);
                    }
                } else {
                    notices.add("Keine der eingeschalteten Windows-Quellen ist nutzbar.");
                    manager = null;
                }
            }
            if (manager != null) {
                anchors.addAll(List.of(manager.getAcceptedIssuers()));
            }
        }
        if (settings.caCertificatesFile() != null) {
            List<X509Certificate> file = caCertificates(settings.caCertificatesFile());
            anchors.addAll(file);
            sources.add(FILE_SOURCE + " (" + file.size() + " Zertifikate)");
        }
        if (anchors.isEmpty()) {
            // Nie stillschweigend auf den JVM-Truststore ausweichen: Wer ihn abschaltet, will ihm nicht vertrauen.
            throw new IllegalArgumentException("tls: keine der eingeschalteten Vertrauensquellen liefert Zertifikate"
                    + (notices.isEmpty() ? "" : " (" + String.join(" ", notices) + ")"));
        }
        TrustPolicy policy = new TrustPolicy(sources, notices, diagnostics, pkix(anchors));
        LOG.info("TLS-Vertrauen: {} ({} Anker){}", String.join(", ", sources), anchors.size(),
                notices.isEmpty() ? "" : "; " + String.join(" ", notices));
        return policy;
    }

    /** Die herangezogenen Vertrauensquellen, für Protokoll und Diagnose. */
    public List<String> sources() {
        return sources;
    }

    /** Hinweise aus dem Aufbau (Windows-Speicher nicht verfügbar, Rückfälle); keine Fehler. */
    public List<String> notices() {
        return notices;
    }

    /** Die Diagnosezeilen von {@code win-trust-java}. */
    public List<String> diagnostics() {
        return diagnostics;
    }

    public X509ExtendedTrustManager trustManager() {
        return trustManager;
    }

    @Override
    public String toString() {
        return "TrustPolicy" + sources;
    }

    /** Alle X.509-Zertifikate einer PEM-Datei (auch mehrere hintereinander) oder einer DER-Datei. */
    static List<X509Certificate> caCertificates(Path file) {
        List<X509Certificate> certificates = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                if (certificate instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("tls.ca-certificates-file: Datei nicht lesbar ("
                    + e.getClass().getSimpleName() + ")");
        } catch (CertificateException e) {
            throw new IllegalArgumentException("tls.ca-certificates-file: kein PEM- oder DER-Zertifikat");
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("tls.ca-certificates-file: Datei enthält kein Zertifikat");
        }
        return certificates;
    }

    static boolean windows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    private static X509TrustManager jvmTrustManager() {
        try {
            return trustManager(null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM-Truststore nicht nutzbar: " + e.getMessage(), e);
        }
    }

    private static X509ExtendedTrustManager pkix(List<X509Certificate> anchors) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate anchor : anchors) {
                store.setCertificateEntry("anchor-" + index++, anchor);
            }
            if (trustManager(store) instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
            throw new IllegalStateException("kein X.509-Trust-Manager mit Endpunktprüfung");
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Vertrauensanker nicht nutzbar (" + e.getClass().getSimpleName() + ")",
                    e);
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("kein X509TrustManager verfügbar");
    }

    /** Baut die Regel bei der ersten Prüfung; ein gescheiterter Aufbau wird nicht gemerkt. */
    private static final class Deferred extends X509ExtendedTrustManager {

        private final TrustSettings settings;
        private volatile X509ExtendedTrustManager delegate;

        Deferred(TrustSettings settings) {
            this.settings = settings;
        }

        private X509ExtendedTrustManager delegate() throws CertificateException {
            X509ExtendedTrustManager current = delegate;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (delegate == null) {
                    try {
                        delegate = from(settings).trustManager();
                    } catch (RuntimeException e) {
                        throw new CertificateException("TLS-Vertrauensquellen nicht nutzbar: " + e.getMessage(), e);
                    }
                }
                return delegate;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate().checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate().checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate().checkServerTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate().checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate().checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate().checkClientTrusted(chain, authType, engine);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            try {
                return delegate().getAcceptedIssuers();
            } catch (CertificateException e) {
                return new X509Certificate[0];
            }
        }

        @Override
        public String toString() {
            return "TrustPolicy.deferred[" + settings.describe() + "]";
        }
    }
}
