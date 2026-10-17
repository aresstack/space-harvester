package com.aresstack.spaceharvester.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;

/**
 * Minimaler Azure-Key-Vault-Ersatz über HTTPS für Tests: listet Secrets und liefert ihre Werte nach der REST-API
 * 7.x. Verlangt keine Anmeldung, damit der Test ohne Azure-AD-Ersatz auskommt; die Authentifizierung gehört dem
 * Azure SDK.
 */
final class FakeKeyVault implements AutoCloseable {

    private final HttpsServer server;
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    FakeKeyVault(SSLContext tls) throws IOException {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", this::handle);
        server.start();
    }

    FakeKeyVault secret(String name, String value) {
        secrets.put(name, value);
        return this;
    }

    URI endpoint() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    List<String> requests() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
        requests.add(exchange.getRequestMethod() + " " + path);
        String body;
        int status = 200;
        if (path.equals("/secrets")) {
            StringBuilder items = new StringBuilder();
            for (String name : secrets.keySet()) {
                items.append(items.isEmpty() ? "" : ",").append("{\"id\":\"").append(endpoint()).append("secrets/")
                        .append(name).append("\",\"attributes\":{\"enabled\":true}}");
            }
            body = "{\"value\":[" + items + "],\"nextLink\":null}";
        } else if (path.startsWith("/secrets/")) {
            String name = path.substring("/secrets/".length()).split("/")[0];
            String value = secrets.get(name);
            if (value == null) {
                status = 404;
                body = "{\"error\":{\"code\":\"SecretNotFound\",\"message\":\"not found\"}}";
            } else {
                body = "{\"value\":\"" + value + "\",\"id\":\"" + endpoint() + "secrets/" + name
                        + "/0001\",\"attributes\":{\"enabled\":true}}";
            }
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
