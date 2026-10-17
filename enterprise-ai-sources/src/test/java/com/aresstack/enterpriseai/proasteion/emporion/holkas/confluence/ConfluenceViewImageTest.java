package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PageView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Angelos Live-Fehler „Bild konnte nicht geladen werden“: Der Bildrequest fragt nach Bildern, und jede Nicht-Bild-
 * Antwort wird unmittelbar nach HTTP mit eindeutigem Grund abgelehnt und mit URL, Status, Content-Type, Größe und
 * Fehlerart (ohne Header) protokolliert.
 */
public class ConfluenceViewImageTest {

    private static final String BASE = "https://confluence.invalid/confluence";
    private static final String IMAGE = BASE + "/download/attachments/7/bild.png?version=1&api=v2";
    /** {@code export_view} schreibt Bildadressen absolut mit der Server-Basis, die Confluence für sich eingestellt hat. */
    private static final String SERVER_BASE = "http://wiki.invalid:8090/confluence";
    private static final String READING_PAGE = "{\"id\":\"7\",\"type\":\"page\",\"title\":\"Seite\","
            + "\"body\":{\"export_view\":{\"value\":\"<p><img src=\\\"" + SERVER_BASE
            + "/download/attachments/7/a.png?version=1&amp;api=v2\\\"></p>\"}},"
            + "\"_links\":{\"base\":\"" + SERVER_BASE + "\",\"webui\":\"/pages/7\"}}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    private final RecordingSecretProvider secrets = new RecordingSecretProvider();
    private final List<String> logLines = new ArrayList<String>();
    private final List<String> infoLines = new ArrayList<String>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            infoLines.add(record.getMessage());
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logLines.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private URI requested;
    private Map<String, String> headers;
    private final List<URI> requests = new ArrayList<URI>();
    private final List<URI> anonymous = new ArrayList<URI>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<Map<String, String>>();
    private final Map<String, ConfluenceHttpResponse> byHost = new java.util.HashMap<String, ConfluenceHttpResponse>();
    private ConfluenceHttpResponse response;
    private ConfluenceHttpResponse restLogin;
    private ConfluenceHttpResponse drawio = new ConfluenceHttpResponse(404, "application/json", new byte[0]);
    private ConfluenceHttpResponse withSession;
    private String readingPage = READING_PAGE;
    private IOException failure;
    private boolean foreignFileRedirects = true;

    @Before
    public void captureLog() {
        Logger.getLogger(ConfluenceRestClient.class.getName()).addHandler(capture);
        Logger.getLogger(ConfluenceKnowledgeSource.class.getName()).addHandler(capture);
    }

    @After
    public void releaseLog() {
        Logger.getLogger(ConfluenceRestClient.class.getName()).removeHandler(capture);
        Logger.getLogger(ConfluenceKnowledgeSource.class.getName()).removeHandler(capture);
    }

