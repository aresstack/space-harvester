package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PageView;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Anhänge im Lesebereich: die Lese-Ansicht nennt alle Anhänge der Seite (unabhängig von {@code includeAttachments}),
 * die Originaldatei kommt über denselben angemeldeten Weg mit {@code Accept: *}{@code /*}, Vorschauen über die
 * Dokumentkonvertierung nach dem Pfadmuster, das Confluence selbst in der Seite und im Files-Endpunkt nennt.
 * Dateinamen und IDs sind erfunden.
 */
public class ConfluenceAttachmentsTest {

    private static final String BASE = "https://confluence.invalid/confluence";
    private static final byte[] PDF = "%PDF-1.7\n%Test\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    private static final String PAGE = "{\"id\":\"7\",\"type\":\"page\",\"title\":\"Seite\","
            + "\"body\":{\"export_view\":{\"value\":\"<p>Text</p>\"}},\"_links\":{\"webui\":\"/pages/7\"}}";

    private static String attachmentJson(String id, String title, String mediaType, long size, String version) {
        return "{\"id\":\"" + id + "\",\"type\":\"attachment\",\"title\":\"" + title + "\","
                + "\"version\":{\"number\":" + version + "},\"container\":{\"id\":\"7\"},"
                + "\"metadata\":{\"mediaType\":\"" + mediaType + "\"},\"extensions\":{\"mediaType\":\"" + mediaType
                + "\",\"fileSize\":" + size + "},\"_links\":{\"download\":\"/download/attachments/7/"
                + title.replace(" ", "%20") + "?version=" + version + "&modificationDate=1&api=v2\","
                + "\"webui\":\"/pages/viewpage.action?pageId=7&preview=" + id + "\"}}";
    }

    private static final String PDF_ATTACHMENT = attachmentJson("att101", "Handbuch Beispiel.pdf",
            "application/pdf", 470000, "2");
    private static final String SHEET_ATTACHMENT = attachmentJson("att102", "Planung.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 84000, "1");
    private static final String UNKNOWN_ATTACHMENT = attachmentJson("att103", "daten.bin",
            "application/x-unbekannt", 12, "1");

    private final List<URI> requests = new ArrayList<URI>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<Map<String, String>>();
    private final Map<String, ConfluenceHttpResponse> byPath = new HashMap<String, ConfluenceHttpResponse>();
    private ConfluenceHttpResponse restLogin;
    private String extraAttachments = "";
    private ConfluenceHttpResponse withSession;

    private ConfluenceKnowledgeSource source(boolean includeAttachments) {
        ConfluenceHttpTransport transport = (uri, sent, maxBytes) -> {
            requests.add(uri);
            sentHeaders.add(sent);
            String path = uri.getRawPath();
            if (path.equals("/confluence/rest/api/content/7")) {
                return json(PAGE);
            }
            if (path.equals("/confluence/rest/api/content/7/child/attachment")) {
                return json("{\"results\":[" + PDF_ATTACHMENT + "," + SHEET_ATTACHMENT + "," + UNKNOWN_ATTACHMENT
                        + extraAttachments + "],\"size\":3,\"_links\":{}}");
            }
            if (path.equals("/confluence/rest/api/content/att101")) {
                return json(PDF_ATTACHMENT);
            }
            if (path.equals("/confluence/rest/api/content/att102")) {
                return json(SHEET_ATTACHMENT);
            }
            if (path.equals("/confluence/rest/api/user/current") && restLogin != null) {
                return restLogin;
            }
            if (withSession != null && sent.containsKey("Cookie")) {
                return withSession;
            }
            if (path.equals("/confluence/login.action")) {
                return new ConfluenceHttpResponse(200, "text/html", "<html>Anmelden</html>".getBytes(
                        StandardCharsets.UTF_8));
            }
            ConfluenceHttpResponse known = byPath.get(path);
            return known != null ? known : new ConfluenceHttpResponse(404, "text/plain", new byte[0]);
        };
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(URI.create(BASE)).credentialRef(RecordingSecretProvider.REF)
                        .includeAttachments(includeAttachments).build(), transport, new RecordingSecretProvider());
    }

    private static ConfluenceHttpResponse json(String body) {
        return new ConfluenceHttpResponse(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeResourceId page() {
        return KnowledgeResourceId.of("confluence:confluence.invalid/confluence/page/7");
    }

    @Test
    public void theReadingViewListsEveryAttachmentWhateverIncludeAttachmentsSays() throws Exception {
        PageView view = source(false).pageView(page());

        assertEquals(3, view.attachments().size());
        PageView.Attachment pdf = view.attachments().get(0);
        assertEquals("att101", pdf.id());
        assertEquals("7", pdf.containerId());
        assertEquals("Handbuch Beispiel.pdf", pdf.fileName());
        assertEquals("application/pdf", pdf.mediaType());
        assertEquals("2", pdf.version());
        assertEquals(470000L, pdf.size());
        assertEquals("/download/attachments/7/Handbuch%20Beispiel.pdf?version=2&modificationDate=1&api=v2",
                pdf.downloadPath());
        assertTrue(pdf.webUiPath().startsWith("/pages/viewpage.action"));
        assertEquals(EnumSet.allOf(PageView.Preview.class), pdf.previews());
        assertEquals("daten.bin", view.attachments().get(2).fileName());
        assertEquals("unbekannter Typ bleibt in der Liste", "application/x-unbekannt",
                view.attachments().get(2).mediaType());
    }

    @Test
    public void aFailingAttachmentListLeavesThePageReadable() throws Exception {
        ConfluenceKnowledgeSource source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(URI.create(BASE)).build(), (uri, sent, maxBytes) ->
                uri.getRawPath().endsWith("/child/attachment") ? new ConfluenceHttpResponse(500, "text/plain",
                        new byte[0]) : json(PAGE), null);

        PageView view = source.pageView(page());

        assertTrue(view.html().contains("Text"));
        assertTrue(view.attachments().isEmpty());
    }

    @Test
    public void theOriginalFileComesOverTheSignedInPathWithFreshMetadata() throws Exception {
        byPath.put("/confluence/download/attachments/7/Handbuch%20Beispiel.pdf",
                new ConfluenceHttpResponse(200, "application/pdf;charset=UTF-8", PDF));
        ConfluenceKnowledgeSource source = source(false);
        PageView.Attachment listed = source.pageView(page()).attachments().get(0);
        // Was die Oberfläche zurückgibt, zählt nicht: Pfad und Version liest der Adapter frisch.
        PageView.Attachment tampered = new PageView.Attachment(listed.id(), "7", "x.pdf", "application/pdf", "9", 1,
                "/etwas/anderes", "", null);
        requests.clear();
        sentHeaders.clear();

        assertArrayEquals(PDF, source.loadAttachment(tampered));

        assertEquals("/confluence/rest/api/content/att101", requests.get(0).getRawPath());
        URI download = requests.get(1);
        assertEquals("/confluence/download/attachments/7/Handbuch%20Beispiel.pdf", download.getRawPath());
        assertEquals("version=2&modificationDate=1&api=v2", download.getRawQuery());
        assertEquals("*/*", sentHeaders.get(1).get("Accept"));
        assertTrue("angemeldet wie alle Aufrufe", sentHeaders.get(1).containsKey("Authorization"));
    }

    @Test
    public void aLoginPageInsteadOfTheFileIsRejected() throws Exception {
        byPath.put("/confluence/download/attachments/7/Handbuch%20Beispiel.pdf",
                new ConfluenceHttpResponse(200, "text/html;charset=UTF-8",
                        "<html>Anmelden</html>".getBytes(StandardCharsets.UTF_8)));
        ConfluenceKnowledgeSource source = source(false);
        try {
            source.loadAttachment(source.pageView(page()).attachments().get(0));
            fail("Anmeldeseite ist kein PDF");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("Anmeldeseite"));
        }
    }

    @Test
    public void previewsUseTheDocumentConversionPathConfluenceNamesItself() throws Exception {
        byPath.put("/confluence/rest/documentConversion/latest/conversion/convert/102/1",
                new ConfluenceHttpResponse(200, "application/pdf", PDF));
        byPath.put("/confluence/rest/documentConversion/latest/conversion/poster/102/1",
                new ConfluenceHttpResponse(200, "image/png", PNG));
        ConfluenceKnowledgeSource source = source(false);
        PageView.Attachment sheet = source.pageView(page()).attachments().get(1);
        requests.clear();
        sentHeaders.clear();

        assertArrayEquals(PDF, source.loadAttachmentPreview(sheet, PageView.Preview.DOCUMENT));
        assertArrayEquals(PNG, source.loadAttachmentPreview(sheet, PageView.Preview.POSTER));

        assertEquals("/confluence/rest/documentConversion/latest/conversion/convert/102/1",
                requests.get(1).getRawPath());
        assertEquals("*/*", sentHeaders.get(1).get("Accept"));
    }

    @Test
    public void aMissingOrNonPreviewAnswerIsAnError() throws Exception {
        byPath.put("/confluence/rest/documentConversion/latest/conversion/poster/102/1",
                new ConfluenceHttpResponse(200, "text/html", "<html>Login</html>".getBytes(StandardCharsets.UTF_8)));
        ConfluenceKnowledgeSource source = source(false);
        PageView.Attachment sheet = source.pageView(page()).attachments().get(1);
        try {
            source.loadAttachmentPreview(sheet, PageView.Preview.THUMBNAIL);
            fail("404");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
        try {
            source.loadAttachmentPreview(sheet, PageView.Preview.POSTER);
            fail("HTML ist keine Vorschau");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
        }
    }

    @Test
    public void conversionPathFollowsThePatternFromConfluence() {
        assertEquals("/rest/documentConversion/latest/conversion/thumbnail/123/4",
                ConfluenceRestClient.conversionPath("att123", "4", "thumbnail"));
        assertEquals("convert", ConfluenceKnowledgeSource.conversionKind(PageView.Preview.DOCUMENT));
        assertEquals("poster", ConfluenceKnowledgeSource.conversionKind(PageView.Preview.POSTER));
        assertEquals("thumbnail", ConfluenceKnowledgeSource.conversionKind(PageView.Preview.THUMBNAIL));
    }

    @Test
    public void foreignAttachmentsAreRefused() {
        try {
            source(false).loadAttachment(new PageView.Attachment("datei-1", "", "a.txt", "text/plain", "", 1, "",
                    "", null));
            fail("keine Confluence-ID");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
        assertFalse(requests.iterator().hasNext());
    }

    private static ConfluenceHttpResponse signedInWithCookie() {
        return new ConfluenceHttpResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null,
                Arrays.asList("JSESSIONID=sitzung1", "seraph.confluence=sitzung2"));
    }

    @Test
    public void aLoginRedirectOnTheAttachmentIsRetriedOnceWithTheSessionCookieLikeImages() throws Exception {
        byPath.put("/confluence/download/attachments/7/Handbuch%20Beispiel.pdf",
                new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action?os_destination=x"));
        restLogin = signedInWithCookie();
        withSession = new ConfluenceHttpResponse(200, "application/pdf", PDF);
        ConfluenceKnowledgeSource source = source(false);
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        sentHeaders.clear();

        assertArrayEquals(PDF, source.loadAttachment(pdf));

        Map<String, String> last = sentHeaders.get(sentHeaders.size() - 1);
        assertEquals("JSESSIONID=sitzung1; seraph.confluence=sitzung2", last.get("Cookie"));
        assertEquals("*/*", last.get("Accept"));
        for (Map<String, String> sent : sentHeaders.subList(0, sentHeaders.size() - 1)) {
            assertFalse("vor dem Neuversuch kein Cookie", sent.containsKey("Cookie"));
        }
    }

    @Test
    public void aLoginPageInsteadOfThePreviewIsRetriedWithTheSessionCookie() throws Exception {
        byPath.put("/confluence/rest/documentConversion/latest/conversion/convert/102/1",
                new ConfluenceHttpResponse(200, "text/html", "<html>Anmelden</html>".getBytes(StandardCharsets.UTF_8)));
        restLogin = signedInWithCookie();
        withSession = new ConfluenceHttpResponse(200, "application/pdf", PDF);
        ConfluenceKnowledgeSource source = source(false);
        PageView.Attachment sheet = source.pageView(page()).attachments().get(1);

        assertArrayEquals(PDF, source.loadAttachmentPreview(sheet, PageView.Preview.DOCUMENT));
    }

    @Test
    public void whenTheCookieDoesNotHelpTheAttachmentReasonNamesBothAttempts() throws Exception {
        byPath.put("/confluence/download/attachments/7/Handbuch%20Beispiel.pdf",
                new ConfluenceHttpResponse(302, "text/html", new byte[0], BASE + "/login.action"));
        restLogin = signedInWithCookie();
        withSession = new ConfluenceHttpResponse(403, "text/html", new byte[0]);
        ConfluenceKnowledgeSource source = source(false);
        try {
            source.loadAttachment(source.pageView(page()).attachments().get(0));
            fail("403 auch mit Cookie");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
            assertTrue(e.getMessage(), e.getMessage().endsWith("(auch mit Sitzungs-Cookie aus der REST-Anmeldung)"));
        }
    }

    @Test
    public void anEntryWithoutUsableIdCostsOnlyItselfNotTheWholeList() throws Exception {
        extraAttachments = "," + attachmentJson("104", "ohne-praefix.txt", "text/plain", 3, "1")
                + "," + attachmentJson("att105", "zahl.txt", "text/plain", 3, "1").replace("\"id\":\"att105\"",
                "\"id\":105")
                + "," + attachmentJson("att106", "ohne-id.txt", "text/plain", 3, "1").replace("\"id\":\"att106\",", "")
                + "," + attachmentJson("att107", "objekt-id.txt", "text/plain", 3, "1").replace("\"id\":\"att107\"",
                "\"id\":{\"value\":107}")
                + "," + attachmentJson("att108", "fremd.txt", "text/plain", 3, "1").replace("\"id\":\"att108\"",
                "\"id\":\"x-108\"");

        List<PageView.Attachment> attachments = source(false).pageView(page()).attachments();

        List<String> names = new ArrayList<String>();
        for (PageView.Attachment attachment : attachments) {
            names.add(attachment.fileName() + "=" + attachment.id());
        }
        assertEquals(Arrays.asList("Handbuch Beispiel.pdf=att101", "Planung.xlsx=att102", "daten.bin=att103",
                "ohne-praefix.txt=att104", "zahl.txt=att105"), names);
    }

    @Test
    public void theIdShapeIsNamedWithoutItsValue() {
        assertEquals("fehlt", ConfluenceRestClient.idShape(new com.google.gson.JsonObject()));
        assertEquals("hat unerwartete Form (5 Zeichen, Text)", ConfluenceRestClient.idShape(
                com.google.gson.JsonParser.parseString("{\"id\":\"x-108\"}").getAsJsonObject()));
        assertEquals("att42", ConfluenceRestClient.attachmentId(
                com.google.gson.JsonParser.parseString("{\"id\":42}").getAsJsonObject()));
    }
}
