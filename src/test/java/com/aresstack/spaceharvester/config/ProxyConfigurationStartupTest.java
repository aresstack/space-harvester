package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** {@code space-harvester.network.proxy} wird gebunden und beim Start geprüft (#34). */
class ProxyConfigurationStartupTest {

    @Test
    void bindsProxySettingsWithKebabCaseModes() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0",
                        "--space-harvester.network.proxy.mode=manual-proxy",
                        "--space-harvester.network.proxy.proxy-host=proxy.example.org",
                        "--space-harvester.network.proxy.proxy-port=3128",
                        "--space-harvester.network.proxy.non-proxy-hosts=intranet.example.org,*.corp.example.org",
                        "--space-harvester.network.proxy.resolve-timeout=5s")) {
            ProxySettings proxy = context.getBean(SpaceHarvesterProperties.class).network().proxy().toSettings();

            assertThat(proxy.mode()).isEqualTo(ProxySettings.Mode.MANUAL_PROXY);
            assertThat(proxy.proxyHost()).isEqualTo("proxy.example.org");
            assertThat(proxy.proxyPort()).isEqualTo(3128);
            assertThat(proxy.nonProxyHosts()).containsExactly("intranet.example.org", "*.corp.example.org");
            assertThat(proxy.resolveTimeout()).hasSeconds(5);
        }
    }

    @Test
    void withoutConfigurationAllConnectionsGoDirect() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0")) {
            assertThat(context.getBean(SpaceHarvesterProperties.class).network().proxy().toSettings().mode())
                    .isEqualTo(ProxySettings.Mode.DISABLED);
        }
    }

    @Test
    void invalidProxySettingsPreventTheStart() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--space-harvester.network.proxy.mode=pac-url-manual")
                .close())
                .rootCause()
                .hasMessageContaining("Ungültige Netzwerkkonfiguration (space-harvester.network.proxy)")
                .hasMessageContaining("mode pac-url-manual braucht pac-url");
    }

    @Test
    void nativeWindowsModesThatTheLibraryDoesNotImplementCannotBeConfigured() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--space-harvester.network.proxy.mode=windows-native-route-resolver")
                .close())
                .hasStackTraceContaining("windows-native-route-resolver");
    }
}
