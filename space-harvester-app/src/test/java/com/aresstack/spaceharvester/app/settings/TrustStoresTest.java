package com.aresstack.spaceharvester.app.settings;

import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
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
        X509TrustManager system = new Accepting();
        X509TrustManager manager = TrustStores.of(Map.of(), () -> {
            built.incrementAndGet();
            return system;
        }).trustManager(TrustStores.WINDOWS);

        assertThat(built).hasValue(0);
        manager.checkServerTrusted(new X509Certificate[0], "RSA");
        manager.checkServerTrusted(new X509Certificate[0], "RSA");
        assertThat(built).hasValue(1);
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
