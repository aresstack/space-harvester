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
    private final char[] password = "geheim".toCharArray();

    private final SharePointTransport fake = new SharePointTransport() {
        @Override
        public Response get(URI uri, String accept, String authorization, long maxBytes) {
            authorizations.add(authorization);
            String key = uri.toString();
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
