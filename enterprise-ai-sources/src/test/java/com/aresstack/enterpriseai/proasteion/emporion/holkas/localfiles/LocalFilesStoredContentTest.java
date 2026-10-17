package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SourcePartition;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Partition, Metadaten und Originalbytes lokaler Dateien für Harvester, die Originale ablegen. */
public class LocalFilesStoredContentTest {

    private static final SourceAddress SOURCE = SourceAddress.of("handbuch");

    private static final ContentDetector DETECTOR = new ContentDetector() {
        @Override
        public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
            String name = filenameHint == null ? "" : filenameHint.toLowerCase();
            if (name.endsWith(".md")) {
                return new DetectedContentType("text/markdown", ContentCategory.MARKDOWN, filenameHint);
            }
            if (name.endsWith(".txt")) {
                return new DetectedContentType("text/plain", ContentCategory.PLAIN_TEXT, filenameHint);
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
        write("b.md", "# B\n");
        write("a/z.txt", "z");
        write("a/x y.md", "Leerzeichen");
        write("c/d/e.txt", "tief");
        write(".versteckt.txt", "nein");
        write("bild.bin", "kein Dokument");
        source = new LocalFilesKnowledgeSource(SOURCE, root, DETECTOR, new ExtractionRegistry(), 1024);
    }

    private void write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String id(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> paths(List<KnowledgeResource> resources) {
        List<String> paths = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            paths.add(resource.metadata().get("file.path").orElse(""));
        }
        return paths;
    }

    @Test
    public void theFolderIsOnePartitionKeyedByTheSourceId() throws Exception {
        List<SourcePartition> partitions = source.partitions();

        assertEquals(1, partitions.size());
        assertEquals("handbuch", partitions.get(0).key());
        assertEquals("Handbuch", partitions.get(0).title());
        assertEquals("handbuch", source.resourceMetadata(id("b.md")).scope());
    }

    @Test
    public void batchesListAllReadableFilesSortedByPathAcrossCursors() throws Exception {
        PartitionResources first = source.resources("handbuch", "", 2);
        PartitionResources second = source.resources("handbuch", first.nextCursor(), 2);

        assertEquals(Arrays.asList("a/x y.md", "a/z.txt"), paths(first.resources()));
        assertEquals(Arrays.asList("b.md", "c/d/e.txt"), paths(second.resources()));
        assertTrue(second.complete());
    }

    @Test
    public void aFileAddedBeforeTheCursorDoesNotShiftTheNextBatch() throws Exception {
        PartitionResources first = source.resources("handbuch", "", 2);
        write("0-neu.txt", "neu");

        assertEquals(Arrays.asList("b.md", "c/d/e.txt"),
                paths(source.resources("handbuch", first.nextCursor(), 10).resources()));
    }

    @Test
    public void unknownPartitionsAndForeignCursorsAreRejected() {
        assertKind(Kind.NOT_FOUND, () -> source.resources("anderes", "", 10));
        assertKind(Kind.UNSUPPORTED, () -> source.resources("handbuch", "p1.xyz.3", 10));
    }

    @Test
    public void localIdsRoundTripAndFitIntoOnePathSegment() throws Exception {
        KnowledgeResource listed = source.resources("handbuch", "", 10).resources().get(0);
        String localId = source.localId(listed.id());

        assertEquals(id("a/x y.md"), localId);
        assertTrue(localId, localId.matches("[A-Za-z0-9_-]+"));
        assertEquals(listed.id(), source.resourceMetadata(localId).id());
        assertEquals(listed.revision(), source.resourceMetadata(localId).revision());
        assertNull(source.localId(KnowledgeResourceId.of("file", "/anderswo/a.txt")));
    }

    @Test
    public void storedContentIsTheFileByteForByteWithItsRevision() throws Exception {
        byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'z', '\r', '\n', (byte) 0xFF};
        Files.write(root.resolve("a/z.txt"), bytes);

        StoredContent stored = source.storedContent(id("a/z.txt"));

        assertArrayEquals(bytes, stored.payload());
        assertEquals("text/plain", stored.mediaType());
        assertEquals("file", stored.nativeFormat());
        assertEquals(String.valueOf(bytes.length), stored.resource().revision().version());
        assertEquals("a/z.txt", stored.resource().metadata().get("file.path").get());
    }

    @Test
    public void aFileOverTheLimitIsAnErrorNeverTruncated() throws Exception {
        byte[] big = new byte[2048];
        Arrays.fill(big, (byte) 'x');
        Files.write(root.resolve("b.md"), big);
        LocalFilesKnowledgeSource roomy = new LocalFilesKnowledgeSource(SOURCE, root, DETECTOR,
                new ExtractionRegistry(), 4096);
        LocalFilesKnowledgeSource small = new LocalFilesKnowledgeSource(SOURCE, root, DETECTOR,
                new ExtractionRegistry(), 1024, LocalFileAccessPolicyHolder.NONE, Collections.<String>emptyList(),
                Collections.<String>emptyList());

        assertEquals(2048, roomy.storedContent(id("b.md")).length());
        try {
            small.resourceMetadata(id("b.md"));
            fail("a file over the limit is not part of the source");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
        try {
            small.storedContent(id("b.md"));
            fail("expected an error");
        } catch (ResponseTooLargeException e) {
            fail("the listing never offers it, so it is NOT_FOUND first");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void hiddenUnknownMissingAndEscapingIdsAreNotServed() {
        assertKind(Kind.NOT_FOUND, () -> source.storedContent(id(".versteckt.txt")));
        assertKind(Kind.NOT_FOUND, () -> source.storedContent(id("bild.bin")));
        assertKind(Kind.NOT_FOUND, () -> source.storedContent(id("fehlt.txt")));
        assertKind(Kind.UNSUPPORTED, () -> source.storedContent(id("../geheim.txt")));
        assertKind(Kind.UNSUPPORTED, () -> source.storedContent("kein base64!"));
        assertKind(Kind.UNSUPPORTED, () -> source.storedContent(id("b.md") + "=="));
        assertKind(Kind.NOT_FOUND, () -> source.storedContent(id("a/../b.md")));
    }

    @Test
    public void anExcludedPartitionHidesTheFilesFromDiscovery() throws Exception {
        SourceScope all = SourceScope.builder().startPoints(".").maxDepth(20).build();

        assertEquals(4, source.discover(all).size());
        assertTrue(source.discover(all.withExcludedPartitions(Collections.singleton("handbuch"))).isEmpty());
    }

    private interface Call {
        Object run() throws KnowledgeSourceException;
    }

    private static void assertKind(Kind expected, Call call) {
        try {
            call.run();
            fail("expected " + expected);
        } catch (KnowledgeSourceException e) {
            assertEquals(e.getMessage(), expected, e.kind());
        }
    }

    /** Ohne weitere Freigaben. */
    private static final class LocalFileAccessPolicyHolder {
        static final java.util.function.Supplier<com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy>
                NONE = com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy::none;
    }
}
