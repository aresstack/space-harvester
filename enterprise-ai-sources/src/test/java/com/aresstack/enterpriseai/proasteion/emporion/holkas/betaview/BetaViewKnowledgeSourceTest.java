package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.BlockKind;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BetaViewKnowledgeSourceTest {

    private static final String BASE = "https://bv.example.test/betaview/";
    private static final String LOGIN_PAGE = "<form><input type=\"hidden\" name=\"struts.token.name\" value=\"token\">"
            + "<input type=\"hidden\" name=\"token\" value=\"T1\"><input name=\"userName\"><input type=\"password\" name=\"password\"></form>";

    /** Fake-Server: Anfragen als „METHODE pfad“, mit Cookie-Prüfung nach dem Login. */
    private final List<String> requests = new ArrayList<String>();
    private boolean acceptLogin = true;

    private final BetaViewTransport fake = new BetaViewTransport() {
        @Override
        public Response exchange(URI uri, String cookieHeader, String formBody, long maxBytes) {
            String path = uri.toString().substring(BASE.length());
            requests.add((formBody == null ? "GET " : "POST ") + path + (formBody == null ? "" : " " + formBody));
            if (path.startsWith("login.action") && formBody == null) {
                return html(LOGIN_PAGE);
            }
            if (path.startsWith("login.action")) {
                if (!acceptLogin) {
                    return html(LOGIN_PAGE);
                }
                Map<String, List<String>> headers = new HashMap<String, List<String>>();
                headers.put("Set-Cookie", Collections.singletonList("JSESSIONID=S1; Path=/betaview"));
                headers.put("Location", Collections.singletonList("welcome.action"));
                return new Response(302, headers, new byte[0]);
            }
            if (!"JSESSIONID=S1".equals(cookieHeader)) {
                return new Response(403, null, new byte[0]);
            }
            if (path.startsWith("welcome.action")) {
                return html("<html>willkommen</html>");
            }
            if (path.startsWith("getAppContext.action")) {
                return html("{\"csrfToken\":{\"field\":\"csrfToken\",\"name\":\"csrfToken\",\"value\":\"C1\"}}");
            }
            if (path.startsWith("select.action?favoriteID=42")) {
                return html(LOGIN_PAGE.replace("<input name=\"userName\">", ""));
            }
            if (path.startsWith("result.action")) {
                return html("ok");
            }
            if (path.startsWith("showResult.action")) {
                return html("<table><thead><tr><th>Datum</th><th>Report</th></tr></thead><tbody>"
                        + "<tr><td>01.10.2026</td><td><a href=\"#\" onclick=\"bwe.result.openDocument('open.action?index=0&amp;source=resulttable');\">ZAUF01</a></td></tr>"
                        + "<tr><td>02.10.2026</td><td><a href=\"#\" onclick=\"bwe.result.openDocument('open.action?index=1&amp;source=resulttable');\">ZAUF02</a></td></tr>"
                        + "</tbody></table>");
            }
            if (path.startsWith("open.action?index=0") || path.startsWith("opendocumentlink.action?docid=AB12")) {
                return html(tabs("AB12", "ZAUF01"));
            }
            if (path.startsWith("open.action?index=1")) {
                return html(tabs("CD34", "ZAUF02"));
            }
            if (path.startsWith("downloadDialog.action")) {
                return html("<input type=\"hidden\" name=\"pages\" value=\"3\">");
            }
            if (path.startsWith("downloadWithOptions.action")) {
                Map<String, List<String>> headers = new HashMap<String, List<String>>();
                headers.put("Content-Type", Collections.singletonList("text/plain"));
                return new Response(200, headers, "Inhalt der Liste".getBytes(StandardCharsets.UTF_8));
            }
            if (path.startsWith("closeAllDocuments.action")) {
                return html("");
            }
            return new Response(404, null, new byte[0]);
        }
    };

    private static String tabs(String docId, String title) {
        return "<ul id=\"we_id_docbrowser_tabs\"><li class=\"active\"><div class=\"el_tab_content\">"
                + "<a href=\"opendocumentlink.action?docid=" + docId + "&amp;favid=42\">x</a>"
                + "<span class=\"el_tab_element\">01.10.2026</span><span class=\"el_tab_element\">" + title + "</span>"
                + "</div></li></ul>";
    }

    private static BetaViewTransport.Response html(String body) {
        return new BetaViewTransport.Response(200, null, body.getBytes(StandardCharsets.UTF_8));
    }

    private BetaViewKnowledgeSource source() {
        return new BetaViewKnowledgeSource(SourceAddress.of("betaview://bv.example.test/betaview"), URI.create(BASE),
                new BetaViewSelection("42", "de", 30, "*", "*", "*", "*"),
                new BetaViewCredentialsProvider() {
                    @Override
                    public BetaViewCredentials credentials() {
                        return new BetaViewCredentials("nutzer", "geheim".toCharArray());
                    }
                }, fake, detector(), registry(), 1024 * 1024);
    }

    @Test
    public void discoverOpensEachRowForItsStableDocumentId() throws Exception {
        BetaViewKnowledgeSource source = source();
        List<KnowledgeResource> found = source.discover(SourceScope.builder().startPoints(".").maxDepth(0).maxResources(10).build());
        assertEquals(2, found.size());
        assertEquals("betaview:bv.example.test/betaview/42/AB12", found.get(0).id().value());
        assertEquals("01.10.2026 · ZAUF01", found.get(0).title());
        assertTrue(requests.toString(), requests.contains("GET getAppContext.action"));
        assertTrue(requests.toString(), requests.contains("GET open.action?index=1&source=resulttable&csrfToken=C1"));
        for (String request : requests) {
            if (request.startsWith("POST result.action")) {
                assertTrue(request, request.contains("token=T1") && request.contains("lasthoursdaysvalue=30"));
            }
        }
    }

    @Test
    public void loadDownloadsTheOriginalAndReusesTheSession() throws Exception {
        BetaViewKnowledgeSource source = source();
        KnowledgeDocument document = source.load(KnowledgeResourceId.of("betaview:bv.example.test/betaview/42/AB12"));
        assertTrue(document.text(), document.text().contains("Inhalt der Liste"));
        assertEquals("ZAUF01", document.resource().title());
        source.load(KnowledgeResourceId.of("betaview:bv.example.test/betaview/42/AB12"));
        int logins = 0;
        for (String request : requests) {
            if (request.startsWith("POST login.action")) {
                logins++;
            }
        }
        assertEquals(1, logins);
    }

    @Test
    public void rejectedLoginIsAccessDenied() {
        acceptLogin = false;
        try {
            source().discover(SourceScope.of("."));
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.ACCESS_DENIED, e.kind());
        }
    }

    @Test
    public void providerRequiresCredentialAndFavorite() {
        BetaViewSourceProvider provider = new BetaViewSourceProvider(ref -> null, null, null, null, detector(), registry());
        Map<String, String> values = new HashMap<String, String>();
        values.put("baseUrl", "https://bv.example.test/betaview");
        assertFalse(provider.validate(SourceSettings.of(values)).isEmpty());
        values.put("credentialRef", "keepass:BetaView");
        values.put("favoriteId", "42");
        assertTrue(provider.validate(SourceSettings.of(values)).toString(), provider.validate(SourceSettings.of(values)).isEmpty());
        assertEquals("bv.example.test/betaview", provider.locator(SourceSettings.of(values)));
    }

    static ContentDetector detector() {
        return new ContentDetector() {
            @Override
            public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
                boolean text = filenameHint != null && filenameHint.endsWith(".txt");
                return new DetectedContentType(text ? "text/plain" : "application/octet-stream",
                        text ? ContentCategory.PLAIN_TEXT : ContentCategory.BINARY, filenameHint);
            }
        };
    }

    static ExtractionRegistry registry() {
        ExtractionRegistry registry = new ExtractionRegistry();
        registry.register(new ResourceExtractor() {
            @Override
            public boolean supports(DetectedContentType contentType) {
                return contentType.category() == ContentCategory.PLAIN_TEXT;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {
                return ExtractionResult.success(request.resourceId(), request.detectedContentType(),
                        ExtractedDocument.builder().contentType(request.detectedContentType())
                                .addBlock(new ExtractedBlock(0, BlockKind.TEXT,
                                        new String(request.content(), StandardCharsets.UTF_8))).build());
            }
        });
        return registry;
    }
}
