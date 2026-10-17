package com.aresstack.spaceharvester.source;

import javax.net.ssl.X509TrustManager;

/**
 * Liefert die Server-Vertrauensquellen ({@code trust-bundle}) einer Quelle nach Namen, z. B. aus den Spring-SSL-Bundles
 * ({@code spring.ssl.bundle.*}). Getrennt vom Client-Zertifikat: Das Bundle trägt nur vertrauenswürdige CAs.
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
}
