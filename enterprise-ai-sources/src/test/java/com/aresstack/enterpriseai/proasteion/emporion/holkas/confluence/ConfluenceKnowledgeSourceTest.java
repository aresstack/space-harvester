package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfluenceKnowledgeSourceTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private final RecordingSecretProvider secrets = new RecordingSecretProvider();
    private ConfluenceConfig.Builder config;

    @Before
    public void setUp() {
        confluence.page("100", "Start", "DEV", "<p>Willkommen</p>").labels("howto", "java");
        confluence.page("101", "Kapitel 1", "DEV", "<h2>Einleitung</h2><p>Eins</p>");
        confluence.page("102", "Kapitel 2", "DEV", "<p>Zwei</p>");
        confluence.page("103", "Unterkapitel", "DEV", "<p>Drei</p>");
        confluence.child("100", "101");
        confluence.child("100", "102");
        confluence.child("101", "103");
        confluence.spaceHomepages.put("DEV", "100");
        confluence.attachment("att900", "100", "notes.txt", "text/plain; charset=UTF-8", "Notizen äöü");
        confluence.attachment("att901", "100", "bild.png", "image/png", "PNG");
        config = ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).pageSize(1);
    }

    private ConfluenceKnowledgeSource source() {
        return new ConfluenceKnowledgeSource(SOURCE, config.build(), confluence, secrets);
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    private static KnowledgeResourceId attachment(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/attachment/" + id);
    }

    // --- Lese-Ansicht: nur Bilder dieser Confluence -----------------------------------------------------------

    @Test
    public void viewImagesFromElsewhereAreNeverFetched() throws Exception {
        ConfluenceKnowledgeSource source = source();
        for (String foreign : new String[] {"https://evil.invalid/wiki/x.png",
                "https://confluence.example.org/wikix/a.png", "https://confluence.example.org/other/a.png",
                "http://confluence.example.org/wiki/a.png", null}) {
            try {
                source.loadViewImage(foreign);
                fail("geladen: " + foreign);
            } catch (KnowledgeSourceException e) {
                assertEquals(foreign, Kind.UNSUPPORTED, e.kind());
            }
        }
        assertTrue("keine Anfrage", confluence.requests().isEmpty());
    }

    // --- discover -----------------------------------------------------------------------------------------

    @Test
    public void spaceStartPointBeginsAtTheHomepageAndCrawlsChildrenBreadthFirst() throws Exception {
        List<KnowledgeResource> resources = source().discover(
                SourceScope.builder().startPoint("DEV").maxDepth(2).build());

        assertEquals(Arrays.asList(page("100"), page("101"), page("102"), page("103")), ids(resources));
        assertEquals(page("100"), resources.get(1).parentId().get());
        assertEquals(page("101"), resources.get(3).parentId().get());
    }

    @Test
    public void pageStartPointsInAllForms() throws Exception {
        for (String start : new String[] {"101", "page:101", "confluence:confluence.example.org/wiki/page/101"}) {
            List<KnowledgeResource> resources = source().discover(SourceScope.of(start));
            assertEquals(start, Collections.singletonList(page("101")), ids(resources));
        }
    }

    @Test
    public void unusableStartPointsAreSkipped() throws Exception {
        assertTrue(source().discover(SourceScope.of("space:NOPE", "999", "kein gültiger ? Startpunkt",
                "confluence:other-source/page/100")).isEmpty());
    }

    @Test
    public void spacesKeepTheHomepageAsCanonicalResourceId() throws Exception {
        confluence.space("DEV", "Entwicklung");
        confluence.space("OPS", "Betrieb");
        List<SourcePartition> spaces = source().partitions();
        assertEquals(2, spaces.size());
        assertEquals("Entwicklung", spaces.get(0).title());
        assertEquals(page("100"), spaces.get(0).homeResource());
        assertEquals("space:DEV", spaces.get(0).startPoint());
        assertNull(spaces.get(1).homeResource());
    }

    @Test
    public void spaceWithoutHomepageListsAllItsPages() throws Exception {
        confluence.space("OPS", "Betrieb");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>");
        List<KnowledgeResource> resources = source().discover(SourceScope.of("space:OPS"));
        assertEquals(1, resources.size());
        assertEquals(page("200"), resources.get(0).id());
    }

    @Test
    public void discoveredResourcesCarryRevisionScopeLocationAndNoContent() throws Exception {
        KnowledgeResource start = source().discover(SourceScope.of("100")).get(0);

        assertEquals("Start", start.title());
        assertEquals("DEV", start.scope());
        assertEquals("3", start.revision().version());
        assertEquals(Instant.parse("2026-10-01T06:15:00Z"), start.revision().modifiedAt().get());
        assertEquals(URI.create("https://confluence.example.org/wiki/pages/viewpage.action?pageId=100"),
                start.location().get());
        assertEquals("100", start.metadata().get("confluence.contentId").get());
        assertEquals(0, confluence.requestCount("body.view"));
    }

    @Test
    public void textAttachmentsAreChildrenOnlyWhenEnabled() throws Exception {
        SourceScope scope = SourceScope.builder().startPoint("100").maxDepth(1).build();
        assertFalse(ids(source().discover(scope)).contains(attachment("att900")));

        config.includeAttachments(true);
        List<KnowledgeResource> resources = source().discover(scope);
        assertEquals(Arrays.asList(page("100"), page("101"), page("102"), attachment("att900")), ids(resources));
        KnowledgeResource notes = resources.get(3);
        assertEquals("notes.txt", notes.title());
        assertEquals(page("100"), notes.parentId().get());
        assertEquals("text/plain; charset=UTF-8", notes.contentType());
    }

    @Test
    public void oversizedAttachmentsAreNotDiscovered() throws Exception {
        config.includeAttachments(true).maxAttachmentBytes(3);
        assertFalse(ids(source().discover(SourceScope.builder().startPoint("100").maxDepth(1).build()))
                .contains(attachment("att900")));
    }

    @Test
    public void maxResourcesStopsCrawlingAndPaging() throws Exception {
        List<KnowledgeResource> resources = source().discover(
                SourceScope.builder().startPoint("100").maxDepth(5).maxResources(2).build());
        assertEquals(Arrays.asList(page("100"), page("101")), ids(resources));
    }

    @Test
    public void discoveredResourcesCarryLabelsWithoutLoadingTheBody() throws Exception {
        List<KnowledgeResource> resources = source().discover(SourceScope.builder().startPoint("DEV").build());
        assertEquals("howto,java", resources.get(0).metadata().get("confluence.labels").get());
        assertEquals(0, confluence.requestCount("body.view"));
    }

    @Test
    public void compactTimezoneOffsetsOfDataCenterAreParsed() throws Exception {
        confluence.pages.get("100").when = "2014-03-10T23:16:50.757+1100";
        KnowledgeResource start = source().discover(SourceScope.of("100")).get(0);
        assertEquals(Instant.parse("2014-03-10T12:16:50.757Z"), start.revision().modifiedAt().get());
    }

    @Test
    public void exhaustedBudgetSkipsChildRequests() throws Exception {
        List<KnowledgeResource> resources = source().discover(
                SourceScope.builder().startPoint("100").maxDepth(5).maxResources(1).build());
        assertEquals(Collections.singletonList(page("100")), ids(resources));
        assertEquals(0, confluence.requestCount("/child/"));
    }

    @Test
    public void unsupportedAttachmentsDoNotConsumeTheBudget() throws Exception {
        confluence.page("300", "Nur Anhänge", "DEV", "<p>x</p>");
        confluence.attachment("att910", "300", "bild.png", "image/png", "PNG");
        confluence.attachment("att911", "300", "readme.md", "text/markdown", "# Hallo");
        config.includeAttachments(true);

        List<KnowledgeResource> resources = source().discover(
                SourceScope.builder().startPoint("300").maxDepth(1).maxResources(2).build());

        assertEquals(Arrays.asList(page("300"), attachment("att911")), ids(resources));
    }

    @Test
    public void pagingFollowsNextLinks() throws Exception {
        for (int i = 0; i < 5; i++) {
            confluence.page("20" + i, "Seite " + i, "DEV", "<p>x</p>");
            confluence.child("102", "20" + i);
        }
        List<SourceLink> links = source().discoverLinks(page("102"));
        assertEquals(5, links.size());
        assertEquals(5, confluence.requestCount("/child/page"));
        assertTrue(confluence.requests.get(confluence.requests.size() - 1).toString(), confluence.requests.get(
                confluence.requests.size() - 1).toString().endsWith("/child/page?expand=version,space,metadata.labels&start=4&limit=1"));
    }

    @Test
    public void nextLinksOutsideTheRestApiAreInvalidResponses() {
        confluence.page("201", "Seite", "DEV", "<p>x</p>");
        confluence.page("202", "Seite", "DEV", "<p>x</p>");
        confluence.child("102", "201");
        confluence.child("102", "202");
        confluence.nextLinkOverride = "https://elsewhere.invalid/rest/api/content/102/child/page";
        assertKind(Kind.INVALID_RESPONSE, () -> source().discoverLinks(page("102")));
    }

    @Test
    public void listResponsesWithoutResultsAreInvalidResponses() {
        confluence.forcedResponse = new ConfluenceHttpResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertKind(Kind.INVALID_RESPONSE, () -> source().search(new SourceQuery("x", 5)));
    }

    // --- load ---------------------------------------------------------------------------------------------

    @Test
    public void loadConvertsTheViewToStructuredText() throws Exception {
        KnowledgeDocument document = source().load(page("101"));

        assertEquals("# Kapitel 1\n\n## Einleitung\n\nEins", document.text());
        assertEquals(page("100"), document.resource().parentId().get());
    }

    @Test
    public void readingViewShowsJiraRowsFromTheExportViewInsteadOfTheBrowserPlaceholder() throws Exception {
        String head = "<h2>Liefergegenstände</h2><table><tr><th>Key</th><th>Summary</th><th>Status</th></tr>";
        confluence.page("110", "Roadmap", "DEV", head + "<tr><td>Getting issues...</td></tr></table>")
                .exportView(head + "<tr><td>PRJ-1</td><td>Feinkonzept</td><td>Done</td></tr>"
                        + "<tr><td>PRJ-2</td><td>Betriebskonzept</td><td>Open</td></tr></table>");

        String html = source().pageView(page("110")).html();

        assertTrue(html, html.contains("PRJ-1") && html.contains("PRJ-2"));
        assertFalse(html, html.contains("Getting issues"));
        // Der Index liest weiter body.view (Entscheidung: nicht blind global ersetzen).
        assertTrue(source().load(page("110")).text().contains("Getting issues"));
    }

    @Test
    public void readingViewFallsBackToTheViewWhenTheExportViewIsEmpty() throws Exception {
        confluence.page("111", "Alt", "DEV", "<p>Nur body.view: <span class=\"jira-issue\" data-jira-key=\"PRJ-7\">"
                + "<a class=\"jira-issue-key\" href=\"https://jira.invalid/browse/PRJ-7\">PRJ-7</a> - "
                + "<span class=\"summary\">Getting issue details...</span>"
                + "<span class=\"aui-lozenge\">STATUS</span></span></p>").exportView("");

        String html = source().pageView(page("111")).html();

        assertTrue(html, html.contains("Nur body.view"));
        // Der Lade-Platzhalter des Jira-Makros bleibt nicht stehen, der Vorgangsschlüssel mit Link schon.
        assertFalse(html, html.contains("Getting issue details"));
        assertFalse(html, html.contains("STATUS"));
        assertTrue(html, html.contains(">PRJ-7</a>"));
    }

    @Test
    public void loadReportsLabelsAsMetadata() throws Exception {
        assertEquals("howto,java", source().load(page("100")).resource().metadata().get("confluence.labels").get());
    }

    @Test
    public void loadDownloadsTextAttachments() throws Exception {
        KnowledgeDocument document = source().load(attachment("att900"));
        assertEquals("Notizen äöü", document.text());
        assertEquals("1", document.resource().revision().version());
    }

    @Test
    public void binaryAttachmentsAreUnsupported() {
        assertKind(Kind.UNSUPPORTED, () -> source().load(attachment("att901")));
        assertEquals(0, confluence.requestCount("/download/"));
    }

    @Test
    public void downloadLinksOutsideTheBaseAreRejected() {
        confluence.attachments.get("att900").downloadOverride = "//evil.example.org/steal";
        assertKind(Kind.INVALID_RESPONSE, () -> source().load(attachment("att900")));
        for (URI uri : confluence.requests) {
            assertEquals("confluence.example.org", uri.getHost());
        }
    }

    @Test
    public void attachmentIdIsNotAPage() {
        assertKind(Kind.NOT_FOUND, () -> source().load(page("900")));
        assertKind(Kind.UNSUPPORTED, () -> source().load(KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/att900")));
    }

    @Test
    public void foreignAndMalformedIdsAreUnsupported() {
        assertKind(Kind.UNSUPPORTED, () -> source().load(KnowledgeResourceId.of("confluence", "other/page/100")));
        assertKind(Kind.UNSUPPORTED, () -> source().load(KnowledgeResourceId.of("wiki", "confluence.example.org/wiki/page/100")));
        assertKind(Kind.UNSUPPORTED, () -> source().discoverLinks(KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/blog/1")));
        assertTrue(confluence.requests.isEmpty());
    }

    // --- Links und Suche ----------------------------------------------------------------------------------

    @Test
    public void linksOfAnAttachmentAreEmptyButCheckedForExistence() throws Exception {
        assertTrue(source().discoverLinks(attachment("att900")).isEmpty());
        assertKind(Kind.NOT_FOUND, () -> source().discoverLinks(attachment("att1")));
    }

    @Test
    public void searchUsesTheSearchEndpointWithEscapedCqlRestrictedToConfiguredSpaces() throws Exception {
        confluence.searchResults.addAll(Arrays.asList("101", "102", "103"));
        config.searchSpaceKey("DEV").searchSpaceKey("OPS").pageSize(25);

        List<SourceSearchHit> hits = source().search(new SourceQuery("say \"hi\" \\ ok", 2));

        assertEquals("siteSearch ~ \"say \\\"hi\\\" \\\\ ok\" AND type=page AND space in (\"DEV\",\"OPS\")",
                confluence.lastCql);
        assertEquals(1, confluence.requestCount("/rest/api/search"));
        assertEquals(0, confluence.requestCount("/rest/api/content/search"));
        assertEquals("highlight", confluence.lastSearchQuery.get("excerpt"));
        assertEquals("0", confluence.lastSearchQuery.get("start"));
        assertEquals("2", confluence.lastSearchQuery.get("limit"));
        assertTrue(confluence.lastSearchQuery.get("expand").contains("content.version"));
        assertEquals(2, hits.size());
        assertEquals(page("101"), hits.get(0).resourceId());
        assertEquals("Kapitel 1", hits.get(0).title());
    }

    @Test
    public void searchPagesUntilTheRequestedLimit() throws Exception {
        confluence.searchResults.addAll(Arrays.asList("100", "101", "102", "103"));

        assertEquals(3, source().search(new SourceQuery("x", 3)).size());
        assertEquals(3, confluence.requestCount("/rest/api/search"));
        assertEquals(4, source().search(new SourceQuery("x", 10)).size());
    }

    @Test
    public void searchPagesByStartAndTotalSizeWithoutNextLinks() throws Exception {
        confluence.searchResults.addAll(Arrays.asList("100", "101", "102", "103"));
        confluence.searchWithoutNextLinks = true;
        config.pageSize(3);

        List<SourceSearchHit> hits = source().search(new SourceQuery("x", 10));

        assertEquals(Arrays.asList(page("100"), page("101"), page("102"), page("103")), hitIds(hits));
        assertEquals(2, confluence.requestCount("/rest/api/search"));
        assertEquals("3", confluence.lastSearchQuery.get("start"));
    }

    @Test
    public void searchMapsTheWrapperWithExcerptTitleIdUrlAndRevision() throws Exception {
        confluence.wrappedSearchResult("102", "Text mit <b>@@@hl@@@wapok@@@endhl@@@</b> drin");

        List<SourceSearchHit> hits = source().search(new SourceQuery("wapok", 5));

        assertEquals(1, hits.size());
        SourceSearchHit hit = hits.get(0);
        assertEquals(page("102"), hit.resourceId());
        assertEquals("Kapitel 2", hit.title());
        assertEquals("Text mit wapok drin", hit.snippet());
        assertEquals("3", hit.revision().version());
        assertEquals(Instant.parse("2026-10-01T06:15:00Z"), hit.revision().modifiedAt().get());
        assertTrue(hit.urlText(), hit.urlText().endsWith("/pages/viewpage.action?pageId=102"));
    }

    @Test
    public void searchFallsBackToTheWrapperUrlAndLastModified() throws Exception {
        confluence.searchContentWithoutVersionAndLinks = true;
        confluence.wrappedSearchResult("101", "Eins");

        SourceSearchHit hit = source().search(new SourceQuery("x", 5)).get(0);

        assertEquals(Instant.parse("2026-03-04T04:06:07Z"), hit.revision().modifiedAt().get());
        assertTrue(hit.urlText(), hit.urlText().endsWith("/pages/viewpage.action?pageId=101"));
    }

    @Test
    public void searchExcerptBecomesReadablePlainText() throws Exception {
        confluence.wrappedSearchResult("101", "<p>R&amp;D&nbsp;&#8211; @@@hl@@@K&uuml;ndigung@@@endhl@@@</p>\n\n"
                + "<span>&lt;wichtig&gt; &#x2192; &quot;sofort&quot;&hellip;</span>");

        SourceSearchHit hit = source().search(new SourceQuery("kündigung", 5)).get(0);

        assertEquals("R&D – Kündigung <wichtig> → \"sofort\"…", hit.snippet());
    }

    @Test
    public void searchExcerptDecodesEveryHtmlEntity() {
        assertEquals("© ® ™ é Æ ∑ ä", ConfluenceRestClient.excerpt("&copy; &reg; &trade; &eacute; &AElig; &sum; &#xE4;"));
    }

    @Test
    public void searchExcerptIsCut() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            raw.append("wort ");
        }
        String excerpt = ConfluenceRestClient.excerpt(raw.toString());
        assertTrue(excerpt, excerpt.length() <= ConfluenceRestClient.EXCERPT_MAX_LENGTH + 1);
        assertTrue(excerpt, excerpt.length() > ConfluenceRestClient.EXCERPT_MAX_LENGTH - 5);
        assertTrue(excerpt.endsWith("…"));
        assertEquals("", ConfluenceRestClient.excerpt(null));
    }

    @Test
    public void searchHitWithoutExcerptIsAValidHitWithEmptySnippet() throws Exception {
        confluence.searchResults("101");

        List<SourceSearchHit> hits = source().search(new SourceQuery("x", 5));

        assertEquals(1, hits.size());
        assertEquals(page("101"), hits.get(0).resourceId());
        assertEquals("Kapitel 1", hits.get(0).title());
        assertEquals("", hits.get(0).snippet());
    }

    @Test
    public void searchNeverLoadsThePageForTheSnippet() throws Exception {
        confluence.wrappedSearchResult("101", "Eins");

        source().search(new SourceQuery("x", 5));

        for (URI request : confluence.requests()) {
            assertTrue(request.toString(), request.getPath().endsWith("/rest/api/search"));
        }
    }

    @Test
    public void searchSkipsNonPageResults() throws Exception {
        confluence.searchResults("att900");
        confluence.wrappedSearchResult("att901", "Bild");

        assertTrue(source().search(new SourceQuery("x", 5)).isEmpty());
    }

    @Test
    public void searchKeepsPagesInConfluenceOrderFromAMixedResultList() throws Exception {
        confluence.searchResults("att900", "103");
        confluence.wrappedSearchResult("att901", "Bild");
        confluence.wrappedSearchResult("101", "Eins");
        confluence.searchResults("100");

        List<SourceSearchHit> hits = source().search(new SourceQuery("x", 10));

        assertEquals(Arrays.asList(page("103"), page("101"), page("100")), hitIds(hits));
        assertEquals("Eins", hits.get(1).snippet());
    }

    @Test
    public void searchFailuresKeepTheirKind() {
        confluence.forcedResponse = new ConfluenceHttpResponse(403, "application/json", new byte[0]);
        assertKind(Kind.ACCESS_DENIED, () -> source().search(new SourceQuery("x", 5)));
        confluence.forcedResponse = new ConfluenceHttpResponse(503, "application/json", new byte[0]);
        assertKind(Kind.UNAVAILABLE, () -> source().search(new SourceQuery("x", 5)));
        confluence.forcedResponse = new ConfluenceHttpResponse(200, "text/html", new byte[0]);
        assertKind(Kind.INVALID_RESPONSE, () -> source().search(new SourceQuery("x", 5)));
    }

    private static List<KnowledgeResourceId> hitIds(List<SourceSearchHit> hits) {
        List<KnowledgeResourceId> ids = new ArrayList<KnowledgeResourceId>();
        for (SourceSearchHit hit : hits) {
            ids.add(hit.resourceId());
        }
        return ids;
    }

    // --- Anmeldung ----------------------------------------------------------------------------------------

    @Test
    public void basicAuthIsSentAndTheSecretIsResolvedOncePerOperationAndWiped() throws Exception {
        source().discover(SourceScope.builder().startPoint("DEV").maxDepth(3).build());

        assertEquals(1, secrets.issued.size());
        assertTrue(secrets.issued.get(0).isClosed());
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "alice:s3cr3t-Pässwort".getBytes(StandardCharsets.UTF_8));
        assertTrue(confluence.requests.size() > 3);
        for (Map<String, String> headers : confluence.requestHeaders) {
            assertEquals(expected, headers.get("Authorization"));
            assertEquals("application/json", headers.get("Accept"));
        }
    }

    @Test
    public void tokenWithoutPrincipalIsSentAsBearer() throws Exception {
        secrets.principal = null;
        secrets.secret = "pat-token";
        source().load(page("100"));
        assertEquals("Bearer pat-token", confluence.requestHeaders.get(0).get("Authorization"));
    }

    @Test
    public void withoutCredentialRefNoSecretIsRequestedAndNoAuthorizationSent() throws Exception {
        ConfluenceKnowledgeSource anonymous = new ConfluenceKnowledgeSource(SOURCE,
                ConfluenceConfig.builder(FakeConfluence.BASE).build(), confluence, null);
        anonymous.load(page("100"));
        assertTrue(secrets.issued.isEmpty());
        assertFalse(confluence.requestHeaders.get(0).containsKey("Authorization"));
    }

    @Test
    public void unavailableSecretsMapToPortKindsWithoutTouchingConfluence() {
        Object[][] cases = {
                {Reason.NOT_AVAILABLE, Kind.UNAVAILABLE},
                {Reason.NOT_FOUND, Kind.ACCESS_DENIED},
                {Reason.ACCESS_DENIED, Kind.ACCESS_DENIED},
                {Reason.CANCELLED, Kind.ACCESS_DENIED},
        };
        for (Object[] c : cases) {
            secrets.failWith = (Reason) c[0];
            KnowledgeSourceException e = assertKind((Kind) c[1], () -> source().load(page("100")));
            assertTrue(e.getMessage(), e.getMessage().contains("keepass:confluence"));
        }
        assertTrue(confluence.requests.isEmpty());
    }

    @Test
    public void secretIsWipedWhenConfluenceFails() {
        confluence.forcedResponse = new ConfluenceHttpResponse(500, "text/html", new byte[0]);
        assertKind(Kind.UNAVAILABLE, () -> source().load(page("100")));
        assertTrue(secrets.issued.get(0).isClosed());
    }

    // --- HTTP-Fehler --------------------------------------------------------------------------------------

    @Test
    public void httpStatusesMapToPortKinds() {
        Object[][] cases = {
                {401, Kind.ACCESS_DENIED}, {403, Kind.ACCESS_DENIED}, {302, Kind.ACCESS_DENIED},
                {404, Kind.NOT_FOUND}, {429, Kind.UNAVAILABLE}, {503, Kind.UNAVAILABLE}, {400, Kind.INVALID_RESPONSE},
        };
        for (Object[] c : cases) {
            confluence.forcedResponse = new ConfluenceHttpResponse((Integer) c[0], "application/json",
                    "{\"message\":\"geheime Details alice:s3cr3t\"}".getBytes(StandardCharsets.UTF_8));
            KnowledgeSourceException e = assertKind((Kind) c[1], () -> source().load(page("100")));
            assertFalse(e.getMessage(), e.getMessage().contains("geheime"));
        }
    }

    @Test
    public void networkFailuresAreUnavailable() {
        confluence.forcedFailure = new IOException("Connection refused");
        assertKind(Kind.UNAVAILABLE, () -> source().discover(SourceScope.of("100")));
    }

    @Test
    public void htmlLoginPagesAndBrokenJsonAreInvalidResponses() {
        confluence.forcedResponse = new ConfluenceHttpResponse(200, "text/html", "<html>Login</html>".getBytes(StandardCharsets.UTF_8));
        assertKind(Kind.INVALID_RESPONSE, () -> source().load(page("100")));
        confluence.forcedResponse = new ConfluenceHttpResponse(200, "application/json", "{kaputt".getBytes(StandardCharsets.UTF_8));
        assertKind(Kind.INVALID_RESPONSE, () -> source().load(page("100")));
        confluence.forcedResponse = new ConfluenceHttpResponse(200, "application/json", "[]".getBytes(StandardCharsets.UTF_8));
        assertKind(Kind.INVALID_RESPONSE, () -> source().load(page("100")));
    }

    @Test
    public void accessDeniedAbortsDiscovery() {
        confluence.forcedResponse = new ConfluenceHttpResponse(401, "application/json", new byte[0]);
        assertKind(Kind.ACCESS_DENIED, () -> source().discover(SourceScope.of("DEV")));
    }

    // --- Diverses -----------------------------------------------------------------------------------------

    @Test
    public void toStringShowsNoSecrets() {
        String text = source().toString();
        assertTrue(text, text.contains("keepass:confluence"));
        assertFalse(text, text.contains("s3cr3t"));
        assertFalse(text, text.contains("alice"));
    }

    @Test
    public void constructorRequiresSecretProviderWhenCredentialsAreConfigured() {
        try {
            new ConfluenceKnowledgeSource(SOURCE, config.build(), confluence, null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void aSelectionOfTheQueryRestrictsTheSearchWithoutListingAllOtherSpaces() throws Exception {
        confluence.searchResults.addAll(Arrays.asList("101"));

        source().search(new SourceQuery("x", 5).withIncludedPartitions(Arrays.asList("OPS", " "))
                .withExcludedPartitions(Arrays.asList("ARCHIV")));

        assertEquals("siteSearch ~ \"x\" AND type=page AND space in (\"OPS\") AND space not in (\"ARCHIV\")",
                confluence.lastCql);
    }

    @Test
    public void aSelectionIsIntersectedWithTheConfiguredSearchSpaces() throws Exception {
        confluence.searchResults.addAll(Arrays.asList("101"));
        config.searchSpaceKey("DEV").searchSpaceKey("OPS");

        source().search(new SourceQuery("x", 5).withIncludedPartitions(Arrays.asList("OPS", "HR")));
        assertEquals("siteSearch ~ \"x\" AND type=page AND space in (\"OPS\")", confluence.lastCql);

        int before = confluence.requestCount("/rest/api/search");
        assertTrue(source().search(new SourceQuery("x", 5).withIncludedPartitions(Arrays.asList("HR"))).isEmpty());
        assertEquals(before, confluence.requestCount("/rest/api/search"));
    }

    @Test
    public void cqlWithoutSpaces() {
        assertEquals("siteSearch ~ \"java\" AND type=page", ConfluenceKnowledgeSource.cql(" java ", new ArrayList<String>()));
    }

    @Test
    public void textLikeMediaTypes() {
        for (String type : new String[] {"text/plain", "text/csv; charset=ISO-8859-1", "application/json",
                "application/xml", "application/vnd.api+json", "image/svg+xml"}) {
            assertTrue(type, ConfluenceKnowledgeSource.isTextLike(type));
        }
        for (String type : new String[] {"application/pdf", "image/png", "application/octet-stream", ""}) {
            assertFalse(type, ConfluenceKnowledgeSource.isTextLike(type));
        }
    }

    private static List<KnowledgeResourceId> ids(List<KnowledgeResource> resources) {
        List<KnowledgeResourceId> ids = new ArrayList<KnowledgeResourceId>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id());
        }
        return ids;
    }

    private static KnowledgeSourceException assertKind(Kind kind, Call call) {
        try {
            call.run();
            fail("erwartet " + kind);
            return null;
        } catch (KnowledgeSourceException e) {
            assertEquals(e.getMessage(), kind, e.kind());
            return e;
        }
    }

    private interface Call {
        void run() throws KnowledgeSourceException;
    }
}
