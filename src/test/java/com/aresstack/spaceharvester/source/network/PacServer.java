package com.aresstack.spaceharvester.source.network;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Liefert ein PAC-Skript unter {@code /proxy.pac}; ohne Skript antwortet er mit 404. */
final class PacServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();
    private volatile String script;

    PacServer(String script) throws IOException {
        this.script = script;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/proxy.pac", exchange -> {
            downloads.incrementAndGet();
            String current = this.script;
            if (current == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] body = current.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/x-ns-proxy-autoconfig");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/proxy.pac");
    }

    void script(String value) {
        script = value;
    }

    int downloads() {
        return downloads.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
