package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import com.aresstack.enterpriseai.source.api.files.FilePatterns;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
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
import com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** #120: grants, links out of the folder, include/exclude rules and an unreachable folder. */
public class LocalFilesAccessTest {

    private static final SourceAddress SOURCE = SourceAddress.of("dateien-test");
    private static final SourceScope DEEP = SourceScope.builder().startPoints(".").maxDepth(20).build();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final ContentDetector DETECTOR = new ContentDetector() {
        @Override
        public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
            String name = filenameHint == null ? "" : filenameHint.toLowerCase();
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".tmp")) {
                return new DetectedContentType("text/plain", ContentCategory.PLAIN_TEXT, filenameHint);
            }
            return new DetectedContentType("application/octet-stream", ContentCategory.UNKNOWN, filenameHint);
        }
    };

    private static ExtractionRegistry registry() {
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
                                        new String(request.content(), StandardCharsets.UTF_8)))
                                .build());
            }
        });
        return registry;
    }

    private LocalFilesKnowledgeSource source(Path root, AtomicReference<LocalFileAccessPolicy> access,
                                             List<String> includes, List<String> excludes) {
        return new LocalFilesKnowledgeSource(SOURCE, root, DETECTOR, registry(), 1024 * 1024, access::get,
                includes, excludes);
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> paths(List<KnowledgeResource> resources) {
        List<String> paths = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            paths.add(resource.metadata().get("file.path").orElse(""));
        }
        Collections.sort(paths);
        return paths;
    }

    @Test
    public void theSourceFolderIsGrantedButALinkOutOfItOnlyWithAnotherGrant() throws Exception {
        Path root = temp.newFolder("wissen").toPath();
        Path secret = temp.newFolder("geheim").toPath();
        write(root.resolve("a.txt"), "eins");
        Path secretFile = write(secret.resolve("s.txt"), "geheim");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), secretFile);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("file system without symbolic links", e);
        }
        AtomicReference<LocalFileAccessPolicy> access =
                new AtomicReference<LocalFileAccessPolicy>(LocalFileAccessPolicy.none());
        LocalFilesKnowledgeSource source = source(root, access, Collections.<String>emptyList(),
                Collections.<String>emptyList());

        assertEquals(Collections.singletonList("a.txt"), paths(source.discover(DEEP)));
        KnowledgeResourceId linkId = KnowledgeResourceId.of("file",
                LocalFilesKnowledgeSource.locatorOf(root) + "/link.txt");
        try {
            source.load(linkId);
            fail("a link out of the granted folders must not be read");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.ACCESS_DENIED, expected.kind());
        }

        access.set(LocalFileAccessPolicy.of(false, Collections.singletonList(secret)));
        // Discovery never follows links; a granted link target can still be read on demand.
        assertEquals(Collections.singletonList("a.txt"), paths(source.discover(DEEP)));
        assertTrue(source.load(linkId).text().contains("geheim"));
    }

    @Test
    public void includeAndExcludePatternsLimitWhatIsRead() throws Exception {
        Path root = temp.newFolder("projekt").toPath();
        write(root.resolve("doku/a.md"), "a");
        write(root.resolve("doku/b.txt"), "b");
        write(root.resolve("doku/c.tmp"), "c");
        write(root.resolve("build/out.md"), "out");
        write(root.resolve("modul/node_modules/x.md"), "x");
        write(root.resolve("~$sperre.txt"), "lock");
        AtomicReference<LocalFileAccessPolicy> access =
                new AtomicReference<LocalFileAccessPolicy>(LocalFileAccessPolicy.none());

        LocalFilesKnowledgeSource all = source(root, access, Collections.<String>emptyList(),
                Arrays.asList("build/**", "node_modules/**", "*.tmp"));
        assertEquals(Arrays.asList("doku/a.md", "doku/b.txt"), paths(all.discover(DEEP)));

        LocalFilesKnowledgeSource markdown = source(root, access, Collections.singletonList("*.MD"),
                Collections.singletonList("/build/**"));
        assertEquals(Arrays.asList("doku/a.md", "modul/node_modules/x.md"),
                paths(markdown.discover(DEEP)));
        try {
            all.load(KnowledgeResourceId.of("file", LocalFilesKnowledgeSource.locatorOf(root) + "/doku/c.tmp"));
            fail("an excluded file must not be loaded by its ID");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.NOT_FOUND, expected.kind());
        }
    }

    @Test
    public void anUnreachableFolderIsUnavailableNotEmpty() {
        Path gone = temp.getRoot().toPath().resolve("netzlaufwerk-weg");
        LocalFilesKnowledgeSource source = source(gone,
                new AtomicReference<LocalFileAccessPolicy>(LocalFileAccessPolicy.none()),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        try {
            source.discover(DEEP);
            fail("an unreachable folder must not look like an empty one");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.UNAVAILABLE, expected.kind());
        }
    }

    @Test
    public void patternsFollowGitignoreStyle() {
        FilePatterns patterns = new FilePatterns(Collections.<String>emptyList(),
                Arrays.asList(".git/**", "*.tmp", "~$*", "/target/**"));
        assertTrue(!patterns.entersFolder(".git"));
        assertTrue(!patterns.entersFolder("sub/.git"));
        assertTrue(!patterns.acceptsFile("a/b/c.TMP"));
        assertTrue(!patterns.acceptsFile("a/~$doc.docx"));
        assertTrue(!patterns.entersFolder("target"));
        assertTrue(patterns.entersFolder("sub/target"));
        assertTrue(patterns.acceptsFile("sub/target/a.md"));
        assertEquals(Arrays.asList("*.pdf", "*.md", "build/**"), FilePatterns.split("*.pdf, *.md;\nbuild/**"));

        FilePatterns deep = new FilePatterns(Arrays.asList("**/*.pdf", "docs/**/readme.md"),
                Collections.<String>emptyList());
        assertTrue(deep.acceptsFile("report.pdf"));
        assertTrue(deep.acceptsFile("a/b/report.pdf"));
        assertTrue(deep.acceptsFile("docs/readme.md"));
        assertTrue(deep.acceptsFile("docs/x/y/readme.md"));
        assertTrue(!deep.acceptsFile("docs/other.md"));
    }
}
