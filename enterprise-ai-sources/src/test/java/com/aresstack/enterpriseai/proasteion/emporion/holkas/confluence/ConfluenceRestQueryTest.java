package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.RestQuerySource;
import com.aresstack.enterpriseai.source.api.RestResponse;
import org.junit.Before;
import org.junit.Test;

/** Beliebige GET-Abfragen unter {@code /rest/} ({@link RestQuerySource}) gegen den Fake, Antwort unverändert. */
public class ConfluenceRestQueryTest {

    private static final String HISTORY = "{ \"latest\": true,\n  \"createdBy\": {\"username\": \"autorin\"} }";

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.stub("/rest/api/content/102/history?expand=lastUpdated", HISTORY);
        confluence.stubStatus("/rest/confanalytics/1.0/content/viewsByDate?contentId=102", 404);
        confluence.stubStatus("/rest/api/content/103/history", 403);
        source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                confluence, new RecordingSecretProvider());
    }

    @Test
    public void bodyComesBackByteForByteWithTheSourcesCredentials() throws Exception {
        RestResponse response = source.restGet("/rest/api/content/102/history?expand=lastUpdated");

        assertEquals(HISTORY, response.bodyAsUtf8());
        assertTrue(response.contentType().contains("json"));
        assertEquals(1, confluence.requestCount("/rest/api/content/102/history"));
        assertTrue(confluence.requestHeaders().get(0).containsKey("Authorization"));
    }

    @Test
    public void statusFailuresKeepTheirKind() {
        try {
            source.restGet("/rest/confanalytics/1.0/content/viewsByDate?contentId=102");
            fail("404 erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
        try {
            source.restGet("/rest/api/content/103/history");
            fail("403 erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
    }

    @Test
    public void onlyPathsBelowTheRestRootAreSent() throws Exception {
        for (String bad : new String[] {"/download/attachments/1/x.png", "/rest/../login.action",
                "/rest/api//content", "/rest/api/content#x", "rest/api/content", "/rest/api/%2e%2e/x",
                "/rest/api/content?title=Prüfung"}) {
            try {
                source.restGet(bad);
                fail("abgelehnt erwartet: " + bad);
            } catch (KnowledgeSourceException e) {
                assertEquals(bad, Kind.UNSUPPORTED, e.kind());
            }
        }
        assertEquals(0, confluence.requests().size());
        assertFalse(ConfluenceRestClient.validRestPath(null));
        assertTrue(ConfluenceRestClient.validRestPath("/rest/experimental/content/1/version?limit=50&start=0"));
    }
}
