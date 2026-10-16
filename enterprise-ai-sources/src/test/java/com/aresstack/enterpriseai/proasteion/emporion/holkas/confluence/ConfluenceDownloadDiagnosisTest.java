package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.PageView;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Angelos Fall: {@code /download/attachments/...} liefert HTTP 200 mit einer HTML-Seite statt der Datei, auch nach
 * dem Cookie aus {@code /rest/api/user/current}. Der Client sagt jetzt, was das für eine Seite ist (Logzeile und
 * Diagnosedatei), welcher Benutzer laut REST angemeldet ist und welche Cookie-Namen gesendet und gesetzt wurden, nie
 * Werte. Seiten, Namen und Werte sind erfunden.
 */
public class ConfluenceDownloadDiagnosisTest {

    private static final String BASE = "https://confluence.invalid/confluence";
    private static final String PAGE = "{\"id\":\"7\",\"type\":\"page\",\"title\":\"Seite\","
            + "\"body\":{\"export_view\":{\"value\":\"<p>Text</p>\"}},\"_links\":{\"webui\":\"/pages/7\"}}";
    private static final String ATTACHMENT = "{\"id\":\"att101\",\"type\":\"attachment\",\"title\":\"Handbuch.pdf\","
            + "\"version\":{\"number\":1},\"container\":{\"id\":\"7\"},"
            + "\"extensions\":{\"mediaType\":\"application/pdf\",\"fileSize\":10},"
            + "\"_links\":{\"download\":\"/download/attachments/7/Handbuch.pdf?version=1&api=v2\"}}";

    /** Eine Zwei-Faktor-Abfrage in Confluence-Rahmen; das Benutzermenü verlinkt die 2FA-Einstellungen. */
    static final String TWO_FACTOR_PAGE = "<html><head><title>Bestätigung erforderlich - Confluence</title>"
            + "<meta name=\"ajs-remote-user\" content=\"\"><meta name=\"ajs-atl-token\" content=\"geheim-token\">"
            + "</head><body><header id=\"header\"><a href=\"/plugins/servlet/twofactor/configure\">Two Factor</a>"
            + "</header><div id=\"main\"><h1>Einmalcode eingeben</h1><p>Wir haben Ihnen einen Code per E-Mail "
            + "gesendet.</p><form id=\"otp-form\" method=\"post\" action=\"/confluence/plugins/servlet/verify?x=1\">"
            + "<input type=\"hidden\" name=\"state\" value=\"geheimer-zustand\">"
            + "<label for=\"otp\">Code</label><input type=\"text\" name=\"otp\" id=\"otp\" autocomplete=\"one-time-code\">"
            + "<button type=\"submit\">Bestätigen</button></form></div></body></html>";

    /** Eine gewöhnliche angemeldete Confluence-Seite: Der 2FA-Link im Kopf ist keine Abfrage. */
    static final String PLAIN_PAGE = "<html><head><title>Startseite - Confluence</title>"
            + "<meta name=\"ajs-remote-user\" content=\"beispiel\"></head><body><header id=\"header\">"
            + "<a href=\"/plugins/servlet/twofactor/configure\">Two Factor Configuration</a></header>"
            + "<div id=\"main\"><h1>Willkommen</h1><p>Inhalt</p></div></body></html>";

    static final String LOGIN_PAGE = "<html><head><title>Anmelden - Confluence</title></head><body><div id=\"main\">"
            + "<form id=\"loginform\" method=\"post\" action=\"/confluence/dologin.action\">"
            + "<input name=\"os_username\" type=\"text\" value=\"jemand\"><input name=\"os_password\" type=\"password\">"
            + "<input type=\"submit\" value=\"Anmelden\"></form></div></body></html>";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private final List<String> lines = new ArrayList<String>();
    private final List<Map<String, String>> sent = new ArrayList<Map<String, String>>();
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
    private String currentUser = "{\"type\":\"known\",\"username\":\"beispiel\"}";
    private String downloadPage = TWO_FACTOR_PAGE;

    @Before
    public void listen() {
        logger.addHandler(capture);
    }

    @After
    public void stopListening() {
        logger.removeHandler(capture);
    }

    @Test
    public void aTwoFactorPageIsRecognisedAndDescribedWithoutValues() {
        HtmlResponseDiagnosis page = HtmlResponseDiagnosis.of(URI.create(BASE + "/download/attachments/7/a.pdf"),
                TWO_FACTOR_PAGE.getBytes(StandardCharsets.UTF_8), Arrays.asList(BASE + "/login.action?x=1"));

        assertEquals(HtmlResponseDiagnosis.Kind.TWO_FACTOR, page.kind());
        assertEquals("Bestätigung erforderlich - Confluence", page.title());
        assertEquals("Einmalcode eingeben", page.heading());
        assertEquals("(anonym)", page.remoteUser().isEmpty() ? "(anonym)" : page.remoteUser());
        assertEquals(1, page.forms().size());
        String form = page.forms().get(0);
        assertTrue(form, form.startsWith("POST https://confluence.invalid/confluence/plugins/servlet/verify id=otp-form"));
        assertTrue(form, form.contains("state:hidden") && form.contains("otp:text") && form.contains("Bestätigen"));
        assertTrue(page.hosts().toString(), page.hosts().contains("confluence.invalid"));
        String summary = page.summary();
        assertFalse(summary, summary.contains("geheim"));
    }

