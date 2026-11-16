package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.DownloadAccess;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Space Harvester, 16.11.2026 (Auftrag „Anhänge beschaffen“, Quelle mit Benutzer und Passwort): Confluence nahm den
 * Einmalcode an und leitete auf den großen Anhang weiter, der die Code-Seite ausgelöst hatte. Der Transport las ihn bis
 * zur Grenze für Antworten mit, und das Absenden scheiterte mit {@link ConfluenceResponseTooLargeException}, obwohl der
 * Code angenommen war. Über den echten Transport gegen einen lokalen Server, der sich wie miniOrange verhält, auch mit
 * neuer Sitzungs-ID nach der Bestätigung.
 */
public class ConfluenceOneTimeCodeRedirectTest {

    private static final String OTP_PATH = "/plugins/servlet/twofactor/validate_otpoveremail";
    private static final String CODE = "482913";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    /** Größer als die Grenze für Antworten der Quelle in diesem Test. */
    private static final int LARGE_FILE_BYTES = 200_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private HttpServer server;
    private URI base;
    private final List<String> postCookies = new CopyOnWriteArrayList<String>();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/download/", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null && cookie.contains("JSESSIONID=sitzung-2")) {
                if (exchange.getRequestURI().getPath().endsWith(".pdf")) {
                    respond(exchange, 200, "application/pdf", new byte[LARGE_FILE_BYTES]);
                } else {
                    respond(exchange, 200, "image/png", PNG);
                }
                return;
            }
            // Ohne bestätigte Sitzung wie miniOrange: Sitzung setzen und auf die Code-Seite weiterleiten.
            if (cookie == null || !cookie.contains("JSESSIONID=sitzung-1")) {
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=sitzung-1; Path=/; HttpOnly");
            }
            exchange.getResponseHeaders().add("Location", OTP_PATH);
            respond(exchange, 302, "text/html", new byte[0]);
        });
        server.createContext(OTP_PATH, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "text/html;charset=UTF-8", page(null));
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            postCookies.add(String.valueOf(cookie));
            if (body.contains("otpCode=" + CODE) && cookie != null && cookie.contains("JSESSIONID=sitzung-1")) {
                // Bestätigt: neue Sitzungs-ID und zurück zur Datei, die die Code-Seite ausgelöst hat.
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=sitzung-2; Path=/; HttpOnly");
                exchange.getResponseHeaders().add("Location", "/download/attachments/7/gross.pdf?version=1");
                respond(exchange, 302, "text/html", new byte[0]);
                return;
            }
            respond(exchange, 200, "text/html;charset=UTF-8", page("Ungültiger Code."));
        });
        server.start();
        base = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void aCodeAnsweredWithALargeFileIsAcceptedAndTheNewSessionCookieCounts() throws Exception {
        ConfluenceKnowledgeSource source = source();
        OneTimeCodeChallenge challenge = challengeOf(source, image());
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());

        assertNull("angenommen", challenge.submit(CODE.toCharArray()));

        assertEquals(DownloadAccess.UNLOCKED, source.downloadAccess());
        assertEquals(1, postCookies.size());
        assertEquals("JSESSIONID=sitzung-1", postCookies.get(0));
        assertArrayEquals("mit der neuen Sitzung ohne weitere Abfrage", PNG, source.loadViewImage(image()));
        assertEquals(1, postCookies.size());
    }

    @Test
    public void aWrongCodeStillComesBackAsTheCodePage() throws Exception {
        ConfluenceKnowledgeSource source = source();
        OneTimeCodeChallenge challenge = challengeOf(source, image());

        OneTimeCodeChallenge again = challenge.submit("000000".toCharArray());

        assertEquals("Ungültiger Code.", again.error());
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());
        assertNull(again.submit(CODE.toCharArray()));
        assertArrayEquals(PNG, source.loadViewImage(image()));
    }

    private String image() {
        return base + "/download/attachments/7/bild.png?version=1";
    }

    private ConfluenceKnowledgeSource source() {
        ConfluenceConfig config = ConfluenceConfig.builder(base).credentialRef(RecordingSecretProvider.REF)
                .allowInsecureHttp(true).maxResponseBytes(MAX_RESPONSE_BYTES).build();
        UrlConnectionConfluenceTransport transport = UrlConnectionConfluenceTransport.builder().proxy(Proxy.NO_PROXY)
                .readTimeoutMillis(5000).build();
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"), config, transport,
                new RecordingSecretProvider());
    }

    private static OneTimeCodeChallenge challengeOf(ConfluenceKnowledgeSource source, String url)
            throws KnowledgeSourceException {
        try {
            source.loadViewImage(url);
        } catch (OneTimeCodeRequiredException e) {
            return e.challenge();
        }
        fail("ohne Einmalcode keine Datei");
        return null;
    }

    private static byte[] page(String error) {
        return ConfluenceOneTimeCodeTest.otpPage(error).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } catch (IOException e) {
            // Der Client schließt die Verbindung, ohne die Datei zu lesen.
        }
    }
}
