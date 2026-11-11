package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredContent;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bilder als Asset (wie source-git): gelistet mit Bildtyp, Platzhaltertext statt Extraktor, Originalbytes unverändert. */
public class LocalFilesAssetTest {

    private static final SourceAddress SOURCE = SourceAddress.of("handbuch");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    /** Wie der einfache Detektor von deigma: Bilder und SVG kennt er nicht. */
    private static final ContentDetector DETECTOR = new ContentDetector() {
        @Override
        public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
            String name = filenameHint == null ? "" : filenameHint.toLowerCase();
            if (name.endsWith(".md")) {
                return new DetectedContentType("text/markdown", ContentCategory.MARKDOWN, filenameHint);
            }
            return new DetectedContentType("application/octet-stream", ContentCategory.UNKNOWN, filenameHint);
        }
    };

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path root;
    private LocalFilesKnowledgeSource source;

    @Before
    public void setUp() throws IOException {
        root = temp.newFolder("Handbuch").toPath();
        write("b.md", "# B\n".getBytes(StandardCharsets.UTF_8));
        write("bilder/logo.png", PNG);
        write("bilder/foto.JPG", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
        write("bilder/favicon.ico", new byte[] {0, 0, 1, 0});
        write("bilder/plan.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"
                .getBytes(StandardCharsets.UTF_8));
        write("bilder/scan.bmp", new byte[] {'B', 'M', 0, 0});
        write("bilder/gross.png", new byte[2048]);
        // Ohne Extraktoren: Bilder brauchen keinen.
        source = new LocalFilesKnowledgeSource(SOURCE, root, DETECTOR, new ExtractionRegistry(), 1024);
    }

    @Test
    public void imagesAreListedWithTheirImageTypeOtherBinariesAndLargeFilesStayOut() throws Exception {
        List<KnowledgeResource> resources = source.resources("handbuch", "", 100).resources();

        assertEquals(Arrays.asList("b.md", "bilder/favicon.ico", "bilder/foto.JPG", "bilder/logo.png",
                "bilder/plan.svg"), paths(resources));
        assertEquals("image/x-icon", resources.get(1).contentType());
        assertEquals("image/jpeg", resources.get(2).contentType());
        assertEquals("image/png", resources.get(3).contentType());
        assertEquals("image/svg+xml", resources.get(4).contentType());
        assertEquals(paths(resources), paths(source.discover(SourceScope.builder().startPoints(".").maxDepth(5)
                .build())));
    }

    @Test
    public void loadingAnImageGivesAPlaceholderWithNameTypeAndSize() throws Exception {
        KnowledgeDocument document = source.load(id("bilder/logo.png"));

        assertEquals("# logo.png\n\n`bilder/logo.png`\n\nBilddatei (image/png, 16 Bytes), als Asset des Ordners "
                + "auffindbar; der Bildinhalt wird nicht als Text gelesen.", document.text());
        assertEquals("image/png", document.resource().contentType());
        assertTrue(document.resource().location().isPresent());
    }

    @Test
    public void theOriginalBytesOfAnImageStayAvailable() throws Exception {
        StoredContent stored = source.storedContent(localId("bilder/logo.png"));

        assertArrayEquals(PNG, stored.payload());
        assertEquals("image/png", stored.mediaType());
        assertEquals("image/png", source.resourceMetadata(localId("bilder/logo.png")).contentType());
        assertEquals("16", source.resourceMetadata(localId("bilder/logo.png")).metadata().get("file.size").get());
    }

    @Test
    public void svgIsReadAsXmlSourceLikeInGit() throws Exception {
        String text = source.load(id("bilder/plan.svg")).text();

        assertEquals("# plan.svg\n\n`bilder/plan.svg` · svg\n\n```svg\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>\n```", text);
        assertFalse(text.contains("Bilddatei"));
    }

    private void write(String relative, byte[] bytes) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private KnowledgeResourceId id(String relative) {
        return KnowledgeResourceId.of("file", LocalFilesKnowledgeSource.locatorOf(root) + "/" + relative);
    }

    private static String localId(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> paths(List<KnowledgeResource> resources) {
        List<String> paths = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            paths.add(resource.metadata().get("file.path").orElse(""));
        }
        return paths;
    }
}
