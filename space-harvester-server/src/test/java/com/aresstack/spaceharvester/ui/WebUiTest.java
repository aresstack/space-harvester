package com.aresstack.spaceharvester.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;

/**
 * Die optionale Weboberfläche (#17): statische Dateien ohne Token ladbar, ohne Zugangsdaten im Code, Daten nur über die
 * API mit Bearer-Token, abschaltbar.
 */
class WebUiTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static HttpResponse<String> get(Environment environment, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    class Enabled {

        @Autowired
        private Environment environment;

        @Test
        void servesTheStaticAssetsWithoutToken() throws Exception {
            HttpResponse<String> index = get(environment, "/ui/");
            assertThat(index.statusCode()).isEqualTo(200);
            assertThat(index.body()).contains("<title>Space Harvester</title>", "app.js");
            assertThat(get(environment, "/ui/app.js").statusCode()).isEqualTo(200);
            assertThat(get(environment, "/ui/app.css").statusCode()).isEqualTo(200);
            assertThat(get(environment, "/ui").statusCode()).isEqualTo(302);
        }

        @Test
        void assetsHoldNoCredentialsAndSendTheTokenOnlyAsHeader() throws Exception {
            String script = get(environment, "/ui/app.js").body();
            assertThat(script).contains("Authorization: `Bearer ${memoryToken}`", "credentials: 'omit'")
                    .doesNotContain(TestClient.TOKEN, "localStorage", "document.cookie");
            assertThat(get(environment, "/ui/").headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(policy -> assertThat(policy).contains("default-src 'self'"));
        }

        @Test
        void dataStillRequiresTheBearerToken() throws Exception {
            assertThat(get(environment, "/api/v1/sources").statusCode()).isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "space-harvester.ui.enabled=false")
    class Disabled {

        @Autowired
        private Environment environment;

        @Test
        void hasNoUserInterface() throws Exception {
            // Ohne eigene Filterkette fällt /ui/** unter die API-Kette: nichts ist ohne Token ausgeliefert.
            assertThat(get(environment, "/ui/").statusCode()).isEqualTo(401);
            assertThat(get(environment, "/ui/index.html").statusCode()).isEqualTo(401);
            assertThat(get(environment, "/ui/app.js").statusCode()).isEqualTo(401);
        }
    }
}
