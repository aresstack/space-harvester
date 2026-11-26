package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

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

public class SharePointKnowledgeSourceTest {

    private static final String SITE = "https://sp.example.test/sites/team";
    private static final String API = SITE + "/_api/web/";

    private final Map<String, String> json = new HashMap<String, String>();
    private final Map<String, byte[]> files = new HashMap<String, byte[]>();
    private final List<String> authorizations = new ArrayList<String>();
    private final List<String> requestedUris = new ArrayList<String>();
    private final char[] password = "geheim".toCharArray();

    private final List<String> postBodies = new ArrayList<String>();
    private final Map<String, String> posts = new HashMap<String, String>();
    /** Answers GetChanges from the request body, if set (continuation after a token). */
    private java.util.function.Function<String, String> changeLog;

    private final SharePointTransport fake = new SharePointTransport() {
        @Override
        public Response post(URI uri, String authorization, String digest, byte[] body, long maxBytes) {
            String key = uri.toString();
            postBodies.add(new String(body, StandardCharsets.UTF_8));
            if (key.endsWith("/GetChanges")) assertEquals("test-digest", digest);
            String payload = key.endsWith("/GetChanges") && changeLog != null
                    ? changeLog.apply(new String(body, StandardCharsets.UTF_8)) : posts.get(key);
            return payload == null ? new Response(404, "application/json", null)
                    : new Response(200, "application/json", payload.getBytes(StandardCharsets.UTF_8),
                    java.time.Instant.parse("2026-11-25T20:00:00Z").toEpochMilli());
        }
        @Override
        public Response download(URI uri, String authorization, java.nio.file.Path target, long maxBytes) throws java.io.IOException {
            Response response = get(uri, null, authorization, maxBytes);
            if (response.status() == 200) {
                if (response.body().length > maxBytes) throw new java.io.IOException("too large");
                java.nio.file.Files.write(target, response.body());
            }
            return new Response(response.status(), response.contentType(), null);
        }

        @Override
        public Response get(URI uri, String accept, String authorization, long maxBytes) {
            authorizations.add(authorization);
            String key = uri.toString();
            requestedUris.add(key);
            if (files.containsKey(key)) {
                return new Response(200, "application/octet-stream", files.get(key));
            }
            String body = json.get(key);
            return body == null ? new Response(404, "application/json", new byte[0])
                    : new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }
    };

