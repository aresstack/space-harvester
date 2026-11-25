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

    private final SharePointTransport fake = new SharePointTransport() {
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
        assertEquals(2002, found.size());
        assertTrue(checkpoints.get() > 2000);
        assertTrue(found.stream().anyMatch(r -> r.title().equals("large.txt")));
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
