package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.TrustBundles;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Locale;
import java.util.Map;

/**
 * Vertrauensquellen für {@code trust-bundle} einer Quelle, gelesen wie {@code spring.ssl.bundle.*} beim Dienst: nur
 * der Truststore zählt, ein Keystore darin bleibt unbeachtet (das Client-Zertifikat steht unter
 * {@code client-certificate}).
 *
 * <p>Windows- und JVM-Zertifikatspeicher verwaltet die App nicht selbst: Quellen ohne Bundle nutzen das Vertrauen des
 * Core aus {@code space-harvester.network.tls} ({@code TrustSettings}/{@code TrustPolicy}, #189). Das frühere
 * eingebaute Bundle {@value #WINDOWS} meint dasselbe; die App schreibt es nicht mehr und reicht dafür
 * {@code legacyWindows} durch. Ein gleichnamiges Bundle unter {@code spring.ssl.bundle} hat Vorrang.
 */
public final class TrustStores {

    /** Name des früheren eingebauten Bundles; gilt als „wie alle Quellen“ ({@code network.tls}). */
    public static final String WINDOWS = "windows";

    private TrustStores() {
    }

    /**
     * @param legacyWindows Trust-Manager für Quellen, deren Datei noch {@code trust-bundle: windows} nennt, gebaut vom
     *                      Core aus {@code network.tls}
     */
    public static TrustBundles of(Map<String, AppSettings.TrustStoreSettings> stores, X509TrustManager legacyWindows) {
        return name -> {
            AppSettings.TrustStoreSettings settings = stores.get(name);
            if (settings == null && WINDOWS.equals(name)) {
                return legacyWindows;
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

    /** Ob {@code bundle} für die Oberfläche „wie alle Quellen“ heißt: keins oder das frühere {@value #WINDOWS}. */
    public static boolean followsNetwork(String bundle, Map<String, AppSettings.TrustStoreSettings> stores) {
        return bundle == null || WINDOWS.equals(bundle) && !stores.containsKey(WINDOWS);
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
}
