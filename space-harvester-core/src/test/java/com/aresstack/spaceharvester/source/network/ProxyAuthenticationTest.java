package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog.AsyncTransport;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.net.Authenticator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Anmeldung am Proxy mit BASIC (#189): im WebClient-Transport je Verbindung (auch im {@code CONNECT}-Tunnel) und für
 * {@code HttpURLConnection} über den prozessweiten {@link ProxyAuthenticator}. Jeder Test hat einen eigenen Proxy,
 * damit der Anmelde-Cache des JDK (je Proxy-Adresse) nichts zwischen Tests trägt.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProxyAuthenticationTest {

    private static final String PASSWORD = "geheim-4711";

    private TestProxy proxy;
    private FakeConfluence fake;
    private FakeConfluenceServer confluence;
    private ConfiguredSourceCatalog catalog;

    @BeforeEach
    void start() throws Exception {
        proxy = new TestProxy().requireBasic("proxy-nutzer", PASSWORD);
        fake = new FakeConfluence();
        fake.space("DEV", "Entwicklung");
        fake.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        fake.homepage("DEV", "100");
        confluence = new FakeConfluenceServer(fake);
    }

    @AfterEach
    void stop() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
        confluence.close();
        proxy.close();
    }

    private ProxySettings settings(ProxySettings.Authentication authentication) {
        return new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "127.0.0.1", proxy.port(), null, null, null,
                null, authentication);
    }

    /** Anonym über HTTP, Ziel {@code *.test} zeigt auf Loopback und geht so nicht an der Proxy-Route vorbei. */
    private HarvestSource source(ProxySettings settings, SourceSecrets provider) {
        URI base = URI.create(confluence.baseUrl().toString().replace("127.0.0.1", "confluence.direct.test"));
        catalog = ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null, base, null,
                        Map.of())), "test", TrustBundles.NONE, settings, AsyncTransport.WEB_CLIENT,
                ConfiguredSourceCatalog.DEFAULT_ADAPTERS, provider);
        return catalog.find("wiki").orElseThrow();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bothTransportsSignInAtTheProxyWithTheConfiguredCredentials(CapturedOutput output) throws Exception {
        HarvestSource source = source(settings(new ProxySettings.Authentication("proxy-nutzer",
                PASSWORD.toCharArray())), null);

        // asynchron: WebClient im CONNECT-Tunnel
        assertThat(source.storedPageAsync("100").toCompletableFuture().get(10, TimeUnit.SECONDS).payload())
                .isNotEmpty();
        // synchron: HttpURLConnection über den Standard-Authenticator
        assertThat(source.partitions()).extracting(space -> space.key()).contains("DEV");

        assertThat(proxy.requestLines()).anySatisfy(line -> assertThat(line).startsWith("CONNECT "))
                .anySatisfy(line -> assertThat(line).startsWith("GET http://confluence.direct.test:"));
        assertThat(proxy.headerBlocks()).filteredOn(head -> head.startsWith("CONNECT ")).allSatisfy(head -> assertThat(
                head).containsIgnoringCase("Proxy-Authorization: " + basic("proxy-nutzer", PASSWORD)));
        assertThat(proxy.headerBlocks()).filteredOn(head -> head.startsWith("GET ")).anySatisfy(head -> assertThat(
                head).containsIgnoringCase("Proxy-Authorization: " + basic("proxy-nutzer", PASSWORD)));
        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(basic("proxy-nutzer", PASSWORD));
        assertThat(fake.requestHeaders()).allSatisfy(headers -> assertThat(headers.keySet())
                .noneMatch(name -> name.equalsIgnoreCase("Proxy-Authorization")));
    }

    @Test
    void thePasswordComesFromTheProgramsProviderOnFirstUse() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SourceSecrets provider = ref -> {
            asked.incrementAndGet();
            return ref.equals(ProxySettings.SECRET_REF)
                    ? Optional.of(new SourceSecrets.Credential("proxy-nutzer", PASSWORD.toCharArray()))
                    : Optional.empty();
        };
        HarvestSource source = source(settings(new ProxySettings.Authentication(null, null)), provider);
        assertThat(asked).hasValue(0);

        assertThat(source.storedPageAsync("100").toCompletableFuture().get(10, TimeUnit.SECONDS).payload())
                .isNotEmpty();

        assertThat(asked.get()).isPositive();
        assertThat(proxy.headerBlocks()).anySatisfy(head -> assertThat(head)
                .containsIgnoringCase("Proxy-Authorization: " + basic("proxy-nutzer", PASSWORD)));
    }

    @Test
    void withoutCredentialsTheProxyRefusesAndNothingGoesDirect() {
        HarvestSource source = source(settings(null), null);

        assertThat(source.storedPageAsync("100").toCompletableFuture()).failsWithin(10, TimeUnit.SECONDS);
        assertThatThrownBy(source::partitions).isInstanceOf(RuntimeException.class);
        assertThat(fake.requestHeaders()).isEmpty();
        assertThat(Authenticator.getDefault()).isNull();
    }

    @Test
    void closingTheCatalogRemovesTheProcessWideSignIn() {
        source(settings(new ProxySettings.Authentication("proxy-nutzer", PASSWORD.toCharArray())), null);
        assertThat(Authenticator.getDefault()).isInstanceOf(ProxyAuthenticator.class);

        catalog.close();
        catalog = null;

        assertThat(Authenticator.getDefault()).isNull();
    }

    @Test
    void settingsShowNoPasswordAndRejectSignInWithoutProxy() {
        ProxySettings settings = settings(new ProxySettings.Authentication("proxy-nutzer", PASSWORD.toCharArray()));

        assertThat(settings.toString()).doesNotContain(PASSWORD);
        assertThat(ProxyRoutes.from(settings).describe()).contains("Anmeldung BASIC").doesNotContain(PASSWORD);
        assertThat(ProxyRoutes.validationProblems(ProxySettings.disabled().withAuthentication(
                ProxySettings.Authentication.basic("proxy-nutzer")), false))
                .contains("auth gilt nur mit Proxy, nicht mit mode disabled");
        assertThat(ProxyRoutes.validationProblems(settings(ProxySettings.Authentication.basic("a:b")), false))
                .contains("auth.username darf für BASIC keinen Doppelpunkt enthalten");
    }
}
