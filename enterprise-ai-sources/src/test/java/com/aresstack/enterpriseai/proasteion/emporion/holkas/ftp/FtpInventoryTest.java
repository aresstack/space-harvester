package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FtpInventoryTest {

    @Test
    public void inventoriesMultipleQualifiersAndAllMembersWithoutTheFormerLimit() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ1", Arrays.asList(directory("HLQ1.SUB"), directory("HLQ1.SEQ")));
        session.children.put("HLQ1.SUB", Collections.singletonList(directory("HLQ1.SUB.SRC")));
        List<FtpClientSession.FtpEntry> members = new ArrayList<FtpClientSession.FtpEntry>();
        for (int i = 0; i < 2100; i++) {
            members.add(file("HLQ1.SUB.SRC(P" + i + ")"));
        }
        session.children.put("HLQ1.SUB.SRC", members);
        session.sequential.add("HLQ1.SEQ");
        session.children.put("HLQ2", Arrays.asList(directory("HLQ2.EMPTY"), directory("HLQ2.SRC")));
        session.children.put("HLQ2.SRC", Collections.singletonList(file("HLQ2.SRC(MAIN)")));
        SourceScope scope = SourceScope.builder().startPoints("HLQ1", "HLQ2", "HLQ1.SUB")
                .maxDepth(Integer.MAX_VALUE).maxResources(Integer.MAX_VALUE).build();
        List<KnowledgeResource> result = source(session).discover(scope);
        assertEquals(2102, result.size());
        assertTrue(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ1.SEQ")));
        assertFalse(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ2.EMPTY")));
        assertTrue(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ2.SRC(MAIN)")));
        assertEquals(1, Collections.frequency(session.listed, "HLQ1.SUB"));
    }

    @Test
    public void rejectsUnqualifiedWildcardBeforeListingAnyStartPoint() throws Exception {
        FakeSession session = new FakeSession();
        try {
            source(session).discover(SourceScope.of("HLQ1", "%"));
            fail("Reject a global wildcard");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
        assertTrue(session.listed.isEmpty());
    }

    @Test
    public void distinguishesSequentialDatasetsFromMemberListsAndPartitionedDatasets() {
        FTPFile sequential = new FTPFile();
        sequential.setRawListing("VOL001 3390 2026/11/20 1 2 FB 80 3200 PS HLQ.SOURCE");
        FTPFile partitioned = new FTPFile();
        partitioned.setRawListing("VOL001 3390 2026/11/20 1 2 FB 80 3200 PO HLQ.SOURCE");
        FTPFile member = new FTPFile();
        member.setRawListing("SOURCE 01.01 2026/11/20 2026/11/20 12:00 2 2 0 USER");
        assertTrue(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{sequential}, "HLQ.SOURCE"));
        assertFalse(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{partitioned, member}, "HLQ.SOURCE"));
        assertFalse(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{sequential}, "HLQ.OTHER"));
    }

    @Test
    public void inventoryAndNavigationShareOneEligibilityCheckWithMetadataFirst() throws Exception {
        // Angelo 26.11., Entscheidung 2 / F37-6: keine Ladebibliotheken, keine Binärinhalte; Metadaten zuerst,
        // Leseprobe nur, wenn sie nicht entscheiden.
        FakeSession session = new FakeSession();
        session.children.put("HLQ", Arrays.asList(directory("HLQ.LOAD"), directory("HLQ.SRC"), directory("HLQ.OLD")));
        session.children.put("HLQ.LOAD", Collections.singletonList(file("HLQ.LOAD(PGM1)")));
        session.children.put("HLQ.SRC", Collections.singletonList(file("HLQ.SRC(PGM1)")));
        session.children.put("HLQ.OLD", Arrays.asList(file("HLQ.OLD(TEXT)"), file("HLQ.OLD(BIN)")));
        session.recordFormats.put("HLQ.LOAD", "U");
        session.recordFormats.put("HLQ.SRC", "FB");
        session.samples.put("HLQ.OLD(TEXT)", "Ordinary text\nsecond line");
        session.samples.put("HLQ.OLD(BIN)", "MZ\u0000\u0001\u0002");
        FtpKnowledgeSource source = source(session);

        List<KnowledgeResource> result = source.discover(SourceScope.builder().startPoints("HLQ")
                .maxDepth(Integer.MAX_VALUE).maxResources(Integer.MAX_VALUE).build());

        assertEquals(Arrays.asList("HLQ.SRC(PGM1)", "HLQ.OLD(TEXT)"), paths(result));
        assertFalse("RECFM U is decided by metadata alone", session.sampled.contains("HLQ.LOAD(PGM1)"));
        assertFalse("a known text record format needs no sample", session.sampled.contains("HLQ.SRC(PGM1)"));
        assertTrue(session.sampled.contains("HLQ.OLD(BIN)"));
        // Navigation fill asks the very same check.
        assertNull(source.navigationResource("HLQ.LOAD(PGM1)"));
        assertNull(source.navigationResource("'HLQ.OLD(BIN)'"));
        assertNotNull(source.navigationResource("HLQ.SRC(PGM1)"));
    }

    @Test
    public void readsDatasetListLines() {
        MvsDatasetListing.Dataset load = MvsDatasetListing.parse("VOL001 3390 2026/11/20 1 2 U 0 32760 PO HLQ.LOAD");
        assertEquals("HLQ.LOAD", load.name());
        assertEquals("U", load.recordFormat());
        assertEquals("PO", load.organization());
        assertEquals("2026/11/20", load.referred());
        MvsDatasetListing.Dataset source = MvsDatasetListing.parse("VOL001 3390 2026/11/20 1 2 FB 80 3200 PS 'HLQ.SEQ'");
        assertEquals(80, source.recordLength());
        assertEquals("PS", source.organization());
        assertTrue(MvsDatasetListing.parse("Migrated                                         HLQ.ARCH").migrated());
        assertNull(MvsDatasetListing.parse("Volume Unit    Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname"));
    }

    private static List<String> paths(List<KnowledgeResource> resources) {
        List<String> paths = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            String id = resource.id().value();
            paths.add(id.substring(id.lastIndexOf('/') + 1));
        }
        return paths;
    }

    @Test
    public void optionalFilterRecognizesProgramSourcesWithoutDownloadingTheWholeDocument() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ.DATA", Arrays.asList(file("HLQ.DATA(ABC)"), file("HLQ.DATA(DEF)"),
                file("HLQ.DATA(GHI)"), file("HLQ.DATA(JKL)")));
        session.samples.put("HLQ.DATA(ABC)", "000100 IDENTIFICATION DIVISION.\n000200 PROGRAM-ID. SAMPLE.");
        session.samples.put("HLQ.DATA(DEF)", "//RUN JOB CLASS=A\n//STEP EXEC PGM=TEST");
        session.samples.put("HLQ.DATA(GHI)", "       01 CUSTOMER-RECORD.\n          05 CUSTOMER-ID PIC 9(8).");
        session.samples.put("HLQ.DATA(JKL)", "Ordinary documentation with the words PROGRAM-ID and JOB");
        List<KnowledgeResource> result = source(session, true).discover(SourceScope.of("HLQ.DATA"));
        assertEquals(3, result.size());
        assertFalse(result.stream().anyMatch(r -> r.id().value().endsWith("(JKL)")));
    }

    @Test
    public void nativeContentKeepsCanonicalIdentityAndSourceColumns() throws Exception {
        FakeSession session = new FakeSession();
        String path = "HLQ.DATA(ABC)";
        String code = "000100 IDENTIFICATION DIVISION.  \n000200 PROGRAM-ID. ABC.   \n\n"
                + "000300* ``` e\u0301 \n";
        session.children.put("HLQ.DATA", Collections.singletonList(file(path)));
        session.contents.put(path, code);
        FtpKnowledgeSource source = source(session);
        KnowledgeResource discovered = source.discover(SourceScope.of("HLQ.DATA")).get(0);
        String id = source.localId(discovered.id());
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                discovered.id().value().getBytes(java.nio.charset.StandardCharsets.UTF_8)), id);
        com.aresstack.enterpriseai.source.api.StoredContent stored = source.storedContent(id);
        assertEquals(discovered.id(), stored.resource().id());
        assertEquals(FtpSourceProvider.SOURCE_TEXT_FORMAT, stored.nativeFormat());
        assertEquals("text/plain", stored.mediaType());
        assertArrayEquals(code.getBytes(java.nio.charset.StandardCharsets.UTF_8), stored.payload());
        assertEquals(FtpKnowledgeSource.sha256(code), stored.sha256());
        assertEquals(1, session.downloads);
        assertEquals(stored.resource().revision(), source.load(discovered.id()).resource().revision());
        assertEquals(2, session.downloads);
        try {
            source.storedContent(id + "=");
            fail("Reject a noncanonical ID");
        } catch (KnowledgeSourceException invalid) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, invalid.kind());
        }
        assertEquals(2, session.downloads);
    }

    @Test
    public void nativeRecordDecoderPreservesPaddingSpacesAndEmptyRecords() throws Exception {
        byte[] bytes = {' ', 'A', ' ', ' ', (byte) 0xff, 1, (byte) 0xff, 1,
                ' ', 'B', ' ', (byte) 0xff, 1, (byte) 0xff, 2};
        assertEquals(" A  \n\n B \n", RecordStructureText.decodeSource(bytes,
                java.nio.charset.StandardCharsets.ISO_8859_1, true));
        assertEquals(" A  \n\n B ", RecordStructureText.decodeSource(" A  \r\n\r\n B "
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8, false));
        try {
            RecordStructureText.decodeSource(new byte[]{(byte) 0xc3, 0x28},
                    java.nio.charset.StandardCharsets.UTF_8, false);
            fail("Reject malformed source encoding");
        } catch (java.nio.charset.CharacterCodingException expected) {
            // Reject silent character replacement.
        }
    }

    @Test
    public void directScanIncludesSequentialDatasetButDoesNotOpenChildDirectories() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ", Arrays.asList(directory("HLQ.SRC"), directory("HLQ.PDS")));
        session.sequential.add("HLQ.SRC");
        List<KnowledgeResource> result = source(session).discover(SourceScope.builder()
                .startPoints("HLQ").maxDepth(0).build());
        assertEquals(1, result.size());
        assertTrue(result.get(0).id().value().endsWith("/HLQ.SRC"));
        assertEquals(Collections.singletonList("HLQ"), session.listed);
    }

    /** Ein gesetztes Limit macht nichts unvollständig; erst was es tatsächlich abschneidet, und nur je Startpunkt. */
    @Test
    public void coverageFollowsWhatTheLimitsActuallyCutOffPerStartPoint() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("SMALL", Collections.singletonList(directory("SMALL.SRC")));
        session.children.put("SMALL.SRC", Arrays.asList(file("SMALL.SRC(A)"), file("SMALL.SRC(B)")));
        session.children.put("DEEP", Collections.singletonList(directory("DEEP.LIB")));
        session.children.put("DEEP.LIB", Collections.singletonList(directory("DEEP.LIB.MORE")));
        FtpKnowledgeSource source = source(session);
        assertFalse("vor der ersten Inventur deckt nichts ab", source.inventoryCovers(id(source, "SMALL.SRC(X)")));

        source.discover(SourceScope.builder().startPoints("SMALL", "DEEP").maxDepth(1).maxResources(100).build());

        assertTrue("Limits gesetzt, aber nichts abgeschnitten", source.inventoryCovers(id(source, "SMALL.SRC(X)")));
        assertFalse("maxDepth hat DEEP.LIB.MORE abgeschnitten", source.inventoryCovers(id(source, "DEEP.LIB(X)")));
        assertFalse("außerhalb aller Startpunkte", source.inventoryCovers(id(source, "OTHER.SRC(X)")));

        source.discover(SourceScope.builder().startPoints("SMALL").maxDepth(5).maxResources(1).build());
        assertFalse("maxResources hat SMALL.SRC(B) abgeschnitten", source.inventoryCovers(id(source, "SMALL.SRC(B)")));

        session.children.put("SMALL.SRC", Collections.singletonList(file("SMALL.SRC(A)")));
        source.discover(SourceScope.builder().startPoints("SMALL").maxDepth(5).maxResources(1).build());
        assertTrue("genau am Limit, aber vollständig", source.inventoryCovers(id(source, "SMALL.SRC(B)")));
    }

    /** Im Umfang heißt: unter einem Startpunkt, auch wenn er abgeschnitten wurde; ohne Inventur unbekannt, also ja. */
    @Test
    public void withinTheScopeMeansUnderAStartPointEvenIfItWasCutOff() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("DEEP", Collections.singletonList(directory("DEEP.LIB")));
        session.children.put("DEEP.LIB", Collections.singletonList(directory("DEEP.LIB.MORE")));
        FtpKnowledgeSource source = source(session);
        assertTrue("ohne Inventur unbekannt", source.withinInventoryScope(id(source, "OTHER.SRC(X)")));

        source.discover(SourceScope.builder().startPoints("DEEP").maxDepth(1).build());

        assertTrue(source.withinInventoryScope(id(source, "DEEP.LIB(X)")));
        assertFalse(source.inventoryCovers(id(source, "DEEP.LIB(X)")));
        assertFalse(source.withinInventoryScope(id(source, "OTHER.SRC(X)")));
    }

    @Test
    public void aFailedInventoryCoversNothing() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ", Collections.singletonList(file("HLQ.SRC(A)")));
        FtpKnowledgeSource source = source(session);
        source.discover(SourceScope.of("HLQ"));
        assertTrue(source.inventoryCovers(id(source, "HLQ.SRC(B)")));
        try {
            source.discover(SourceScope.of("HLQ", "%"));
            fail("Reject a global wildcard");
        } catch (KnowledgeSourceException expected) {
            assertFalse(source.inventoryCovers(id(source, "HLQ.SRC(B)")));
        }
    }

    private static com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId id(FtpKnowledgeSource source,
                                                                                   String path) {
        return source.idOf(path);
    }

    private static FtpKnowledgeSource source(FakeSession session) {
        return source(session, false);
    }

    private static FtpKnowledgeSource source(FakeSession session, boolean programsOnly) {
        SourceAddress address = SourceAddress.of("ftp");
        FtpConnectionSettings settings = FtpSourceProvider.connection(new SourceSettingsReader(
                FtpSourceProvider.sourceType(), SourceSettings.empty().with("host", "localhost")));
        FtpSessionPool<FtpClientSession> pool = new FtpSessionPool<FtpClientSession>(address, settings,
                null, (ignored, user, password) -> session);
        return new FtpKnowledgeSource(address, settings, pool, programsOnly);
    }

    private static FtpClientSession.FtpEntry directory(String path) {
        return new FtpClientSession.FtpEntry(path, path, true, 0);
    }

    private static FtpClientSession.FtpEntry file(String path) {
        return new FtpClientSession.FtpEntry(path, path, false, 0);
    }

    private static final class FakeSession implements FtpClientSession {
        private final Map<String, List<FtpEntry>> children = new LinkedHashMap<String, List<FtpEntry>>();
        private final List<String> listed = new ArrayList<String>();
        private final List<String> sequential = new ArrayList<String>();
        private final Map<String, String> samples = new LinkedHashMap<String, String>();
        private final Map<String, String> recordFormats = new LinkedHashMap<String, String>();
        private final List<String> sampled = new ArrayList<String>();
        private final Map<String, String> contents = new LinkedHashMap<String, String>();
        private int downloads;

        @Override
        public boolean mvs() {
            return true;
        }

        @Override
        public List<FtpEntry> list(String path) {
            listed.add(path);
            return children.getOrDefault(path, Collections.<FtpEntry>emptyList());
        }

        @Override
        public String sampleText(String path, int maxBytes) {
            sampled.add(path);
            return samples.getOrDefault(path, "");
        }

        @Override
        public String recordFormat(String path) {
            String dataset = path.indexOf('(') >= 0 ? path.substring(0, path.indexOf('(')) : path;
            return recordFormats.getOrDefault(dataset, "");
        }

        @Override
        public String readText(String path) {
            if (!contents.containsKey(path)) throw new AssertionError("Unexpected download: " + path);
            downloads++;
            return contents.get(path);
        }

        @Override
        public boolean isSequentialDataset(String path) {
            return sequential.contains(path);
        }

        @Override
        public void close() {
        }
    }
}