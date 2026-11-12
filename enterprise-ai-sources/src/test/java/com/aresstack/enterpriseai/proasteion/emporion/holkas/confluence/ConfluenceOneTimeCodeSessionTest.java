package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.DownloadAccess;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Space Harvester, Log vom 12.11.2026: Eine freigeschaltete Sitzung lud einen Anhang, dessen Datei im Dateispeicher
 * fehlt ({@code /attachmentnotfound.action}). Das galt als Anmeldeseite, die REST-Anmeldung eröffnete eine neue
 * Sitzung, die einen Einmalcode verlangte, und der Code ging dann mit dem Cookie der alten Sitzung hinaus: Confluence
 * antwortete bei jedem Code mit HTTP 500.
 */
public class ConfluenceOneTimeCodeSessionTest {

    private static final String BASE = "https://confluence.invalid";
    private static final String OTP_PATH = "/plugins/servlet/twofactor/validate_otpoveremail";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    private static final String IMAGE = BASE + "/download/attachments/7/bild.png?version=1";
    private static final String MISSING = BASE + "/download/attachments/7/fehlt.png?version=1";

    private final List<Map<String, String>> postHeaders = new ArrayList<Map<String, String>>();
    private int restLogins;
    private String unlockedSession;
    /** Mit Basic und ohne Cookie: 401 (nur REST nimmt Basic an), sonst die Einmalcode-Seite mit neuer Sitzung. */
    private boolean basicOnlyForRest;

    @Test
    public void aMissingFileIsNotFoundAndKeepsTheUnlockedSession() throws Exception {
        ConfluenceKnowledgeSource source = source();
        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadViewImage(IMAGE));
        assertNull(challenge.submit("482913".toCharArray()));
        assertEquals(DownloadAccess.UNLOCKED, source.downloadAccess());

        try {
            source.loadViewImage(MISSING);
            fail("die Datei fehlt");
        } catch (OneTimeCodeRequiredException e) {
            fail("keine neue Einmalcode-Abfrage für eine fehlende Datei");
        } catch (KnowledgeSourceException e) {
            assertEquals(e.getMessage(), Kind.NOT_FOUND, e.kind());
        }
        assertEquals("keine REST-Anmeldung, die die Sitzung ersetzt", 0, restLogins);
        assertEquals(DownloadAccess.UNLOCKED, source.downloadAccess());
        assertArrayEquals(PNG, source.loadViewImage(IMAGE));
        assertEquals(1, postHeaders.size());
    }

    @Test
    public void theCodeGoesToTheSessionOfTheRestLoginThatReachedTheCodePage() throws Exception {
        basicOnlyForRest = true;
        ConfluenceKnowledgeSource source = source();

        OneTimeCodeChallenge challenge = challengeOf(() -> source.loadViewImage(IMAGE));
        assertEquals(1, restLogins);
        assertNull("angenommen", challenge.submit("482913".toCharArray()));
        assertEquals("JSESSIONID=sitzung-rest", postHeaders.get(0).get("Cookie"));
        assertArrayEquals(PNG, source.loadViewImage(IMAGE));
    }

    private interface Load {
        byte[] run() throws KnowledgeSourceException;
    }

    private static OneTimeCodeChallenge challengeOf(Load load) throws KnowledgeSourceException {
        try {
            load.run();
        } catch (OneTimeCodeRequiredException e) {
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

    private ConfluenceHttpTransport transport() {
        return new ConfluenceHttpTransport() {
            @Override
            public ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) {
                String path = uri.getRawPath();
                String cookie = headers.get("Cookie");
                if (path.equals("/rest/api/user/current")) {
                    restLogins++;
                    return new ConfluenceHttpResponse(200, "application/json",
                            "{\"type\":\"known\",\"username\":\"beispiel\"}".getBytes(StandardCharsets.UTF_8), null,
                            Collections.singletonList("JSESSIONID=sitzung-rest"));
                }
                if (cookie != null && cookie.equals(unlockedSession)) {
                    if (path.endsWith("/fehlt.png")) {
                        return html("<html><head><title>Attachment File Not Found - Confluence</title>"
                                + "<meta name=\"ajs-remote-user\" content=\"beispiel\"></head><body><div id=\"main\">"
                                + "<div class=\"aui-message aui-message-error\">The attachment you were trying to "
                                + "download could not be found.</div></div></body></html>",
                                Collections.<String>emptyList(), "/attachmentnotfound.action");
                    }
                    return new ConfluenceHttpResponse(200, "image/png", PNG);
                }
                if (basicOnlyForRest && cookie == null) {
                    return new ConfluenceHttpResponse(401, "text/html", new byte[0]);
                }
                if (cookie != null) {
                    return otp(Collections.<String>emptyList());
                }
                return otp(Collections.singletonList("JSESSIONID=sitzung-1"));
            }

            @Override
            public ConfluenceHttpResponse postForm(URI uri, Map<String, String> headers, byte[] form, int maxBytes) {
                postHeaders.add(headers);
                String cookie = headers.get("Cookie");
                if (cookie == null || !(basicOnlyForRest ? "JSESSIONID=sitzung-rest" : "JSESSIONID=sitzung-1")
                        .equals(cookie)) {
                    // Wie miniOrange: der Code einer fremden Sitzung endet auf der Fehlerseite.
                    return new ConfluenceHttpResponse(500, "text/html", new byte[0], null,
                            Collections.<String>emptyList(), URI.create(BASE + "/error_path/500.html"),
                            Collections.singletonList(BASE + "/error_path/500.html"));
                }
                unlockedSession = cookie;
                return html("<html><body>Dashboard</body></html>", Collections.<String>emptyList(),
                        "/download/attachments/7/bild.png");
            }
        };
    }

    private static ConfluenceHttpResponse otp(List<String> cookies) {
        String page = ConfluenceOneTimeCodeTest.otpPage(null);
        return html(page, cookies, OTP_PATH);
    }

    private static ConfluenceHttpResponse html(String body, List<String> cookies, String at) {
        return new ConfluenceHttpResponse(200, "text/html;charset=UTF-8", body.getBytes(StandardCharsets.UTF_8), null,
                cookies, URI.create(BASE + at), Collections.singletonList(BASE + at));
    }

}
