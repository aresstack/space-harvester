package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Ein abgerissener Inhalt wird ab dem letzten Byte mit Range fortgesetzt (Angelo 26.11.2026, WLAN), über einen
 * lokalen Testserver mit erfundenem Inhalt.
 */
public class UrlConnectionSharePointTransportResumeTest {

    private static final byte[] CONTENT = new byte[64 * 1024];

    static {
        for (int i = 0; i < CONTENT.length; i++) {
            CONTENT[i] = (byte) (i * 31);
        }
    }

    private HttpServer server;
    private final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
    private URI uri;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/datei", new HttpHandler() {
            private int calls;

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String range = exchange.getRequestHeaders().getFirst("Range");
                ranges.add(String.valueOf(range));
                exchange.getResponseHeaders().add("ETag", "\"erfunden-1\"");
                if (calls++ == 0) {
                    // Volle Länge angekündigt, dann bricht die Verbindung nach einem Viertel ab.
                    exchange.sendResponseHeaders(200, CONTENT.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(CONTENT, 0, CONTENT.length / 4);
                    out.flush();
                    exchange.close();
                    return;
                }
                long from = Long.parseLong(range.substring("bytes=".length(), range.indexOf('-')));
                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + (CONTENT.length - 1) + "/" + CONTENT.length);
                exchange.sendResponseHeaders(206, CONTENT.length - from);
                OutputStream out = exchange.getResponseBody();
                out.write(CONTENT, (int) from, CONTENT.length - (int) from);
                exchange.close();
            }
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/datei");
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private UrlConnectionSharePointTransport transport(int resumes) {
        return new UrlConnectionSharePointTransport(HttpRoutePort.direct(), null, "test", 5000, 5000, resumes);
    }

    @Test
    public void contentContinuesFromTheLastByte() throws IOException {
        SharePointTransport.Response response = transport(2).get(uri, null, null, Long.MAX_VALUE);

        assertEquals(200, response.status());
        assertArrayEquals(CONTENT, response.body());
        assertEquals(2, ranges.size());
        assertEquals("null", ranges.get(0));
        assertEquals("bytes=" + (CONTENT.length / 4) + "-", ranges.get(1));
    }

    @Test
    public void downloadContinuesFromTheLastByte() throws IOException {
        Path target = Files.createTempFile("sharepoint-resume", ".bin");
        try {
            SharePointTransport.Response response = transport(1).download(uri, null, target, Long.MAX_VALUE);

            assertEquals(200, response.status());
            assertArrayEquals(CONTENT, Files.readAllBytes(target));
            assertEquals(2, ranges.size());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test(expected = IOException.class)
    public void withoutResumesTheBreakIsReported() throws IOException {
        transport(0).get(uri, null, null, Long.MAX_VALUE);
    }
}
