package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.wintrust.CertificateTrustConfiguration;
import com.aresstack.wintrust.SystemTrustSslSocketFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vertrauensquellen für {@code trust-bundle} einer Quelle, gelesen wie {@code spring.ssl.bundle.*} beim Dienst: nur
 * der Truststore zählt, ein Keystore darin bleibt unbeachtet (das Client-Zertifikat steht unter
 * {@code client-certificate}).
 *
 * <p>Dazu kommt das eingebaute Bundle {@value #WINDOWS}: Serverzertifikate gelten, wenn der Java-Truststore oder der
 * Windows-Zertifikatspeicher (Root und CA, wie Browser und PowerShell) sie akzeptiert. Die Quellen liefert
 * win-trust-java wie im Enterprise AI Client ({@code TrustPolicy}); außerhalb von Windows bleibt nur der
 * Java-Truststore. Der Export der Windows-Speicher dauert Sekunden und läuft deshalb erst bei der ersten Prüfung
 * eines Serverzertifikats, nie beim Start. Ein gleichnamiges Bundle unter {@code spring.ssl.bundle} hat Vorrang.
 */
public final class TrustStores {

    /** Name des eingebauten Bundles für {@code trust-bundle}. */
    public static final String WINDOWS = "windows";

    private TrustStores() {
    }

    public static TrustBundles of(Map<String, AppSettings.TrustStoreSettings> stores) {
        return of(stores, windows());
    }

    static TrustBundles of(Map<String, AppSettings.TrustStoreSettings> stores, boolean windows) {
        return of(stores, () -> system(windows));
    }

    static TrustBundles of(Map<String, AppSettings.TrustStoreSettings> stores, Supplier<X509TrustManager> system) {
        // Ein Trust-Manager für alle Quellen mit "windows": Der Export der Windows-Speicher läuft einmal, nicht je Quelle.
        Deferred shared = new Deferred(system);
        return name -> {
            AppSettings.TrustStoreSettings settings = stores.get(name);
            if (settings == null && WINDOWS.equals(name)) {
                return shared;
            }
            if (settings == null) {
                throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht konfiguriert");
            }
            try {
                return trustManager(load(settings));
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht lesbar ("
                        + e.getClass().getSimpleName() + ")", e);
            }
        };
    }

    private static KeyStore load(AppSettings.TrustStoreSettings settings) throws IOException, GeneralSecurityException {
        if (settings.pem() != null) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream in = Files.newInputStream(settings.pem())) {
                int index = 0;
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("ca-" + index++, certificate);
                }
            }
            return store;
        }
        String type = settings.type();
        if (type == null || type.isBlank()) {
            String file = settings.location().getFileName().toString().toLowerCase(Locale.ROOT);
            type = file.endsWith(".p12") || file.endsWith(".pfx") ? "PKCS12" : "JKS";
        }
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(settings.location())) {
            store.load(in, settings.password() == null ? null : settings.password().toCharArray());
        }
        return store;
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("kein X.509-Trust-Manager");
    }

    /** JVM-Truststore plus Windows Root und CA (unter Windows), aufgebaut von win-trust-java. */
    static X509TrustManager system(boolean windows) {
        CertificateTrustConfiguration configuration = CertificateTrustConfiguration.builder()
                .useJvmDefault(true)
                .useWindowsRoot(windows)
                .useWindowsCaStores(windows)
                .build();
        X509TrustManager manager = SystemTrustSslSocketFactory.build(configuration).getTrustManager();
        if (manager == null) {
            throw new IllegalStateException("Windows-Zertifikatspeicher nicht nutzbar");
        }
        return manager;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Baut den eigentlichen Trust-Manager erst bei der ersten Prüfung und behält ihn; scheitert der Aufbau, scheitert
     * nur diese Prüfung, die nächste versucht es erneut.
     */
    static final class Deferred extends X509ExtendedTrustManager {

        private final Supplier<X509TrustManager> factory;
        private volatile X509TrustManager delegate;

        Deferred(Supplier<X509TrustManager> factory) {
            this.factory = factory;
        }

        private X509TrustManager delegate() throws CertificateException {
            X509TrustManager current = delegate;
            if (current == null) {
                synchronized (this) {
                    current = delegate;
                    if (current == null) {
                        try {
                            current = factory.get();
                        } catch (RuntimeException e) {
                            throw new CertificateException("Vertrauensquellen nicht nutzbar ("
                                    + e.getClass().getSimpleName() + ")", e);
                        }
                        delegate = current;
                    }
                }
            }
            return current;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate().checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate() instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, socket);
            } else {
                delegate().checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate() instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, engine);
            } else {
                delegate().checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate().checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate() instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, socket);
            } else {
                delegate().checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate() instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, engine);
            } else {
                delegate().checkClientTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            try {
                return delegate().getAcceptedIssuers();
            } catch (CertificateException e) {
                return new X509Certificate[0];
            }
        }
    }
}
