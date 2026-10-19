package com.aresstack.spaceharvester.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Geht alle registrierten Routen unter {@code /api} durch, auch künftige: Jede verlangt eine Anmeldung, und jede, die
 * eine Quelle adressiert, prüft Rechte über den {@code AccessContext} und gibt einem Client ohne Freigabe nichts heraus.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApiRouteCoverageTest {

    private static final String NOBODY_TOKEN = "nobody-token-c41e07";

    /** Routen ohne Quellenbezug, die jeder angemeldete Client lesen darf. */
    // Die Suche ohne sourceId ist global (#114): 200, aber nur Treffer freigegebener Quellen, hier keine.
    // Sammellöschungen betreffen nur eigene Aufträge: 200 mit {"deleted": 0}.
    // Die Adressauflösung ist ebenso global (#178): 200 ohne Treffer.
    // Der regelmäßige Abgleich (ADR 0014) zeigt nur Spaces mit sync-Freigabe: 200 mit leerer Liste.
    // Die progressive Suche (ADR 0017) ist ebenso global; mit wait=true 200 ohne Treffer.
    private static final String PROGRESSIVE = "/api/v1/search/progressive";
    // Begriffe und ihre Fundstellen (ADR 0019) sind global wie die Suche: 200 ohne Einträge.
    // Anmeldungen (ADR 0018) zeigen nur solche, auf die eigene Aufträge warten: 200 mit leerer Liste.
    private static final Set<String> WITHOUT_SOURCE = Set.of("/api/v1/info", "/api/v1/sources", "/api/v1/search",
            "/api/v1/exports", "/api/v1/syncs", "/api/v1/addresses", "/api/v1/reconciliations",
            "/api/v1/sign-ins", PROGRESSIVE, "/api/v1/terms", "/api/v1/terms/occurrences");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "https://wiki.invalid");
        registry.add("space-harvester.security.clients[0].id", () -> "nobody");
        registry.add("space-harvester.security.clients[0].token-sha256", () -> ClientTokens.sha256Hex(NOBODY_TOKEN));
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void everyApiRouteRequiresAuthentication() throws Exception {
        List<Route> routes = apiRoutes();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            assertThat(send(route, null).statusCode()).as("%s ohne Token", route).isEqualTo(401);
        }
    }

    @Test
    void everyRouteWithSourceDeclaresTheCaller() {
        for (Route route : apiRoutes()) {
            if (takesSourceId(route)) {
                assertThat(Arrays.stream(route.handler().getMethodParameters())
                        .anyMatch(parameter -> parameter.getParameterType() == AccessContext.class))
                        .as("%s nimmt AccessContext und prüft über den AccessGuard", route).isTrue();
            }
        }
    }

    @Test
    void clientWithoutGrantsGetsNoDataFromAnyRoute() throws Exception {
        for (Route route : apiRoutes()) {
            HttpResponse<String> response = send(route, NOBODY_TOKEN);
            if (WITHOUT_SOURCE.contains(route.path())) {
                assertThat(response.statusCode()).as(route.toString()).isEqualTo(200);
                assertThat(response.body()).as(route.toString()).doesNotContain("\"wiki\"", "\"pageId\"");
            } else {
                assertThat(response.statusCode()).as("%s ohne Freigabe", route).isIn(403, 404);
                assertThat(response.body()).as(route.toString()).doesNotContain("\"pageId\"", "\"pages\"", "\"roots\"", "\"spaceKey\"");
            }
        }
    }

    private List<Route> apiRoutes() {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            for (String path : entry.getKey().getPatternValues()) {
                if (path.startsWith("/api/")) {
                    for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                        routes.add(new Route(method.name(), path, entry.getValue()));
                    }
                }
            }
        }
        return routes;
    }

    private HttpResponse<String> send(Route route, String token) throws Exception {
        String path = route.path().replace("{sourceId}", "wiki").replaceAll("\\{[^}]+}", "x1") + query(route);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .header("Content-Type", "application/json")
                .method(route.method(), HttpRequest.BodyPublishers.ofString(PROGRESSIVE.equals(route.path())
                        ? "{\"q\":\"x1\",\"wait\":true}" : "{}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Pflicht-Query-Parameter mit Platzhaltern, damit die Anfrage die Rechteprüfung erreicht. */
    private static String query(Route route) {
        List<String> pairs = new ArrayList<>();
        for (MethodParameter parameter : route.handler().getMethodParameters()) {
            RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
            if (param != null && param.required() && ValueConstants.DEFAULT_NONE.equals(param.defaultValue())) {
                String name = param.name().isEmpty() ? param.value() : param.name();
                if (name.isEmpty()) {
                    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                    name = parameter.getParameterName();
                }
                pairs.add(name + "=" + ("sourceId".equals(name) ? "wiki" : "x1"));
            }
        }
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    private static boolean takesSourceId(Route route) {
        return route.path().contains("{sourceId}") || query(route).contains("sourceId=");
    }

    private record Route(String method, String path, HandlerMethod handler) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
