package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Ein abgerissener Jira-Anhang wird ab dem letzten Byte mit Range fortgesetzt (Angelo 26.11.2026, WLAN: „zentral bei allen
 * Downloads“), über einen lokalen Testserver mit erfundenem Inhalt.
 */
public class UrlConnectionJiraTransportResumeTest {

    private static final byte[] CONTENT = new byte[64 * 1024];

    static {
        for (int i = 0; i < CONTENT.length; i++) {
            CONTENT[i] = (byte) (i * 17);
        }
    }

    private HttpServer server;
    private final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> cookies = Collections.synchronizedList(new ArrayList<String>());
    private URI uri;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/anhang", new HttpHandler() {
            private int calls;

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String range = exchange.getRequestHeaders().getFirst("Range");
                ranges.add(String.valueOf(range));
                cookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
                exchange.getResponseHeaders().add("Content-Type", "application/pdf");
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
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/anhang");
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static UrlConnectionJiraTransport transport(int resumes) {
        return UrlConnectionJiraTransport.builder().readTimeoutMillis(5000).resumes(resumes).build();
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cookie", "SESSION=erfunden");
        return headers;
    }

    @Test
    public void responseContinuesFromTheLastByteWithTheSameHeaders() throws IOException {
        JiraHttpResponse response = transport(2).get(uri, headers(), Integer.MAX_VALUE);

        assertEquals(200, response.status());
        assertArrayEquals(CONTENT, response.body());
        assertEquals(2, ranges.size());
        assertEquals("null", ranges.get(0));
        assertEquals("bytes=" + (CONTENT.length / 4) + "-", ranges.get(1));
        assertEquals("SESSION=erfunden", cookies.get(1));
    }

    @Test(expected = JiraResponseTooLargeException.class)
    public void theLimitStillApplies() throws IOException {
        transport(2).get(uri, headers(), CONTENT.length / 2);
    }

    @Test(expected = IOException.class)
    public void withoutResumesTheBreakIsReported() throws IOException {
        transport(0).get(uri, headers(), Integer.MAX_VALUE);
    }
}
