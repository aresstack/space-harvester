package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Angelo 23.11.: Proxy je Verbindung wählbar, Vorgabe direkt wie MainframeMate. */
class NdvNetworkTest {

    private static final NdvProfile PROFILE = new NdvProfile("ndv.example.invalid", 8011, "", true);

    @Test
    void withoutAProxyInTheSettingsTheWayIsDirect() throws Exception {
        assertThat(NdvNetwork.route(ProxySettings.disabled(), PROFILE)).isEqualTo(Proxy.NO_PROXY);
    }

    @Test
    void aFixedProxyTunnelsTheConnection() throws Exception {
        ProxySettings proxy = new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "proxy.example.invalid", 3128,
                List.of(), null, null, null);
        Proxy route = NdvNetwork.route(proxy, PROFILE);
        assertThat(route.type()).isEqualTo(Proxy.Type.HTTP);
        InetSocketAddress address = (InetSocketAddress) route.address();
        assertThat(address.getHostString()).isEqualTo("proxy.example.invalid");
        assertThat(address.getPort()).isEqualTo(3128);
    }

    @Test
    void theProfileIsDirectUnlessChosen() {
        assertThat(new NdvProfile("ndv.example.invalid", 8011).useProxy()).isFalse();
        assertThat(new NdvProfile("ndv.example.invalid", 8011, "LAGER").useProxy()).isFalse();
    }
}
