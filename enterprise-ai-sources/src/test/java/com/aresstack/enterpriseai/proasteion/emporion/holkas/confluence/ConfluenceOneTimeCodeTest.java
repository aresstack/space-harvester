package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.DownloadAccess;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.SourceFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Angelos echter Test mit 0.20.2: {@code /download/...} leitet auf die Einmalcode-Seite eines Zwei-Faktor-Plugins
 * (Code per E-Mail) um. Das Formular hat dieselben Feldnamen wie die echte Seite ({@code otpCode},
 * {@code mo2fa_return_url}, {@code resendOtpLinkClicked}, {@code sendOtpClicked}, Knopf {@code Validate}); Host,
 * Texte und Werte sind erfunden.
 *
 * <p>Abnahme: PDF öffnen → Abfrage → Code → PDF; danach ein Bild ohne neue Abfrage. Die Abfrage kommt als
 * {@link OneTimeCodeRequiredException}; die Oberfläche zeigt sie im Anzeigebereich und lädt danach neu.
 */
public class ConfluenceOneTimeCodeTest {

    private static final String BASE = "https://confluence.invalid";
    private static final String OTP_PATH = "/plugins/servlet/twofactor/validate_otpoveremail";
    private static final byte[] PDF = "%PDF-1.7\n%Test\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    private static final String PAGE = "{\"id\":\"7\",\"type\":\"page\",\"title\":\"Seite\","
            + "\"body\":{\"export_view\":{\"value\":\"<p>Text</p>\"}},\"_links\":{\"webui\":\"/pages/7\"}}";
    private static final String ATTACHMENT = "{\"id\":\"att101\",\"type\":\"attachment\",\"title\":\"Handbuch.pdf\","
            + "\"version\":{\"number\":1},\"container\":{\"id\":\"7\"},"
            + "\"extensions\":{\"mediaType\":\"application/pdf\",\"fileSize\":10},"
            + "\"_links\":{\"download\":\"/download/attachments/7/Handbuch.pdf?version=1&api=v2\"}}";

    static String otpPage(String error) {
        return "<html><head><title> - Confluence</title><meta name=\"ajs-remote-user\" content=\"beispiel\">"
                + "</head><body><header id=\"header\"><a href=\"/plugins/servlet/twofactor/configure\">2FA</a>"
                + "</header><div id=\"main\"><h1>Einmalcode bestätigen</h1>"
                + (error == null ? "" : "<div class=\"aui-message aui-message-error\"><p>" + error + "</p></div>")
                + "<form action=\"\" class=\"mo-aui\" id=\"otp-validate-form\" method=\"post\">"
                + "<input type=\"hidden\" name=\"resendOtpLinkClicked\" id=\"resendOtpLinkClicked\" value=\"false\">"
                + "<input type=\"hidden\" name=\"sendOtpClicked\" id=\"sendOtpClicked\" value=\"false\">"
                + "<input type=\"hidden\" name=\"mo2fa_return_url\" value=\"r+ück/sprung==\">"
                + "<div class=\"aui-message aui-message-info\" id=\"infoMessage\"><p>Code per E-Mail gesendet.</p></div>"
                + "<input class=\"mo-text\" id=\"otpCode\" name=\"otpCode\" type=\"password\" value=\"\" required "
                + "autocomplete=\"new-password\">"
                + "<button id=\"showContent\" class=\"aui-button\" type=\"button\">zeigen</button>"
                + "<a href=\"#\" onclick=\"return resetOtpCalled()\">Resend OTP</a>"
                + "<input class=\"aui-button\" id=\"otp-validate-form-submit\" name=\"Validate\" type=\"submit\" "
                + "value=\"Validate\"></form></div></body></html>";
    }

    private final List<String> lines = new ArrayList<String>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            lines.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger logger = Logger.getLogger(ConfluenceRestClient.class.getName());

    /** Was der Fake-Server sieht und wie er sich verhält. */
    private final List<Map<String, String>> fileRequests = new ArrayList<Map<String, String>>();
    private final List<String> posts = new ArrayList<String>();
    private final List<Map<String, String>> postHeaders = new ArrayList<Map<String, String>>();
    private boolean verified;
    private int sessions;
    private String drawioShape = "rect";

    @Before
    public void listen() {
        logger.addHandler(capture);
    }

    @After
    public void stopListening() {
        logger.removeHandler(capture);
    }

    @Test
    public void theFormIsReadFromTheUnchangedPage() {
        OneTimeCodeForm form = OneTimeCodeForm.parse(URI.create(BASE + OTP_PATH + "?x=1"),
                otpPage(null).getBytes(StandardCharsets.UTF_8), URI.create(BASE));

        assertNotNull(form);
        assertEquals(URI.create(BASE + OTP_PATH + "?x=1"), form.action());
        assertEquals("otp-validate-form", form.formId());
        assertEquals("Einmalcode bestätigen", form.title());
        assertEquals("Code per E-Mail gesendet.", form.message());
        assertEquals("", form.error());
        assertTrue(form.resendAvailable());
        assertEquals(Arrays.asList("resendOtpLinkClicked", "sendOtpClicked", "mo2fa_return_url", "otpCode",
                "Validate"), form.fieldNames());
        assertEquals("resendOtpLinkClicked=false&sendOtpClicked=false&mo2fa_return_url=r%2B%C3%BCck%2Fsprung%3D%3D"
                + "&otpCode=12+34&Validate=Validate", new String(form.submitBody("12 34".toCharArray()),
                StandardCharsets.US_ASCII));
        assertEquals("resendOtpLinkClicked=true&sendOtpClicked=false&mo2fa_return_url=r%2B%C3%BCck%2Fsprung%3D%3D"
                + "&otpCode=", new String(form.resendBody(), StandardCharsets.US_ASCII));
        assertFalse(form.toString(), form.toString().contains("sprung"));
    }

    @Test
    public void aFormThatPostsToAnotherHostIsNotAccepted() {
        String page = otpPage(null).replace("action=\"\"", "action=\"https://fremd.invalid/otp\"");
        assertNull(OneTimeCodeForm.parse(URI.create(BASE + OTP_PATH), page.getBytes(StandardCharsets.UTF_8),
                URI.create(BASE)));
    }

    @Test
    public void pdfAfterTheCodeAndThenAnImageWithoutAskingAgain() throws Exception {
        ConfluenceKnowledgeSource source = source();
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        assertEquals(DownloadAccess.OPEN, source.downloadAccess());

        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadAttachment(pdf));
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());
        assertEquals("confluence.invalid", challenge.service());
        assertEquals("Einmalcode bestätigen", challenge.title());
        assertEquals("Code per E-Mail gesendet.", challenge.message());
        assertTrue(challenge.resendAvailable());

