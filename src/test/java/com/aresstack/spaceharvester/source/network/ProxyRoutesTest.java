package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.winproxy.ProxyDiagnostics;
import com.aresstack.winproxy.ProxyResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Routenentscheidung, Cache und Fail-Closed ohne PowerShell und ohne PAC-Server. */
class ProxyRoutesTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final List<String> resolved = new CopyOnWriteArrayList<>();

    private ProxyRoutes routes(ProxySettings settings, Function<String, ProxyResult> answer) {
        return ProxyRoutes.create(settings, new ProxyRoutes.Resolver() {
            @Override
            public ProxyResult resolve(String url) {
                resolved.add(url);
                return answer.apply(url);
            }

            @Override
            public ProxyDiagnostics diagnose(String url) {
                throw new UnsupportedOperationException();
            }
        }, now::get);
    }

    private static ProxySettings pac(List<String> nonProxyHosts, Duration timeout) {
        return new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, URI.create("http://pac.example/proxy.pac"), null,
                null, nonProxyHosts, timeout, Duration.ofMinutes(10), Duration.ofMinutes(1));
    }

    @Test
    void translatesLibraryResultsAndNeverTurnsErrorsIntoDirect() {
        ProxyRoutes routes = routes(pac(null, null), url -> {
            if (url.contains("proxied")) {
                return ProxyResult.proxy("proxy.example", 8080, "resolved");
            }
            if (url.contains("direct")) {
                return ProxyResult.direct("pac-direct");
            }
            if (url.contains("native")) {
                return ProxyResult.notImplemented("windows-native", "WinHTTP fehlt");
            }
            return ProxyResult.error("pac-download-failed", "HTTP 404");
        });

        assertThat(routes.routeFor(URI.create("https://proxied.example/rest")))
                .isEqualTo(HttpRoute.proxy("proxy.example", 8080, "resolved"));
        assertThat(routes.routeFor(URI.create("https://direct.example/")))
                .isEqualTo(HttpRoute.direct("pac-direct"));
        HttpRoute failed = routes.routeFor(URI.create("https://broken.example/"));
        assertThat(failed.isUnavailable()).isTrue();
        assertThat(failed.reason()).isEqualTo("pac-download-failed");
        assertThat(failed.detail()).isEqualTo("HTTP 404");
        assertThat(routes.routeFor(URI.create("https://native.example/")).isUnavailable()).isTrue();
    }

    @Test
    void httpsIsResolvedAndCachedPerOriginLikeBrowsers() {
        ProxyRoutes routes = routes(pac(null, null), url -> ProxyResult.proxy("proxy.example", 8080, "resolved"));

        routes.routeFor(URI.create("https://Confluence.Example.org/rest/api/space?limit=10"));
        routes.routeFor(URI.create("https://confluence.example.org:443/rest/api/content/1"));
        routes.routeFor(URI.create("https://confluence.example.org:8443/rest/api/space"));

        assertThat(resolved).containsExactly("https://confluence.example.org/",
                "https://confluence.example.org:8443/");
    }

    @Test
    void httpIsResolvedWithTheFullUrlBecausePacRulesMayDependOnThePath() {
        ProxyRoutes routes = routes(pac(null, null), url -> ProxyResult.direct("pac-direct"));

        routes.routeFor(URI.create("http://user:pw@wiki.example/a?x=1#frag"));
        routes.routeFor(URI.create("http://wiki.example/b"));
        routes.routeFor(URI.create("http://wiki.example/a?x=1"));

        assertThat(resolved).containsExactly("http://wiki.example/a?x=1", "http://wiki.example/b");
    }

    @Test
    void cachedRoutesExpireAndFailuresAreRetriedSooner() {
        ProxyRoutes routes = routes(pac(null, null), url -> url.contains("broken")
                ? ProxyResult.error("pac-evaluation-failed", "SyntaxError")
                : ProxyResult.direct("pac-direct"));
        URI ok = URI.create("https://ok.example/");
        URI broken = URI.create("https://broken.example/");

        routes.routeFor(ok);
        routes.routeFor(broken);
        now.addAndGet(Duration.ofSeconds(61).toMillis());
        routes.routeFor(ok);
        routes.routeFor(broken);
        now.addAndGet(Duration.ofMinutes(10).toMillis());
        routes.routeFor(ok);

        assertThat(resolved).containsExactly("https://ok.example/", "https://broken.example/",
                "https://broken.example/", "https://ok.example/");
    }

    @Test
    void slowResolutionBecomesUnavailableInsteadOfDirect() {
        ProxyRoutes routes = routes(pac(null, Duration.ofMillis(100)), url -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ProxyResult.direct("too-late");
        });

        HttpRoute route = routes.routeFor(URI.create("https://slow.example/"));

        assertThat(route.isUnavailable()).isTrue();
        assertThat(route.reason()).isEqualTo(ProxyRoutes.REASON_TIMEOUT);
    }

    @Test
    void resolverExceptionsBecomeUnavailable() {
        ProxyRoutes routes = routes(pac(null, null), url -> {
            throw new IllegalStateException("No language and polyglot implementation was found");
        });

        HttpRoute route = routes.routeFor(URI.create("https://confluence.example.org/"));

        assertThat(route.isUnavailable()).isTrue();
        assertThat(route.reason()).isEqualTo(ProxyRoutes.REASON_RESOLVER_FAILED);
        assertThat(route.detail()).contains("polyglot");
    }

    @Test
    void loopbackNonProxyHostsAndDisabledModeGoDirectWithoutAskingTheLibrary() {
        ProxyRoutes routes = routes(pac(List.of("intranet.example", "*.corp.example"), null),
                url -> ProxyResult.error("unexpected", "should not be asked"));

        assertThat(routes.routeFor(URI.create("http://127.0.0.1:8080/")).reason()).isEqualTo("loopback");
        assertThat(routes.routeFor(URI.create("http://localhost/")).reason()).isEqualTo("loopback");
        assertThat(routes.routeFor(URI.create("http://[::1]:9000/")).reason()).isEqualTo("loopback");
        assertThat(routes.routeFor(URI.create("https://intranet.example/")).reason()).isEqualTo("non-proxy-host");
        assertThat(routes.routeFor(URI.create("https://wiki.corp.example/")).reason()).isEqualTo("non-proxy-host");
        assertThat(resolved).isEmpty();

        ProxyRoutes disabled = routes(ProxySettings.disabled(), url -> ProxyResult.error("unexpected", ""));
        assertThat(disabled.routeFor(URI.create("https://confluence.example.org/")))
                .isEqualTo(HttpRoute.direct("disabled"));
        assertThat(resolved).isEmpty();
    }

    @Test
    void validatesModeSpecificSettings() {
        assertThat(ProxyRoutes.validationProblems(new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, null, 0,
                null, null, null, null), true)).containsExactly(
                "mode manual-proxy braucht proxy-host",
                "mode manual-proxy braucht proxy-port zwischen 1 und 65535");
        assertThat(ProxyRoutes.validationProblems(new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, null, null,
                null, null, null, null, null), true)).containsExactly("mode pac-url-manual braucht pac-url");
        assertThat(ProxyRoutes.validationProblems(new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL,
                URI.create("ftp://pac.example/proxy.pac"), null, null, null, null, null, null), true))
                .containsExactly("pac-url muss mit http://, https:// oder file:// beginnen");
        assertThat(ProxyRoutes.validationProblems(new ProxySettings(ProxySettings.Mode.DISABLED,
                URI.create("http://pac.example/proxy.pac"), "proxy", 8080, List.of(" "), Duration.ZERO, null, null),
                true)).containsExactly(
                "pac-url gilt nur für mode pac-url-manual",
                "proxy-host/proxy-port gelten nur für mode manual-proxy",
                "resolve-timeout muss positiv sein",
                "non-proxy-hosts enthält einen leeren Eintrag");
        assertThat(ProxyRoutes.validationProblems(new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null,
                "proxy.example", 8080, null, null, null, null), false)).isEmpty();
    }

    @Test
    void windowsModesAreRejectedOutsideWindows() {
        for (ProxySettings.Mode mode : List.of(ProxySettings.Mode.PAC_URL_POWERSHELL,
                ProxySettings.Mode.PAC_URL_WINDOWS_SETTINGS, ProxySettings.Mode.PAC_URL_WSCRIPT,
                ProxySettings.Mode.WINDOWS_STATIC_PROXY)) {
            ProxySettings settings = new ProxySettings(mode, null, null, null, null, null, null, null);
            assertThat(ProxyRoutes.validationProblems(settings, false)).singleElement().asString()
                    .contains("läuft nur unter Windows");
            assertThat(ProxyRoutes.validationProblems(settings, true)).isEmpty();
        }
    }

    @Test
    void everyConfigurableModeExistsInTheLibrary() {
        for (ProxySettings.Mode mode : ProxySettings.Mode.values()) {
            assertThat(ProxyRoutes.libraryMode(mode).name()).isEqualTo(mode.name());
        }
    }

    @Test
    void invalidSettingsFailFast() {
        assertThatThrownBy(() -> ProxyRoutes.from(new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, null, null,
                null, null, null, null, null)))
                .isInstanceOf(InvalidNetworkConfigurationException.class)
                .hasMessageContaining("space-harvester.network.proxy")
                .hasMessageContaining("mode pac-url-manual braucht pac-url");
    }

    @Test
    void describeShowsNoPacQuery() {
        ProxyRoutes routes = ProxyRoutes.create(new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL,
                URI.create("https://pac.example/proxy.pac?token=geheim"), null, null, List.of("*.corp"), null, null,
                null), new ProxyRoutes.Resolver() {
                    @Override
                    public ProxyResult resolve(String url) {
                        return null;
                    }

                    @Override
                    public ProxyDiagnostics diagnose(String url) {
                        return null;
                    }
                }, now::get);

        assertThat(routes.describe()).isEqualTo(
                "pac-url-manual https://pac.example/proxy.pac?…, ohne Proxy: [*.corp], Frist 20000 ms")
                .doesNotContain("geheim");
    }
}
