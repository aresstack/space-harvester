package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.RestResponse;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.StoredContent;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JiraKnowledgeSourceTest {

    private static final SourceAddress SOURCE = SourceAddress.of("jira-dc");
    private static final String LOCATOR = "jira.example.org/jira";

    private FakeJira jira;
    private RecordingSecretProvider secrets;
    private JiraKnowledgeSource source;

    @Before
    public void setUp() {
        jira = JiraKnowledgeSourceContractTest.sample();
        secrets = new RecordingSecretProvider();
        source = source(JiraConfig.builder(FakeJira.BASE).credentialRef(RecordingSecretProvider.REF).pageSize(2).build());
    }

    private JiraKnowledgeSource source(JiraConfig config) {
        return new JiraKnowledgeSource(SOURCE, config, jira, secrets);
    }

    private static KnowledgeResourceId id(String key) {
        return KnowledgeResourceId.of("jira", LOCATOR + "/issue/" + key);
    }

    @Test
    public void partitionsAreTheProjects() throws Exception {
        List<SourcePartition> partitions = source.partitions();
        assertEquals(2, partitions.size());
        assertEquals("DEMO", partitions.get(0).key());
        assertEquals("Demo-Projekt", partitions.get(0).title());
        assertEquals("project:DEMO", partitions.get(0).startPoint());
        assertNull(partitions.get(0).homeResource());
    }

    @Test
    public void discoverWithoutStartPointsListsAllIssuesOfTheSelectedProjects() throws Exception {
        List<KnowledgeResource> all = source.discover(SourceScope.of("project:*"));
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-2"), id("DEMO-3"), id("OPS-7")), ids(all));
        // Zwei Stapel für DEMO (pageSize 2), einer für OPS; der zweite DEMO-Stapel ist voll, also ein leerer dritter.
        assertTrue(jira.jqls.toString(), jira.jqls.contains("project = \"DEMO\" ORDER BY key ASC"));

        List<KnowledgeResource> withoutOps = source.discover(
                SourceScope.of("project:*").withExcludedPartitions(Collections.singleton("OPS")));
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-2"), id("DEMO-3")), ids(withoutOps));
    }

    @Test
    public void discoverOfAProjectCarriesMetadataAndParent() throws Exception {
        List<KnowledgeResource> demo = source.discover(SourceScope.of("project:DEMO"));
        KnowledgeResource sub = demo.get(2);
        assertEquals("DEMO-3: Teilaufgabe Protokoll", sub.title());
        assertEquals(id("DEMO-1"), sub.parentId().get());
        assertEquals("DEMO", sub.scope());
        assertEquals("text/markdown", sub.contentType());
        assertEquals("Open", sub.metadata().get("jira.status").get());
        assertEquals("Bug", sub.metadata().get("jira.issueType").get());
        assertEquals("Bob", sub.metadata().get("jira.assignee").get());
        assertEquals("https://jira.example.org/jira/browse/DEMO-3", sub.location().get().toString());
        assertEquals("2026-02-01T07:00:00Z", sub.revision().modifiedAt().get().toString());
    }

    @Test
    public void discoverFromAnIssueFollowsLinksSubtasksAndParentUpToDepth() throws Exception {
        assertEquals(Collections.singletonList(id("DEMO-1")),
                ids(source.discover(SourceScope.of("DEMO-1"))));
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-3"), id("DEMO-2")),
                ids(source.discover(SourceScope.builder().startPoint("issue:demo-1").maxDepth(1).build())));
        assertEquals(Arrays.asList(id("DEMO-3"), id("DEMO-1")),
                ids(source.discover(SourceScope.builder().startPoint(id("DEMO-3").value()).maxDepth(1).build())));
    }

    @Test
    public void discoverWithJqlStartPointAppliesTheFilter() throws Exception {
        jira.issues.get("DEMO-2").resolution = "Done";
        source = source(JiraConfig.builder(FakeJira.BASE).jqlFilter("resolution = Unresolved").build());
        List<KnowledgeResource> open = source.discover(SourceScope.of("jql:project = \"DEMO\""));
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-3")), ids(open));
        assertEquals("(resolution = Unresolved) AND (project = \"DEMO\") ORDER BY key ASC", jira.jqls.get(0));
    }

    @Test
    public void excludedProjectsAreSkippedForJqlAndLinkedStartPoints() throws Exception {
        jira.link("DEMO-2", "relates to", "OPS-7");
        SourceScope jql = SourceScope.of("jql:project in (DEMO, OPS)").withExcludedPartitions(Collections.singleton("OPS"));
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-2"), id("DEMO-3")), ids(source.discover(jql)));
        assertEquals("(project in (DEMO, OPS)) AND project not in (OPS) ORDER BY key ASC", jira.jqls.get(0));

        SourceScope linked = SourceScope.builder().startPoint("DEMO-2").maxDepth(2).build()
                .withExcludedPartitions(Collections.singleton("OPS"));
        // DEMO-2 verweist nur auf OPS-7 (abgehakt); der Vorgang wird gelesen, aber nicht aufgenommen.
        assertEquals(Collections.singletonList(id("DEMO-2")), ids(source.discover(linked)));

        SourceScope ignoring = SourceScope.builder().startPoint("DEMO-2").maxDepth(1).ignoringSelection().build()
                .withExcludedPartitions(Collections.singleton("OPS"));
        assertTrue(ids(source.discover(ignoring)).contains(id("OPS-7")));
    }

    @Test
    public void htmlAttachmentsAreDownloadedWhileHtmlImagesAreTreatedAsLoginPages() throws Exception {
        FakeJira.FakeIssue issue = jira.issues.get("DEMO-1");
        issue.attachment("901", "bericht.html", "text/html", 20);
        issue.attachment("902", "bild.png", "image/png", 20);
        jira.files.put("/secure/attachment/901/bericht.html", "<html>x</html>".getBytes(StandardCharsets.UTF_8));
        jira.files.put("/secure/attachment/902/bild.png", "<html>login</html>".getBytes(StandardCharsets.UTF_8));
        jira.fileTypes.put("/secure/attachment/901/bericht.html", "text/html;charset=UTF-8");
        jira.fileTypes.put("/secure/attachment/902/bild.png", "text/html");
        PageView view = source.pageView(id("DEMO-1"));
        assertEquals("<html>x</html>", new String(source.loadAttachment(view.attachments().get(0)), StandardCharsets.UTF_8));
        assertKind(Kind.ACCESS_DENIED, () -> source.loadAttachment(view.attachments().get(1)));
        assertKind(Kind.ACCESS_DENIED, () -> source.loadViewImage("/jira/secure/attachment/901/bericht.html"));
    }

    @Test
    public void loadRendersTitleMetadataDescriptionAndComments() throws Exception {
        jira.issues.get("DEMO-1").descriptionHtml = "<p>Nach dem <b>Update</b> klappt die Anmeldung nicht mehr.</p>"
                + "<ul><li>Schritt eins</li><li>Schritt zwei</li></ul>";
        jira.issues.get("DEMO-1").labels.add("login");
        KnowledgeDocument document = source.load(id("DEMO-1"));
        String text = document.text();
        assertTrue(text, text.startsWith("# DEMO-1: Anmeldung schlägt fehl\n"));
        assertTrue(text, text.contains("Projekt: Projekt DEMO (DEMO)\n"));
        assertTrue(text, text.contains("Status: Open\n"));
        assertTrue(text, text.contains("Labels: login\n"));
        assertTrue(text, text.contains("## Beschreibung\n\nNach dem Update klappt die Anmeldung nicht mehr.\n\n- Schritt eins\n- Schritt zwei"));
        assertTrue(text, text.contains("## Verknüpfungen\n\n- Teilvorgang DEMO-3: Teil DEMO-3\n- blocks DEMO-2: Ziel DEMO-2"));
        assertTrue(text, text.contains("## Kommentare\n\n### Carol, 2026-01-10 11:00 UTC\n\nIch schaue es mir an."));
        assertEquals(id("DEMO-1"), document.id());
        // Anmeldedaten einmal je Port-Aufruf, als Basic-Header.
        assertEquals(1, secrets.resolved.size());
        assertEquals("Basic YWxpY2U6Z2VoZWlt", jira.requestHeaders.get(0).get("Authorization"));
    }

    @Test
    public void loadFallsBackToWikiMarkupWithoutRenderedFields() throws Exception {
        jira.withoutRenderedFields = true;
        String text = source.load(id("DEMO-2")).text();
        assertTrue(text, text.contains("## Beschreibung\n\nDas Zertifikat läuft im März ab."));
    }

    @Test
    public void loadFetchesTruncatedCommentsUpToTheLimit() throws Exception {
        FakeJira.FakeIssue issue = jira.issues.get("DEMO-2");
        for (int i = 1; i <= 5; i++) {
            issue.comment("Dan", "Kommentar " + i, "<p>Kommentar " + i + "</p>");
        }
        jira.inlineCommentLimit = 1;
        source = source(JiraConfig.builder(FakeJira.BASE).pageSize(2).maxComments(4).build());
        String text = source.load(id("DEMO-2")).text();
        assertTrue(text, text.contains("Kommentar 1"));
        assertTrue(text, text.contains("Kommentar 4"));
        assertFalse(text, text.contains("Kommentar 5"));
        assertTrue(jira.requests.toString(), jira.requests.get(1).toString().contains("/issue/DEMO-2/comment?startAt=0&maxResults=2"));
        assertTrue(jira.requests.toString(), jira.requests.get(2).toString().contains("startAt=2&maxResults=2"));
    }

    @Test
    public void unknownAndForeignIdsAreMappedToTheRightKinds() {
        assertKind(Kind.NOT_FOUND, () -> source.load(id("DEMO-404")));
        assertKind(Kind.NOT_FOUND, () -> source.load(KnowledgeResourceId.of("jira", "other.example/jira/issue/DEMO-1")));
        assertKind(Kind.UNSUPPORTED, () -> source.load(KnowledgeResourceId.of("confluence", LOCATOR + "/page/1")));
        jira.restricted.add("DEMO-2");
        assertKind(Kind.ACCESS_DENIED, () -> source.load(id("DEMO-2")));
    }

    @Test
    public void linksAreTheIssueLinksSubtasksAndParent() throws Exception {
        List<SourceLink> links = source.discoverLinks(id("DEMO-1"));
        assertEquals(2, links.size());
        assertEquals(id("DEMO-3"), links.get(0).target());
        assertEquals("Teilvorgang DEMO-3: Teil DEMO-3", links.get(0).label());
        assertEquals(id("DEMO-2"), links.get(1).target());
        assertEquals(Collections.singletonList(id("DEMO-1")), targets(source.discoverLinks(id("DEMO-3"))));
    }

    @Test
    public void searchBuildsJqlFromTextAndPartitions() throws Exception {
        List<SourceSearchHit> hits = source.search(new SourceQuery("Anmeldung", 10));
        assertEquals(2, hits.size());
        assertEquals("text ~ \"Anmeldung\" ORDER BY updated DESC", jira.jqls.get(0));
        SourceSearchHit hit = hits.get(1);
        assertEquals(id("DEMO-1"), hit.resourceId());
        assertEquals("DEMO-1: Anmeldung schlägt fehl", hit.title());
        assertEquals("Bug · Open — Nach dem Update klappt die Anmeldung nicht mehr.", hit.snippet());
        assertEquals("DEMO", hit.partitionKey());
        assertEquals("https://jira.example.org/jira/browse/DEMO-1", hit.urlText());
        assertTrue(hit.revision().isKnown());

        source.search(SourceQuery.of("x \"y\" \\z").withTitleOnly(true)
                .withIncludedPartitions(Arrays.asList("demo", "OPS")).withExcludedPartitions(Collections.singleton("OPS")));
        assertEquals("summary ~ \"x \\\"y\\\" \\\\z\" AND project in (DEMO, OPS) AND project not in (OPS) ORDER BY updated DESC",
                jira.jqls.get(1));
    }

    @Test
    public void searchAppliesTheConfiguredFilterAndTheLimit() throws Exception {
        source = source(JiraConfig.builder(FakeJira.BASE).jqlFilter("updated >= -30d").build());
        source.search(new SourceQuery("Server", 1));
        assertEquals("(updated >= -30d) AND text ~ \"Server\" ORDER BY updated DESC", jira.jqls.get(0));
        assertTrue(jira.requests.get(0).toString(), jira.requests.get(0).toString().contains("maxResults=1"));
    }

    @Test
    public void pageViewShowsRenderedHtmlLinksAttachmentsAndRawJson() throws Exception {
        FakeJira.FakeIssue issue = jira.issues.get("DEMO-1");
        issue.descriptionHtml = "<p>Siehe <a href=\"/jira/browse/DEMO-2\">DEMO-2</a> und "
                + "<img src=\"/jira/secure/attachment/900/shot.png\" alt=\"Screenshot\"><script>alert(1)</script></p>";
        issue.attachment("900", "shot.png", "image/png", 1234);
        jira.files.put("/secure/attachment/900/shot.png", new byte[] {1, 2, 3});
        PageView view = source.pageView(id("DEMO-1"));
        String html = view.html();
        assertEquals("https://jira.example.org/jira/", view.baseUri());
        assertTrue(html, html.contains("<h1>DEMO-1: Anmeldung schlägt fehl</h1>"));
        assertTrue(html, html.contains("<td><b>Status</b></td><td>Open</td>"));
        assertTrue(html, html.contains("<h2>Beschreibung</h2>"));
        assertTrue(html, html.contains("href=\"https://jira.example.org/jira/browse/DEMO-2\""));
        assertTrue(html, html.contains("src=\"https://jira.example.org/jira/secure/attachment/900/shot.png\""));
        assertFalse(html, html.contains("<script"));
        assertTrue(html, html.contains("<h2>Verknüpfungen</h2>"));
        assertTrue(html, html.contains("<h2>Kommentare (1)</h2><h3>Carol, 2026-01-10 11:00 UTC</h3><p>Ich schaue es mir an.</p>"));
        assertTrue(html, html.contains("<h2>Rohdaten</h2>"));
        assertTrue(html, html.contains("<pre>{\n  &quot;id&quot;:"));
        assertTrue(html, html.contains("&quot;key&quot;: &quot;DEMO-1&quot;"));
        assertEquals(1, view.images().size());
        assertEquals("https://jira.example.org/jira/secure/attachment/900/shot.png", view.images().get(0).src());
        assertEquals(1, view.attachments().size());
        PageView.Attachment attachment = view.attachments().get(0);
        assertEquals("shot.png", attachment.fileName());
        assertEquals("image/png", attachment.mediaType());
        assertEquals(1234, attachment.size());
        assertEquals("DEMO-1", attachment.containerId());

        assertEquals(3, source.loadViewImage(view.images().get(0).src()).length);
        assertEquals(3, source.loadViewImage("/jira/secure/attachment/900/shot.png").length);
        assertEquals(3, source.loadAttachment(attachment).length);
        assertKind(Kind.UNSUPPORTED, () -> source.loadViewImage("https://evil.example/x.png"));
        // Das Bild geht mit der Anmeldung der Quelle, aber mit Datei-Accept.
        Map<String, String> headers = jira.requestHeaders.get(jira.requestHeaders.size() - 2);
        assertEquals("*/*", headers.get("Accept"));
        assertNotNull(headers.get("Authorization"));
    }

    @Test
    public void pageViewWithoutCommentsSaysSo() throws Exception {
        String html = source.pageView(id("DEMO-2")).html();
        assertTrue(html, html.contains("<h2>Kommentare (0)</h2><p>Keine Kommentare.</p>"));
        assertTrue(html, html.contains("DEMO-2 in Jira öffnen"));
    }

    @Test
    public void addressesOfThisJiraResolveToIssueIds() throws Exception {
        assertEquals(id("DEMO-1"), source.resolveAddress("https://jira.example.org/jira/browse/DEMO-1"));
        assertEquals(id("DEMO-1"), source.resolveAddress("https://JIRA.example.org/jira/browse/DEMO-1?focusedCommentId=1#comment-1"));
        assertEquals(id("OPS-7"), source.resolveAddress("https://jira.example.org/jira/projects/OPS/issues/OPS-7?filter=allissues"));
        assertEquals(id("OPS-7"), source.resolveAddress("https://jira.example.org/jira/secure/RapidBoard.jspa?rapidView=1&selectedIssue=OPS-7"));
        assertEquals(id("DEMO-1"), source.resolveAddress("jira://jira.example.org/jira/issue/DEMO-1"));
        assertEquals(id("DEMO-1"), source.resolveAddress("jira://jira.example.org/jira/browse/DEMO-1"));
        assertKind(Kind.NOT_FOUND, () -> source.resolveAddress("https://jira.example.org/jira/secure/Dashboard.jspa"));
        assertKind(Kind.NOT_FOUND, () -> source.resolveAddress("https://other.example.org/jira/browse/DEMO-1"));
        assertKind(Kind.NOT_FOUND, () -> source.resolveAddress("https://jira.example.org/other/browse/DEMO-1"));
        assertTrue(source.addressPattern().matcher("https://jira.example.org/jira/browse/DEMO-1").matches());
        assertTrue(source.addressPattern().matcher("jira://x/issue/A-1").matches());
        assertFalse(source.addressPattern().matcher("DEMO-1").matches());
    }

    @Test
    public void partitionListingPagesThroughAProject() throws Exception {
        PartitionResources first = source.resources("DEMO", "", 2);
        assertEquals(Arrays.asList(id("DEMO-1"), id("DEMO-2")), ids(first.resources()));
        assertEquals("p1.DEMO.2", first.nextCursor());
        PartitionResources second = source.resources("DEMO", first.nextCursor(), 2);
        assertEquals(Collections.singletonList(id("DEMO-3")), ids(second.resources()));
        assertTrue(second.complete());
        assertKind(Kind.NOT_FOUND, () -> source.resources("NOPE", "", 2));
        assertKind(Kind.UNSUPPORTED, () -> source.resources("DEMO", "p1.OPS.2", 2));
        // Die erste Seite prüft das Projekt, die Folgeseite nicht mehr.
        assertEquals(3, jira.requests.size() - 1);
    }

    @Test
    public void storedContentIsTheRawIssueJson() throws Exception {
        assertEquals("DEMO-1", source.localId(id("DEMO-1")));
        assertNull(source.localId(KnowledgeResourceId.of("jira", "elsewhere/issue/DEMO-1")));
        StoredContent stored = source.storedContent("DEMO-1");
        assertEquals("application/json", stored.mediaType());
        assertEquals("jira-rest-issue+json;expand=renderedFields", stored.nativeFormat());
        String payload = new String(stored.payload(), StandardCharsets.UTF_8);
        assertTrue(payload, payload.startsWith("{\"id\":"));
        assertTrue(payload, payload.contains("\"renderedFields\""));
        assertEquals(id("DEMO-1"), stored.resource().id());
        assertEquals("DEMO-1: Anmeldung schlägt fehl", source.resourceMetadata("DEMO-1").title());
        assertKind(Kind.UNSUPPORTED, () -> source.storedContent("nicht-ein-schlüssel"));
    }

    @Test
    public void restGetPassesThroughUnderRestOnly() throws Exception {
        jira.stubs.put("/rest/api/2/issue/DEMO-1/transitions?expand=transitions.fields", "{\"transitions\":[]}");
        RestResponse response = source.restGet("/rest/api/2/issue/DEMO-1/transitions?expand=transitions.fields");
        assertEquals("{\"transitions\":[]}", response.bodyAsUtf8());
        assertTrue(response.contentType(), response.contentType().contains("json"));
        assertKind(Kind.UNSUPPORTED, () -> source.restGet("/secure/Dashboard.jspa"));
        assertKind(Kind.UNSUPPORTED, () -> source.restGet("/rest/../secure"));
    }

    @Test
    public void statusCodesAndTransportFailuresAreMapped() {
        jira.forcedResponse = FakeJira.json(401, "{}");
        assertKind(Kind.ACCESS_DENIED, () -> source.partitions());
        jira.forcedResponse = new JiraHttpResponse(302, "text/html", new byte[0]);
        assertKind(Kind.ACCESS_DENIED, () -> source.partitions());
        jira.forcedResponse = new JiraHttpResponse(200, "text/html", "<html>login</html>".getBytes(StandardCharsets.UTF_8));
        assertKind(Kind.INVALID_RESPONSE, () -> source.partitions());
        jira.forcedResponse = FakeJira.json(500, "{}");
        assertKind(Kind.UNAVAILABLE, () -> source.partitions());
        jira.forcedResponse = FakeJira.json(429, "{}");
        try {
            source.partitions();
            fail();
        } catch (KnowledgeSourceException e) {
            assertTrue(e instanceof RateLimitedException);
        }
        jira.forcedResponse = null;
        jira.forcedFailure = new IOException("connection reset");
        assertKind(Kind.UNAVAILABLE, () -> source.partitions());
        jira.forcedFailure = new JiraResponseTooLargeException(10);
        assertKind(Kind.INVALID_RESPONSE, () -> source.partitions());
    }

    @Test
    public void withoutCredentialRefNoSecretIsResolvedAndNoHeaderSent() throws Exception {
        source = source(JiraConfig.builder(FakeJira.BASE).build());
        source.partitions();
        assertTrue(secrets.resolved.isEmpty());
        assertNull(jira.requestHeaders.get(0).get("Authorization"));
    }

    @Test
    public void tokenWithoutUserBecomesBearerAndRequiresLoginFalseSendsNothing() throws Exception {
        secrets.principal = "";
        secrets.secret = "tok3n".toCharArray();
        source.partitions();
        assertEquals("Bearer tok3n", jira.requestHeaders.get(0).get("Authorization"));
        secrets.attributes.put(VaultEntryOptions.REQUIRES_LOGIN, "false");
        source = source(JiraConfig.builder(FakeJira.BASE).credentialRef(RecordingSecretProvider.REF).build());
        source.partitions();
        assertNull(jira.requestHeaders.get(1).get("Authorization"));
    }

    @Test
    public void messagesNeverContainTheSecret() {
        jira.forcedResponse = FakeJira.json(403, "{\"errorMessages\":[\"geheim\"]}");
        try {
            source.load(id("DEMO-1"));
            fail();
        } catch (KnowledgeSourceException e) {
            assertFalse(e.getMessage(), e.getMessage().contains("geheim"));
            assertFalse(e.getMessage(), e.getMessage().contains("Basic"));
        }
    }

    private static List<KnowledgeResourceId> ids(List<KnowledgeResource> resources) {
        List<KnowledgeResourceId> ids = new java.util.ArrayList<KnowledgeResourceId>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id());
        }
        return ids;
    }

    private static List<KnowledgeResourceId> targets(List<SourceLink> links) {
        List<KnowledgeResourceId> ids = new java.util.ArrayList<KnowledgeResourceId>();
        for (SourceLink link : links) {
            ids.add(link.target());
        }
        return ids;
    }

    private static void assertKind(Kind kind, Call call) {
        try {
            call.run();
            fail("expected " + kind);
        } catch (KnowledgeSourceException e) {
            assertEquals(e.getMessage(), kind, e.kind());
        }
    }

    private interface Call {
        void run() throws KnowledgeSourceException;
    }
}