        assertNull("angenommen", challenge.submit("482913".toCharArray()));
        assertEquals(DownloadAccess.UNLOCKED, source.downloadAccess());
        assertEquals(1, posts.size());
        assertTrue(posts.get(0), posts.get(0).contains("otpCode=482913")
                && posts.get(0).contains("mo2fa_return_url=r%2B%C3%BCck%2Fsprung%3D%3D"));
        assertEquals("dieselbe Sitzung wie die Einmalcode-Seite", "JSESSIONID=sitzung-1",
                postHeaders.get(0).get("Cookie"));

        assertArrayEquals(PDF, source.loadAttachment(pdf));
        Map<String, String> last = fileRequests.get(fileRequests.size() - 1);
        assertEquals("JSESSIONID=sitzung-1", last.get("Cookie"));
        assertFalse("wie der Browser nur mit der Sitzung", last.containsKey("Authorization"));
        assertArrayEquals("keine zweite Abfrage, solange die Sitzung gilt", PNG,
                source.loadViewImage(BASE + "/download/attachments/7/bild.png?version=1"));
        assertEquals(1, sessions);
        assertEquals(1, posts.size());

        boolean confirmed = false;
        for (String line : lines) {
            assertFalse(line, line.contains("482913") || line.contains("sitzung-1") || line.contains("sprung"));
            confirmed |= line.startsWith("[Confluence] Einmalcode bestätigt");
        }
        assertTrue(lines.toString(), confirmed);
    }

    @Test
    public void aWrongCodeComesBackWithTheMessageOfThePage() throws Exception {
        ConfluenceKnowledgeSource source = source();
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadAttachment(pdf));

        OneTimeCodeChallenge again = challenge.submit("000000".toCharArray());
        assertNotNull(again);
        assertEquals("Ungültiger Code.", again.error());
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());
        assertNull(again.submit("482913".toCharArray()));
        assertArrayEquals(PDF, source.loadAttachment(pdf));
    }

    @Test
    public void resendPostsTheResendSwitchWithoutTheButton() throws Exception {
        ConfluenceKnowledgeSource source = source();
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadAttachment(pdf));

        OneTimeCodeChallenge after = challenge.resend();
        assertNotNull(after);
        assertEquals(1, posts.size());
        assertTrue(posts.get(0), posts.get(0).contains("resendOtpLinkClicked=true")
                && !posts.get(0).contains("Validate="));
        assertNull(after.submit("482913".toCharArray()));
    }

    @Test
    public void anExpiredSessionAsksAgainOnlyWhenAFileIsRequested() throws Exception {
        ConfluenceKnowledgeSource source = source();
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        assertNull(challengeOf(() -> source.loadAttachment(pdf)).submit("482913".toCharArray()));
        assertArrayEquals(PDF, source.loadAttachment(pdf));

        verified = false; // Sitzung auf dem Server abgelaufen
        OneTimeCodeChallenge again = challengeOf(() -> source.loadViewImage(BASE + "/download/attachments/7/bild.png"));
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());
        assertNull(again.submit("482913".toCharArray()));
        assertArrayEquals(PNG, source.loadViewImage(BASE + "/download/attachments/7/bild.png"));
    }

    @Test
    public void attachmentsAreListedAndLoadedAsFilesWithTheirOriginAfterTheCode() throws Exception {
        ConfluenceKnowledgeSource source = source();
        List<KnowledgeResource> attachments = source.attachmentsOf(page());
        assertEquals(1, attachments.size());
        KnowledgeResource pdf = attachments.get(0);
        assertTrue(source.isAttachment(pdf.id()));
        assertFalse(source.isAttachment(page()));
        assertEquals("Handbuch.pdf", pdf.title());
        assertEquals("application/pdf", pdf.contentType());
        assertEquals(page(), pdf.parentId().get());
        assertEquals("10", pdf.metadata().get(KnowledgeMetadata.FILE_SIZE).get());

        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadAttachmentFile(pdf.id()).content());
        assertNull(challenge.submit("482913".toCharArray()));
        SourceFile file = source.loadAttachmentFile(pdf.id());
        assertArrayEquals(PDF, file.content());
        assertEquals(pdf.id(), file.resource().id());
        assertEquals(page(), file.resource().parentId().get());
    }

    /**
     * Issue #104: The drawing is not drawable (unsupported shape), the PNG preview is behind the one-time code. The
     * challenge must arrive as such, not as a plain image error; after the code the preview loads.
     */
    @Test
    public void anUndrawableDiagramAsksForTheCodeOfItsPreviewAndThenShowsThePreview() throws Exception {
        drawioShape = "ext";
        ConfluenceKnowledgeSource source = source();

        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadViewImage(DIAGRAM));
        assertEquals(DownloadAccess.ONE_TIME_CODE_REQUIRED, source.downloadAccess());
        assertEquals("Einmalcode bestätigen", challenge.title());

        assertNull(challenge.submit("482913".toCharArray()));
        assertArrayEquals(PNG, source.loadViewImage(DIAGRAM));
        assertArrayEquals("Original nach dem Code ohne neue Abfrage", PNG,
                source.loadViewImage(DIAGRAM + "&view=original"));
        assertEquals(1, posts.size());
    }

    /** Drawable diagram: shown as SVG without asking; only the original view asks for the code. */
    @Test
    public void aDrawableDiagramNeedsNoCodeButItsOriginalDoes() throws Exception {
        ConfluenceKnowledgeSource source = source();

        String svg = new String(source.loadViewImage(DIAGRAM), StandardCharsets.UTF_8);
        assertTrue(svg, svg.contains("<svg"));
        assertTrue("keine Datei angefragt", fileRequests.isEmpty());
        assertEquals(DownloadAccess.OPEN, source.downloadAccess());

        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadViewImage(DIAGRAM + "&view=original"));
        assertNull(challenge.submit("482913".toCharArray()));
        assertArrayEquals(PNG, source.loadViewImage(DIAGRAM + "&view=original"));
    }

    private static final String DIAGRAM = BASE + "/download/attachments/7/Bild%20A.png?version=3&api=v2"
            + "#drawio=Bild%20A&page=7&revision=3";

    private static String drawio(String shape) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("name", "Bild A");
        json.addProperty("xml", "<mxfile><diagram id=\"d\"><mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"a\" value=\"Kasten\" style=\"shape=" + shape
                + ";\" vertex=\"1\" parent=\"1\"><mxGeometry width=\"80\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell></root></mxGraphModel></diagram></mxfile>");
        return json.toString();
    }

    private interface Load {
        byte[] run() throws KnowledgeSourceException;
    }

    private static OneTimeCodeChallenge challengeOf(Load load) throws KnowledgeSourceException {
        try {
            load.run();
        } catch (OneTimeCodeRequiredException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("Einmalcode (OTP)"));
            return e.challenge();
        }
        fail("ohne Einmalcode keine Datei");
        return null;
    }

    private ConfluenceKnowledgeSource source() {
        ConfluenceConfig config = ConfluenceConfig.builder(URI.create(BASE)).credentialRef(RecordingSecretProvider.REF)
                .build();
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"), config, transport(),
                new RecordingSecretProvider());
    }

    /**
     * Fake-Confluence mit Zwei-Faktor-Plugin: REST geht mit Basic; {@code /download/} ohne bestätigte Sitzung setzt
     * eine Sitzung und liefert (nach der Weiterleitung) die Einmalcode-Seite; das Formular nimmt nur den richtigen
     * Code für genau diese Sitzung.
     */
    private ConfluenceHttpTransport transport() {
        return new ConfluenceHttpTransport() {
            @Override
            public ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) {
                String path = uri.getRawPath();
                if (path.equals("/rest/api/content/7")) {
                    return json(PAGE);
                }
                if (path.equals("/rest/api/content/7/child/attachment")) {
                    return json("{\"results\":[" + ATTACHMENT + "],\"size\":1,\"_links\":{}}");
                }
                if (path.equals("/rest/api/content/att101")) {
                    return json(ATTACHMENT);
                }
                if (path.startsWith("/rest/drawio/")) {
                    return json(drawio(drawioShape));
                }
                if (path.startsWith("/rest/")) {
                    return json("{\"type\":\"known\",\"username\":\"beispiel\"}");
                }
                fileRequests.add(headers);
                boolean ourSession = "JSESSIONID=sitzung-1".equals(headers.get("Cookie"));
                if (verified && ourSession) {
                    return new ConfluenceHttpResponse(200, path.endsWith(".pdf") ? "application/pdf" : "image/png",
                            path.endsWith(".pdf") ? PDF : PNG);
                }
                List<String> cookies = Collections.emptyList();
                if (!ourSession) {
                    sessions++;
                    cookies = Collections.singletonList("JSESSIONID=sitzung-1");
                }
                return otp(null, cookies);
            }

            @Override
            public ConfluenceHttpResponse postForm(URI uri, Map<String, String> headers, byte[] form, int maxBytes)
                    throws IOException {
                assertEquals(BASE + OTP_PATH, uri.toString());
                String body = new String(form, StandardCharsets.US_ASCII);
                posts.add(body);
                postHeaders.add(headers);
                if (!"JSESSIONID=sitzung-1".equals(headers.get("Cookie"))) {
                    throw new IOException("fremde Sitzung");
                }
                if (body.contains("resendOtpLinkClicked=true")) {
                    return otp(null, Collections.<String>emptyList());
                }
                if (body.contains("otpCode=482913&Validate=Validate")) {
                    verified = true;
                    return new ConfluenceHttpResponse(200, "text/html", "<html><body>Dashboard</body></html>"
                            .getBytes(StandardCharsets.UTF_8), null, Collections.<String>emptyList(),
                            URI.create(BASE + "/download/attachments/7/Handbuch.pdf"),
                            Collections.singletonList(BASE + "/download/attachments/7/Handbuch.pdf"));
                }
                return otp("Ungültiger Code.", Collections.<String>emptyList());
            }
        };
    }

    private static ConfluenceHttpResponse otp(String error, List<String> cookies) {
        return new ConfluenceHttpResponse(200, "text/html;charset=UTF-8", otpPage(error).getBytes(StandardCharsets.UTF_8),
                null, cookies, URI.create(BASE + OTP_PATH), Collections.singletonList(BASE + OTP_PATH));
    }

    private static ConfluenceHttpResponse json(String body) {
        return new ConfluenceHttpResponse(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeResourceId page() {
        return KnowledgeResourceId.of("confluence:confluence.invalid/page/7");
    }
}
