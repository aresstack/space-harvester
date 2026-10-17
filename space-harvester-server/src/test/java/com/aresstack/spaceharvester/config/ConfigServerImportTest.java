package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Optionaler Spring Cloud Config Server (#3) gegen einen nachgebildeten Server: Quellen kommen aus der Remote-YAML,
 * Platzhalter darin löst der Harvester lokal auf, ohne erreichbaren Server startet er mit der lokalen Konfiguration.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfigServerImportTest {

    private static final String PAT = "pat-aus-umgebung-71c2";

    private final FakeConfluence confluence = new FakeConfluence();
    private FakeConfluenceServer confluenceServer;
    private HttpServer configServer;
    private final List<String> configRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        confluence.space("DEV", "Entwicklung");
        confluenceServer = new FakeConfluenceServer(confluence);
        configServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        configServer.createContext("/", exchange -> {
            configRequests.add(exchange.getRequestURI().getPath());
            byte[] body = environment().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        configServer.start();
    }

    @AfterEach
    void stop() {
        configServer.stop(0);
        confluenceServer.close();
    }

    /** Antwort im Format des Config Servers ({@code GET /{application}/{profile}}). */
    private String environment() {
        return """
                {"name":"space-harvester","profiles":["config-server"],"label":null,"version":"1","state":null,
                 "propertySources":[{"name":"remote:space-harvester.yml","source":{
                   "space-harvester.sources[0].id":"remote-wiki",
                   "space-harvester.sources[0].type":"confluence",
                   "space-harvester.sources[0].base-url":"%s",
                   "space-harvester.sources[0].auth.type":"pat",
                   "space-harvester.sources[0].auth.token":"${CONFLUENCE_PAT}",
                   "space-harvester.sources[0].options.allowInsecureHttp":"true",
                   "server.shutdown":"immediate","precedence.check":"remote"}}]}
                """.formatted(confluenceServer.baseUrl());
    }

    @Test
    void sourcesComeFromTheConfigServerAndPlaceholdersAreResolvedLocally(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = run("http://127.0.0.1:" + configServer.getAddress().getPort())) {
            String port = context.getEnvironment().getRequiredProperty("local.server.port");

            assertThat(get(port, "/api/v1/sources/remote-wiki/spaces").statusCode()).isEqualTo(200);
            assertThat(confluence.requestHeaders().getLast().get("Authorization")).isEqualTo("Bearer " + PAT);
            assertThat(configRequests).contains("/space-harvester/config-server,local");
        }
        assertThat(output).doesNotContain(PAT);
    }

    @Test
    void precedenceBetweenRemoteAndLocalValues() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--spring.profiles.active=config-server,local",
                        "--space-harvester.security.mode=development",
                        "--SPRING_CLOUD_CONFIG_URI=http://127.0.0.1:" + configServer.getAddress().getPort(),
                        "--CONFLUENCE_PAT=" + PAT, "--precedence.check=kommandozeile")) {
            // Remote schlägt application.yml (server.shutdown: graceful), die Kommandozeile schlägt Remote.
            assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("immediate");
            assertThat(context.getEnvironment().getProperty("precedence.check")).isEqualTo("kommandozeile");
        }
    }

    @Test
    void unreachableConfigServerStartsWithTheLocalConfiguration() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0)) {
            unused = socket.getLocalPort();
        }
        try (ConfigurableApplicationContext context = run("http://127.0.0.1:" + unused)) {
            String port = context.getEnvironment().getRequiredProperty("local.server.port");

            HttpResponse<String> sources = get(port, "/api/v1/sources");
            assertThat(sources.statusCode()).isEqualTo(200);
            assertThat(sources.body()).doesNotContain("remote-wiki");
        }
    }

    @Test
    void withoutTheProfileTheConfigServerIsNeverAsked() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--spring.profiles.active=local", "--space-harvester.security.mode=development",
                        "--SPRING_CLOUD_CONFIG_URI=http://127.0.0.1:" + configServer.getAddress().getPort())) {
            assertThat(context.getEnvironment().getProperty("space-harvester.sources[0].id")).isNull();
        }
        assertThat(configRequests).isEmpty();
    }

    private ConfigurableApplicationContext run(String configServerUri) {
        return new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--spring.profiles.active=config-server,local",
                        "--space-harvester.security.mode=development",
                        "--SPRING_CLOUD_CONFIG_URI=" + configServerUri, "--CONFLUENCE_PAT=" + PAT,
                        "--spring.cloud.config.request-connect-timeout=500",
                        "--spring.cloud.config.request-read-timeout=1000");
    }

    private static HttpResponse<String> get(String port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

}