    private ConfluenceKnowledgeSource source(int maxAttachmentBytes) {
        ConfluenceHttpTransport fake = (uri, sent, maxBytes) -> {
            requested = uri;
            headers = sent;
            requests.add(uri);
            sentHeaders.add(sent);
            if (failure != null) {
                throw failure;
            }
            if (uri.getRawPath().equals("/confluence/rest/api/content/7/child/attachment")) {
                return new ConfluenceHttpResponse(200, "application/json",
                        "{\"results\":[],\"_links\":{}}".getBytes(StandardCharsets.UTF_8));
            }
            if (uri.getRawPath().startsWith("/confluence/rest/api/content/7")) {
                return new ConfluenceHttpResponse(200, "application/json", readingPage.getBytes(StandardCharsets.UTF_8));
            }
            if (uri.getRawPath().contains("/rest/drawio/")) {
                return drawio;
            }
            if (uri.getRawPath().endsWith("/rest/api/user/current")) {
                return restLogin != null ? restLogin : new ConfluenceHttpResponse(200, "application/json",
                        "{}".getBytes(StandardCharsets.UTF_8));
            }
            if (withSession != null && sent.containsKey("Cookie")) {
                return withSession;
            }
            ConfluenceHttpResponse hosted = byHost.get(uri.getHost());
            return hosted != null ? hosted : response;
        };
        ConfluenceHttpTransport transport = new ConfluenceHttpTransport() {
            @Override
            public ConfluenceHttpResponse get(URI uri, Map<String, String> sent, int maxBytes) throws IOException {
                return fake.get(uri, sent, maxBytes);
            }

            @Override
            public ConfluenceHttpResponse getAnonymous(URI uri, Map<String, String> sent, int maxBytes)
                    throws IOException {
                anonymous.add(uri);
                return fake.get(uri, sent, maxBytes);
            }
        };
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(URI.create(BASE)).credentialRef(RecordingSecretProvider.REF)
                        .maxAttachmentBytes(maxAttachmentBytes).foreignFileRedirects(foreignFileRedirects).build(),
                transport, secrets);
    }

    private ConfluenceKnowledgeSource source() {
        return source(5 * 1024 * 1024);
    }

    @Test
    public void theImageRequestAsksForImagesNotJson() throws Exception {
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage(IMAGE));

        assertEquals(IMAGE, requested.toString());
        assertEquals("image/*,*/*;q=0.8", headers.get("Accept"));
        assertTrue("angemeldet wie alle Aufrufe", headers.containsKey("Authorization"));
        assertTrue(logLines.isEmpty());
    }

    @Test
    public void aFileNameWithSpacesIsRequestedEncoded() throws Exception {
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage(BASE + "/download/attachments/7/Übersicht A.png"));

        assertEquals("/confluence/download/attachments/7/%C3%9Cbersicht%20A.png", requested.getRawPath());
    }

    @Test
    public void anOctetStreamWithImageBytesIsAnImage() throws Exception {
        response = new ConfluenceHttpResponse(200, "application/octet-stream", PNG);

        assertArrayEquals(PNG, source().loadViewImage(IMAGE));
    }

    @Test
    public void jsonIsRejectedRightAfterHttpWithItsContentType() {
        response = new ConfluenceHttpResponse(200, "application/json;charset=UTF-8",
                "{\"statusCode\":200}".getBytes(StandardCharsets.UTF_8));

        KnowledgeSourceException e = failing();

        assertEquals(Kind.INVALID_RESPONSE, e.kind());
        assertEquals("Bild: erwartet Bild, erhalten application/json (HTTP 200, 18 Bytes)", e.getMessage());
    }

    @Test
    public void aLoginPageIsNamedAsSuch() {
        response = new ConfluenceHttpResponse(200, "text/html; charset=UTF-8",
                "<html><body>Anmelden</body></html>".getBytes(StandardCharsets.UTF_8));

        KnowledgeSourceException e = failing();

        assertEquals(Kind.INVALID_RESPONSE, e.kind());
        assertTrue(e.getMessage(), e.getMessage().startsWith(
                "Bild: erwartet Bild, erhalten text/html (vermutlich eine Anmeldeseite)"));
    }

    @Test
    public void anImageOutsideTheConfiguredBaseIsNeverRequestedAndSaysWhy() {
        String foreign = "https://other.invalid/confluence/download/attachments/7/bild.png";
        try {
            source().loadViewImage(foreign);
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
            assertEquals("Bild liegt nicht unter der eingestellten Confluence-Adresse " + BASE
                    + " und wird nicht geladen: " + foreign, e.getMessage());
        }
        assertNull("kein Request an fremde Hosts", requested);
        assertEquals(1, logLines.size());
        assertTrue(logLines.get(0), logLines.get(0).contains("url=" + foreign));
        assertTrue(logLines.get(0), logLines.get(0).contains("fehler=UNSUPPORTED"));
    }

    @Test
    public void anImageAboveTheLimitSaysSo() {
        failure = new ConfluenceResponseTooLargeException(1024);

        try {
            source(1024).loadViewImage(IMAGE);
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertEquals("Bild größer als 1024 Bytes (Grenze maxAttachmentBytes)", e.getMessage());
        }
    }

    @Test
    public void theLogLineNamesUrlStatusTypeSizeAndKindButNoHeaders() {
        response = new ConfluenceHttpResponse(200, "application/json",
                "{\"statusCode\":200}".getBytes(StandardCharsets.UTF_8));

        failing();

        assertEquals(1, logLines.size());
        String line = logLines.get(0);
        assertTrue(line, line.contains("url=" + BASE + "/download/attachments/7/bild.png "));
        assertFalse("ohne Query", line.contains("api=v2"));
        assertTrue(line, line.contains("status=200"));
        assertTrue(line, line.contains("contentType=application/json"));
        assertTrue(line, line.contains("bytes=18"));
        assertTrue(line, line.contains("fehler=INVALID_RESPONSE"));
        assertFalse(line, line.contains("Authorization"));
        assertFalse(line, line.contains(headers.get("Authorization")));
        assertFalse(line, line.contains(secrets.principal));
        assertFalse(line, line.contains(secrets.secret));
    }

    @Test
    public void imagesUnderTheServerBaseLoadThroughTheConfiguredAddress() throws Exception {
        response = new ConfluenceHttpResponse(200, "image/png", PNG);
        ConfluenceKnowledgeSource source = source();

        PageView view = source.pageView(KnowledgeResourceId.of("confluence", "confluence.invalid/confluence/page/7"));

        String src = BASE + "/download/attachments/7/a.png?version=1&api=v2";
        assertEquals(1, view.images().size());
        assertEquals(src, view.images().get(0).src());
        assertTrue(view.html(), view.html().contains(BASE + "/download/attachments/7/a.png"));
        assertArrayEquals(PNG, source.loadViewImage(src));
        assertEquals(URI.create(src), requested);
        assertTrue(logLines.isEmpty());
    }

    @Test
    public void theSameHostInAnotherSpellingIsNotForeign() throws Exception {
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage("https://CONFLUENCE.invalid:443/confluence/download/x.png"));

        assertEquals(URI.create(BASE + "/download/x.png"), requested);
    }

    @Test
    public void aRedirectToAnotherHostIsFollowedWithoutCredentials() throws Exception {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0],
                "https://files.invalid/blob/a.png?sig=x");
        byHost.put("files.invalid", new ConfluenceHttpResponse(200, "image/png", PNG));

        assertArrayEquals(PNG, source().loadViewImage(IMAGE));

        assertEquals(2, requests.size());
        assertEquals("files.invalid", requests.get(1).getHost());
        assertTrue(sentHeaders.get(0).containsKey("Authorization"));
        assertFalse("Anmeldung verlässt die Confluence nie", sentHeaders.get(1).containsKey("Authorization"));
        assertEquals("image/*,*/*;q=0.8", sentHeaders.get(1).get("Accept"));
        assertEquals("ohne Client-Zertifikat", java.util.Collections.singletonList(requests.get(1)), anonymous);
    }

    @Test
    public void withoutForeignFileRedirectsARedirectToAnotherHostIsNotFollowed() throws Exception {
        foreignFileRedirects = false;
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0],
                "https://files.invalid/blob/a.png?sig=x");
        byHost.put("files.invalid", new ConfluenceHttpResponse(200, "image/png", PNG));

        try {
            source().loadViewImage(IMAGE);
            fail("fremder Host");
        } catch (KnowledgeSourceException expected) {
            assertTrue(requests.toString(), requests.stream().noneMatch(uri -> "files.invalid".equals(uri.getHost())));
            assertTrue(anonymous.isEmpty());
        }
        assertTrue(infoLines.toString(), infoLines.stream()
                .anyMatch(line -> line.contains("nicht gefolgt (foreignFileRedirects=false)")
                        && !line.contains("sig=x")));
    }

    @Test
    public void theSessionCookieNeverFollowsARedirectToAnotherHost() throws Exception {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action");
        restLogin = new ConfluenceHttpResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null,
                java.util.Collections.singletonList("JSESSIONID=abc123"));
        withSession = new ConfluenceHttpResponse(302, "text/html", new byte[0], "https://files.invalid/a.png");
        byHost.put("files.invalid", new ConfluenceHttpResponse(200, "image/png", PNG));

        assertArrayEquals(PNG, source().loadViewImage(IMAGE));

        int last = requests.size() - 1;
        assertEquals("files.invalid", requests.get(last).getHost());
        assertEquals("JSESSIONID=abc123", sentHeaders.get(last - 1).get("Cookie"));
        assertFalse(sentHeaders.get(last).containsKey("Cookie"));
        assertFalse(sentHeaders.get(last).containsKey("Authorization"));
        assertTrue(anonymous.contains(requests.get(last)));
    }

    @Test
    public void notFoundIsNotRetriedWithASession() {
        response = new ConfluenceHttpResponse(404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(Kind.NOT_FOUND, failing().kind());
        assertNoRestLogin();
    }

    @Test
    public void anUnreachableConfluenceIsNotRetriedWithASession() {
        failure = new IOException("Verbindung abgelehnt");

        assertEquals(Kind.UNAVAILABLE, failing().kind());
        assertNoRestLogin();
    }

    @Test
    public void aFailedRestLoginIsNamedInTheReason() {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action");
        restLogin = new ConfluenceHttpResponse(401, "application/json", "{}".getBytes(StandardCharsets.UTF_8));

        KnowledgeSourceException e = failing();

        assertTrue(e.getMessage(), e.getMessage().endsWith(
                "; REST-Anmeldung für das Sitzungs-Cookie gescheitert (HTTP 401)"));
    }

    private static final String DIAGRAM_IMAGE = BASE + "/download/attachments/42/Bild%20A.png?version=3&api=v2"
            + "#drawio=Bild%20A&page=42&revision=3";

    private static ConfluenceHttpResponse diagram(String shape) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("name", "Bild A");
        json.addProperty("xml", "<mxfile><diagram id=\"d\"><mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"a\" value=\"Kasten\" style=\"shape=" + shape + ";\""
                + " vertex=\"1\" parent=\"1\"><mxGeometry width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
                + "</root></mxGraphModel></diagram></mxfile>");
        return new ConfluenceHttpResponse(200, "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aDrawioDiagramIsDrawnFromItsXmlAsSvg() throws Exception {
        drawio = diagram("rect");
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        String svg = new String(source().loadViewImage(DIAGRAM_IMAGE), StandardCharsets.UTF_8);

        assertTrue(svg, svg.contains("<svg") && svg.contains("Kasten"));
        assertEquals(1, requests.size());
        assertEquals("/confluence/rest/drawio/1.0/diagram/crud/Bild%20A/42", requests.get(0).getRawPath());
        assertEquals("revision=3", requests.get(0).getRawQuery());
        assertTrue("angemeldet wie jeder REST-Aufruf", sentHeaders.get(0).containsKey("Authorization"));
    }

    @Test
    public void anUnsupportedDiagramFallsBackToThePreviewPng() throws Exception {
        drawio = diagram("mxgraph.unbekannt.form");
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage(DIAGRAM_IMAGE));

        URI preview = requests.get(requests.size() - 1);
        assertEquals("/confluence/download/attachments/42/Bild%20A.png", preview.getRawPath());
        assertEquals("version=3&api=v2", preview.getRawQuery());
        assertNull("das Fragment geht nicht über die Leitung", preview.getRawFragment());
        assertTrue("Rückfall ist kein Fehler", logLines.isEmpty());
        assertTrue(infoLines.toString(), infoLines.contains("[Confluence] draw.io: rendere Diagramm name=Bild A page=42"
                + " revision=3"));
        assertTrue(infoLines.toString(), infoLines.stream().anyMatch(line -> line.startsWith(
                "[Confluence] draw.io: JGraphX nicht möglich (nicht unterstützt: shape=mxgraph.unbekannt.form),"
                        + " verwende PNG-Vorschau")));
    }

    /**
     * Echte Seitenstruktur: das draw.io-Makro bringt sein Vorschau-PNG schon als {@code <img>} mit; die Vorschau
     * liefert ohne Sitzung nur eine Anmeldeseite. Die Zeichnung kommt trotzdem als SVG, ohne das PNG anzufragen.
     */
    @Test
    public void aMacroWithADeliveredPreviewIsDrawnAsSvgEvenWhenThePreviewIsALoginPage() throws Exception {
        String data = java.util.Base64.getEncoder().encodeToString(("{\"diagramName\":\"Bild A\",\"previewName\":"
                + "\"Bild A.png\",\"owningPageId\":42,\"revision\":55}").getBytes(StandardCharsets.UTF_8));
        readingPage = "{\"id\":\"7\",\"type\":\"page\",\"title\":\"Seite\",\"body\":{\"export_view\":{\"value\":"
                + "\"<div data-macro-name=\\\"drawio\\\"><img src=\\\"" + SERVER_BASE + "/download/attachments/42/"
                + "Bild%20A.png?version=55&amp;modificationDate=1700000000000&amp;api=v2\\\">"
                + "<div style=\\\"display:none\\\" data-diagramdata=\\\"" + data + "\\\"></div></div>\"}},"
                + "\"_links\":{\"base\":\"" + SERVER_BASE + "\",\"webui\":\"/pages/7\"}}";
        drawio = diagram("mxgraph.arrows2.arrow");
        response = new ConfluenceHttpResponse(200, "text/html",
                "<html><body>Login</body></html>".getBytes(StandardCharsets.UTF_8));
        ConfluenceKnowledgeSource source = source();

        PageView view = source.pageView(KnowledgeResourceId.of("confluence", "confluence.invalid/confluence/page/7"));
        assertEquals(1, view.images().size());
        String src = view.images().get(0).src();
        assertTrue(src, src.endsWith("#drawio=Bild%20A&page=42&revision=55"));
        requests.clear();

        String svg = new String(source.loadViewImage(src), StandardCharsets.UTF_8);

        assertTrue(svg, svg.contains("<svg") && svg.contains("Kasten") && svg.contains("<path"));
        assertEquals(1, requests.size());
        assertEquals("/confluence/rest/drawio/1.0/diagram/crud/Bild%20A/42", requests.get(0).getRawPath());
        assertTrue(infoLines.toString(), infoLines.contains("[Confluence] draw.io: rendere Diagramm name=Bild A page=42"
                + " revision=55"));
        assertTrue(infoLines.toString(), infoLines.stream().anyMatch(
                line -> line.startsWith("[Confluence] draw.io: SVG erzeugt")));
        assertTrue(logLines.isEmpty());
    }

    /** Umschalter „Original“: nur die PNG-Vorschau, die Zeichnung wird nicht angefragt. */
    @Test
    public void theOriginalViewLoadsOnlyThePreviewPng() throws Exception {
        drawio = diagram("rect");
        response = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage(DIAGRAM_IMAGE + "&view=original"));

        assertEquals(1, requests.size());
        assertEquals("/confluence/download/attachments/42/Bild%20A.png", requests.get(0).getRawPath());
        assertEquals("version=3&api=v2", requests.get(0).getRawQuery());
    }

    @Test
    public void whenDiagramAndPreviewFailBothReasonsAreNamed() {
        response = new ConfluenceHttpResponse(404, "application/json", new byte[0]);

        KnowledgeSourceException e;
        try {
            source().loadViewImage(DIAGRAM_IMAGE);
            fail("expected KnowledgeSourceException");
            return;
        } catch (KnowledgeSourceException failed) {
            e = failed;
        }

        assertTrue(e.getMessage(), e.getMessage().startsWith("Bild: "));
        assertTrue(e.getMessage(), e.getMessage().contains("; draw.io-Zeichnung: draw.io-Diagramm: "));
    }

    private void assertNoRestLogin() {
        for (URI request : requests) {
            assertFalse(request.toString(), request.getRawPath().endsWith("/rest/api/user/current"));
        }
    }

    @Test
    public void aRedirectToALoginPageNamesItAndLogsItsTargetWithoutQuery() {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0],
                "https://sso.invalid/login?os_destination=x");
        byHost.put("sso.invalid", new ConfluenceHttpResponse(200, "text/html", "<html>Login</html>".getBytes(
                StandardCharsets.UTF_8)));

        KnowledgeSourceException e = failing();

        assertTrue(e.getMessage(), e.getMessage().contains("text/html (vermutlich eine Anmeldeseite)"));
        String failure = null;
        for (String line : logLines) {
            assertFalse(line, line.contains("os_destination"));
            if (line.contains("Bild nicht geladen")) {
                failure = failure == null ? line : failure;
            }
        }
        assertTrue(String.valueOf(failure), failure != null && failure.contains("url=https://sso.invalid/login "));
    }

    @Test
    public void aHttpsRedirectToHttpIsNotFollowed() {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0], "http://files.invalid/a.png");

        KnowledgeSourceException e = failing();

        assertEquals(Kind.ACCESS_DENIED, e.kind());
        for (URI request : requests) {
            assertFalse(request.toString(), "files.invalid".equals(request.getHost()));
        }
        assertTrue(logLines.get(0), logLines.get(0).contains("weiterleitung=http://files.invalid/a.png "));
    }

    @Test
    public void aLoginRedirectIsRetriedOnceWithTheSessionCookieOfAnAuthenticatedRestCall() throws Exception {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action?os_destination=x");
        restLogin = new ConfluenceHttpResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null,
                java.util.Arrays.asList("JSESSIONID=abc123", "seraph.confluence=xyz789"));
        withSession = new ConfluenceHttpResponse(200, "image/png", PNG);

        assertArrayEquals(PNG, source().loadViewImage(IMAGE));

        URI last = requests.get(requests.size() - 1);
        Map<String, String> lastHeaders = sentHeaders.get(sentHeaders.size() - 1);
        assertEquals(URI.create(IMAGE), last);
        assertEquals("JSESSIONID=abc123; seraph.confluence=xyz789", lastHeaders.get("Cookie"));
        assertTrue("REST-Aufruf angemeldet", sentHeaders.get(requests.indexOf(
                URI.create(BASE + "/rest/api/user/current"))).containsKey("Authorization"));
        assertFalse("erster Versuch ohne Cookie", sentHeaders.get(0).containsKey("Cookie"));
        for (String line : logLines) {
            assertFalse(line, line.contains("abc123") || line.contains("xyz789"));
        }
    }

    @Test
    public void withoutASessionCookieTheReasonSaysSo() {
        response = new ConfluenceHttpResponse(200, "text/html", "<html>Login</html>".getBytes(StandardCharsets.UTF_8));

        KnowledgeSourceException e = failing();

        assertTrue(e.getMessage(), e.getMessage().endsWith(
                "; Confluence setzt beim angemeldeten REST-Aufruf kein Sitzungs-Cookie"));
    }

    @Test
    public void whenTheSessionCookieDoesNotHelpEitherBothAttemptsAreNamed() {
        response = new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action");
        restLogin = new ConfluenceHttpResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null,
                java.util.Collections.singletonList("JSESSIONID=abc123"));
        withSession = new ConfluenceHttpResponse(403, "text/html", new byte[0]);

        KnowledgeSourceException e = failing();

        assertEquals(Kind.ACCESS_DENIED, e.kind());
        assertEquals("Bild: Zugriff verweigert (HTTP 403) (auch mit Sitzungs-Cookie aus der REST-Anmeldung)",
                e.getMessage());
    }

    @Test
    public void htmlWithAnInlineSvgIsNoImageButAnSvgDocumentIs() {
        assertFalse(ConfluenceRestClient.isSvgDocument(
                "<html><body><svg></svg></body></html>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ConfluenceRestClient.isSvgDocument(("<?xml version=\"1.0\"?>\n<!-- x -->\n<!DOCTYPE svg>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\"/>").getBytes(StandardCharsets.UTF_8)));
        assertEquals("text/html (vermutlich eine Anmeldeseite)", ConfluenceRestClient.notAnImage("text/html",
                "<html><svg/></html>".getBytes(StandardCharsets.UTF_8)));
    }

    private KnowledgeSourceException failing() {
        try {
            source().loadViewImage(IMAGE);
            fail("expected KnowledgeSourceException");
            return null;
        } catch (KnowledgeSourceException e) {
            return e;
        }
    }
}