    @Test
    public void theTwoFactorLinkInTheUserMenuOfANormalPageIsNoChallenge() {
        HtmlResponseDiagnosis page = HtmlResponseDiagnosis.of(URI.create(BASE + "/x"),
                PLAIN_PAGE.getBytes(StandardCharsets.UTF_8), new ArrayList<String>());

        assertEquals(HtmlResponseDiagnosis.Kind.CONFLUENCE_PAGE, page.kind());
        assertEquals("beispiel", page.remoteUser());
    }

    @Test
    public void theConfluenceLoginFormIsRecognised() {
        HtmlResponseDiagnosis page = HtmlResponseDiagnosis.of(URI.create(BASE + "/x"),
                LOGIN_PAGE.getBytes(StandardCharsets.UTF_8), new ArrayList<String>());

        assertEquals(HtmlResponseDiagnosis.Kind.CONFLUENCE_LOGIN, page.kind());
        assertFalse(page.summary(), page.summary().contains("jemand"));
    }

    @Test
    public void aHtmlAnswerToTheDownloadIsLoggedAndSavedWithoutSecrets() throws Exception {
        File directory = temp.newFolder("logs");
        ConfluenceKnowledgeSource source = source(directory);
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        try {
            source.loadAttachment(pdf);
            fail("HTML ist kein PDF");
        } catch (KnowledgeSourceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("[Seite: TWO_FACTOR „Bestätigung erforderlich"));
        }

        String examined = null;
        for (String line : lines) {
            if (line.contains("HTML statt Datei untersucht")) {
                examined = line;
            }
            assertFalse(line, line.contains("wert-1") || line.contains("wert-2") || line.contains("geheim")
                    || line.contains("Basic dGVzdA"));
        }
        assertNotNull(lines.toString(), examined);
        assertTrue(examined, examined.contains("art=TWO_FACTOR"));
        assertTrue(examined, examined.contains("gesendet={authorization=Basic, cookies=[], accept=*/*}"));
        assertTrue(examined, examined.contains("gesetzteCookies=[DOWNLOADSESSION]"));
        assertTrue(examined, examined.contains("adresse=" + BASE + "/download/attachments/7/Handbuch.pdf "));
        for (String line : lines) {
            assertFalse("eine Einmalcode-Seite löst kein Cookie aus user/current", line.contains("benutzerTyp="));
        }

        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals("ohne Abfrage (keine Oberfläche) ein Versuch, eine Datei", 1, files.length);
        for (File file : files) {
            assertTrue(file.getName(), file.getName().matches("confluence-failed-download-\\d{8}-\\d{6}-\\d{3}\\.html"));
            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(html, html.contains("Einmalcode eingeben") && html.contains("name=\"otp\""));
            assertFalse(html, html.contains("geheim") || html.contains("wert-1") || html.contains("wert-2")
                    || html.contains("Basic dGVzdA"));
        }
    }

    @Test
    public void anAnonymousRestUserIsNoSessionAndIsNotRetried() throws Exception {
        currentUser = "{\"type\":\"anonymous\"}";
        downloadPage = LOGIN_PAGE;
        ConfluenceKnowledgeSource source = source(null);
        PageView.Attachment pdf = source.pageView(page()).attachments().get(0);
        sent.clear();
        try {
            source.loadAttachment(pdf);
            fail("HTML ist kein PDF");
        } catch (KnowledgeSourceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("anonymen Benutzer"));
        }
        for (Map<String, String> headers : sent) {
            assertFalse("kein Neuversuch mit dem Cookie einer anonymen Sitzung", headers.containsKey("Cookie"));
        }
    }

    private ConfluenceKnowledgeSource source(File diagnostics) {
        ConfluenceHttpTransport transport = (uri, headers, maxBytes) -> {
            sent.add(headers);
            String path = uri.getRawPath();
            if (path.equals("/confluence/rest/api/content/7")) {
                return json(PAGE);
            }
            if (path.equals("/confluence/rest/api/content/7/child/attachment")) {
                return json("{\"results\":[" + ATTACHMENT + "],\"size\":1,\"_links\":{}}");
            }
            if (path.equals("/confluence/rest/api/content/att101")) {
                return json(ATTACHMENT);
            }
            if (path.equals("/confluence/rest/api/user/current")) {
                return new ConfluenceHttpResponse(200, "application/json", currentUser.getBytes(StandardCharsets.UTF_8),
                        null, Arrays.asList("JSESSIONID=wert-1", "extra=wert-2"));
            }
            return new ConfluenceHttpResponse(200, "text/html;charset=UTF-8",
                    downloadPage.getBytes(StandardCharsets.UTF_8), null,
                    Arrays.asList("DOWNLOADSESSION=wert-1"), uri, new ArrayList<String>());
        };
        ConfluenceConfig.Builder config = ConfluenceConfig.builder(URI.create(BASE))
                .credentialRef(RecordingSecretProvider.REF);
        if (diagnostics != null) {
            config.diagnosticsDirectory(diagnostics.toPath());
        }
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"), config.build(), transport,
                new RecordingSecretProvider());
    }

    private static ConfluenceHttpResponse json(String body) {
        return new ConfluenceHttpResponse(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeResourceId page() {
        return KnowledgeResourceId.of("confluence:confluence.invalid/confluence/page/7");
    }
}
