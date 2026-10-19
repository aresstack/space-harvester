package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.source.network.ReconfigurableTrustManager;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import javax.net.ssl.X509TrustManager;

/**
 * Liefert die Server-Vertrauensquellen ({@code trust-bundle}) einer Quelle nach Namen, z. B. aus den Spring-SSL-Bundles
 * ({@code spring.ssl.bundle.*}). Getrennt vom Client-Zertifikat: Das Bundle trägt nur vertrauenswürdige CAs.
 * Dazu kommt das Vertrauen für Quellen und KI-Dienst ohne eigenes Bundle ({@link #defaultTrust}, #189).
 */
@FunctionalInterface
public interface TrustBundles {

    /** Ohne Bundles: jede Quelle mit {@code trust-bundle} ist fehlerhaft konfiguriert. */
    TrustBundles NONE = name -> {
        throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht konfiguriert");
    };

    /**
     * @throws IllegalArgumentException wenn es das Bundle nicht gibt oder es keinen X.509-Trust-Manager hat
     */
    X509TrustManager trustManager(String name);

    /**
     * Das Vertrauen für Verbindungen ohne {@code trust-bundle}; {@code null} heißt JVM-Truststore.
     */
    default X509TrustManager defaultTrust() {
        return null;
    }

    /**
     * Stellt das Vertrauen ohne Bundle auf {@code trust} um (ADR 0015); wirkt ab dem nächsten Handshake auch für
     * Verbindungen, die {@link #defaultTrust} schon übernommen haben.
     *
     * @return {@code false}, wenn diese Bundles das Vertrauen ohne Bundle nicht umstellen können (nur
     *         {@link #reconfigurable} kann es)
     * @throws IllegalArgumentException mit den Problemen der Einstellungen; dann bleibt das bisherige Vertrauen
     */
    default boolean reconfigure(TrustSettings trust) {
        return false;
    }

    /**
     * Dieselben Bundles mit einem Vertrauen ohne Bundle, das {@link #reconfigure} zur Laufzeit umstellt: Anders als
     * {@link #withDefault} ist {@link #defaultTrust} nie {@code null}, sondern ein {@link ReconfigurableTrustManager},
     * der je Handshake die zuletzt gesetzte Regel liest.
     *
     * @throws IllegalArgumentException mit den Problemen von {@code trust}
     */
    static TrustBundles reconfigurable(TrustBundles named, TrustSettings trust) {
        ReconfigurableTrustManager fallback = new ReconfigurableTrustManager(trust);
        return new TrustBundles() {
            @Override
            public X509TrustManager trustManager(String name) {
                return named.trustManager(name);
            }

            @Override
            public X509TrustManager defaultTrust() {
                return fallback;
            }

            @Override
            public boolean reconfigure(TrustSettings next) {
                fallback.reconfigure(next);
                return true;
            }
        };
    }

    /**
     * Dieselben Bundles mit {@code fallback} als {@link #defaultTrust}; {@code fallback == null} gibt {@code named}
     * unverändert zurück.
     */
    static TrustBundles withDefault(TrustBundles named, X509TrustManager fallback) {
        if (fallback == null) {
            return named;
        }
        return new TrustBundles() {
            @Override
            public X509TrustManager trustManager(String name) {
                return named.trustManager(name);
            }

            @Override
            public X509TrustManager defaultTrust() {
                return fallback;
            }
        };
    }
}
