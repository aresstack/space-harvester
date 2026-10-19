package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MediaWikiClientTest {

    private final FakeMediaWikiTransport wiki = new FakeMediaWikiTransport();

    private MediaWikiClient anonymous() {
        return new MediaWikiClient(MediaWikiSiteConfig.builder("w", "https://wiki.example/w").build(), wiki,
                MediaWikiCredentialsProvider.anonymous());
    }

    @Test
    public void parsesPageAndFollowsRedirect() throws Exception {
        wiki.page("Ziel", "<p>Inhalt</p>", 5, 50);
        wiki.redirects.put("Alt", "Ziel");

        WikiParsedPage page = anonymous().parse("Alt");

        assertEquals("Ziel", page.title());
        assertEquals(50L, page.revisionId());
        Map<String, String> request = wiki.requests.get(0);
        assertEquals("parse", request.get("action"));
        assertEquals("1", request.get("redirects"));
        assertEquals("2", request.get("formatversion"));
    }

    @Test
    public void missingPageIsNotFound() {
        try {
            anonymous().parse("Gibt es nicht");
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void followsLinkContinuation() throws Exception {
        wiki.linkPageSize = 2;
        wiki.page("A", "", 1, 1, "B", "C", "D", "E", "F");

        List<String> links = anonymous().outgoingLinks("A");

        assertEquals(Arrays.asList("B", "C", "D", "E", "F"), links);
        assertEquals(3, wiki.requests.size());
        assertEquals("0", wiki.requests.get(0).get("plnamespace"));
    }

    @Test
    public void batchesPageInfoRequests() throws Exception {
        String[] titles = new String[120];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = "P" + i;
            wiki.page(titles[i], "", i + 1, i + 1);
        }

        List<WikiPageInfo> infos = anonymous().pageInfos(Arrays.asList(titles));

        assertEquals(120, infos.size());
        assertEquals(3, wiki.requests.size());
    }

    @Test
    public void searchCapsLimitAndReturnsTitles() throws Exception {
        wiki.page("Alpha", "<p>REST Schnittstelle</p>", 1, 1);
        wiki.page("Beta", "<p>anderes</p>", 2, 1);

        List<WikiSearchHit> hits = anonymous().search("REST", 500);

        assertEquals(1, hits.size());
        assertEquals("Alpha", hits.get(0).title());
        assertEquals("50", wiki.requests.get(0).get("srlimit"));
    }

    @Test
    public void logsInOnceWithFreshCredentialsAndClearsThem() throws Exception {
        wiki.requiredUser = "bot";
        wiki.requiredPassword = "geh€im";
        wiki.page("A", "<p>x</p>", 1, 1);
        final MediaWikiCredentials[] handedOut = new MediaWikiCredentials[1];
        MediaWikiClient client = new MediaWikiClient(
                MediaWikiSiteConfig.builder("w", "https://wiki.example/w").requiresLogin(true).build(), wiki,
                new MediaWikiCredentialsProvider() {
                    @Override
                    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) {
                        handedOut[0] = new MediaWikiCredentials("bot", "geh€im".toCharArray());
                        return handedOut[0];
                    }
                });

        client.parse("A");
        client.parse("A");

        assertEquals(1, wiki.logins);
        assertTrue(Arrays.equals(new char[6], handedOut[0].password()));
        assertFalse(handedOut[0].toString().contains("geh"));
    }

    @Test
    public void reLoginsOnceWhenSessionExpired() throws Exception {
        wiki.requiredUser = "bot";
        wiki.requiredPassword = "pw";
        wiki.page("A", "<p>x</p>", 1, 1);
        MediaWikiClient client = loggedInClient("bot", "pw");

        client.parse("A");
        wiki.sessionValid = false;
        client.parse("A");

        assertEquals(2, wiki.logins);
    }

    @Test
    public void staleDenialDoesNotDiscardSessionRenewedByAnotherThread() throws Exception {
        wiki.requiredUser = "bot";
        wiki.requiredPassword = "pw";
        wiki.page("A", "<p>x</p>", 1, 1);
        final MediaWikiClient client = loggedInClient("bot", "pw");
        client.parse("A");
        wiki.sessionValid = false;
        final Exception[] other = new Exception[1];
        // Während Thread 1 noch auf seine Ablehnung wartet, meldet Thread 2 die Sitzung neu an.
        wiki.beforeFirstDenial = new Runnable() {
            @Override
            public void run() {
                Thread second = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            client.parse("A");
                        } catch (Exception e) {
                            other[0] = e;
                        }
                    }
                });
                second.start();
                try {
                    second.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        client.parse("A");

        assertEquals(null, other[0]);
        assertEquals(2, wiki.logins);
    }

    @Test
    public void transientApiErrorDuringLoginStaysUnavailable() {
        wiki.requiredUser = "bot";
        wiki.requiredPassword = "pw";
        wiki.readOnly = true;
        wiki.page("A", "<p>x</p>", 1, 1);
        try {
            loggedInClient("bot", "pw").parse("A");
            fail("expected exception");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
        }
    }

    @Test
    public void wrongPasswordIsAccessDeniedWithoutSecretInMessage() {
        wiki.requiredUser = "bot";
        wiki.requiredPassword = "richtig";
        try {
            loggedInClient("bot", "falsch-geheim").parse("A");
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
            assertFalse(e.getMessage().contains("falsch-geheim"));
            assertFalse(e.getMessage().contains("tok"));
        }
    }

    @Test
    public void credentialFailureIsAccessDeniedWithoutCause() {
        MediaWikiClient client = new MediaWikiClient(
                MediaWikiSiteConfig.builder("w", "https://wiki.example/w").requiresLogin(true).build(), wiki,
                new MediaWikiCredentialsProvider() {
                    @Override
                    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) throws Exception {
                        throw new IllegalStateException("vault locked: secret=abc");
                    }
                });
        try {
            client.parse("A");
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
            assertEquals(null, e.getCause());
            assertFalse(e.getMessage().contains("abc"));
        }
    }

    @Test
    public void mapsHttpStatusAndIoErrors() {
        assertTransportKind(new MediaWikiTransport.Response(403, "x"), null, Kind.ACCESS_DENIED);
        assertTransportKind(new MediaWikiTransport.Response(503, "x"), null, Kind.UNAVAILABLE);
        assertTransportKind(new MediaWikiTransport.Response(404, "x"), null, Kind.UNAVAILABLE);
        assertTransportKind(new MediaWikiTransport.Response(400, "x"), null, Kind.INVALID_RESPONSE);
        assertTransportKind(null, new IOException("https://wiki/api.php?token=s3cr3t"), Kind.UNAVAILABLE);
    }

    private void assertTransportKind(final MediaWikiTransport.Response response, final IOException error, Kind kind) {
        MediaWikiTransport transport = new MediaWikiTransport() {
            @Override
            public Response get(String query) throws IOException {
                if (error != null) {
                    throw error;
                }
                return response;
            }

            @Override
            public Response postForm(String formBody) throws IOException {
                return get(formBody);
            }

            @Override
            public void resetSession() {
            }
        };
        try {
            new MediaWikiClient(MediaWikiSiteConfig.builder("w", "https://wiki.example/w").build(), transport,
                    MediaWikiCredentialsProvider.anonymous()).parse("A");
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(kind, e.kind());
            assertFalse(e.getMessage().contains("s3cr3t"));
        }
    }

    private MediaWikiClient loggedInClient(final String user, final String password) {
        return new MediaWikiClient(
                MediaWikiSiteConfig.builder("w", "https://wiki.example/w").requiresLogin(true).build(), wiki,
                new MediaWikiCredentialsProvider() {
                    @Override
                    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) {
                        return new MediaWikiCredentials(user, password.toCharArray());
                    }
                });
    }
}