    private SharePointKnowledgeSource source() {
        json.put(API + "lists?$select=Title,Hidden,BaseTemplate,RootFolder/ServerRelativeUrl&$expand=RootFolder"
                        + "&$filter=BaseTemplate%20eq%20101",
                "{\"d\":{\"results\":[{\"Title\":\"Dokumente\",\"Hidden\":false,\"RootFolder\":{\"ServerRelativeUrl\":\"/sites/team/Dokumente\"}},"
                        + "{\"Title\":\"Intern\",\"Hidden\":true,\"RootFolder\":{\"ServerRelativeUrl\":\"/sites/team/Intern\"}}]}}");
        json.put(API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                        + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel",
                "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/IAR Handbuch.txt", 5)
                        + "," + file("/sites/team/Dokumente/bild.png", 5) + "]}}");
        json.put(API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Folders?$select=Name,ServerRelativeUrl",
                "{\"d\":{\"results\":[{\"Name\":\"Forms\",\"ServerRelativeUrl\":\"/sites/team/Dokumente/Forms\"},"
                        + "{\"Name\":\"Unter\",\"ServerRelativeUrl\":\"/sites/team/Dokumente/Unter\"}]}}");
        json.put(API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente/Unter')/Files"
                        + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel",
                "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/Unter/NIZZA.txt", 6) + "]}}");
        json.put(API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente/Unter')/Folders?$select=Name,ServerRelativeUrl",
                "{\"d\":{\"results\":[]}}");
        String fileApi = API + "GetFileByServerRelativeUrl('/sites/team/Dokumente/IAR%20Handbuch.txt')";
        json.put(fileApi + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel",
                "{\"d\":" + file("/sites/team/Dokumente/IAR Handbuch.txt", 5) + "}");
        files.put(fileApi + "/$value", "Hallo".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, String> entry : new java.util.HashMap<String, String>(json).entrySet()) {
            if (entry.getKey().contains("/Folders?$select=") || entry.getKey().contains("/Files?$select=")) {
                json.put(entry.getKey() + "&$top=100", entry.getValue());
            }
        }
        return new SharePointKnowledgeSource(SourceAddress.of("sharepoint://sp.example.test/sites/team"), URI.create(SITE),
                new SharePointCredentialsProvider() {
                    @Override
                    public SharePointCredentials credentials() {
                        return new SharePointCredentials("DOM\\nutzer", password);
                    }
                }, fake, detector(), registry(), 1024);
    }

    private static String file(String path, int length) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "{\"Name\":\"" + name + "\",\"ServerRelativeUrl\":\"" + path + "\",\"TimeLastModified\":\"2026-10-01T08:00:00Z\","
                + "\"Length\":\"" + length + "\",\"UIVersionLabel\":\"3.0\"}";
    }

    @Test
    public void discoverWalksVisibleLibrariesSkipsFormsAndNonText() throws Exception {
        List<KnowledgeResource> found = source().discover(SourceScope.builder().startPoints(".").maxDepth(5).maxResources(10).build());
        assertEquals(2, found.size());
        assertEquals("sharepoint:sp.example.test/sites/team/Dokumente/IAR Handbuch.txt", found.get(0).id().value());
        assertEquals("sharepoint:sp.example.test/sites/team/Dokumente/Unter/NIZZA.txt", found.get(1).id().value());
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("DOM\\nutzer:geheim".getBytes(StandardCharsets.UTF_8)),
                authorizations.get(0));
    }

    @Test
    public void loadExtractsTextAndRespectsDepth() throws Exception {
        SharePointKnowledgeSource source = source();
        KnowledgeDocument document = source.load(KnowledgeResourceId.of("sharepoint:sp.example.test/sites/team/Dokumente/IAR Handbuch.txt"));
        assertTrue(document.text(), document.text().contains("Hallo"));
        assertEquals("IAR Handbuch.txt", document.resource().title());
        assertEquals(1, source.discover(SourceScope.builder().startPoints("Dokumente").maxDepth(0).maxResources(10).build()).size());
    }

    @Test
    public void verifiesConnectionWithoutInventory() throws Exception {
        SharePointKnowledgeSource source = source();
        json.put(SITE + "/_api/web?$select=Id", "{\"d\":{\"Id\":\"test-site\"}}");
        source.verifyConnection();
        assertEquals(java.util.Collections.singletonList(SITE + "/_api/web?$select=Id"), requestedUris);
    }

    @Test
    public void followsOdataPagesAndRejectsForeignLinks() throws Exception {
        SharePointKnowledgeSource source = source();
        String first = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel";
        String second = first + "&$skiptoken=2";
        json.put(first, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/one.txt", 5)
                + "],\"__next\":\"" + second + "\"}}");
        json.put(second, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/two.txt", 6) + "]}}");
        SourceScope scope = SourceScope.builder().startPoints("Dokumente").maxDepth(0).maxResources(10).build();
        assertEquals(2, source.discover(scope).size());
        assertTrue(requestedUris.contains(second));
        for (String next : new String[]{"https://outside.example.test/_api/web/Files", first}) {
            json.put(first, "{\"d\":{\"results\":[],\"__next\":\"" + next + "\"}}");
            try {
                source.discover(scope);
                fail("Unsafe continuation accepted");
            } catch (KnowledgeSourceException e) {
                assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
            }
        }
        assertFalse(requestedUris.contains("https://outside.example.test/_api/web/Files"));
    }

    @Test
    public void nativeChangeLogResolvesChangedFileAndFallsBackForStructuralEvents() throws Exception {
        SharePointKnowledgeSource source = source();
        String list = "11111111-2222-3333-4444-555555555555";
        String listApi = API + "lists(guid'" + list + "')";
        json.put(API + "lists?$select=Id,Hidden,RootFolder/ServerRelativeUrl&$expand=RootFolder&$filter=BaseTemplate%20eq%20101",
                "{\"d\":{\"results\":[{\"Id\":\"" + list + "\",\"Hidden\":false,\"RootFolder\":{\"ServerRelativeUrl\":\"/sites/team/Dokumente\"}}]}}");
        posts.put(SITE + "/_api/contextinfo", "{\"d\":{\"GetContextWebInformation\":{\"FormDigestValue\":\"test-digest\"}}}");
        String item = listApi + "/items(1)?$select=FileSystemObjectType,File/Name,File/ServerRelativeUrl,File/TimeLastModified,File/Length,File/UIVersionLabel&$expand=File";
        json.put(item, "{\"d\":{\"FileSystemObjectType\":0,\"File\":" + file("/sites/team/Dokumente/IAR Handbuch.txt", 5) + "}}");
        String event = "{\"__metadata\":{\"type\":\"SP.ChangeItem\"},\"ChangeType\":2,\"ItemId\":1,\"ListId\":\"" + list
                + "\",\"ChangeToken\":{\"StringValue\":\"1;3;" + list + ";639000000000000000;5\"}}";
        posts.put(listApi + "/GetChanges", "{\"d\":{\"results\":[" + event + "]}}");
        com.aresstack.enterpriseai.source.api.PartitionResources changed =
                source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), "", 10);
        assertEquals(1, changed.resources().size());
        assertEquals("IAR Handbuch.txt", changed.resources().get(0).title());
        assertTrue(requestedUris.contains(item));
        assertTrue(postBodies.stream().anyMatch(body -> body.contains("ChangeTokenStart")
                && body.contains("1;3;" + list + ";")));
        assertTrue(requestedUris.stream().noneMatch(uri -> uri.contains("GetFolderByServerRelativeUrl")));
        posts.put(listApi + "/GetChanges", "{\"d\":{\"results\":[" + event.replace("\"ChangeType\":2", "\"ChangeType\":3") + "]}}");
        com.aresstack.enterpriseai.source.api.PartitionResources first =
                source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), "", 1);
        assertEquals(1, first.resources().size());
        assertFalse(first.complete());
        // The structural fallback is the full inventory: both files and their two folders, page by page.
        List<String> titles = new ArrayList<String>(Collections.singletonList(first.resources().get(0).title()));
        com.aresstack.enterpriseai.source.api.PartitionResources last = first;
        while (!last.complete()) {
            last = source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1),
                    last.nextCursor(), 1);
            assertTrue(last.resources().size() <= 1);
            for (KnowledgeResource resource : last.resources()) titles.add(resource.title());
        }
        assertEquals(java.util.Arrays.asList("Dokumente", "IAR Handbuch.txt", "Unter", "NIZZA.txt"), titles);
    }

    /**
     * ADR 0042, point 6: the cursor continues after the token the server delivered, and no register limits the number
     * of listings; the first of six listings still continues after the other five started.
     */
    @Test
    public void changeListingsContinueAfterTheServerTokenWithoutARegister() throws Exception {
        SharePointKnowledgeSource source = source();
        String list = "11111111-2222-3333-4444-555555555555";
        String listApi = API + "lists(guid'" + list + "')";
        json.put(API + "lists?$select=Id,Hidden,RootFolder/ServerRelativeUrl&$expand=RootFolder&$filter=BaseTemplate%20eq%20101",
                "{\"d\":{\"results\":[{\"Id\":\"" + list + "\",\"Hidden\":false,\"RootFolder\":{\"ServerRelativeUrl\":\"/sites/team/Dokumente\"}}]}}");
        posts.put(SITE + "/_api/contextinfo", "{\"d\":{\"GetContextWebInformation\":{\"FormDigestValue\":\"test-digest\"}}}");
        String[] names = {"Eins.txt", "Zwei.txt"};
        String[] tokens = new String[names.length];
        String[] events = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            int item = i + 1;
            json.put(listApi + "/items(" + item + ")?$select=FileSystemObjectType,File/Name,File/ServerRelativeUrl,File/TimeLastModified,File/Length,File/UIVersionLabel&$expand=File",
                    "{\"d\":{\"FileSystemObjectType\":0,\"File\":" + file("/sites/team/Dokumente/" + names[i], 5) + "}}");
            tokens[i] = "1;3;" + list + ";63900000000000000" + item + ";" + item;
            events[i] = "{\"__metadata\":{\"type\":\"SP.ChangeItem\"},\"ChangeType\":2,\"ItemId\":" + item
                    + ",\"ListId\":\"" + list + "\",\"ChangeToken\":{\"StringValue\":\"" + tokens[i] + "\"}}";
        }
        // Wie der Server: nach dem ersten Token nur noch die zweite Änderung, nach dem zweiten keine.
        changeLog = body -> {
            if (body.contains(tokens[1] + "\"")) return "{\"d\":{\"results\":[]}}";
            if (body.contains(tokens[0] + "\"")) return "{\"d\":{\"results\":[" + events[1] + "]}}";
            return "{\"d\":{\"results\":[" + events[0] + "," + events[1] + "]}}";
        };
        List<com.aresstack.enterpriseai.source.api.PartitionResources> firsts =
                new ArrayList<com.aresstack.enterpriseai.source.api.PartitionResources>();
        for (int i = 0; i < 6; i++) {
            firsts.add(source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), "", 1));
        }
        com.aresstack.enterpriseai.source.api.PartitionResources first = firsts.get(0);
        assertEquals("Eins.txt", first.resources().get(0).title());
        assertFalse(first.complete());

        postBodies.clear();
        com.aresstack.enterpriseai.source.api.PartitionResources second =
                source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), first.nextCursor(), 1);
        assertEquals("Zwei.txt", second.resources().get(0).title());
        assertTrue(postBodies.stream().anyMatch(body -> body.contains("ChangeTokenStart") && body.contains(tokens[0])));
        com.aresstack.enterpriseai.source.api.PartitionResources last =
                source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), second.nextCursor(), 1);
        assertTrue(last.resources().isEmpty());
        assertTrue(last.complete());
        try {
            source.changedResources(source.sourceId().value(), java.time.Duration.ofHours(1), first.nextCursor() + "x", 1);
            fail("Foreign cursor accepted");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void usesResourcePathForSpecialNames() throws Exception {
        SharePointKnowledgeSource source = source();
        String path = "/sites/team/Dokumente/100% + #.txt";
        String id = source.localId(KnowledgeResourceId.of("sharepoint:sp.example.test" + path));
        String endpoint = API + "GetFileByServerRelativePath(decodedurl=@p)?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel&@p="
                + SharePointKnowledgeSource.odataString(path);
        json.put(endpoint, "{\"d\":" + file(path, 5) + "}");
        assertEquals("100% + #.txt", source.resourceMetadata(id).title());
        assertTrue(requestedUris.contains(endpoint));
    }

    @Test
    public void kqlUsesSeparateTermsAndDecodedLibraryPaths() throws Exception {
        com.aresstack.enterpriseai.source.api.SourceQuery query =
                new com.aresstack.enterpriseai.source.api.SourceQuery("Vertrag 2026", 10);
        String kql = SharePointSearch.query(query, java.util.Collections.singletonList("/sites/team/Shared Documents"),
                path -> URI.create("https://sp.example.test" + path.replace(" ", "%20")));
        assertTrue(kql.contains("\"Vertrag\" AND \"2026\""));
        assertTrue(kql.contains("Path:\"https://sp.example.test/sites/team/Shared Documents/\""));
    }

    @Test
    public void nativeSearchFiltersForeignAndHiddenLibraryResults() throws Exception {
        SharePointKnowledgeSource source = source();
        com.aresstack.enterpriseai.source.api.SourceQuery query = new com.aresstack.enterpriseai.source.api.SourceQuery("test", 3);
        String kql = SharePointSearch.query(query, java.util.Collections.singletonList("/sites/team/Dokumente"),
                path -> URI.create("https://sp.example.test" + path));
        String endpoint = SITE + "/_api/search/query?querytext=" + SharePointKnowledgeSource.odataString(kql)
                + "&rowlimit=3&startrow=0&trimduplicates=false&selectproperties='Path,Filename,Title,LastModifiedTime,IsDocument'";
        json.put(endpoint, searchRows("https://sp.example.test/sites/team/Dokumente/test file.txt?d=w123",
                "https://sp.example.test/sites/team/Intern/test.txt", "https://other.example.test/sites/team/Dokumente/test.txt"));
        json.put(endpoint.replace("rowlimit=3&startrow=0", "rowlimit=2&startrow=3"), searchRows());
        java.util.List<com.aresstack.enterpriseai.source.api.SourceSearchHit> hits = source.search(query);
        assertEquals(1, hits.size());
        assertEquals("sharepoint:sp.example.test/sites/team/Dokumente/test file.txt", hits.get(0).resourceId().value());
        assertTrue(requestedUris.contains(endpoint.replace("rowlimit=3&startrow=0", "rowlimit=2&startrow=3")));
    }

    private static String searchRows(String... paths) {
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (String path : paths) {
            com.google.gson.JsonArray cells = new com.google.gson.JsonArray();
            for (String[] pair : new String[][] {{"Path", path}, {"IsDocument", "true"}}) {
                com.google.gson.JsonObject cell = new com.google.gson.JsonObject();
                cell.addProperty("Key", pair[0]);
                cell.addProperty("Value", pair[1]);
                cells.add(cell);
            }
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject();
            wrapper.add("results", cells);
            row.add("Cells", wrapper);
            rows.add(row);
        }
        return "{\"d\":{\"query\":{\"PrimaryQueryResult\":{\"RelevantResults\":{\"Table\":{\"Rows\":{\"results\":"
                + rows + "}}}}}}}";
    }

    @Test
    public void inventoryHasNoImplicitTwoThousandFileLimitAndKeepsLargeMetadata() throws Exception {
        SharePointKnowledgeSource source = source();
        String first = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel";
        String second = first + "&$skiptoken=2000";
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            if (i > 0) rows.append(',');
            rows.append(file("/sites/team/Dokumente/file" + i + ".txt", 5));
        }
        json.put(first, "{\"d\":{\"results\":[" + rows + "],\"__next\":\"" + second + "\"}}");
        json.put(second, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/large.txt", 4096) + "]}}");
        List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
        java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        source.visitInventory(source.sourceId().value(), checkpoints::incrementAndGet, found::add);
        assertEquals("2002 files and their 2 folders", 2004, found.size());
        assertTrue(checkpoints.get() > 2000);
        assertTrue(found.stream().anyMatch(r -> r.title().equals("large.txt")));
    }

    @Test
    public void inventoryReportsFoldersWithTheirParentsSoTheHierarchyCanBeRebuilt() throws Exception {
        SharePointKnowledgeSource source = source();
        List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
        source.visitInventory(source.sourceId().value(), () -> { }, found::add);
        Map<String, KnowledgeResource> byTitle = new HashMap<String, KnowledgeResource>();
        for (KnowledgeResource resource : found) byTitle.put(resource.title(), resource);

        KnowledgeResource library = byTitle.get("Dokumente");
        KnowledgeResource folder = byTitle.get("Unter");
        assertEquals("folder", library.metadata().asMap().get("resource.kind"));
        assertFalse("a library root hangs below the site", library.parentId().isPresent());
        assertEquals(library.id(), folder.parentId().get());
        assertEquals(library.id(), byTitle.get("IAR Handbuch.txt").parentId().get());
        assertEquals(folder.id(), byTitle.get("NIZZA.txt").parentId().get());
        assertTrue("folder before its files", found.indexOf(folder) < found.indexOf(byTitle.get("NIZZA.txt")));

        // Same id as the navigation node; a folder has no content of its own.
        com.aresstack.enterpriseai.source.api.NavigationNode root =
                com.aresstack.enterpriseai.source.api.NavigationNode.root("root", "root", "root",
                        com.aresstack.enterpriseai.source.api.NavigationNode.Children.UNKNOWN);
        assertEquals(library.id(), source.children(root, "").nodes().get(0).resource());
        com.aresstack.enterpriseai.source.api.StoredContent stored = source.storedContent(source.localId(folder.id()));
        assertEquals(SharePointKnowledgeSource.FOLDER_FORMAT, stored.nativeFormat());
        assertEquals(0, stored.payload().length);
        assertEquals(library.id(), source.resourceMetadata(source.localId(folder.id())).parentId().get());
        assertEquals("", source.load(folder.id()).text());
    }

    @Test
    public void streamsOriginalAndPreservesTargetOnLimitFailure() throws Exception {
        SharePointKnowledgeSource source = source();
        String id = source.localId(KnowledgeResourceId.of("sharepoint:sp.example.test/sites/team/Dokumente/IAR Handbuch.txt"));
        java.nio.file.Path target = java.nio.file.Files.createTempFile("sharepoint-test-", ".txt");
        try {
            java.nio.file.Files.write(target, "previous".getBytes(StandardCharsets.UTF_8));
            try {
                source.downloadOriginal(id, target, type -> true, 4);
                fail("Limit ignored");
            } catch (com.aresstack.enterpriseai.source.api.ResponseTooLargeException expected) {
                assertEquals("previous", new String(java.nio.file.Files.readAllBytes(target), StandardCharsets.UTF_8));
            }
            assertEquals("text/plain", source.downloadOriginal(id, target, type -> true, 0).get());
            assertEquals("Hallo", new String(java.nio.file.Files.readAllBytes(target), StandardCharsets.UTF_8));
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }

    @Test
    public void lazyNavigationAndOriginalsKeepExistingResourceIds() throws Exception {
        SharePointKnowledgeSource source = source();
        com.aresstack.enterpriseai.source.api.NavigationNode root =
                com.aresstack.enterpriseai.source.api.NavigationNode.root("root", "root", "root",
                        com.aresstack.enterpriseai.source.api.NavigationNode.Children.UNKNOWN);
        com.aresstack.enterpriseai.source.api.NavigationChildren libraries = source.children(root, "");
        assertEquals(1, libraries.nodes().size());
        assertTrue(libraries.nodes().get(0).isFolder());
        assertEquals(1, requestedUris.size());
        com.aresstack.enterpriseai.source.api.NavigationChildren children = source.children(libraries.nodes().get(0), "");
        assertEquals(3, children.nodes().size());
        assertTrue(children.nodes().get(0).isFolder());
        KnowledgeResourceId id = children.nodes().get(1).resource();
        String existingId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
        assertEquals(existingId, source.localId(id));
        assertEquals(id, source.resourceMetadata(existingId).id());
        assertEquals(1, source.position(id).ancestors().size());
        com.aresstack.enterpriseai.source.api.StoredContent stored = source.storedContent(existingId);
        assertEquals("file", stored.nativeFormat());
        assertEquals("Hallo", new String(stored.payload(), StandardCharsets.UTF_8));
    }

    @Test
    public void aFullFirstPageWithoutContinuationIsReadCompletelyInsteadOfCountingAsTheWholeFolder() throws Exception {
        // Angelo 26.11., Entscheidung 5: $top ist nur Stapelgröße; ohne __next darf eine volle Seite nie als vollständig gelten.
        SharePointKnowledgeSource source = source();
        com.aresstack.enterpriseai.source.api.NavigationNode root =
                com.aresstack.enterpriseai.source.api.NavigationNode.root("root", "root", "root",
                        com.aresstack.enterpriseai.source.api.NavigationNode.Children.UNKNOWN);
        com.aresstack.enterpriseai.source.api.NavigationNode library = source.children(root, "").nodes().get(0);
        String folders = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Folders?$select=Name,ServerRelativeUrl&$top=100";
        String files = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel&$top=100";
        String allFiles = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel";
        json.put(folders, "{\"d\":{\"results\":[]}}");
        StringBuilder truncated = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            String row = file("/sites/team/Dokumente/file" + i + ".txt", 5);
            if (i < 100) truncated.append(i > 0 ? "," : "").append(row);
            all.append(i > 0 ? "," : "").append(row);
        }
        json.put(files, "{\"d\":{\"results\":[" + truncated + "]}}");
        json.put(allFiles, "{\"d\":{\"results\":[" + all + "]}}");

        java.util.Set<String> ids = new java.util.HashSet<String>();
        com.aresstack.enterpriseai.source.api.NavigationChildren batch = source.children(library, "");
        while (true) {
            for (com.aresstack.enterpriseai.source.api.NavigationNode node : batch.nodes()) assertTrue(ids.add(node.resource().value()));
            if (batch.complete()) break;
            batch = source.children(library, batch.nextCursor());
        }
        assertEquals(130, ids.size());
        assertTrue(requestedUris.contains(allFiles));
    }

    @Test
    public void withoutTopKeepsEveryOtherParameter() {
        assertEquals(URI.create("https://h/_api/x/Files?$select=Name&$skiptoken=2"),
                SharePointKnowledgeSource.withoutTop(URI.create("https://h/_api/x/Files?$select=Name&$top=100&$skiptoken=2")));
        assertEquals(URI.create("https://h/_api/x/Files"),
                SharePointKnowledgeSource.withoutTop(URI.create("https://h/_api/x/Files?$top=100")));
    }

    @Test
    public void explorerDefersServerPagesAndLimitsLargeResponses() throws Exception {
        SharePointKnowledgeSource source = source();
        com.aresstack.enterpriseai.source.api.NavigationNode root =
                com.aresstack.enterpriseai.source.api.NavigationNode.root("root", "root", "root",
                        com.aresstack.enterpriseai.source.api.NavigationNode.Children.UNKNOWN);
        com.aresstack.enterpriseai.source.api.NavigationNode library = source.children(root, "").nodes().get(0);
        String folders = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Folders?$select=Name,ServerRelativeUrl&$top=100";
        String files = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel&$top=100";
        String nextFolders = folders + "&$skiptoken=2";
        String nextFiles = files + "&$skiptoken=250";
        json.put(folders, "{\"d\":{\"results\":[{\"Name\":\"Unter\",\"ServerRelativeUrl\":\"/sites/team/Dokumente/Unter\"}],\"__next\":\""
                + nextFolders + "\"}}");
        json.put(nextFolders, "{\"d\":{\"results\":[{\"Name\":\"Zweite\",\"ServerRelativeUrl\":\"/sites/team/Dokumente/Zweite\"}]}}");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            if (i > 0) rows.append(',');
            rows.append(file("/sites/team/Dokumente/file" + i + ".txt", 5));
        }
        json.put(files, "{\"d\":{\"results\":[" + rows + "],\"__next\":\"" + nextFiles + "\"}}");
        json.put(nextFiles, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/last.txt", 5) + "]}}");
        com.aresstack.enterpriseai.source.api.NavigationChildren batch = source.children(library, "");
        assertEquals(1, batch.nodes().size());
        assertFalse(batch.complete());
        assertFalse(requestedUris.contains(nextFolders));
        assertFalse(requestedUris.contains(files));
        try {
            source.children(batch.nodes().get(0), batch.nextCursor());
            fail("Foreign folder cursor accepted");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, expected.kind());
        }
        java.util.Set<String> ids = new java.util.HashSet<String>();
        ids.add(batch.nodes().get(0).resource().value());
        batch = source.children(library, batch.nextCursor());
        assertEquals(100, batch.nodes().size());
        assertTrue(requestedUris.contains(nextFolders));
        assertTrue(requestedUris.contains(files));
        assertFalse(requestedUris.contains(nextFiles));
        for (com.aresstack.enterpriseai.source.api.NavigationNode node : batch.nodes()) assertTrue(ids.add(node.resource().value()));
        batch = source.children(library, batch.nextCursor());
        assertEquals(100, batch.nodes().size());
        assertFalse(requestedUris.contains(nextFiles));
        for (com.aresstack.enterpriseai.source.api.NavigationNode node : batch.nodes()) assertTrue(ids.add(node.resource().value()));
        batch = source.children(library, batch.nextCursor());
        assertTrue(batch.complete());
        assertEquals(52, batch.nodes().size());
        for (com.aresstack.enterpriseai.source.api.NavigationNode node : batch.nodes()) assertTrue(ids.add(node.resource().value()));
        assertEquals(253, ids.size());
        assertEquals(3, java.util.Collections.frequency(requestedUris, files));
        assertEquals(1, java.util.Collections.frequency(requestedUris, nextFiles));
    }

    @Test
    public void explorerRejectsUnsafeContinuationAndAllowsRetryAfterServerFailure() throws Exception {
        SharePointKnowledgeSource source = source();
        com.aresstack.enterpriseai.source.api.NavigationNode root =
                com.aresstack.enterpriseai.source.api.NavigationNode.root("root", "root", "root",
                        com.aresstack.enterpriseai.source.api.NavigationNode.Children.UNKNOWN);
        com.aresstack.enterpriseai.source.api.NavigationNode library = source.children(root, "").nodes().get(0);
        String files = API + "GetFolderByServerRelativeUrl('/sites/team/Dokumente')/Files"
                + "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel&$top=100";
        String next = files + "&$skiptoken=2";
        json.put(files, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/first.txt", 5)
                + "],\"__next\":\"" + next + "\"}}");
        com.aresstack.enterpriseai.source.api.NavigationChildren first = source.children(library, "");
        assertFalse(first.complete());
        try {
            source.children(library, first.nextCursor());
            fail("Missing continuation accepted");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.NOT_FOUND, expected.kind());
        }
        for (String unsafe : new String[]{files, "https://outside.example.test/_api/web/Files"}) {
            json.put(next, "{\"d\":{\"results\":[],\"__next\":\"" + unsafe + "\"}}");
            try {
                source.children(library, first.nextCursor());
                fail("Unsafe continuation accepted");
            } catch (KnowledgeSourceException expected) {
                assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, expected.kind());
            }
        }
        json.put(next, "{\"d\":{\"results\":[" + file("/sites/team/Dokumente/last.txt", 5) + "]}}");
        com.aresstack.enterpriseai.source.api.NavigationChildren last = source.children(library, first.nextCursor());
        assertTrue(last.complete());
        assertEquals(1, last.nodes().size());
        assertEquals("last.txt", last.nodes().get(0).title());
        assertFalse(requestedUris.contains("https://outside.example.test/_api/web/Files"));
    }


    @Test
    public void foreignIdIsUnsupported() {
        try {
            source().load(KnowledgeResourceId.of("sharepoint:other.example.test/x.txt"));
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void providerValidatesSiteUrl() {
        SharePointSourceProvider provider = new SharePointSourceProvider(ref -> SharePointCredentialsProvider.none(), null, null,
                null, detector(), registry());
        Map<String, String> values = new HashMap<String, String>();
        values.put("siteUrl", "keine-url");
        assertFalse(provider.validate(SourceSettings.of(values)).isEmpty());
        values.put("siteUrl", SITE + "/");
        assertTrue(provider.validate(SourceSettings.of(values)).isEmpty());
        assertEquals("sp.example.test/sites/team", provider.locator(SourceSettings.of(values)));
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
