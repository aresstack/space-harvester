package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** Nur noch {@code spring.ssl.bundle.*}; Windows- und JVM-Speicher kommen aus {@code network.tls} des Core (#189). */
class TrustStoresTest {

    @Test
    void theFormerWindowsBundleMeansTheTrustOfAllSources() {
        X509TrustManager network = TrustPolicy.deferred(TrustSettings.jvm());

        TrustBundles bundles = TrustStores.of(Map.of(), network);

        assertThat(bundles.trustManager(TrustStores.WINDOWS)).isSameAs(network);
        assertThat(TrustStores.followsNetwork(null, Map.of())).isTrue();
        assertThat(TrustStores.followsNetwork(TrustStores.WINDOWS, Map.of())).isTrue();
        assertThat(TrustStores.followsNetwork("firma", Map.of())).isFalse();
    }

    @Test
    void aConfiguredBundleNamedWindowsStillWins() {
        Map<String, AppSettings.TrustStoreSettings> stores = Map.of(TrustStores.WINDOWS,
                new AppSettings.TrustStoreSettings(TrustStores.WINDOWS, Path.of("fehlt.p12"), null, null, null));

        assertThat(TrustStores.followsNetwork(TrustStores.WINDOWS, stores)).isFalse();
        assertThatThrownBy(() -> TrustStores.of(stores, null).trustManager(TrustStores.WINDOWS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht lesbar");
    }

    @Test
    void otherNamesStillNeedAConfiguredBundle() {
        assertThatThrownBy(() -> TrustStores.of(Map.of(), null).trustManager("firma"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SSL-Bundle 'firma' ist nicht konfiguriert");
    }
}
