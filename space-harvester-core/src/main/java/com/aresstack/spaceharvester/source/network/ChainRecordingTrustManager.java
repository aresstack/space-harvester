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
import javax.net.ssl.X509TrustManager;

/**
 * Merkt sich die Zertifikatskette, die der Server im Handshake schickt, und das Urteil des Vertrauens der Quelle,
 * ohne die Prüfung zu ändern: Jede Prüfung geht unverändert an den eigentlichen Trust-Manager, auch die
 * Hostnamenprüfung. Für den Zertifikatsexport einer Quelle (Strang ZX).
 */
public final class ChainRecordingTrustManager extends X509ExtendedTrustManager {

    private final X509TrustManager delegate;
    private volatile List<X509Certificate> chain = List.of();
    private volatile CertificateException rejection;
    private volatile boolean checked;

    /** @param delegate das Vertrauen der Quelle; {@code null} = JVM-Truststore */
    public ChainRecordingTrustManager(X509TrustManager delegate) {
        this.delegate = delegate == null ? jvmDefault() : delegate;
    }

    /** Die vom Server gesendete Kette in Server-Reihenfolge; leer, solange kein Handshake bis dahin kam. */
    public List<X509Certificate> chain() {
        return chain;
    }

    /** Ob der Server seine Kette geschickt hat und die Prüfung lief. */
    public boolean checked() {
        return checked;
    }

    /** Die Ablehnung durch das Vertrauen der Quelle; {@code null}, wenn sie die Kette annahm. */
    public CertificateException rejection() {
        return rejection;
    }

    /** Alle Vertrauensanker und mitgelieferten Zwischenzertifikate des Vertrauens der Quelle. */
    public List<X509Certificate> anchors() {
        return List.of(delegate.getAcceptedIssuers());
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        record(chain);
        try {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, socket);
            } else {
                delegate.checkServerTrusted(chain, authType);
            }
        } catch (CertificateException e) {
            rejection = e;
            throw e;
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        record(chain);
        try {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, engine);
            } else {
                delegate.checkServerTrusted(chain, authType);
            }
        } catch (CertificateException e) {
            rejection = e;
            throw e;
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        record(chain);
        try {
            delegate.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            rejection = e;
            throw e;
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("nur für Serverprüfung");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("nur für Serverprüfung");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("nur für Serverprüfung");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    private void record(X509Certificate[] presented) {
        chain = presented == null ? List.of() : List.of(presented);
        rejection = null;
        checked = true;
    }

    private static X509TrustManager jvmDefault() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM-Truststore nicht verfügbar", e);
        }
        throw new IllegalStateException("JVM-Truststore ohne X.509-Trust-Manager");
    }
}
