package com.aresstack.spaceharvester.source.network;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Das Vertrauen ohne {@code trust-bundle}, das zur Laufzeit wechseln kann (ADR 0015): leitet jede Prüfung an die
 * Regel weiter, die {@link #reconfigure} zuletzt gesetzt hat. Wer diesen Trust-Manager in einen TLS-Kontext übernommen
 * hat, prüft also schon beim nächsten Handshake nach den neuen {@link TrustSettings}; laufende Verbindungen bleiben,
 * wie sie sind. Nur JVM-Truststore heißt hier der PKIX-Trust-Manager der JVM, sonst {@link TrustPolicy#deferred}.
 */
public final class ReconfigurableTrustManager extends X509ExtendedTrustManager {

    private volatile X509ExtendedTrustManager delegate;
    private volatile TrustSettings settings;

    /**
     * @throws IllegalArgumentException mit den Problemen aus {@link TrustPolicy#problems}
     */
    public ReconfigurableTrustManager(TrustSettings settings) {
        reconfigure(settings);
    }

    /**
     * Prüft ab dem nächsten Handshake nach {@code settings}; die Windows-Speicher werden wie bei
     * {@link TrustPolicy#deferred} erst bei der ersten Prüfung gelesen.
     *
     * @throws IllegalArgumentException mit den Problemen aus {@link TrustPolicy#problems}; dann bleibt die bisherige
     *                                  Regel
     */
    public void reconfigure(TrustSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        List<String> problems = TrustPolicy.problems(settings);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        this.delegate = settings.jvmOnly() ? jvm() : TrustPolicy.deferred(settings);
        this.settings = settings;
    }

    private static X509ExtendedTrustManager jvm() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) {
                    return extended;
                }
            }
            throw new IllegalStateException("kein X.509-Trust-Manager der JVM mit Endpunktprüfung");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM-Truststore nicht nutzbar (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    @Override
    public String toString() {
        return "ReconfigurableTrustManager[" + settings.describe() + "]";
    }
}
